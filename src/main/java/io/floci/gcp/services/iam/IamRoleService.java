package io.floci.gcp.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.iam.model.StoredRole;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project and organization custom roles ({@code projects.roles}, {@code organizations.roles}) and
 * the permissions their bindings contribute to policy evaluation. Roles are keyed by their full
 * resource name. Deleted roles are kept with {@code deleted=true} and never purged.
 */
@ApplicationScoped
public class IamRoleService {

    private static final Logger LOG = Logger.getLogger(IamRoleService.class);

    private static final Pattern PARENT = Pattern.compile("(projects|organizations)/([^/:]+)");
    private static final Pattern ROLE_NAME = Pattern.compile("((projects|organizations)/([^/:]+))/roles/([^/:]+)");
    private static final Pattern ROLE_ID = Pattern.compile("[A-Za-z0-9_.]{3,64}");
    private static final Set<String> STAGES = Set.of("ALPHA", "BETA", "GA", "DEPRECATED", "DISABLED", "EAP");
    // Proto3 JSON also accepts enum numbers; RoleLaunchStage skips 3.
    private static final Map<Integer, String> STAGE_NUMBERS = Map.of(0, "ALPHA", 1, "BETA", 2, "GA",
            4, "DEPRECATED", 5, "DISABLED", 6, "EAP");
    private static final Set<String> MUTABLE_FIELDS = Set.of("title", "description", "includedPermissions", "stage");
    private static final int DEFAULT_PAGE_SIZE = 300;
    private static final int MAX_PAGE_SIZE = 1000;
    private static final int MAX_TITLE_BYTES = 100;
    private static final int MAX_DESCRIPTION_BYTES = 300;
    private static final int MAX_PERMISSIONS = 3000;

    private final StorageBackend<String, StoredRole> roles;
    private final ProjectRegistry projects;

    @Inject
    public IamRoleService(StorageFactory storageFactory, ProjectRegistry projects) {
        this(storageFactory.createGlobal("iam-roles", "iam-roles.json",
                new TypeReference<Map<String, StoredRole>>() {}), projects);
    }

    IamRoleService(StorageBackend<String, StoredRole> roles, ProjectRegistry projects) {
        this.roles = roles;
        this.projects = projects;
    }

    public record RolePage(List<StoredRole> roles, String nextPageToken) {}

    // Instantiates the bean at boot so the store is registered before a reset or flush runs.
    void onStart(@Observes StartupEvent event) {
        LOG.debugf("custom roles loaded: %d", roles.keys().size());
    }

    public synchronized StoredRole create(String parent, String roleId, Map<String, Object> role) {
        requireParent(parent);
        if (roleId == null || !ROLE_ID.matcher(roleId).matches()) {
            throw GcpException.invalidArgument("The role_id \"" + (roleId != null ? roleId : "") + "\" is invalid. It doesn't match pattern "
                    + "\"[a-zA-Z0-9_\\.]{3,64}\". The role_id must be 3 to 64 characters long and can be a mix of "
                    + "uppercase and lowercase English letters, digits, underscores and periods.");
        }
        String name = parent + "/roles/" + roleId;
        Optional<StoredRole> existing = roles.get(name);
        // Deleted roles are never purged here, so their IDs stay reserved as during GCP's deletion window.
        if (existing.isPresent() && existing.get().softDeleted()) {
            throw GcpException.failedPrecondition("You can't create a role with role_id (" + roleId
                    + ") where there is an existing role with that role_id in a deleted state.");
        }
        if (existing.isPresent()) {
            throw GcpException.alreadyExists("A role named " + roleId + " in " + parent + " already exists.");
        }
        StoredRole stored = new StoredRole();
        stored.setName(name);
        apply(stored, role != null ? role : Map.of(), MUTABLE_FIELDS);
        stored.setEtag(newEtag());
        roles.put(name, stored);
        LOG.debugf("createRole name=%s permissions=%d", name, stored.getIncludedPermissions().size());
        return stored.copy();
    }

    public StoredRole get(String name) {
        return stored(name).copy();
    }

    public RolePage list(String parent, String view, boolean showDeleted, int pageSize, String pageToken) {
        requireParent(parent);
        boolean full = parseView(view);
        if (pageSize < 0 || pageSize > MAX_PAGE_SIZE) {
            throw GcpException.invalidArgument("The field page_size is out of range. It must be between 0 and 1,000.");
        }
        int size = pageSize == 0 ? DEFAULT_PAGE_SIZE : pageSize;
        String prefix = parent + "/roles/";
        List<StoredRole> matching = roles.scan(key -> key.startsWith(prefix)).stream()
                .filter(role -> showDeleted || !role.softDeleted())
                .sorted(Comparator.comparing(StoredRole::getName))
                .map(role -> full ? role.copy() : role.basicView())
                .toList();
        PageToken.Page<StoredRole> page = PageToken.paginate(matching, size, pageToken);
        return new RolePage(page.items(), page.nextPageToken());
    }

    public synchronized StoredRole update(String name, Map<String, Object> role, String updateMask) {
        StoredRole stored = stored(name).copy();
        Map<String, Object> source = role != null ? role : Map.of();
        Object requestName = source.get("name");
        if (requestName != null && !requestName.toString().isEmpty() && !requestName.equals(name)) {
            throw GcpException.invalidArgument("The name in the request (" + name + ") and the name in the role ("
                    + requestName + ") must match.");
        }
        checkEtag(stored, source.get("etag"), "role.etag");
        if (stored.softDeleted()) {
            throw GcpException.failedPrecondition("You can't update a role (" + name + ") which is in a deleted state.");
        }
        apply(stored, source, updateFields(updateMask));
        stored.setEtag(newEtag());
        roles.put(name, stored);
        LOG.debugf("updateRole name=%s mask=%s", name, updateMask);
        return stored.copy();
    }

    public synchronized StoredRole delete(String name, String etag) {
        StoredRole stored = stored(name).copy();
        if (stored.softDeleted()) {
            throw GcpException.failedPrecondition("You can't delete role_id (" + name + ") as it is already deleted.");
        }
        checkEtag(stored, etag, "etag");
        stored.setDeleted(true);
        stored.setEtag(newEtag());
        roles.put(name, stored);
        LOG.debugf("deleteRole name=%s", name);
        return stored.copy();
    }

    public synchronized StoredRole undelete(String name, String etag) {
        StoredRole stored = stored(name).copy();
        // GCP reports the lifecycle state before a stale etag on delete and undelete, but the etag first on update.
        if (!stored.softDeleted()) {
            throw GcpException.failedPrecondition("A role that is not deleted cannot be undeleted.");
        }
        checkEtag(stored, etag, "etag");
        stored.setDeleted(false);
        stored.setEtag(newEtag());
        roles.put(name, stored);
        LOG.debugf("undeleteRole name=%s", name);
        return stored.copy();
    }

    public static boolean isCustomRole(String role) {
        return role != null && ROLE_NAME.matcher(role).matches();
    }

    /**
     * Permissions a binding of {@code role} contributes in the policy stored under {@code policyResource}.
     * A project role applies only to policies of that project; an organization role applies to the
     * organization's policy and to projects whose recorded parent is that organization. Any other
     * placement, an unknown ancestry, and missing, deleted or disabled roles grant nothing.
     */
    public Set<String> grantedPermissions(String role, String policyResource) {
        if (role == null) {
            return Set.of();
        }
        Matcher matcher = ROLE_NAME.matcher(role);
        if (!matcher.matches() || !inScope(matcher.group(2), matcher.group(3), policyResource)) {
            return Set.of();
        }
        return roles.get(role).filter(StoredRole::grantsPermissions)
                .map(stored -> Set.copyOf(stored.getIncludedPermissions()))
                .orElse(Set.of());
    }

    /** Whether {@code role} is an active custom role that applies to the policy under {@code policyResource}. */
    public boolean applies(String role, String policyResource) {
        if (role == null) {
            return false;
        }
        Matcher matcher = ROLE_NAME.matcher(role);
        return matcher.matches() && inScope(matcher.group(2), matcher.group(3), policyResource)
                && roles.get(role).filter(StoredRole::grantsPermissions).isPresent();
    }

    /**
     * Rejects a new grant of a custom role that GCP refuses when the policy on {@code policyResource}
     * is written: a role defined outside the resource's project or organization, a missing role, or a
     * deleted one. A placement this emulator cannot decide (folder or unrecorded parents, unresolved
     * project numbers) is accepted. Predefined roles are not checked.
     */
    public void requireBindable(String role, String policyResource) {
        Matcher matcher = role == null ? null : ROLE_NAME.matcher(role);
        if (matcher == null || !matcher.matches()) {
            return;
        }
        if (outsideHierarchy(matcher.group(2), matcher.group(3), policyResource)) {
            throw GcpException.invalidArgument("Role (" + role + ") does not exist in the resource's hierarchy.");
        }
        StoredRole stored = roles.get(role).orElseThrow(() -> GcpException.invalidArgument(
                "Role (" + role + ") does not exist in resource's hierarchy."));
        if (stored.softDeleted()) {
            throw GcpException.invalidArgument("Cannot add new grants to deleted role (" + role + ").");
        }
    }

    private boolean outsideHierarchy(String parentType, String parentId, String policyResource) {
        String project = projectOf(policyResource);
        if (parentType.equals("projects")) {
            if (project == null || parentId.equals(project)) {
                return false;
            }
            String roleProject = canonicalProjectId(parentId);
            String resourceProject = canonicalProjectId(project);
            return roleProject != null && resourceProject != null && !roleProject.equals(resourceProject);
        }
        if (policyResource.startsWith("organizations/")) {
            return !policyResource.equals("organizations/" + parentId);
        }
        Optional<ProjectRecord> record = project == null ? Optional.empty() : projects.find(project);
        if (record.isEmpty()) {
            return false;
        }
        if (record.get().hasParent()) {
            return "organization".equals(record.get().getParentType()) && !parentId.equals(record.get().getParentId());
        }
        return record.get().isCreated();
    }

    /** The project ID for an ID or a registered number; null for a number that is not registered. */
    private String canonicalProjectId(String idOrNumber) {
        if (!ProjectRegistry.isNumber(idOrNumber)) {
            return idOrNumber;
        }
        return projects.find(idOrNumber).map(ProjectRecord::getProjectId).orElse(null);
    }

    private boolean inScope(String parentType, String parentId, String policyResource) {
        String project = projectOf(policyResource);
        if (parentType.equals("projects")) {
            return parentId.equals(project);
        }
        if (policyResource.equals("organizations/" + parentId)) {
            return true;
        }
        if (project == null || ProjectRegistry.isNumber(project)) {
            return false;
        }
        Optional<ProjectRecord> record = projects.find(project);
        return record.isPresent() && "organization".equals(record.get().getParentType())
                && parentId.equals(record.get().getParentId());
    }

    private static String projectOf(String policyResource) {
        String[] parts = policyResource.split("/", 3);
        if (parts.length < 2 || !parts[0].equals("projects") || parts[1].isBlank()
                || parts[1].equals("-") || parts[1].equals("_")) {
            return null;
        }
        return parts[1];
    }

    /** Rejects names the role API cannot address, in every authorization mode. */
    static void requireRoleName(String name) {
        if (name == null || !ROLE_NAME.matcher(name).matches()) {
            throw GcpException.invalidArgument("The role name must be in form \"roles/{role}\", "
                    + "\"organizations/{organization_id}/roles/{role}\", \"projects/{project_id}/roles/{role}\".");
        }
        requireParent(name.substring(0, name.indexOf("/roles/")));
    }

    private StoredRole stored(String name) {
        requireRoleName(name);
        return roles.get(name).orElseThrow(() -> GcpException.notFound("The role named " + name + " was not found."));
    }

    static void requireParent(String parent) {
        Matcher matcher = parent == null ? null : PARENT.matcher(parent);
        if (matcher == null || !matcher.matches() || Set.of("-", "_", "*").contains(matcher.group(2))) {
            throw GcpException.invalidArgument("You must specify a complete project ID or organization ID: " + parent);
        }
    }

    private static boolean parseView(String view) {
        if (view == null || view.isEmpty() || view.equals("BASIC")) {
            return false;
        }
        if (view.equals("FULL")) {
            return true;
        }
        throw GcpException.invalidArgument("Invalid value at 'view' (type.googleapis.com/google.iam.admin.v1.RoleView), \""
                + view + "\"");
    }

    /** Without a mask, GCP replaces every mutable field, clearing those absent from the request. */
    private static Set<String> updateFields(String updateMask) {
        if (updateMask == null || updateMask.isBlank()) {
            return MUTABLE_FIELDS;
        }
        Set<String> fields = new LinkedHashSet<>();
        for (String path : updateMask.split(",")) {
            String field = path.trim().equals("included_permissions") ? "includedPermissions" : path.trim();
            if (!MUTABLE_FIELDS.contains(field)) {
                throw GcpException.invalidArgument("Invalid field mask for Role.");
            }
            fields.add(field);
        }
        return fields;
    }

    private static void apply(StoredRole target, Map<String, Object> source, Set<String> fields) {
        if (fields.contains("title")) {
            String title = stringField(source, "title");
            requireMaxBytes("display_name", title, MAX_TITLE_BYTES);
            target.setTitle(title);
        }
        if (fields.contains("description")) {
            String description = stringField(source, "description");
            requireMaxBytes("description", description, MAX_DESCRIPTION_BYTES);
            target.setDescription(description);
        }
        if (fields.contains("includedPermissions")) {
            target.setIncludedPermissions(permissions(source.get("includedPermissions")));
        }
        if (fields.contains("stage")) {
            Object value = source.get("stage");
            String stage = value instanceof Integer number ? STAGE_NUMBERS.get(number)
                    : value != null ? value.toString() : StoredRole.ALPHA;
            if (stage == null || !STAGES.contains(stage)) {
                throw GcpException.invalidArgument("Invalid value at 'role.stage' "
                        + "(type.googleapis.com/google.iam.admin.v1.Role.RoleLaunchStage), \"" + value + "\"");
            }
            target.setStage(stage);
        }
    }

    private static String stringField(Map<String, Object> source, String field) {
        Object value = source.get(field);
        if (value != null && !(value instanceof String)) {
            throw GcpException.invalidArgument("Invalid value at 'role." + field + "' (TYPE_STRING), " + value);
        }
        return (String) value;
    }

    private static void requireMaxBytes(String field, String value, int max) {
        int length = value != null ? value.getBytes(StandardCharsets.UTF_8).length : 0;
        if (length > max) {
            throw GcpException.invalidArgument("The " + field + " length (" + length
                    + ") is longer than the maximum allowed length " + max + ".");
        }
    }

    private static List<String> permissions(Object value) {
        if (value == null) {
            return List.of();
        }
        // Proto3 JSON accepts a single value for a repeated field.
        List<?> list = value instanceof List<?> values ? values : List.of(value);
        if (list.size() > MAX_PERMISSIONS) {
            throw GcpException.invalidArgument("The number of permissions in the role is greater than the maximum of 3,000.");
        }
        Set<String> permissions = new LinkedHashSet<>();
        for (Object permission : list) {
            if (!(permission instanceof String text) || text.isBlank() || text.contains("*")) {
                throw GcpException.invalidArgument("Permission " + permission + " is not valid.");
            }
            permissions.add(text);
        }
        return new ArrayList<>(permissions);
    }

    private static void checkEtag(StoredRole stored, Object requested, String field) {
        if (requested != null && !(requested instanceof String)) {
            throw GcpException.invalidArgument("Invalid value at '" + field + "' (TYPE_BYTES), " + requested);
        }
        if (requested == null || requested.toString().isEmpty()) {
            return;
        }
        if (!Arrays.equals(etagBytes(requested.toString(), field), etagBytes(stored.getEtag(), field))) {
            throw GcpException.aborted("There were concurrent policy changes. Please retry the whole "
                    + "read-modify-write with exponential backoff.");
        }
    }

    /** Clients may re-encode the bytes etag in either base64 alphabet. */
    private static byte[] etagBytes(String etag, String field) {
        try {
            return Base64.getDecoder().decode(etag);
        } catch (IllegalArgumentException standardFailure) {
            try {
                return Base64.getUrlDecoder().decode(etag);
            } catch (IllegalArgumentException urlSafeFailure) {
                throw GcpException.invalidArgument("Invalid value at '" + field
                        + "' (TYPE_BYTES), Base64 decoding failed for \"" + etag + "\"");
            }
        }
    }

    private static String newEtag() {
        byte[] bytes = new byte[8];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}

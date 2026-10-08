package io.floci.gcp.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.iam.model.StoredRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Project custom roles for the IAM v1 API ({@code projects.roles}). Roles are soft-deleted like
 * the real service: a deleted role stays readable with {@code deleted=true} until it is undeleted.
 * Permissions are stored as sent and are not validated against a permission catalog.
 */
@ApplicationScoped
public class IamRoleService {

    private static final Pattern ROLE_ID = Pattern.compile("[A-Za-z0-9_.]{3,64}");
    private static final Set<String> STAGES = Set.of("ALPHA", "BETA", "GA", "DEPRECATED", "DISABLED", "EAP");

    private final StorageBackend<String, StoredRole> roleStore;

    @Inject
    public IamRoleService(StorageFactory storageFactory) {
        this.roleStore = storageFactory.createGlobal("iam-roles", "iam-roles.json",
                new TypeReference<Map<String, StoredRole>>() {});
    }

    IamRoleService(StorageBackend<String, StoredRole> roleStore) {
        this.roleStore = roleStore;
    }

    public synchronized StoredRole create(String project, String roleId, Map<String, Object> role) {
        if (roleId == null || !ROLE_ID.matcher(roleId).matches()) {
            throw GcpException.invalidArgument("roleId must be 3 to 64 characters of letters, digits, underscores and periods.");
        }
        String key = key(project, roleId);
        if (roleStore.get(key).isPresent()) {
            throw GcpException.alreadyExists("Role already exists: projects/" + project + "/roles/" + roleId);
        }
        StoredRole stored = new StoredRole();
        stored.setName("projects/" + project + "/roles/" + roleId);
        apply(stored, role, null);
        stored.setEtag(newEtag());
        roleStore.put(key, stored);
        return stored.copy();
    }

    public StoredRole get(String project, String roleId) {
        return stored(project, roleId).copy();
    }

    private StoredRole stored(String project, String roleId) {
        return roleStore.get(key(project, roleId))
                .orElseThrow(() -> GcpException.notFound("The role was not found: projects/" + project + "/roles/" + roleId));
    }

    public synchronized StoredRole update(String project, String roleId, Map<String, Object> role, String updateMask) {
        StoredRole stored = stored(project, roleId).copy();
        checkEtag(stored, role == null ? null : role.get("etag"));
        List<String> mask = updateMask == null || updateMask.isBlank() ? null : List.of(updateMask.split(","));
        apply(stored, role, mask);
        stored.setEtag(newEtag());
        roleStore.put(key(project, roleId), stored);
        return stored.copy();
    }

    public synchronized StoredRole delete(String project, String roleId, String etag) {
        StoredRole stored = stored(project, roleId).copy();
        checkEtag(stored, etag);
        stored.setDeleted(true);
        stored.setEtag(newEtag());
        roleStore.put(key(project, roleId), stored);
        return stored.copy();
    }

    public synchronized StoredRole undelete(String project, String roleId, String etag) {
        StoredRole stored = stored(project, roleId).copy();
        checkEtag(stored, etag);
        stored.setDeleted(null);
        stored.setEtag(newEtag());
        roleStore.put(key(project, roleId), stored);
        return stored.copy();
    }

    public Map<String, Object> list(String project, boolean showDeleted, int pageSize, String pageToken) {
        String prefix = "role:" + project + ":";
        List<StoredRole> roles = roleStore.scan(k -> k.startsWith(prefix)).stream().map(StoredRole::copy).collect(Collectors.toCollection(ArrayList::new));
        roles.removeIf(role -> !showDeleted && Boolean.TRUE.equals(role.getDeleted()));
        roles.sort((a, b) -> a.getName().compareTo(b.getName()));
        PageToken.Page<StoredRole> page = PageToken.paginate(roles, pageSize, pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("roles", page.items());
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private static void apply(StoredRole target, Map<String, Object> source, List<String> mask) {
        if (source == null) {
            return;
        }
        if (mask == null || mask.contains("title")) {
            target.setTitle((String) source.get("title"));
        }
        if (mask == null || mask.contains("description")) {
            target.setDescription((String) source.get("description"));
        }
        if (mask == null || mask.contains("includedPermissions") || mask.contains("included_permissions")) {
            Object permissions = source.get("includedPermissions");
            target.setIncludedPermissions(permissions instanceof List<?> list ? new ArrayList<>((List<String>) list) : new ArrayList<>());
        }
        if (mask == null || mask.contains("stage")) {
            Object stage = source.get("stage");
            if (stage != null) {
                if (!STAGES.contains(stage.toString())) {
                    throw GcpException.invalidArgument("Invalid role stage: " + stage);
                }
                target.setStage(stage.toString());
            } else if (mask != null) {
                target.setStage("GA");
            }
        }
    }

    private static void checkEtag(StoredRole stored, Object requested) {
        if (requested != null && !requested.toString().isEmpty() && !requested.toString().equals(stored.getEtag())) {
            throw GcpException.aborted("There were concurrent policy changes. Please retry the whole read-modify-write with exponential backoff.");
        }
    }

    private static String key(String project, String roleId) {
        return "role:" + project + ":" + roleId;
    }

    private static String newEtag() {
        byte[] bytes = new byte[8];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}

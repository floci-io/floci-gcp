package io.floci.gcp.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Custom-role management for {@code projects.roles} and {@code organizations.roles}. */
@ApplicationScoped
public class IamRoleAuthorizationAdapter implements IamAuthorizationAdapter {

    private static final String SERVICE = "iam.googleapis.com";
    private static final Set<String> ROLE_ADMIN = Set.of("iam.roles.create", "iam.roles.delete", "iam.roles.get",
            "iam.roles.list", "iam.roles.undelete", "iam.roles.update");
    private static final Set<String> ROLE_VIEWER = Set.of("iam.roles.get", "iam.roles.list");
    private static final Set<String> PROJECT_READ = Set.of("resourcemanager.projects.get",
            "resourcemanager.projects.getIamPolicy");

    @Override
    public String serviceName() {
        return "iam-roles";
    }

    @Override
    public Set<Class<?>> restControllers() {
        return Set.of(IamRolesController.class, IamOrganizationRolesController.class);
    }

    @Override
    public Set<String> grpcServices() {
        return Set.of();
    }

    @Override
    public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
        String parent;
        if (path.containsKey("project")) {
            parent = "projects/" + path.get("project");
        } else if (path.containsKey("organization")) {
            parent = "organizations/" + path.get("organization");
        } else {
            throw unsupported("REST path " + path);
        }
        IamRoleService.requireParent(parent);
        String name = path.containsKey("role") ? parent + "/roles/" + path.get("role") : null;
        if (name != null) {
            IamRoleService.requireRoleName(name);
        }
        return switch (method) {
            case "create" -> new IamOperation("CreateRole", parent);
            case "list" -> new IamOperation("ListRoles", parent);
            case "get" -> new IamOperation("GetRole", requireName(name, method));
            case "patch" -> new IamOperation("UpdateRole", requireName(name, method));
            case "delete" -> new IamOperation("DeleteRole", requireName(name, method));
            case "undelete" -> new IamOperation("UndeleteRole", requireName(name, method));
            default -> throw unsupported("REST method " + method);
        };
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        throw unsupported("gRPC operation " + method);
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        String permission = switch (operation.name()) {
            case "CreateRole" -> "iam.roles.create";
            case "ListRoles" -> "iam.roles.list";
            case "GetRole" -> "iam.roles.get";
            case "UpdateRole" -> "iam.roles.update";
            case "DeleteRole" -> "iam.roles.delete";
            case "UndeleteRole" -> "iam.roles.undelete";
            default -> throw unsupported("operation " + operation.name());
        };
        boolean parentCheck = operation.name().equals("CreateRole") || operation.name().equals("ListRoles");
        IamResource target = parentCheck ? parent(operation.resource()) : requireResource(operation.resource());
        return List.of(new IamPermissionCheck(permission, target));
    }

    @Override
    public Optional<IamResource> resource(String name) {
        if (!IamRoleService.isCustomRole(name)) {
            return Optional.empty();
        }
        String parent = name.substring(0, name.indexOf("/roles/"));
        return Optional.of(new IamResource(SERVICE, SERVICE + "/Role", name, parent,
                parent.startsWith("projects/") ? parent : null));
    }

    @Override
    public Map<String, Set<String>> roles() {
        return Map.of(
                "roles/iam.roleAdmin", union(ROLE_ADMIN, PROJECT_READ),
                "roles/iam.roleViewer", union(ROLE_VIEWER, PROJECT_READ),
                "roles/iam.organizationRoleAdmin", union(ROLE_ADMIN, PROJECT_READ),
                "roles/iam.organizationRoleViewer", union(ROLE_VIEWER, PROJECT_READ));
    }

    /** Basic-role {@code iam.roles.*} membership as listed by {@code gcloud iam roles describe}. */
    @Override
    public Map<String, Set<String>> basicRoles() {
        return Map.of("roles/owner", ROLE_ADMIN, "roles/editor", ROLE_VIEWER, "roles/viewer", ROLE_VIEWER);
    }

    /**
     * The organization policy is read from the shared policy store under {@code organizations/{id}}.
     * Resource Manager organization policy methods are not implemented, so evaluated callers are
     * denied unless such a policy was stored through the shared IAM policy gRPC mixin.
     */
    private IamResource parent(String parent) {
        if (parent.startsWith("projects/")) {
            return IamResource.project(parent);
        }
        return new IamResource("cloudresourcemanager.googleapis.com",
                "cloudresourcemanager.googleapis.com/Organization", parent, parent);
    }

    private String requireName(String name, String method) {
        if (name == null) {
            throw unsupported("REST method " + method + " without a role");
        }
        return name;
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        return Stream.concat(first.stream(), second.stream()).collect(Collectors.toUnmodifiableSet());
    }
}

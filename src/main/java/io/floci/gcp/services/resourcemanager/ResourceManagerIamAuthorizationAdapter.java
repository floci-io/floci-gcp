package io.floci.gcp.services.resourcemanager;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class ResourceManagerIamAuthorizationAdapter implements IamAuthorizationAdapter {
    @Override
    public String serviceName() {
        return "resourcemanager";
    }

    @Override
    public Set<Class<?>> restControllers() {
        return Set.of(ResourceManagerController.class, ResourceManagerIamController.class);
    }

    @Override
    public Set<String> grpcServices() {
        return Set.of();
    }

    @Override
    public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
        return new IamOperation(Character.toUpperCase(method.charAt(0)) + method.substring(1),
                "projects/" + path.get("project"));
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        throw unsupported("gRPC operation " + method);
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        String permission = switch (operation.name()) {
            case "GetProject" -> "resourcemanager.projects.get";
            case "GetIamPolicy" -> "resourcemanager.projects.getIamPolicy";
            case "SetIamPolicy" -> "resourcemanager.projects.setIamPolicy";
            // Permission queries are evaluated separately; the GET variants return 405.
            case "TestIamPermissions", "GetIamPolicyWithGet", "SetIamPolicyWithGet", "TestIamPermissionsWithGet" -> null;
            default -> throw unsupported("operation " + operation.name());
        };
        return permission == null ? List.of() : List.of(new IamPermissionCheck(permission, requireResource(operation.resource())));
    }

    @Override
    public Optional<IamResource> resource(String name) {
        return name.matches("projects/[^/:]+") ? Optional.of(IamResource.project(name)) : Optional.empty();
    }

    @Override
    public Map<String, Set<String>> roles() {
        return Map.of("roles/browser", Set.of("resourcemanager.projects.get", "resourcemanager.projects.getIamPolicy"),
                "roles/resourcemanager.projectIamAdmin", Set.of("resourcemanager.projects.get",
                        "resourcemanager.projects.getIamPolicy", "resourcemanager.projects.setIamPolicy"));
    }
    @Override
    public Map<String, Set<String>> basicRoles() {
        Map<String, Set<String>> predefined = roles();
        return Map.of("roles/owner", predefined.get("roles/resourcemanager.projectIamAdmin"),
                "roles/editor", predefined.get("roles/browser"), "roles/viewer", predefined.get("roles/browser"));
    }

}

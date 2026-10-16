package io.floci.gcp.services.resourcemanager;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.IamBinding;
import io.floci.gcp.services.iam.IamPolicy;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashSet;
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
        return Set.of(ResourceManagerController.class, ResourceManagerProjectsController.class,
                ResourceManagerIamController.class, ResourceManagerOperationsController.class);
    }

    @Override
    public Set<String> grpcServices() {
        return Set.of();
    }

    @Override
    public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
        String name = Character.toUpperCase(method.charAt(0)) + method.substring(1);
        if (path.containsKey("project")) {
            return new IamOperation(name, "projects/" + path.get("project"));
        }
        if (path.containsKey("operation")) {
            // Creation operations are named cp.{projectNumber}; polling one reads the project it created.
            return new IamOperation(name, "projects/" + path.get("operation").substring("cp.".length()));
        }
        return new IamOperation(name, "CreateProject".equals(name)
                ? "projects/" + body.path("projectId").asText("") : "projects");
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        throw unsupported("gRPC operation " + method);
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        String permission = switch (operation.name()) {
            case "GetProject" -> "resourcemanager.projects.get";
            case "UpdateProject" -> "resourcemanager.projects.update";
            case "DeleteProject" -> "resourcemanager.projects.delete";
            case "UndeleteProject" -> "resourcemanager.projects.undelete";
            case "GetAncestry" -> "resourcemanager.projects.get";
            // GCP checks create on the parent organization or folder, whose policies are not modelled,
            // so no grant on the new project satisfies it and service accounts are denied.
            case "CreateProject" -> "resourcemanager.projects.create";
            case "GetOperation" -> "resourcemanager.projects.get";
            // List returns only the projects the caller may get; ResourceManagerService filters them.
            case "ListProjects" -> null;
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
    public void validatePolicy(String resource, IamPolicy policy) {
        for (IamBinding binding : policy.bindings()) {
            for (String member : binding.members()) {
                if (member.equals("allUsers") || member.equals("allAuthenticatedUsers")) {
                    throw GcpException.invalidArgument("Project IAM policy " + resource
                            + " does not support member " + member);
                }
            }
        }
    }

    @Override
    public Map<String, Set<String>> roles() {
        return Map.of("roles/browser", Set.of("resourcemanager.projects.get", "resourcemanager.projects.getIamPolicy"),
                "roles/resourcemanager.projectIamAdmin", Set.of("resourcemanager.projects.get",
                        "resourcemanager.projects.getIamPolicy", "resourcemanager.projects.setIamPolicy"),
                "roles/resourcemanager.projectDeleter", Set.of("resourcemanager.projects.delete"),
                "roles/resourcemanager.projectMover", Set.of("resourcemanager.projects.get",
                        "resourcemanager.projects.update"));
    }
    @Override
    public Map<String, Set<String>> basicRoles() {
        Map<String, Set<String>> predefined = roles();
        Set<String> editor = union(predefined.get("roles/browser"), Set.of("resourcemanager.projects.update"));
        Set<String> owner = union(predefined.get("roles/resourcemanager.projectIamAdmin"), editor,
                Set.of("resourcemanager.projects.delete", "resourcemanager.projects.undelete"));
        return Map.of("roles/owner", owner, "roles/editor", editor, "roles/viewer", predefined.get("roles/browser"));
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> all = new HashSet<>();
        for (Set<String> set : sets) {
            all.addAll(set);
        }
        return Set.copyOf(all);
    }

}

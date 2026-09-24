package io.floci.gcp.services.secretmanager;

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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Secret Manager v1 uses secret policies for both secrets and their versions. */
@ApplicationScoped
public class SecretManagerIamAuthorizationAdapter implements IamAuthorizationAdapter {
    private static final Map<String, String> PERMISSIONS = Map.ofEntries(
            Map.entry("CreateSecret", "secretmanager.secrets.create"),
            Map.entry("ListSecrets", "secretmanager.secrets.list"),
            Map.entry("GetSecret", "secretmanager.secrets.get"),
            Map.entry("UpdateSecret", "secretmanager.secrets.update"),
            Map.entry("DeleteSecret", "secretmanager.secrets.delete"),
            Map.entry("AddSecretVersion", "secretmanager.versions.add"),
            Map.entry("ListSecretVersions", "secretmanager.versions.list"),
            Map.entry("GetSecretVersion", "secretmanager.versions.get"),
            Map.entry("AccessSecretVersion", "secretmanager.versions.access"),
            Map.entry("EnableSecretVersion", "secretmanager.versions.enable"),
            Map.entry("DisableSecretVersion", "secretmanager.versions.disable"),
            Map.entry("DestroySecretVersion", "secretmanager.versions.destroy"),
            Map.entry("GetIamPolicy", "secretmanager.secrets.getIamPolicy"),
            Map.entry("SetIamPolicy", "secretmanager.secrets.setIamPolicy"));

    @Override
    public String serviceName() {
        return "secretmanager";
    }

    @Override
    public Set<Class<?>> restControllers() {
        return Set.of(SecretManagerHttpController.class);
    }

    @Override
    public Set<String> grpcServices() {
        return Set.of("google.cloud.secretmanager.v1.SecretManagerService");
    }

    @Override
    public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
        String resource = "projects/" + path.get("project");
        if (path.containsKey("secretId")) {
            resource += "/secrets/" + path.get("secretId");
        }
        if (path.containsKey("version")) {
            resource += "/versions/" + path.get("version");
        }
        return new IamOperation(Character.toUpperCase(method.charAt(0)) + method.substring(1), resource);
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        String resource = switch (method) {
            case "CreateSecret", "ListSecrets", "AddSecretVersion", "ListSecretVersions" -> request.path("parent").asText();
            case "UpdateSecret" -> request.path("secret").path("name").asText();
            case "GetIamPolicy", "SetIamPolicy", "TestIamPermissions" -> request.path("resource").asText();
            default -> request.path("name").asText();
        };
        return new IamOperation(method, resource);
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        IamResource resource = operation.resource().matches("projects/[^/]+")
                ? IamResource.project(operation.resource()) : requireResource(operation.resource());
        if (Set.of("TestIamPermissions", "SetIamPolicyWithGet", "TestIamPermissionsWithGet",
                "GetIamPolicyWithPost").contains(operation.name())) {
            return List.of();
        }
        String permission = PERMISSIONS.get(operation.name());
        if (permission == null) {
            throw unsupported("operation " + operation.name());
        }
        return List.of(new IamPermissionCheck(permission, resource));
    }

    @Override
    public Optional<IamResource> resource(String name) {
        if (!name.matches("projects/[^/]+/secrets/[^/]+(/versions/[^/]+)?")) {
            return Optional.empty();
        }
        int version = name.indexOf("/versions/");
        return Optional.of(IamResource.projectChild("secretmanager.googleapis.com",
                version < 0 ? "Secret" : "SecretVersion", name, version < 0 ? name : name.substring(0, version)));
    }

    @Override
    public Map<String, Set<String>> roles() {
        Set<String> viewer = Stream.concat(Stream.of("resourcemanager.projects.get"), PERMISSIONS.values().stream()
                .filter(permission -> permission.endsWith(".get") || permission.endsWith(".list")
                        || permission.endsWith(".getIamPolicy")))
                .collect(Collectors.toUnmodifiableSet());
        return Map.of(
                "roles/secretmanager.secretAccessor", Set.of("secretmanager.versions.access", "resourcemanager.projects.get"),
                "roles/secretmanager.secretVersionAdder", Set.of("secretmanager.versions.add", "resourcemanager.projects.get"),
                "roles/secretmanager.secretVersionManager", Set.of("secretmanager.versions.add", "secretmanager.versions.get",
                        "secretmanager.versions.list", "secretmanager.versions.enable", "secretmanager.versions.disable",
                        "secretmanager.versions.destroy", "resourcemanager.projects.get"),
                "roles/secretmanager.viewer", viewer,
                "roles/secretmanager.admin", Stream.concat(PERMISSIONS.values().stream(), Stream.of("resourcemanager.projects.get"))
                        .collect(Collectors.toUnmodifiableSet()));
    }
    @Override
    public Map<String, Set<String>> basicRoles() {
        Map<String, Set<String>> predefined = roles();
        Set<String> admin = predefined.get("roles/secretmanager.admin");
        Set<String> editor = admin.stream().filter(permission -> !Set.of(
                        "secretmanager.versions.access", "secretmanager.secrets.setIamPolicy").contains(permission))
                .collect(Collectors.toUnmodifiableSet());
        return Map.of("roles/owner", admin, "roles/editor", editor,
                "roles/viewer", predefined.get("roles/secretmanager.viewer"));
    }

}

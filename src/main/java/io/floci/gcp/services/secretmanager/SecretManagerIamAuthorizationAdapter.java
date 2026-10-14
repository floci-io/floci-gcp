package io.floci.gcp.services.secretmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.secretmanager.v1.AccessSecretVersionRequest;
import com.google.cloud.secretmanager.v1.AddSecretVersionRequest;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.DeleteSecretRequest;
import com.google.cloud.secretmanager.v1.DestroySecretVersionRequest;
import com.google.cloud.secretmanager.v1.DisableSecretVersionRequest;
import com.google.cloud.secretmanager.v1.EnableSecretVersionRequest;
import com.google.cloud.secretmanager.v1.GetSecretRequest;
import com.google.cloud.secretmanager.v1.GetSecretVersionRequest;
import com.google.cloud.secretmanager.v1.ListSecretVersionsRequest;
import com.google.cloud.secretmanager.v1.ListSecretsRequest;
import com.google.cloud.secretmanager.v1.UpdateSecretRequest;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.protobuf.Message;
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
public class SecretManagerIamAuthorizationAdapter implements IamAuthorizationAdapter {
    private static final String SERVICE = "secretmanager.googleapis.com";
    private static final Set<String> POLICY_METHODS = Set.of("GetIamPolicy", "SetIamPolicy", "TestIamPermissions");
    private static final Set<String> VIEWER = Set.of(
            "secretmanager.secrets.get", "secretmanager.secrets.getIamPolicy",
            "secretmanager.secrets.list",
            "secretmanager.versions.get", "secretmanager.versions.list");
    private static final Set<String> ADMIN = Set.of(
            "secretmanager.secrets.create", "secretmanager.secrets.get", "secretmanager.secrets.list",
            "secretmanager.secrets.update", "secretmanager.secrets.delete",
            "secretmanager.secrets.getIamPolicy", "secretmanager.secrets.setIamPolicy",
            "secretmanager.versions.add", "secretmanager.versions.get", "secretmanager.versions.list",
            "secretmanager.versions.access", "secretmanager.versions.enable",
            "secretmanager.versions.disable", "secretmanager.versions.destroy");
    private static final Set<String> EDITOR = Set.of(
            "secretmanager.secrets.create", "secretmanager.secrets.get", "secretmanager.secrets.list",
            "secretmanager.secrets.update", "secretmanager.secrets.delete",
            "secretmanager.secrets.getIamPolicy", "secretmanager.versions.add",
            "secretmanager.versions.get", "secretmanager.versions.list",
            "secretmanager.versions.enable", "secretmanager.versions.disable",
            "secretmanager.versions.destroy");

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
        String project = "projects/" + path.get("project");
        String secretId = path.get("secretId");
        String secret = secretId == null ? project : project + "/secrets/" + secretId;
        String version = path.get("version");
        String target = version == null ? secret : secret + "/versions/" + version;
        return new IamOperation(capitalize(method), target);
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        String target = switch (method) {
            case "CreateSecret", "ListSecrets" -> request.path("parent").asText();
            case "AddSecretVersion", "ListSecretVersions" -> request.path("parent").asText();
            case "UpdateSecret" -> request.path("secret").path("name").asText();
            case "GetIamPolicy", "SetIamPolicy", "TestIamPermissions" -> request.path("resource").asText();
            default -> request.path("name").asText();
        };
        return new IamOperation(method, target);
    }

    public IamOperation grpcOperation(String method, Message request) {
        String target = switch (request) {
            case CreateSecretRequest value -> value.getParent();
            case ListSecretsRequest value -> value.getParent();
            case AddSecretVersionRequest value -> value.getParent();
            case ListSecretVersionsRequest value -> value.getParent();
            case UpdateSecretRequest value -> value.getSecret().getName();
            case GetIamPolicyRequest value -> value.getResource();
            case SetIamPolicyRequest value -> value.getResource();
            case TestIamPermissionsRequest value -> value.getResource();
            default -> switch (request) {
                case GetSecretRequest value -> value.getName();
                case DeleteSecretRequest value -> value.getName();
                case GetSecretVersionRequest value -> value.getName();
                case AccessSecretVersionRequest value -> value.getName();
                case EnableSecretVersionRequest value -> value.getName();
                case DisableSecretVersionRequest value -> value.getName();
                case DestroySecretVersionRequest value -> value.getName();
                default -> throw unsupported("operation " + method);
            };
        };
        return new IamOperation(method, target);
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        if (POLICY_METHODS.contains(operation.name())) {
            IamResource policyTarget = requireResource(operation.resource());
            if (!policyTarget.name().equals(policyTarget.policyResource())) {
                throw unsupported("policy resource " + operation.resource());
            }
        }
        String permission = switch (operation.name()) {
            case "CreateSecret" -> "secretmanager.secrets.create";
            case "ListSecrets" -> "secretmanager.secrets.list";
            case "GetSecret" -> "secretmanager.secrets.get";
            case "UpdateSecret" -> "secretmanager.secrets.update";
            case "DeleteSecret" -> "secretmanager.secrets.delete";
            case "AddSecretVersion" -> "secretmanager.versions.add";
            case "ListSecretVersions" -> "secretmanager.versions.list";
            case "GetSecretVersion" -> "secretmanager.versions.get";
            case "AccessSecretVersion" -> "secretmanager.versions.access";
            case "EnableSecretVersion" -> "secretmanager.versions.enable";
            case "DisableSecretVersion" -> "secretmanager.versions.disable";
            case "DestroySecretVersion" -> "secretmanager.versions.destroy";
            case "GetIamPolicy" -> "secretmanager.secrets.getIamPolicy";
            case "SetIamPolicy" -> "secretmanager.secrets.setIamPolicy";
            case "TestIamPermissions", "GetIamPolicyWithPost", "SetIamPolicyWithGet",
                    "TestIamPermissionsWithGet" -> null;
            default -> throw unsupported("operation " + operation.name());
        };
        if (permission == null) {
            return List.of();
        }
        IamResource target = switch (operation.name()) {
            case "CreateSecret", "ListSecrets" -> IamResource.project(operation.resource());
            default -> requireResource(operation.resource());
        };
        return List.of(new IamPermissionCheck(permission, target));
    }

    @Override
    public Optional<IamResource> resource(String name) {
        if (name == null) {
            return Optional.empty();
        }
        if (name.matches("projects/[^/:]+/secrets/[^/:]+")) {
            return Optional.of(IamResource.projectChild(SERVICE, "Secret", name, name));
        }
        if (name.matches("projects/[^/:]+/secrets/[^/:]+/versions/[^/:]+")) {
            String secret = name.substring(0, name.indexOf("/versions/"));
            return Optional.of(IamResource.projectChild(SERVICE, "SecretVersion", name, secret));
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Set<String>> roles() {
        return Map.of(
                "roles/secretmanager.admin", ADMIN,
                "roles/secretmanager.editor", EDITOR,
                "roles/secretmanager.viewer", VIEWER,
                "roles/secretmanager.secretAccessor", Set.of("secretmanager.versions.access"),
                "roles/secretmanager.secretVersionAdder", Set.of("secretmanager.versions.add"),
                "roles/secretmanager.secretVersionManager", Set.of(
                        "secretmanager.versions.add", "secretmanager.versions.get",
                        "secretmanager.versions.list", "secretmanager.versions.enable",
                        "secretmanager.versions.disable", "secretmanager.versions.destroy"));
    }

    @Override
    public Map<String, Set<String>> basicRoles() {
        return Map.of("roles/owner", ADMIN, "roles/editor", EDITOR, "roles/viewer", VIEWER);
    }

    private static String capitalize(String method) {
        return Character.toUpperCase(method.charAt(0)) + method.substring(1);
    }
}

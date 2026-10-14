package io.floci.gcp.services.cloudrun;

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
public class CloudRunIamAuthorizationAdapter implements IamAuthorizationAdapter {
    private static final String SERVICE = "run.googleapis.com";
    private static final String LOCATION_PATTERN = "projects/[^/]+/locations/[^/]+";
    private static final String SERVICE_PATTERN = LOCATION_PATTERN + "/services/[^/]+";
    private static final String REVISION_PATTERN = SERVICE_PATTERN + "/revisions/[^/]+";
    private static final Set<String> VIEWER = Set.of("run.services.get", "run.services.list",
            "run.services.getIamPolicy", "run.revisions.get", "run.revisions.list");
    private static final Set<String> DEVELOPER = Set.of("run.services.create", "run.services.get",
            "run.services.list", "run.services.update", "run.services.delete",
            "run.services.getIamPolicy", "run.revisions.get", "run.revisions.list", "run.revisions.delete");
    private static final Set<String> ADMIN = Set.of("run.services.create", "run.services.get",
            "run.services.list", "run.services.update", "run.services.delete",
            "run.services.getIamPolicy", "run.services.setIamPolicy",
            "run.revisions.get", "run.revisions.list", "run.revisions.delete");

    @Override
    public String serviceName() {
        return "cloudrun";
    }

    @Override
    public Set<Class<?>> restControllers() {
        return Set.of(CloudRunController.class);
    }

    @Override
    public Set<String> grpcServices() {
        return Set.of();
    }

    @Override
    public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
        String parent = "projects/" + path.get("project") + "/locations/" + path.get("location");
        String resource = switch (method) {
            case "createService", "listServices" -> parent;
            case "getService", "updateService", "deleteService", "getIamPolicy", "setIamPolicy",
                    "testIamPermissions", "listRevisions" -> parent + "/services/" + path.get("serviceId");
            case "getRevision", "deleteRevision" -> parent + "/services/" + path.get("serviceId")
                    + "/revisions/" + path.get("revisionId");
            default -> throw unsupported("REST operation " + method);
        };
        return new IamOperation(Character.toUpperCase(method.charAt(0)) + method.substring(1), resource);
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        throw unsupported("gRPC operation " + method);
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        String permission = switch (operation.name()) {
            case "CreateService" -> "run.services.create";
            case "ListServices" -> "run.services.list";
            case "GetService" -> "run.services.get";
            case "UpdateService" -> "run.services.update";
            case "DeleteService" -> "run.services.delete";
            case "GetIamPolicy" -> "run.services.getIamPolicy";
            case "SetIamPolicy" -> "run.services.setIamPolicy";
            case "ListRevisions" -> "run.revisions.list";
            case "GetRevision" -> "run.revisions.get";
            case "DeleteRevision" -> "run.revisions.delete";
            case "TestIamPermissions" -> null;
            default -> throw unsupported("operation " + operation.name());
        };
        if (permission == null) {
            return List.of();
        }
        IamResource target = switch (operation.name()) {
            case "CreateService", "ListServices" -> IamResource.projectChild(SERVICE, "Location",
                    operation.resource(), IamResource.projectName(operation.resource()));
            case "ListRevisions" -> operation.resource().endsWith("/services/-")
                    ? IamResource.projectChild(SERVICE, "Location",
                            operation.resource().substring(0, operation.resource().length() - "/services/-".length()),
                            IamResource.projectName(operation.resource()))
                    : requireResource(operation.resource());
            case "GetRevision", "DeleteRevision" -> revisionResource(operation.resource());
            default -> requireResource(operation.resource());
        };
        return List.of(new IamPermissionCheck(permission, target));
    }

    @Override
    public Optional<IamResource> resource(String name) {
        if (name.matches(SERVICE_PATTERN)) {
            return Optional.of(IamResource.projectChild(SERVICE, "Service", name, name));
        }
        return Optional.empty();
    }

    private IamResource revisionResource(String name) {
        if (!name.matches(REVISION_PATTERN)) {
            throw unsupported("resource " + name);
        }
        String service = name.substring(0, name.indexOf("/revisions/"));
        return IamResource.projectChild(SERVICE, "Revision", name, service);
    }

    @Override
    public Map<String, Set<String>> roles() {
        return Map.of("roles/run.viewer", VIEWER, "roles/run.developer", DEVELOPER,
                "roles/run.admin", ADMIN);
    }

    @Override
    public Map<String, Set<String>> basicRoles() {
        return Map.of("roles/viewer", VIEWER, "roles/editor", DEVELOPER, "roles/owner", ADMIN);
    }
}

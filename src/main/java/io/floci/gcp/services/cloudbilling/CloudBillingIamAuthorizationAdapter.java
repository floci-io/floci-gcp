package io.floci.gcp.services.cloudbilling;

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

/** Cloud Billing checks project permissions; project resources, policies and roles belong to Resource Manager. */
@ApplicationScoped
public class CloudBillingIamAuthorizationAdapter implements IamAuthorizationAdapter {
    @Override
    public String serviceName() {
        return "cloudbilling";
    }

    @Override
    public Set<Class<?>> restControllers() {
        return Set.of(CloudBillingController.class, CloudBillingAccountsController.class);
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
        return new IamOperation(name, "billingAccounts/" + path.getOrDefault("account", ""));
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        throw unsupported("gRPC operation " + method);
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        return switch (operation.name()) {
            case "GetBillingInfo" -> List.of(new IamPermissionCheck("resourcemanager.projects.get",
                    IamResource.project(operation.resource())));
            // GCP requires ownership of the project and the billing account without naming the permission
            // checked, and billing accounts are not modelled, so the update is not evaluated.
            case "UpdateBillingInfo" -> List.of();
            // Billing accounts have no policies here; the stub answers every requested permission.
            case "TestIamPermissions" -> List.of();
            default -> throw unsupported("operation " + operation.name());
        };
    }

    @Override
    public Optional<IamResource> resource(String name) {
        return Optional.empty();
    }

    @Override
    public Map<String, Set<String>> roles() {
        return Map.of();
    }
}

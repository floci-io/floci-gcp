package io.floci.gcp.services.iam.authorization;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.IamResource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Service-owned authorization contract. Implementations are CDI beans discovered by the registry.
 * Both transports must map to the same operation table. Unknown operations must throw, never return
 * an empty check list. An empty list is reserved for explicitly permission-free operations such as
 * testIamPermissions, whose result is evaluated separately.
 */
public interface IamAuthorizationAdapter {
    String serviceName();

    Set<Class<?>> restControllers();

    Set<String> grpcServices();

    IamOperation restOperation(String method, Map<String, String> path, JsonNode body);

    IamOperation grpcOperation(String method, JsonNode request);

    List<IamPermissionCheck> checks(IamOperation operation);

    Optional<IamResource> resource(String name);

    Map<String, Set<String>> roles();

    /** Exact supported-service slices of the basic roles, never inferred from permission suffixes. */
    default Map<String, Set<String>> basicRoles() {
        return Map.of();
    }

    default IamResource requireResource(String name) {
        return resource(name).orElseThrow(() -> unsupported("resource " + name));
    }

    default GcpException unsupported(String detail) {
        return GcpException.failedPrecondition("IAM enforcement has no mapping for " + serviceName() + " " + detail);
    }
}

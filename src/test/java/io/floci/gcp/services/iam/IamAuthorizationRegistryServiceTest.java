package io.floci.gcp.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamAuthorizationRegistry;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import io.floci.gcp.services.resourcemanager.ResourceManagerIamAuthorizationAdapter;
import jakarta.ws.rs.HttpMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IamAuthorizationRegistryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<IamAuthorizationAdapter> adapters = List.of(new ResourceManagerIamAuthorizationAdapter());

    @Test
    void everyImplementedRestHandlerHasAnExplicitOperationDecision() {
        for (IamAuthorizationAdapter adapter : adapters) {
            for (Class<?> controller : adapter.restControllers()) {
                for (Method method : controller.getDeclaredMethods()) {
                    if (Arrays.stream(method.getAnnotations())
                            .noneMatch(annotation -> annotation.annotationType().isAnnotationPresent(HttpMethod.class))) {
                        continue;
                    }
                    Map<String, String> path = Map.of("project", "p");
                    JsonNode body = mapper.createObjectNode();
                    IamOperation operation = adapter.restOperation(method.getName(), path, body);
                    assertDoesNotThrow(() -> adapter.checks(operation), controller.getSimpleName() + "." + method.getName());
                }
            }
        }
    }

    @Test
    void newOperationAndUnrecognizedResourceFailClosedWithNames() {
        for (IamAuthorizationAdapter adapter : adapters) {
            GcpException exception = assertThrows(GcpException.class,
                    () -> adapter.checks(new IamOperation("UnmappedOperation", "projects/p")));
            assertTrue(exception.getMessage().contains("UnmappedOperation"));
            GcpException resource = assertThrows(GcpException.class,
                    () -> adapter.requireResource("projects/p/unimplemented/x"));
            assertTrue(resource.getMessage().contains("projects/p/unimplemented/x"));
        }
    }

    @Test
    void registryRejectsDuplicateTransportOwnership() {
        assertThrows(IllegalStateException.class,
                () -> new IamAuthorizationRegistry(List.of(adapters.getFirst(), adapters.getFirst())));
    }

    @Test
    void extensionCanContributeRoutesResourcesAndRolesWithoutChangingSharedCode() {
        IamAuthorizationAdapter extension = new ExampleAdapter();
        IamAuthorizationRegistry registry = new IamAuthorizationRegistry(List.of(extension));
        assertSame(extension, registry.rest(ExampleAdapter.class).orElseThrow());
        assertSame(extension, registry.grpc("example.v1.Service").orElseThrow());
        IamResource resource = registry.resource("projects/p/examples/e").orElseThrow()
                .requireResource("projects/p/examples/e");
        assertEquals(List.of("projects/p/examples/e", "projects/p"),
                new IamResourceHierarchy().policyResourcesFor(resource));
        IamRoleCatalog catalog = new IamRoleCatalog(registry);
        assertTrue(catalog.grants("roles/example.reader", "example.resources.get"));
        assertFalse(catalog.grants("roles/example.reader", "example.resources.delete"));
    }

    @Test
    void projectRolesHaveExplicitBasicRoleSlices() {
        IamRoleCatalog catalog = new IamRoleCatalog(new IamAuthorizationRegistry(adapters));
        for (String role : List.of("roles/browser", "roles/viewer", "roles/editor")) {
            assertTrue(catalog.grants(role, "resourcemanager.projects.get"));
            assertTrue(catalog.grants(role, "resourcemanager.projects.getIamPolicy"));
            assertFalse(catalog.grants(role, "resourcemanager.projects.setIamPolicy"));
        }
        for (String role : List.of("roles/owner", "roles/resourcemanager.projectIamAdmin")) {
            assertTrue(catalog.grants(role, "resourcemanager.projects.setIamPolicy"));
        }
        assertEquals(List.of("projects/p"), new IamResourceHierarchy().policyResourcesFor(IamResource.project("projects/p")));
    }

    @Test
    void childEvaluationIncludesProjectButNeverSiblingPolicies() {
        IamAuthorizationRegistry registry = new IamAuthorizationRegistry(List.of(new ExampleAdapter()));
        IamResource resource = registry.resource("projects/p/examples/e").orElseThrow()
                .requireResource("projects/p/examples/e");
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new IamRoleCatalog(registry),
                new IamResourceHierarchy(), new NessieIamConditionEvaluator());
        IamPrincipal principal = IamPrincipal.serviceAccount("reader@p.iam.gserviceaccount.com");
        IamPolicy grant = new IamPolicy(1, List.of(new IamBinding("roles/example.reader",
                List.of("serviceAccount:reader@p.iam.gserviceaccount.com"), null)), null);
        assertTrue(evaluator.isAllowed(principal, "example.resources.get", resource, Map.of("projects/p", grant)));
        assertFalse(evaluator.isAllowed(principal, "example.resources.get", resource,
                Map.of("projects/p/examples/sibling", grant, "projects/other", grant)));
        assertEquals("projects/p/examples/e", resource.name());
    }

    private static class ExampleAdapter implements IamAuthorizationAdapter {
        @Override
        public String serviceName() { return "example"; }
        @Override
        public Set<Class<?>> restControllers() { return Set.of(ExampleAdapter.class); }
        @Override
        public Set<String> grpcServices() { return Set.of("example.v1.Service"); }
        @Override
        public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
            return new IamOperation("Get", "projects/p/examples/e");
        }
        @Override
        public IamOperation grpcOperation(String method, JsonNode request) {
            return new IamOperation("Get", request.path("name").asText());
        }
        @Override
        public List<IamPermissionCheck> checks(IamOperation operation) {
            if (!operation.name().equals("Get")) {
                throw unsupported(operation.name());
            }
            return List.of(new IamPermissionCheck("example.resources.get", requireResource(operation.resource())));
        }
        @Override
        public Optional<IamResource> resource(String name) {
            return name.matches("projects/[^/]+/examples/[^/]+")
                    ? Optional.of(IamResource.projectChild("example.googleapis.com", "Example", name, name))
                    : Optional.empty();
        }
        @Override
        public Map<String, Set<String>> roles() {
            return Map.of("roles/example.reader", Set.of("example.resources.get"));
        }
    }
}

package io.floci.gcp.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Message;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamAuthorizationRegistry;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import io.floci.gcp.services.pubsub.PubSubIamAuthorizationAdapter;
import io.floci.gcp.services.pubsub.PubSubPublisherController;
import io.floci.gcp.services.pubsub.PubSubService;
import io.floci.gcp.services.pubsub.PubSubSubscriberController;
import io.floci.gcp.services.pubsub.model.StoredSubscription;
import io.floci.gcp.services.resourcemanager.ResourceManagerIamAuthorizationAdapter;
import io.floci.gcp.services.secretmanager.SecretManagerController;
import io.floci.gcp.services.secretmanager.SecretManagerIamAuthorizationAdapter;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.HttpMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IamAuthorizationRegistryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<IamAuthorizationAdapter> adapters = List.of(pubsubAdapter(),
            new SecretManagerIamAuthorizationAdapter(), new ResourceManagerIamAuthorizationAdapter());

    @Test
    void everyImplementedRestHandlerHasAnExplicitOperationDecision() {
        for (IamAuthorizationAdapter adapter : adapters) {
            for (Class<?> controller : adapter.restControllers()) {
                for (Method method : controller.getDeclaredMethods()) {
                    if (Arrays.stream(method.getAnnotations())
                            .noneMatch(annotation -> annotation.annotationType().isAnnotationPresent(HttpMethod.class))) {
                        continue;
                    }
                    Map<String, String> path = switch (adapter.serviceName()) {
                        case "pubsub" -> method.getName().toLowerCase().contains("subscription")
                                || Set.of("pull", "acknowledge").contains(method.getName())
                                ? Map.of("project", "p", "subscription", "s")
                                : method.getName().contains("Snapshot") ? Map.of("project", "p", "snapshot", "s")
                                : Map.of("project", "p", "topic", "t");
                        case "secretmanager" -> Map.of("project", "p", "secretId", "s", "version", "1");
                        default -> Map.of("project", "p");
                    };
                    JsonNode body = mapper.valueToTree(Map.of("topic", "projects/p/topics/t"));
                    IamOperation operation = adapter.restOperation(method.getName(), path, body);
                    assertDoesNotThrow(() -> adapter.checks(operation), controller.getSimpleName() + "." + method.getName());
                }
            }
        }
    }

    @Test
    void everyImplementedGrpcHandlerHasAnExplicitOperationDecision() {
        Map<Class<?>, IamAuthorizationAdapter> controllers = Map.of(
                PubSubPublisherController.class, adapters.get(0), PubSubSubscriberController.class, adapters.get(0),
                SecretManagerController.class, adapters.get(1));
        controllers.forEach((controller, adapter) -> {
            for (Method method : controller.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 || (!Message.class.isAssignableFrom(method.getParameterTypes()[0])
                        && !method.getName().equals("streamingPull"))) {
                    continue;
                }
                String operation = Character.toUpperCase(method.getName().charAt(0)) + method.getName().substring(1);
                ObjectNode request = mapper.createObjectNode();
                String name = operation.contains("Snapshot") ? "projects/p/snapshots/s"
                        : operation.contains("Subscription") ? "projects/p/subscriptions/s"
                        : adapter.serviceName().equals("pubsub") ? "projects/p/topics/t"
                        : operation.contains("Version") ? "projects/p/secrets/s/versions/1" : "projects/p/secrets/s";
                request.put("name", name).put("resource", name).put("project", "projects/p")
                        .put("parent", operation.equals("CreateSecret") || operation.equals("ListSecrets")
                                ? "projects/p" : "projects/p/secrets/s");
                for (String field : List.of("topic", "subscription", "snapshot", "secret")) {
                    String fieldName = "projects/p/" + field + "s/" + field;
                    if (operation.equals("Update" + Character.toUpperCase(field.charAt(0)) + field.substring(1))) {
                        request.putObject(field).put("name", fieldName);
                    } else {
                        request.put(field, fieldName);
                    }
                }
                IamOperation mapped = adapter.grpcOperation(operation, request);
                assertDoesNotThrow(() -> adapter.checks(mapped), controller.getSimpleName() + "." + method.getName());
            }
        });
    }

    @Test
    void relatedResourcesUseTheSameSelectionForRestAndGrpc() {
        IamAuthorizationAdapter pubsub = adapters.getFirst();
        for (Map.Entry<String, String> operation : Map.of("CreateSubscription", "topic",
                "CreateSnapshot", "subscription", "Seek", "snapshot").entrySet()) {
            JsonNode body = mapper.valueToTree(Map.of(operation.getValue(), "projects/p/" + operation.getValue() + "s/related",
                    "name", "projects/p/snapshots/new"));
            String restMethod = Character.toLowerCase(operation.getKey().charAt(0)) + operation.getKey().substring(1);
            IamOperation rest = pubsub.restOperation(restMethod, Map.of("project", "p", "subscription", "s"), body);
            IamOperation grpc = pubsub.grpcOperation(operation.getKey(), body);
            assertEquals("projects/p/" + operation.getValue() + "s/related", rest.relatedResource());
            assertEquals(rest.relatedResource(), grpc.relatedResource());
        }
    }

    @SuppressWarnings("unchecked")
    private static PubSubIamAuthorizationAdapter pubsubAdapter() {
        Instance<PubSubService> instance = mock(Instance.class);
        PubSubService service = mock(PubSubService.class);
        StoredSubscription subscription = mock(StoredSubscription.class);
        when(instance.get()).thenReturn(service);
        when(service.getSubscription(anyString())).thenReturn(subscription);
        when(subscription.getTopic()).thenReturn("projects/p/topics/t");
        return new PubSubIamAuthorizationAdapter(instance);
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
    void childPoliciesInheritProjectWithoutInheritingSiblingPolicies() {
        IamResourceHierarchy hierarchy = new IamResourceHierarchy();
        for (String resource : List.of("projects/p/topics/t", "projects/p/subscriptions/s", "projects/p/secrets/s/versions/1")) {
            IamAuthorizationRegistry registry = new IamAuthorizationRegistry(adapters);
            IamResource target = registry.resource(resource).orElseThrow().requireResource(resource);
            assertEquals("projects/p", hierarchy.policyResourcesFor(target).getLast());
            assertEquals(2, hierarchy.policyResourcesFor(target).size());
            assertEquals(resource, target.name(), "CEL sees the resource name, not the policy storage key");
        }
        assertEquals(List.of("projects/p"), hierarchy.policyResourcesFor(IamResource.project("projects/p")));
    }

    @Test
    void narrowAndBasicRoleMappingsRespectUpstreamPolicyReadAndSecretAccessDifferences() {
        IamRoleCatalog catalog = new IamRoleCatalog(new IamAuthorizationRegistry(adapters));
        for (String role : List.of("roles/pubsub.viewer", "roles/pubsub.editor", "roles/viewer", "roles/editor")) {
            assertFalse(catalog.grants(role, "pubsub.topics.getIamPolicy"), role);
        }
        assertTrue(catalog.grants("roles/pubsub.admin", "pubsub.topics.getIamPolicy"));
        assertTrue(catalog.grants("roles/pubsub.subscriber", "pubsub.topics.attachSubscription"));
        for (String role : List.of("roles/viewer", "roles/editor")) {
            assertTrue(catalog.grants(role, "secretmanager.secrets.getIamPolicy"));
            assertFalse(catalog.grants(role, "secretmanager.versions.access"));
        }
        assertTrue(catalog.grants("roles/owner", "secretmanager.versions.access"));
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

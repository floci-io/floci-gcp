package io.floci.gcp.services.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.google.pubsub.v1.StreamingPullRequest;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.pubsub.model.StoredSubscription;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PubSubIamAuthorizationAdapterServiceTest {
    private static final String PROJECT = "projects/iam-inventory";
    private static final String TOPIC = PROJECT + "/topics/topic";
    private static final String SUBSCRIPTION = PROJECT + "/subscriptions/subscription";
    private static final String SNAPSHOT = PROJECT + "/snapshots/snapshot";

    @Test
    void everyImplementedRestAndGrpcHandlerHasAnAuthorizationMapping() throws Exception {
        PubSubService service = mock(PubSubService.class);
        when(service.getSubscription(SUBSCRIPTION))
                .thenReturn(new StoredSubscription(SUBSCRIPTION, TOPIC, 10));
        PubSubIamAuthorizationAdapter adapter = new PubSubIamAuthorizationAdapter(service);
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> path = Map.of("project", "iam-inventory", "topic", "topic",
                "subscription", "subscription", "snapshot", "snapshot");
        ObjectNode body = mapper.createObjectNode().put("topic", TOPIC);

        Method[] restHandlers = Arrays.stream(PubSubRestController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Path.class)).toArray(Method[]::new);
        assertEquals(22, restHandlers.length);
        for (Method method : restHandlers) {
            IamOperation operation = adapter.restOperation(method.getName(), path, body);
            assertFalse(operation.resource().isBlank(), method.getName());
            adapter.checks(operation);
        }

        int grpcHandlers = 0;
        for (Class<?> controller : new Class<?>[] {
                PubSubPublisherController.class, PubSubSubscriberController.class}) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                String name = Character.toUpperCase(method.getName().charAt(0))
                        + method.getName().substring(1);
                ObjectNode request = mapper.createObjectNode()
                        .put("name", TOPIC).put("topic", TOPIC)
                        .put("subscription", SUBSCRIPTION).put("snapshot", SNAPSHOT)
                        .put("project", PROJECT);
                switch (name) {
                    case "UpdateTopic" -> request.putObject("topic").put("name", TOPIC);
                    case "UpdateSubscription" -> request.putObject("subscription").put("name", SUBSCRIPTION);
                    case "UpdateSnapshot" -> request.putObject("snapshot").put("name", SNAPSHOT);
                    case "CreateSubscription" -> request.put("name", SUBSCRIPTION);
                    case "CreateSnapshot" -> request.put("name", SNAPSHOT);
                    default -> { }
                }
                IamOperation operation = adapter.grpcOperation(name, request);
                assertFalse(operation.resource().isBlank(), name);
                adapter.checks(operation);
                Message proto;
                if (name.equals("StreamingPull")) {
                    proto = StreamingPullRequest.newBuilder().setSubscription(SUBSCRIPTION).build();
                } else {
                    Message defaultInstance = (Message) method.getParameterTypes()[0]
                            .getMethod("getDefaultInstance").invoke(null);
                    Message.Builder builder = defaultInstance.newBuilderForType();
                    JsonFormat.parser().ignoringUnknownFields().merge(request.toString(), builder);
                    proto = builder.build();
                }
                IamOperation typed = adapter.grpcOperation(name, proto, mapper);
                assertEquals(operation, typed, name);
                grpcHandlers++;
            }
        }
        assertEquals(24, grpcHandlers);
    }
}

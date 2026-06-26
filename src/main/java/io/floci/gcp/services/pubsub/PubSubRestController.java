package io.floci.gcp.services.pubsub;

import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.ReceivedMessage;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.pubsub.model.StoredSubscription;
import io.floci.gcp.services.pubsub.model.StoredTopic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/v1/projects/{project}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PubSubRestController {

    private static final Logger LOG = Logger.getLogger(PubSubRestController.class);

    private final PubSubService service;

    PubSubRestController() {
        this.service = null;
    }

    @Inject
    public PubSubRestController(PubSubService service) {
        this.service = service;
    }

    @PUT
    @Path("/topics/{topic}")
    public Response createTopic(@PathParam("project") String project,
                                @PathParam("topic") String topicId,
                                Map<String, Object> body) {
        String name = topicName(project, topicId);
        LOG.infof("REST create Pub/Sub topic name=%s", name);
        StoredTopic topic = service.createTopic(name, stringMap(body, "labels"),
                stringValue(body, "messageRetentionDuration"));
        return Response.ok(topicResponse(topic)).build();
    }

    @GET
    @Path("/topics/{topic}")
    public Response getTopic(@PathParam("project") String project,
                             @PathParam("topic") String topicId) {
        return Response.ok(topicResponse(service.getTopic(topicName(project, topicId)))).build();
    }

    @GET
    @Path("/topics")
    public Response listTopics(@PathParam("project") String project,
                               @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                               @QueryParam("pageToken") String pageToken) {
        PageToken.Page<StoredTopic> page = PageToken.paginate(
                service.listTopics(project), pageSize, pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("topics", page.items().stream()
                .map(PubSubRestController::topicResponse)
                .toList());
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/topics/{topic}")
    public Response updateTopic(@PathParam("project") String project,
                                @PathParam("topic") String topicId,
                                @QueryParam("updateMask") String updateMask,
                                Map<String, Object> body) {
        Map<String, Object> topicBody = updateResource(body, "topic");
        StoredTopic topic = service.updateTopic(topicName(project, topicId),
                stringMap(topicBody, "labels"), stringValue(topicBody, "messageRetentionDuration"),
                updateMaskPaths(updateMask(updateMask, body)));
        return Response.ok(topicResponse(topic)).build();
    }

    @DELETE
    @Path("/topics/{topic}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteTopic(@PathParam("project") String project,
                                @PathParam("topic") String topicId) {
        service.deleteTopic(topicName(project, topicId));
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/topics/{topic}:publish")
    public Response publish(@PathParam("project") String project,
                            @PathParam("topic") String topicId,
                            Map<String, Object> body) {
        List<PubsubMessage> messages = messages(body);
        List<String> ids = service.publish(topicName(project, topicId), messages);
        return Response.ok(Map.of("messageIds", ids)).build();
    }

    @PUT
    @Path("/subscriptions/{subscription}")
    public Response createSubscription(@PathParam("project") String project,
                                       @PathParam("subscription") String subscriptionId,
                                       Map<String, Object> body) {
        String name = subscriptionName(project, subscriptionId);
        String topic = stringValue(body, "topic");
        LOG.infof("REST create Pub/Sub subscription name=%s topic=%s", name, topic);
        StoredSubscription subscription = service.createSubscription(
                name,
                topic,
                intValue(body, "ackDeadlineSeconds"),
                stringMap(body, "labels"),
                booleanValue(body, "retainAckedMessages"),
                stringValue(body, "messageRetentionDuration"),
                stringValue(body, "filter"),
                pushEndpoint(body),
                objectMap(body, "bigqueryConfig"),
                objectMap(body, "expirationPolicy"),
                objectMap(body, "retryPolicy"),
                deadLetterTopic(body),
                maxDeliveryAttempts(body),
                booleanValue(body, "enableMessageOrdering"),
                booleanValue(body, "enableExactlyOnceDelivery"));
        return Response.ok(subscriptionResponse(subscription)).build();
    }

    @GET
    @Path("/subscriptions/{subscription}")
    public Response getSubscription(@PathParam("project") String project,
                                    @PathParam("subscription") String subscriptionId) {
        return Response.ok(subscriptionResponse(
                service.getSubscription(subscriptionName(project, subscriptionId)))).build();
    }

    @GET
    @Path("/subscriptions")
    public Response listSubscriptions(@PathParam("project") String project,
                                      @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                      @QueryParam("pageToken") String pageToken) {
        PageToken.Page<StoredSubscription> page = PageToken.paginate(
                service.listSubscriptions(project), pageSize, pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("subscriptions", page.items().stream()
                .map(PubSubRestController::subscriptionResponse)
                .toList());
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/subscriptions/{subscription}")
    public Response updateSubscription(@PathParam("project") String project,
                                       @PathParam("subscription") String subscriptionId,
                                       @QueryParam("updateMask") String updateMask,
                                       Map<String, Object> body) {
        Map<String, Object> subscriptionBody = updateResource(body, "subscription");
        StoredSubscription subscription = service.updateSubscription(
                subscriptionName(project, subscriptionId),
                intValue(subscriptionBody, "ackDeadlineSeconds"),
                stringMap(subscriptionBody, "labels"),
                optionalBooleanValue(subscriptionBody, "retainAckedMessages"),
                stringValue(subscriptionBody, "messageRetentionDuration"),
                stringValue(subscriptionBody, "filter"),
                pushEndpoint(subscriptionBody),
                objectMap(subscriptionBody, "bigqueryConfig"),
                objectMap(subscriptionBody, "expirationPolicy"),
                objectMap(subscriptionBody, "retryPolicy"),
                deadLetterTopic(subscriptionBody),
                maxDeliveryAttempts(subscriptionBody) == 0 ? null : maxDeliveryAttempts(subscriptionBody),
                optionalBooleanValue(subscriptionBody, "enableMessageOrdering"),
                optionalBooleanValue(subscriptionBody, "enableExactlyOnceDelivery"),
                updateMaskPaths(updateMask(updateMask, body)));
        return Response.ok(subscriptionResponse(subscription)).build();
    }

    @DELETE
    @Path("/subscriptions/{subscription}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteSubscription(@PathParam("project") String project,
                                       @PathParam("subscription") String subscriptionId) {
        service.deleteSubscription(subscriptionName(project, subscriptionId));
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/subscriptions/{subscription}:pull")
    public Response pull(@PathParam("project") String project,
                         @PathParam("subscription") String subscriptionId,
                         Map<String, Object> body) {
        int maxMessages = intValue(body, "maxMessages");
        List<Map<String, Object>> messages = service.pull(
                        subscriptionName(project, subscriptionId), maxMessages > 0 ? maxMessages : 1)
                .stream()
                .map(PubSubRestController::receivedMessageResponse)
                .toList();
        return Response.ok(Map.of("receivedMessages", messages)).build();
    }

    @POST
    @Path("/subscriptions/{subscription}:acknowledge")
    public Response acknowledge(@PathParam("project") String project,
                                @PathParam("subscription") String subscriptionId,
                                Map<String, Object> body) {
        service.acknowledge(subscriptionName(project, subscriptionId), stringList(body, "ackIds"));
        return Response.ok(Map.of()).build();
    }

    private static String topicName(String project, String topicId) {
        return "projects/" + project + "/topics/" + topicId;
    }

    private static String subscriptionName(String project, String subscriptionId) {
        return "projects/" + project + "/subscriptions/" + subscriptionId;
    }

    private static Map<String, Object> topicResponse(StoredTopic topic) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", topic.getName());
        if (topic.getLabels() != null) {
            response.put("labels", topic.getLabels());
        }
        if (topic.getMessageRetentionDuration() != null) {
            response.put("messageRetentionDuration", topic.getMessageRetentionDuration());
        }
        return response;
    }

    private static Map<String, Object> subscriptionResponse(StoredSubscription subscription) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", subscription.getName());
        response.put("topic", subscription.getTopic());
        response.put("ackDeadlineSeconds", subscription.getAckDeadlineSeconds());
        response.put("retainAckedMessages", subscription.isRetainAckedMessages());
        response.put("enableMessageOrdering", subscription.isEnableMessageOrdering());
        response.put("enableExactlyOnceDelivery", subscription.isEnableExactlyOnceDelivery());
        response.put("detached", subscription.isDetached());
        if (subscription.getLabels() != null) {
            response.put("labels", subscription.getLabels());
        }
        if (subscription.getMessageRetentionDuration() != null) {
            response.put("messageRetentionDuration", subscription.getMessageRetentionDuration());
        }
        if (subscription.getFilter() != null) {
            response.put("filter", subscription.getFilter());
        }
        if (subscription.getPushEndpoint() != null) {
            response.put("pushConfig", Map.of("pushEndpoint", subscription.getPushEndpoint()));
        }
        if (subscription.getBigQueryConfig() != null) {
            Map<String, Object> bigQueryConfig = new LinkedHashMap<>(subscription.getBigQueryConfig());
            bigQueryConfig.putIfAbsent("state", "ACTIVE");
            response.put("bigqueryConfig", bigQueryConfig);
        }
        if (subscription.getExpirationPolicy() != null) {
            response.put("expirationPolicy", subscription.getExpirationPolicy());
        }
        if (subscription.getRetryPolicy() != null) {
            response.put("retryPolicy", subscription.getRetryPolicy());
        }
        if (subscription.getDeadLetterTopic() != null) {
            response.put("deadLetterPolicy", Map.of(
                    "deadLetterTopic", subscription.getDeadLetterTopic(),
                    "maxDeliveryAttempts", subscription.getMaxDeliveryAttempts()));
        }
        return response;
    }

    private static Map<String, Object> receivedMessageResponse(ReceivedMessage received) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ackId", received.getAckId());
        response.put("message", messageResponse(received.getMessage()));
        return response;
    }

    private static Map<String, Object> messageResponse(PubsubMessage message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("messageId", message.getMessageId());
        response.put("data", Base64.getEncoder().encodeToString(message.getData().toByteArray()));
        if (!message.getAttributesMap().isEmpty()) {
            response.put("attributes", message.getAttributesMap());
        }
        if (!message.getOrderingKey().isEmpty()) {
            response.put("orderingKey", message.getOrderingKey());
        }
        return response;
    }

    private static List<PubsubMessage> messages(Map<String, Object> body) {
        Object raw = value(body, "messages");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<PubsubMessage> messages = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                PubsubMessage.Builder builder = PubsubMessage.newBuilder();
                String data = stringValue(map, "data");
                if (data != null) {
                    builder.setData(ByteString.copyFrom(Base64.getDecoder().decode(data)));
                }
                Map<String, String> attributes = stringMap(map, "attributes");
                if (attributes != null) {
                    builder.putAllAttributes(attributes);
                }
                String orderingKey = stringValue(map, "orderingKey");
                if (orderingKey != null) {
                    builder.setOrderingKey(orderingKey);
                }
                messages.add(builder.build());
            }
        }
        return messages;
    }

    private static List<String> updateMaskPaths(String updateMask) {
        if (updateMask == null || updateMask.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(updateMask.split(","))
                .map(String::trim)
                .filter(path -> !path.isEmpty())
                .toList();
    }

    private static String updateMask(String queryParam, Map<String, Object> body) {
        return queryParam == null || queryParam.isBlank() ? stringValue(body, "updateMask") : queryParam;
    }

    private static Map<String, Object> updateResource(Map<String, Object> body, String key) {
        Map<String, Object> nested = objectMap(body, key);
        return nested == null ? body : nested;
    }

    private static String pushEndpoint(Map<String, Object> body) {
        Map<String, Object> pushConfig = objectMap(body, "pushConfig");
        return pushConfig == null ? null : stringValue(pushConfig, "pushEndpoint");
    }

    private static int maxDeliveryAttempts(Map<String, Object> body) {
        Map<String, Object> deadLetterPolicy = objectMap(body, "deadLetterPolicy");
        return deadLetterPolicy == null ? 0 : intValue(deadLetterPolicy, "maxDeliveryAttempts");
    }

    private static String deadLetterTopic(Map<String, Object> body) {
        Map<String, Object> deadLetterPolicy = objectMap(body, "deadLetterPolicy");
        return deadLetterPolicy == null ? null : stringValue(deadLetterPolicy, "deadLetterTopic");
    }

    private static Map<String, String> stringMap(Map<?, ?> body, String key) {
        Object raw = value(body, key);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return out;
    }

    private static Map<String, Object> objectMap(Map<String, Object> body, String key) {
        Object raw = value(body, key);
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return out;
    }

    private static List<String> stringList(Map<String, Object> body, String key) {
        Object raw = value(body, key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item != null)
                .map(Object::toString)
                .toList();
    }

    private static String stringValue(Map<?, ?> body, String key) {
        Object value = value(body, key);
        return value == null ? null : value.toString();
    }

    private static int intValue(Map<String, Object> body, String key) {
        Object value = value(body, key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            return Integer.parseInt(string);
        }
        return 0;
    }

    private static boolean booleanValue(Map<String, Object> body, String key) {
        return Boolean.TRUE.equals(value(body, key));
    }

    private static Boolean optionalBooleanValue(Map<String, Object> body, String key) {
        Object value = value(body, key);
        return value instanceof Boolean bool ? bool : null;
    }

    private static Object value(Map<?, ?> body, String key) {
        return body == null ? null : body.get(key);
    }
}

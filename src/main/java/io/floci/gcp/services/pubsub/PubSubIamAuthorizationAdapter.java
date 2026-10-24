package io.floci.gcp.services.pubsub;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Message;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.CreateSnapshotRequest;
import com.google.pubsub.v1.DeleteSnapshotRequest;
import com.google.pubsub.v1.DeleteSubscriptionRequest;
import com.google.pubsub.v1.DeleteTopicRequest;
import com.google.pubsub.v1.DetachSubscriptionRequest;
import com.google.pubsub.v1.GetSnapshotRequest;
import com.google.pubsub.v1.GetSubscriptionRequest;
import com.google.pubsub.v1.GetTopicRequest;
import com.google.pubsub.v1.ListSnapshotsRequest;
import com.google.pubsub.v1.ListSubscriptionsRequest;
import com.google.pubsub.v1.ListTopicSnapshotsRequest;
import com.google.pubsub.v1.ListTopicSubscriptionsRequest;
import com.google.pubsub.v1.ListTopicsRequest;
import com.google.pubsub.v1.ModifyAckDeadlineRequest;
import com.google.pubsub.v1.ModifyPushConfigRequest;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.SeekRequest;
import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.Topic;
import com.google.pubsub.v1.UpdateSnapshotRequest;
import com.google.pubsub.v1.UpdateSubscriptionRequest;
import com.google.pubsub.v1.UpdateTopicRequest;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class PubSubIamAuthorizationAdapter implements IamAuthorizationAdapter {
    private static final String SERVICE = "pubsub.googleapis.com";
    private static final Set<String> TOPIC_PERMISSIONS = Set.of(
            "pubsub.topics.create", "pubsub.topics.get", "pubsub.topics.list",
            "pubsub.topics.update", "pubsub.topics.delete", "pubsub.topics.publish",
            "pubsub.topics.attachSubscription", "pubsub.topics.detachSubscription",
            "pubsub.topics.getIamPolicy", "pubsub.topics.setIamPolicy");
    private static final Set<String> SUBSCRIPTION_PERMISSIONS = Set.of(
            "pubsub.subscriptions.create", "pubsub.subscriptions.get", "pubsub.subscriptions.list",
            "pubsub.subscriptions.update", "pubsub.subscriptions.delete",
            "pubsub.subscriptions.consume", "pubsub.subscriptions.getIamPolicy",
            "pubsub.subscriptions.setIamPolicy");
    private static final Set<String> SNAPSHOT_PERMISSIONS = Set.of(
            "pubsub.snapshots.create", "pubsub.snapshots.get", "pubsub.snapshots.list",
            "pubsub.snapshots.update", "pubsub.snapshots.delete", "pubsub.snapshots.seek",
            "pubsub.snapshots.getIamPolicy", "pubsub.snapshots.setIamPolicy");
    private static final Set<String> ADMIN_PERMISSIONS = union(TOPIC_PERMISSIONS,
            SUBSCRIPTION_PERMISSIONS, SNAPSHOT_PERMISSIONS);
    private static final Set<String> EDITOR_PERMISSIONS = ADMIN_PERMISSIONS.stream()
            .filter(permission -> !permission.endsWith("IamPolicy"))
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> VIEWER_PERMISSIONS = Set.of(
            "pubsub.topics.get", "pubsub.topics.list",
            "pubsub.subscriptions.get", "pubsub.subscriptions.list",
            "pubsub.snapshots.get", "pubsub.snapshots.list");
    private static final Set<String> BASIC_VIEWER_PERMISSIONS = withPermission(
            VIEWER_PERMISSIONS, "pubsub.snapshots.seek");

    private final PubSubService service;

    @Inject
    public PubSubIamAuthorizationAdapter(PubSubService service) {
        this.service = service;
    }

    @Override
    public String serviceName() {
        return "pubsub";
    }

    @Override
    public Set<Class<?>> restControllers() {
        return Set.of(PubSubRestController.class);
    }

    @Override
    public Set<String> grpcServices() {
        return Set.of("google.pubsub.v1.Publisher", "google.pubsub.v1.Subscriber");
    }

    @Override
    public Optional<GrpcStreamAuthorization> grpcStreamAuthorization(String method) {
        if (!method.equals("StreamingPull")) {
            return Optional.empty();
        }
        return Optional.of((message, previous) -> {
            StreamingPullRequest request = (StreamingPullRequest) message;
            String subscription = request.getSubscription();
            if (previous == null) {
                if (subscription.isBlank()) {
                    throw GcpException.invalidArgument("StreamingPull requires a subscription in its first request");
                }
                return new IamOperation("streamingPull", subscription);
            }
            if (!subscription.isBlank()) {
                throw GcpException.invalidArgument("StreamingPull subscription must only be set in the first request");
            }
            return previous;
        });
    }

    @Override
    public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
        String project = "projects/" + required(path.get("project"), "project");
        String topic = path.containsKey("topic") ? project + "/topics/" + path.get("topic") : project;
        String subscription = path.containsKey("subscription")
                ? project + "/subscriptions/" + path.get("subscription") : project;
        String snapshot = path.containsKey("snapshot") ? project + "/snapshots/" + path.get("snapshot") : project;
        return switch (method) {
            case "createTopic", "getTopic", "updateTopic", "deleteTopic", "publish", "listTopicSnapshots",
                    "getTopicIamPolicy", "setTopicIamPolicy", "testTopicIamPermissions" ->
                    new IamOperation(method, topic);
            case "listTopics" -> new IamOperation(method, project);
            case "createSubscription" -> new IamOperation(method, subscription,
                    required(body.path("topic").asText(), "topic"));
            case "getSubscription", "updateSubscription", "deleteSubscription", "pull", "acknowledge",
                    "modifyAckDeadline",
                    "getSubscriptionIamPolicy", "setSubscriptionIamPolicy",
                    "testSubscriptionIamPermissions" -> new IamOperation(method, subscription);
            case "listSubscriptions" -> new IamOperation(method, project);
            case "seek" -> new IamOperation(method, subscription, body.path("snapshot").asText());
            case "createSnapshot" -> new IamOperation(method, snapshot,
                    required(body.path("subscription").asText(), "subscription"));
            case "getSnapshot", "updateSnapshot", "deleteSnapshot",
                    "getSnapshotIamPolicy", "setSnapshotIamPolicy", "testSnapshotIamPermissions" ->
                    new IamOperation(method, snapshot);
            case "listSnapshots" -> new IamOperation(method, project);
            default -> throw unsupported("REST operation " + method);
        };
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        return switch (method) {
            case "CreateTopic" -> new IamOperation("createTopic", required(request.path("name").asText(), "name"));
            case "GetTopic" -> named("getTopic", request, "topic");
            case "ListTopics" -> named("listTopics", request, "project");
            case "UpdateTopic" -> new IamOperation("updateTopic",
                    required(request.path("topic").path("name").asText(), "topic.name"));
            case "DeleteTopic" -> named("deleteTopic", request, "topic");
            case "Publish" -> named("publish", request, "topic");
            case "ListTopicSubscriptions" -> named("listTopicSubscriptions", request, "topic");
            case "ListTopicSnapshots" -> named("listTopicSnapshots", request, "topic");
            case "DetachSubscription" -> new IamOperation("detachSubscription",
                    required(request.path("subscription").asText(), "subscription"));
            case "CreateSubscription" -> new IamOperation("createSubscription",
                    required(request.path("name").asText(), "name"),
                    required(request.path("topic").asText(), "topic"));
            case "GetSubscription" -> named("getSubscription", request, "subscription");
            case "ListSubscriptions" -> named("listSubscriptions", request, "project");
            case "UpdateSubscription" -> new IamOperation("updateSubscription",
                    required(request.path("subscription").path("name").asText(), "subscription.name"));
            case "DeleteSubscription" -> named("deleteSubscription", request, "subscription");
            case "Pull" -> named("pull", request, "subscription");
            case "Acknowledge" -> named("acknowledge", request, "subscription");
            case "ModifyPushConfig" -> named("modifyPushConfig", request, "subscription");
            case "ModifyAckDeadline" -> named("modifyAckDeadline", request, "subscription");
            case "StreamingPull" -> named("streamingPull", request, "subscription");
            case "CreateSnapshot" -> new IamOperation("createSnapshot",
                    required(request.path("name").asText(), "name"),
                    required(request.path("subscription").asText(), "subscription"));
            case "GetSnapshot" -> named("getSnapshot", request, "snapshot");
            case "ListSnapshots" -> named("listSnapshots", request, "project");
            case "UpdateSnapshot" -> new IamOperation("updateSnapshot",
                    required(request.path("snapshot").path("name").asText(), "snapshot.name"));
            case "DeleteSnapshot" -> named("deleteSnapshot", request, "snapshot");
            case "Seek" -> new IamOperation("seek", required(request.path("subscription").asText(),
                    "subscription"), request.path("snapshot").asText());
            default -> throw unsupported("gRPC operation " + method);
        };
    }

    @Override
    public IamOperation grpcOperation(String method, Message request) {
        return switch (method) {
            case "CreateTopic" -> new IamOperation("createTopic", required(((Topic) request).getName(), "name"));
            case "GetTopic" -> new IamOperation("getTopic", required(((GetTopicRequest) request).getTopic(), "topic"));
            case "ListTopics" -> new IamOperation("listTopics", required(((ListTopicsRequest) request).getProject(), "project"));
            case "UpdateTopic" -> new IamOperation("updateTopic", required(((UpdateTopicRequest) request).getTopic().getName(), "topic.name"));
            case "DeleteTopic" -> new IamOperation("deleteTopic", required(((DeleteTopicRequest) request).getTopic(), "topic"));
            case "Publish" -> new IamOperation("publish", required(((PublishRequest) request).getTopic(), "topic"));
            case "ListTopicSubscriptions" -> new IamOperation("listTopicSubscriptions", required(((ListTopicSubscriptionsRequest) request).getTopic(), "topic"));
            case "ListTopicSnapshots" -> new IamOperation("listTopicSnapshots", required(((ListTopicSnapshotsRequest) request).getTopic(), "topic"));
            case "DetachSubscription" -> new IamOperation("detachSubscription", required(((DetachSubscriptionRequest) request).getSubscription(), "subscription"));
            case "CreateSubscription" -> {
                Subscription subscription = (Subscription) request;
                yield new IamOperation("createSubscription", required(subscription.getName(), "name"), required(subscription.getTopic(), "topic"));
            }
            case "GetSubscription" -> new IamOperation("getSubscription", required(((GetSubscriptionRequest) request).getSubscription(), "subscription"));
            case "ListSubscriptions" -> new IamOperation("listSubscriptions", required(((ListSubscriptionsRequest) request).getProject(), "project"));
            case "UpdateSubscription" -> new IamOperation("updateSubscription", required(((UpdateSubscriptionRequest) request).getSubscription().getName(), "subscription.name"));
            case "DeleteSubscription" -> new IamOperation("deleteSubscription", required(((DeleteSubscriptionRequest) request).getSubscription(), "subscription"));
            case "Pull" -> new IamOperation("pull", required(((PullRequest) request).getSubscription(), "subscription"));
            case "Acknowledge" -> new IamOperation("acknowledge", required(((AcknowledgeRequest) request).getSubscription(), "subscription"));
            case "ModifyPushConfig" -> new IamOperation("modifyPushConfig", required(((ModifyPushConfigRequest) request).getSubscription(), "subscription"));
            case "ModifyAckDeadline" -> new IamOperation("modifyAckDeadline", required(((ModifyAckDeadlineRequest) request).getSubscription(), "subscription"));
            case "StreamingPull" -> new IamOperation("streamingPull", ((StreamingPullRequest) request).getSubscription());
            case "CreateSnapshot" -> new IamOperation("createSnapshot", required(((CreateSnapshotRequest) request).getName(), "name"), required(((CreateSnapshotRequest) request).getSubscription(), "subscription"));
            case "GetSnapshot" -> new IamOperation("getSnapshot", required(((GetSnapshotRequest) request).getSnapshot(), "snapshot"));
            case "ListSnapshots" -> new IamOperation("listSnapshots", required(((ListSnapshotsRequest) request).getProject(), "project"));
            case "UpdateSnapshot" -> new IamOperation("updateSnapshot", required(((UpdateSnapshotRequest) request).getSnapshot().getName(), "snapshot.name"));
            case "DeleteSnapshot" -> new IamOperation("deleteSnapshot", required(((DeleteSnapshotRequest) request).getSnapshot(), "snapshot"));
            case "Seek" -> new IamOperation("seek", required(((SeekRequest) request).getSubscription(), "subscription"), ((SeekRequest) request).getSnapshot());
            default -> throw unsupported("gRPC operation " + method);
        };
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        if (operation.name().equals("detachSubscription")) {
            String topic = service.getSubscription(operation.resource()).getTopic();
            return List.of(new IamPermissionCheck("pubsub.topics.detachSubscription",
                    requireResource(topic)));
        }
        IamResource target = requireResource(operation.resource());
        String permission = switch (operation.name()) {
            case "createTopic" -> "pubsub.topics.create";
            case "getTopic" -> "pubsub.topics.get";
            case "listTopics" -> "pubsub.topics.list";
            case "updateTopic" -> "pubsub.topics.update";
            case "deleteTopic" -> "pubsub.topics.delete";
            case "publish" -> "pubsub.topics.publish";
            case "listTopicSubscriptions", "listTopicSnapshots" -> "pubsub.topics.get";
            case "createSubscription" -> "pubsub.subscriptions.create";
            case "getSubscription" -> "pubsub.subscriptions.get";
            case "listSubscriptions" -> "pubsub.subscriptions.list";
            case "updateSubscription", "modifyPushConfig" -> "pubsub.subscriptions.update";
            case "deleteSubscription" -> "pubsub.subscriptions.delete";
            case "pull", "acknowledge", "modifyAckDeadline", "streamingPull" ->
                    "pubsub.subscriptions.consume";
            case "createSnapshot" -> "pubsub.snapshots.create";
            case "getSnapshot" -> "pubsub.snapshots.get";
            case "listSnapshots" -> "pubsub.snapshots.list";
            case "updateSnapshot" -> "pubsub.snapshots.update";
            case "deleteSnapshot" -> "pubsub.snapshots.delete";
            case "seek" -> "pubsub.subscriptions.consume";
            case "getTopicIamPolicy", "GetIamPolicy" -> policyPermission(target, "getIamPolicy");
            case "setTopicIamPolicy", "SetIamPolicy" -> policyPermission(target, "setIamPolicy");
            case "getSubscriptionIamPolicy", "getSnapshotIamPolicy" -> policyPermission(target, "getIamPolicy");
            case "setSubscriptionIamPolicy", "setSnapshotIamPolicy" -> policyPermission(target, "setIamPolicy");
            case "testTopicIamPermissions", "testSubscriptionIamPermissions",
                    "testSnapshotIamPermissions", "TestIamPermissions" -> null;
            default -> throw unsupported("operation " + operation.name());
        };
        if (permission == null) {
            return List.of();
        }
        IamResource primaryTarget = switch (operation.name()) {
            case "createSubscription", "createSnapshot" ->
                    IamResource.project(target.projectResource());
            default -> target;
        };
        IamPermissionCheck primary = new IamPermissionCheck(permission, primaryTarget);
        if (operation.name().equals("createSubscription")) {
            return List.of(primary, new IamPermissionCheck("pubsub.topics.attachSubscription",
                    requireResource(operation.relatedResource())));
        }
        if (operation.name().equals("createSnapshot")) {
            return List.of(primary, new IamPermissionCheck("pubsub.subscriptions.consume",
                    requireResource(operation.relatedResource())));
        }
        if (operation.name().equals("seek") && !operation.relatedResource().isEmpty()) {
            return List.of(primary, new IamPermissionCheck("pubsub.snapshots.seek",
                    requireResource(operation.relatedResource())));
        }
        return List.of(primary);
    }

    @Override
    public Optional<IamResource> resource(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String[] parts = name.split("/", -1);
        if (parts.length != 4 || !parts[0].equals("projects") || parts[1].isBlank()
                || parts[3].isBlank()) {
            return Optional.empty();
        }
        String type = switch (parts[2]) {
            case "topics" -> "Topic";
            case "subscriptions" -> "Subscription";
            case "snapshots" -> "Snapshot";
            default -> null;
        };
        if (type == null) {
            return Optional.empty();
        }
        return Optional.of(IamResource.projectChild(SERVICE, type, name, name));
    }

    @Override
    public IamResource requireResource(String name) {
        if (name != null && name.matches("projects/[^/]+")) {
            return IamResource.project(name);
        }
        return IamAuthorizationAdapter.super.requireResource(name);
    }

    @Override
    public Map<String, Set<String>> roles() {
        return Map.of(
                "roles/pubsub.admin", ADMIN_PERMISSIONS,
                "roles/pubsub.editor", EDITOR_PERMISSIONS,
                "roles/pubsub.viewer", VIEWER_PERMISSIONS,
                "roles/pubsub.publisher", Set.of("pubsub.topics.publish"),
                "roles/pubsub.subscriber", Set.of("pubsub.subscriptions.consume",
                        "pubsub.topics.attachSubscription", "pubsub.snapshots.seek"));
    }

    @Override
    public Map<String, Set<String>> basicRoles() {
        return Map.of("roles/owner", ADMIN_PERMISSIONS,
                "roles/editor", EDITOR_PERMISSIONS,
                "roles/viewer", BASIC_VIEWER_PERMISSIONS);
    }

    private static IamOperation named(String method, JsonNode request, String field) {
        return new IamOperation(method, required(request.path(field).asText(), field));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw GcpException.invalidArgument("Pub/Sub " + field + " is required");
        }
        return value;
    }

    private static String policyPermission(IamResource resource, String action) {
        String kind = switch (resource.type()) {
            case SERVICE + "/Topic" -> "topics";
            case SERVICE + "/Subscription" -> "subscriptions";
            case SERVICE + "/Snapshot" -> "snapshots";
            default -> throw GcpException.invalidArgument("Unsupported Pub/Sub policy resource: " + resource.name());
        };
        return "pubsub." + kind + "." + action;
    }

    private static Set<String> union(Set<String> first, Set<String> second, Set<String> third) {
        HashSet<String> permissions = new HashSet<>(first);
        permissions.addAll(second);
        permissions.addAll(third);
        return Set.copyOf(permissions);
    }

    private static Set<String> withPermission(Set<String> permissions, String extra) {
        HashSet<String> result = new HashSet<>(permissions);
        result.add(extra);
        return Set.copyOf(result);
    }
}

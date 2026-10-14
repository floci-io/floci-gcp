package io.floci.gcp.services.pubsub;

import com.fasterxml.jackson.databind.JsonNode;
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
    public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
        String project = "projects/" + required(path.get("project"), "project");
        String topic = path.containsKey("topic") ? project + "/topics/" + path.get("topic") : project;
        String subscription = path.containsKey("subscription")
                ? project + "/subscriptions/" + path.get("subscription") : project;
        String snapshot = path.containsKey("snapshot") ? project + "/snapshots/" + path.get("snapshot") : project;
        return switch (method) {
            case "createTopic", "getTopic", "updateTopic", "deleteTopic", "publish",
                    "getTopicIamPolicy", "setTopicIamPolicy", "testTopicIamPermissions" ->
                    new IamOperation(method, topic);
            case "listTopics" -> new IamOperation(method, project);
            case "createSubscription" -> new IamOperation(method, subscription,
                    required(body.path("topic").asText(), "topic"));
            case "getSubscription", "updateSubscription", "deleteSubscription", "pull", "acknowledge",
                    "getSubscriptionIamPolicy", "setSubscriptionIamPolicy",
                    "testSubscriptionIamPermissions" -> new IamOperation(method, subscription);
            case "listSubscriptions" -> new IamOperation(method, project);
            case "getSnapshotIamPolicy", "setSnapshotIamPolicy", "testSnapshotIamPermissions" ->
                    new IamOperation(method, snapshot);
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
            case "listTopicSubscriptions" -> "pubsub.topics.get";
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
                "roles/viewer", VIEWER_PERMISSIONS);
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
}

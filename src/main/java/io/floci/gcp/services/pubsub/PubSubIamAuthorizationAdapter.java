package io.floci.gcp.services.pubsub;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Pub/Sub v1 permissions: https://cloud.google.com/pubsub/docs/access-control */
@ApplicationScoped
public class PubSubIamAuthorizationAdapter implements IamAuthorizationAdapter {
    private static final Map<String, String> PERMISSIONS = Map.ofEntries(
            Map.entry("CreateTopic", "pubsub.topics.create"),
            Map.entry("GetTopic", "pubsub.topics.get"),
            Map.entry("ListTopics", "pubsub.topics.list"),
            Map.entry("UpdateTopic", "pubsub.topics.update"),
            Map.entry("DeleteTopic", "pubsub.topics.delete"),
            Map.entry("Publish", "pubsub.topics.publish"),
            Map.entry("ListTopicSubscriptions", "pubsub.topics.get"),
            Map.entry("ListTopicSnapshots", "pubsub.topics.get"),
            Map.entry("CreateSubscription", "pubsub.subscriptions.create"),
            Map.entry("GetSubscription", "pubsub.subscriptions.get"),
            Map.entry("ListSubscriptions", "pubsub.subscriptions.list"),
            Map.entry("UpdateSubscription", "pubsub.subscriptions.update"),
            Map.entry("ModifyPushConfig", "pubsub.subscriptions.update"),
            Map.entry("DeleteSubscription", "pubsub.subscriptions.delete"),
            Map.entry("DetachSubscription", "pubsub.topics.detachSubscription"),
            Map.entry("Pull", "pubsub.subscriptions.consume"),
            Map.entry("StreamingPull", "pubsub.subscriptions.consume"),
            Map.entry("Acknowledge", "pubsub.subscriptions.consume"),
            Map.entry("ModifyAckDeadline", "pubsub.subscriptions.consume"),
            Map.entry("Seek", "pubsub.subscriptions.consume"),
            Map.entry("CreateSnapshot", "pubsub.snapshots.create"),
            Map.entry("GetSnapshot", "pubsub.snapshots.get"),
            Map.entry("ListSnapshots", "pubsub.snapshots.list"),
            Map.entry("UpdateSnapshot", "pubsub.snapshots.update"),
            Map.entry("DeleteSnapshot", "pubsub.snapshots.delete"));

    private final Instance<PubSubService> service;

    @Inject
    public PubSubIamAuthorizationAdapter(Instance<PubSubService> service) {
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
        String resource = "projects/" + path.get("project");
        for (String kind : List.of("topic", "subscription", "snapshot")) {
            if (path.containsKey(kind)) {
                resource += "/" + kind + "s/" + path.get(kind);
            }
        }
        String operation = Character.toUpperCase(method.charAt(0)) + method.substring(1);
        if (method.endsWith("IamPolicy") || method.endsWith("IamPermissions")) {
            operation = method.startsWith("get") ? "GetIamPolicy"
                    : method.startsWith("set") ? "SetIamPolicy" : "TestIamPermissions";
        }
        return new IamOperation(operation, resource, relatedResource(operation, body));
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        String resource = switch (method) {
            case "CreateTopic", "CreateSubscription", "CreateSnapshot" -> request.path("name").asText();
            case "UpdateTopic" -> request.path("topic").path("name").asText();
            case "UpdateSubscription" -> request.path("subscription").path("name").asText();
            case "UpdateSnapshot" -> request.path("snapshot").path("name").asText();
            case "ListTopics", "ListSubscriptions", "ListSnapshots" -> request.path("project").asText();
            case "GetTopic", "DeleteTopic", "Publish", "ListTopicSubscriptions", "ListTopicSnapshots" ->
                    request.path("topic").asText();
            case "GetSnapshot", "DeleteSnapshot" -> request.path("snapshot").asText();
            default -> request.path("subscription").asText();
        };
        return new IamOperation(method, resource, relatedResource(method, request));
    }

    private static String relatedResource(String method, JsonNode request) {
        return switch (method) {
            case "CreateSubscription" -> request.path("topic").asText();
            case "CreateSnapshot" -> request.path("subscription").asText();
            case "Seek" -> request.path("snapshot").asText();
            default -> "";
        };
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        String name = operation.name();
        IamResource target = operation.resource().matches("projects/[^/]+")
                ? IamResource.project(operation.resource()) : requireResource(operation.resource());
        if (name.equals("TestIamPermissions")) {
            return List.of();
        }
        String permission;
        if (name.equals("GetIamPolicy") || name.equals("SetIamPolicy")) {
            String collection = operation.resource().split("/")[2];
            permission = "pubsub." + collection + "." + (name.startsWith("Get") ? "getIamPolicy" : "setIamPolicy");
        } else {
            permission = PERMISSIONS.get(name);
            if (permission == null) {
                throw unsupported("operation " + name);
            }
        }
        if (name.startsWith("Create")) {
            target = IamResource.project(operation.resource());
        }
        if (name.equals("DetachSubscription")) {
            target = requireResource(service.get().getSubscription(operation.resource()).getTopic());
        }
        List<IamPermissionCheck> checks = new ArrayList<>();
        checks.add(new IamPermissionCheck(permission, target));
        if (name.equals("CreateSubscription")) {
            checks.add(new IamPermissionCheck("pubsub.topics.attachSubscription", requireResource(operation.relatedResource())));
        } else if (name.equals("CreateSnapshot")) {
            checks.add(new IamPermissionCheck("pubsub.subscriptions.consume", requireResource(operation.relatedResource())));
        } else if (name.equals("Seek") && !operation.relatedResource().isEmpty()) {
            checks.add(new IamPermissionCheck("pubsub.snapshots.seek", requireResource(operation.relatedResource())));
        }
        return List.copyOf(checks);
    }

    @Override
    public Optional<IamResource> resource(String name) {
        if (!name.matches("projects/[^/]+/(topics|subscriptions|snapshots)/[^/]+")) {
            return Optional.empty();
        }
        String type = switch (name.split("/")[2]) {
            case "topics" -> "Topic";
            case "subscriptions" -> "Subscription";
            default -> "Snapshot";
        };
        return Optional.of(IamResource.projectChild("pubsub.googleapis.com", type, name, name));
    }

    @Override
    public Map<String, Set<String>> roles() {
        Set<String> viewer = Stream.concat(PERMISSIONS.values().stream()
                        .filter(permission -> permission.endsWith(".get") || permission.endsWith(".list")),
                Stream.of("resourcemanager.projects.get"))
                .collect(Collectors.toUnmodifiableSet());
        Set<String> editor = Stream.concat(PERMISSIONS.values().stream(),
                Stream.concat(viewer.stream(), Stream.of("pubsub.topics.attachSubscription", "pubsub.snapshots.seek")))
                .collect(Collectors.toUnmodifiableSet());
        Set<String> admin = Stream.concat(editor.stream(),
                Stream.of("pubsub.topics.getIamPolicy", "pubsub.subscriptions.getIamPolicy", "pubsub.snapshots.getIamPolicy",
                        "pubsub.topics.setIamPolicy", "pubsub.subscriptions.setIamPolicy", "pubsub.snapshots.setIamPolicy"))
                .collect(Collectors.toUnmodifiableSet());
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        roles.put("roles/pubsub.publisher", Set.of("pubsub.topics.publish"));
        roles.put("roles/pubsub.subscriber", Set.of("pubsub.subscriptions.consume", "pubsub.topics.attachSubscription", "pubsub.snapshots.seek"));
        roles.put("roles/pubsub.viewer", viewer);
        roles.put("roles/pubsub.editor", editor);
        roles.put("roles/pubsub.admin", admin);
        return Map.copyOf(roles);
    }
    @Override
    public Map<String, Set<String>> basicRoles() {
        Map<String, Set<String>> predefined = roles();
        return Map.of("roles/owner", predefined.get("roles/pubsub.admin"),
                "roles/editor", predefined.get("roles/pubsub.editor"),
                "roles/viewer", predefined.get("roles/pubsub.viewer"));
    }

}

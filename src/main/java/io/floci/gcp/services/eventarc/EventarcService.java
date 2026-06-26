package io.floci.gcp.services.eventarc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.eventarc.v1.Trigger;
import com.google.cloud.eventarc.v1.ListTriggersResponse;
import com.google.cloud.eventarc.v1.Channel;
import com.google.cloud.eventarc.v1.ListChannelsResponse;
import com.google.cloud.eventarc.v1.Provider;
import com.google.cloud.eventarc.v1.ListProvidersResponse;
import com.google.cloud.eventarc.v1.EventFilter;
import com.google.cloud.eventarc.v1.Destination;
import com.google.cloud.eventarc.v1.Transport;
import com.google.cloud.eventarc.v1.OperationMetadata;
import com.google.longrunning.Operation;
import com.google.protobuf.Timestamp;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpResourceNames;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.cloudrun.CloudRunUrlService;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import io.floci.gcp.services.pubsub.model.StoredMessage;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class EventarcService {

    private static final Logger LOG = Logger.getLogger(EventarcService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackend<String, String> triggerStore;
    private final LongRunningOperationsService operations;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final CloudRunUrlService cloudRunUrlService;
    private final HttpClient httpClient;

    @Inject
    public EventarcService(StorageFactory storageFactory,
                           LongRunningOperationsService operations,
                           ServiceRegistry serviceRegistry,
                           EmulatorConfig config,
                           CloudRunUrlService cloudRunUrlService) {
        this.triggerStore = storageFactory.createGlobal("eventarc-triggers", "eventarc-triggers.json",
                new TypeReference<Map<String, String>>() {});
        this.operations = operations;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.cloudRunUrlService = cloudRunUrlService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    EventarcService(StorageBackend<String, String> triggerStore,
                    LongRunningOperationsService operations,
                    EmulatorConfig config,
                    CloudRunUrlService cloudRunUrlService,
                    HttpClient httpClient) {
        this.triggerStore = triggerStore;
        this.operations = operations;
        this.serviceRegistry = null;
        this.config = config;
        this.cloudRunUrlService = cloudRunUrlService;
        this.httpClient = httpClient;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("eventarc")
                .enabled(config.services().eventarc().enabled())
                .storageKey("eventarc")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(EventarcController.class)
                .build());
    }

    // ── Triggers CRUD ─────────────────────────────────────────────────────────

    public Operation createTrigger(String project, String location, String triggerId,
                                   String body, boolean validateOnly) {
        String parent = parent(project, location);
        Trigger requested = ProtoJson.merge(body, Trigger.newBuilder()).build();
        String id = triggerId != null && !triggerId.isBlank() ? triggerId : GcpResourceNames.lastSegment(requested.getName());
        if (id == null || id.isBlank()) {
            throw GcpException.invalidArgument("triggerId query parameter is required");
        }
        String name = parent + "/triggers/" + id;
        if (triggerStore.get(name).isPresent()) {
            throw GcpException.alreadyExists("Trigger already exists: " + name);
        }

        Timestamp now = timestampNow();
        Trigger trigger = requested.toBuilder()
                .setName(name)
                .setUid(UUID.randomUUID().toString())
                .setCreateTime(now)
                .setUpdateTime(now)
                .setEtag(UUID.randomUUID().toString())
                .build();

        if (!validateOnly) {
            triggerStore.put(name, ProtoJson.print(trigger));
        }

        LOG.infof("create Eventarc trigger name=%s validateOnly=%s", name, validateOnly);
        return done(parent, trigger, trigger, validateOnly);
    }

    public Trigger getTrigger(String name) {
        return triggerStore.get(name)
                .map(json -> ProtoJson.merge(json, Trigger.newBuilder()).build())
                .orElseThrow(() -> GcpException.notFound("Trigger not found: " + name));
    }

    public ListTriggersResponse listTriggers(String project, String location, int pageSize, String pageToken) {
        String prefix = parent(project, location) + "/triggers/";
        List<Trigger> triggers = triggerStore.scan(k -> k.startsWith(prefix)).stream()
                .map(json -> ProtoJson.merge(json, Trigger.newBuilder()).build())
                .sorted(Comparator.comparing(Trigger::getName))
                .toList();
        PageToken.Page<Trigger> page = PageToken.paginate(triggers, pageSize, pageToken);
        ListTriggersResponse.Builder response = ListTriggersResponse.newBuilder()
                .addAllTriggers(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    public Operation updateTrigger(String name, String body, String updateMask, boolean allowMissing, boolean validateOnly) {
        Trigger existing;
        try {
            existing = getTrigger(name);
        } catch (GcpException e) {
            if (e.getGrpcCode() == io.grpc.Status.Code.NOT_FOUND && allowMissing) {
                // Treat as create
                String project = GcpResourceNames.parseProject(name);
                String location = parseLocation(name);
                String triggerId = GcpResourceNames.lastSegment(name);
                return createTrigger(project, location, triggerId, body, validateOnly);
            }
            throw e;
        }

        Trigger requested = ProtoJson.merge(body, Trigger.newBuilder()).build();
        Timestamp now = timestampNow();
        Trigger.Builder builder = existing.toBuilder()
                .setUpdateTime(now)
                .setEtag(UUID.randomUUID().toString());

        // Basic mask-based field copy or replace all if mask is empty
        boolean replaceAll = updateMask == null || updateMask.isBlank();
        List<String> maskPaths = replaceAll ? List.of() : Arrays.asList(updateMask.split(","));

        if (replaceAll || maskPaths.contains("eventFilters") || maskPaths.contains("event_filters")) {
            builder.clearEventFilters().addAllEventFilters(requested.getEventFiltersList());
        }
        if (replaceAll || maskPaths.contains("destination")) {
            builder.setDestination(requested.getDestination());
        }
        if (replaceAll || maskPaths.contains("serviceAccount") || maskPaths.contains("service_account")) {
            builder.setServiceAccount(requested.getServiceAccount());
        }
        if (replaceAll || maskPaths.contains("labels")) {
            builder.clearLabels().putAllLabels(requested.getLabelsMap());
        }
        if (replaceAll || maskPaths.contains("transport")) {
            builder.setTransport(requested.getTransport());
        }

        Trigger updated = builder.build();
        if (!validateOnly) {
            triggerStore.put(name, ProtoJson.print(updated));
        }

        LOG.infof("update Eventarc trigger name=%s validateOnly=%s", name, validateOnly);
        return done(parentFromName(name), updated, updated, validateOnly);
    }

    public Operation deleteTrigger(String name, boolean allowMissing, boolean validateOnly) {
        Trigger existing = null;
        try {
            existing = getTrigger(name);
        } catch (GcpException e) {
            if (e.getGrpcCode() == io.grpc.Status.Code.NOT_FOUND && allowMissing) {
                // return successfully with a mock/empty Operation
                Timestamp now = timestampNow();
                Trigger mockDeleted = Trigger.newBuilder().setName(name).build();
                return done(parentFromName(name), mockDeleted, mockDeleted, true);
            }
            throw e;
        }

        if (!validateOnly) {
            triggerStore.delete(name);
        }

        LOG.infof("delete Eventarc trigger name=%s validateOnly=%s", name, validateOnly);
        return done(parentFromName(name), existing, existing, validateOnly);
    }

    // ── Channels & Providers Stubs ────────────────────────────────────────────

    public ListChannelsResponse listChannels(String project, String location, int pageSize, String pageToken) {
        // Return empty list of channels by default
        return ListChannelsResponse.newBuilder().build();
    }

    public Channel getChannel(String name) {
        throw GcpException.notFound("Channel not found: " + name);
    }

    public ListProvidersResponse listProviders(String project, String location, int pageSize, String pageToken) {
        List<Provider> providers = List.of(
            Provider.newBuilder()
                .setName("projects/" + project + "/locations/" + location + "/providers/storage.googleapis.com")
                .setDisplayName("Cloud Storage")
                .build(),
            Provider.newBuilder()
                .setName("projects/" + project + "/locations/" + location + "/providers/pubsub.googleapis.com")
                .setDisplayName("Cloud Pub/Sub")
                .build()
        );
        return ListProvidersResponse.newBuilder()
                .addAllProviders(providers)
                .build();
    }

    public Provider getProvider(String name) {
        String id = GcpResourceNames.lastSegment(name);
        if ("storage.googleapis.com".equals(id) || "pubsub.googleapis.com".equals(id)) {
            String project = GcpResourceNames.parseProject(name);
            String location = parseLocation(name);
            return Provider.newBuilder()
                    .setName(name)
                    .setDisplayName("storage.googleapis.com".equals(id) ? "Cloud Storage" : "Cloud Pub/Sub")
                    .build();
        }
        throw GcpException.notFound("Provider not found: " + name);
    }

    // ── Event Dispatching / Interception ──────────────────────────────────────

    public void onPubSubPublish(String topicName, StoredMessage message) {
        String project = GcpResourceNames.parseProject(topicName);
        if (project == null) {
            project = config.defaultProjectId();
        }
        
        List<Trigger> activeTriggers = triggerStore.scan(k -> true).stream()
                .map(json -> ProtoJson.merge(json, Trigger.newBuilder()).build())
                .toList();

        Map<String, String> attributes = new java.util.HashMap<>();
        attributes.put("type", "google.cloud.pubsub.topic.v1.messagePublished");
        attributes.put("topic", topicName);
        if (message.getAttributes() != null) {
            attributes.putAll(message.getAttributes());
        }

        for (Trigger trigger : activeTriggers) {
            // Check if trigger uses this topic as transport, OR event filters match it
            boolean matchesTransport = trigger.hasTransport() &&
                    trigger.getTransport().hasPubsub() &&
                    matchAttributeValue("topic", trigger.getTransport().getPubsub().getTopic(), topicName);

            boolean matchesFilters = matches(trigger, "google.cloud.pubsub.topic.v1.messagePublished", attributes);

            if (matchesTransport || matchesFilters) {
                String eventId = message.getMessageId() != null ? message.getMessageId() : UUID.randomUUID().toString();
                String source = "//pubsub.googleapis.com/" + topicName;
                String triggerId = GcpResourceNames.lastSegment(trigger.getName());
                
                byte[] payload = buildPubSubPayload(project, triggerId, eventId, message.getPublishTime(), message.getData(), message.getAttributes());
                deliverEvent(trigger, eventId, "google.cloud.pubsub.topic.v1.messagePublished", source, message.getPublishTime(), payload);
            }
        }
    }

    public void onGcsEvent(String bucket, String objectName, GcsObjectMeta meta, String eventType) {
        List<Trigger> activeTriggers = triggerStore.scan(k -> true).stream()
                .map(json -> ProtoJson.merge(json, Trigger.newBuilder()).build())
                .toList();

        Map<String, String> attributes = new java.util.HashMap<>();
        attributes.put("type", eventType);
        attributes.put("bucket", bucket);
        attributes.put("object", objectName);

        for (Trigger trigger : activeTriggers) {
            if (matches(trigger, eventType, attributes)) {
                String eventId = UUID.randomUUID().toString();
                String source = "//storage.googleapis.com/projects/_/buckets/" + bucket;
                String time = meta.getUpdated() != null ? meta.getUpdated() : Instant.now().toString();

                try {
                    byte[] payload = MAPPER.writeValueAsBytes(meta);
                    deliverEvent(trigger, eventId, eventType, source, time, payload);
                } catch (Exception e) {
                    LOG.warnf("Failed to serialize GCS event payload for trigger %s: %s", trigger.getName(), e.getMessage());
                }
            }
        }
    }

    private boolean matches(Trigger trigger, String eventType, Map<String, String> attributes) {
        if (trigger.getEventFiltersCount() == 0) {
            return false;
        }
        for (EventFilter filter : trigger.getEventFiltersList()) {
            String attrName = filter.getAttribute();
            String attrVal = filter.getValue();
            String actualVal = attributes.get(attrName);
            if (actualVal == null) {
                if ("type".equals(attrName)) {
                    actualVal = eventType;
                } else {
                    return false;
                }
            }
            if (!matchAttributeValue(attrName, attrVal, actualVal)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchAttributeValue(String name, String filterVal, String actualVal) {
        if (filterVal.equals(actualVal)) {
            return true;
        }
        if ("topic".equals(name) || "bucket".equals(name)) {
            String filterLast = lastSegment(filterVal);
            String actualLast = lastSegment(actualVal);
            return filterLast.equals(actualLast);
        }
        return false;
    }

    private String lastSegment(String path) {
        int idx = path.lastIndexOf('/');
        return idx < 0 ? path : path.substring(idx + 1);
    }

    private void deliverEvent(Trigger trigger, String eventId, String eventType, String source, String time, byte[] data) {
        Destination dest = trigger.getDestination();
        String targetUrl = null;
        if (dest.hasCloudRun()) {
            var run = dest.getCloudRun();
            String project = GcpResourceNames.parseProject(trigger.getName());
            targetUrl = cloudRunUrlService.invocationUri(project, run.getRegion(), run.getService());
            if (!run.getPath().isEmpty()) {
                if (!run.getPath().startsWith("/")) {
                    targetUrl += "/";
                }
                targetUrl += run.getPath();
            }
        } else if (dest.hasHttpEndpoint()) {
            targetUrl = dest.getHttpEndpoint().getUri();
        } else {
            LOG.warnf("Unsupported Eventarc trigger destination: %s", dest);
            return;
        }

        if (targetUrl == null || targetUrl.isBlank()) {
            LOG.warnf("Eventarc trigger destination URL is empty: %s", dest);
            return;
        }

        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Content-Type", "application/json")
                    .header("ce-id", eventId)
                    .header("ce-source", source)
                    .header("ce-specversion", "1.0")
                    .header("ce-type", eventType)
                    .header("ce-time", time)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(data));

            String finalTargetUrl = targetUrl;
            httpClient.sendAsync(req.build(), HttpResponse.BodyHandlers.discarding())
                    .thenAccept(resp -> {
                        LOG.infof("Eventarc event %s delivered to %s -> HTTP %d", eventId, finalTargetUrl, resp.statusCode());
                    })
                    .exceptionally(t -> {
                        LOG.warnf("Failed to deliver Eventarc event %s to %s: %s", eventId, finalTargetUrl, t.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            LOG.warnf("Failed to build Eventarc delivery request: %s", e.getMessage());
        }
    }

    private byte[] buildPubSubPayload(String project, String triggerId, String messageId, String publishTime, byte[] data, Map<String, String> attributes) {
        try {
            Map<String, Object> messageMap = new java.util.HashMap<>();
            messageMap.put("messageId", messageId);
            messageMap.put("publishTime", publishTime != null ? publishTime : Instant.now().toString());
            if (data != null) {
                messageMap.put("data", Base64.getEncoder().encodeToString(data));
            }
            if (attributes != null && !attributes.isEmpty()) {
                messageMap.put("attributes", attributes);
            }

            Map<String, Object> payload = Map.of(
                "message", messageMap,
                "subscription", "projects/" + project + "/subscriptions/eventarc-" + triggerId
            );
            return MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            LOG.warnf("Failed to build Pub/Sub CloudEvent payload: %s", e.getMessage());
            return new byte[0];
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String parent(String project, String location) {
        return "projects/" + project + "/locations/" + location;
    }

    private static String parentFromName(String name) {
        int idx = name.indexOf("/triggers/");
        if (idx < 0) {
            idx = name.indexOf("/channels/");
        }
        return idx < 0 ? name : name.substring(0, idx);
    }

    private static String parseLocation(String resourceName) {
        if (resourceName == null) {
            return null;
        }
        int start = resourceName.indexOf("locations/");
        if (start < 0) {
            return null;
        }
        start += "locations/".length();
        int end = resourceName.indexOf('/', start);
        return end < 0 ? resourceName.substring(start) : resourceName.substring(start, end);
    }

    private static Timestamp timestampNow() {
        Instant now = Instant.now();
        return Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
    }

    private Operation done(String parent, Trigger response, Trigger metadata, boolean transientOnly) {
        // Build OperationMetadata
        OperationMetadata opMeta = OperationMetadata.newBuilder()
                .setCreateTime(metadata.getCreateTime())
                .setTarget(metadata.getName())
                .setVerb("create")
                .setApiVersion("v1")
                .build();

        return transientOnly
                ? operations.doneTransient(parent, response, opMeta)
                : operations.done(parent, response, opMeta);
    }
}

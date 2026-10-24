package io.floci.gcp.services.pubsub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import com.google.pubsub.v1.DeadLetterPolicy;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.ReceivedMessage;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpResourceNames;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.pubsub.model.StoredMessage;
import io.floci.gcp.services.pubsub.model.StoredSnapshot;
import io.floci.gcp.services.pubsub.model.StoredSubscription;
import io.floci.gcp.services.pubsub.model.StoredTopic;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Set;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

@ApplicationScoped
public class PubSubService {

    private static final Logger LOG = Logger.getLogger(PubSubService.class);

    private static final Predicate<Map<String, String>> NEVER_MATCHES = attributes -> false;

    private static final int MAX_ACK_DEADLINE_SECONDS = 600;

    private static final int MIN_STREAM_ACK_DEADLINE_SECONDS = 10;

    private static final Duration LEASE_EXPIRY_INTERVAL = Duration.ofSeconds(1);

    private static final int DEFAULT_MAX_DELIVERY_ATTEMPTS = 5;

    private static final Duration SNAPSHOT_LIFETIME = Duration.ofDays(7);

    private static final Duration MIN_SNAPSHOT_LIFETIME = Duration.ofHours(1);

    private static final DateTimeFormatter DEAD_LETTER_PUBLISH_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx").withZone(ZoneOffset.UTC);

    private static final Runnable NO_FOLLOW_UP = () -> { };

    private final StorageBackend<String, StoredTopic> topicStore;
    private final StorageBackend<String, StoredSubscription> subStore;
    private final StorageBackend<String, StoredSnapshot> snapshotStore;

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<QueuedMessage>> queues = new ConcurrentHashMap<>();
    // Each lease map is also the lock for moving that subscription's messages between queue and leases,
    // so a seek never misses a message in transit. It is the innermost lock, so never notify listeners under it.
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Lease>> delivered = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MessageListener>> listeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Predicate<Map<String, String>>> compiledFilters = new ConcurrentHashMap<>();
    // What each snapshot retains, oldest first: its subscription's unacked backlog when it was created, then every
    // message published to its topic since. In memory only, like the queues, so a snapshot that survives a restart
    // keeps its metadata with an empty backlog.
    private final ConcurrentHashMap<String, Backlog> backlogs = new ConcurrentHashMap<>();
    // Push messages a subscription has not had acknowledged yet (sent or awaiting a retry), with how many
    // deliveries of each are still in flight (a retry and a seek can push the same message at once). They never
    // enter the queues or leases, but a snapshot of a push subscription captures them like any unacked message.
    private final ConcurrentHashMap<String, ConcurrentHashMap<StoredMessage, Integer>> pendingPush =
            new ConcurrentHashMap<>();
    // Publishing a message (backlog appends and queue adds), creating a snapshot and seeking to one all hold the
    // topic's lock, so a message is either captured by them or added after them, never both and never neither.
    // It is taken before a subscription's lease lock, never inside it.
    private final ConcurrentHashMap<String, Object> topicLocks = new ConcurrentHashMap<>();
    private final AtomicLong messageIdCounter = new AtomicLong(0);

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final GrpcServerManager grpcServerManager;
    private final IamService iamService;
    private final HttpClient httpClient;
    private final ExecutorService pushDeliveryExecutor;
    private final ScheduledExecutorService pushRetryExecutor;
    private final ScheduledExecutorService leaseExpiryExecutor;
    private final Clock clock;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    jakarta.enterprise.inject.Instance<io.floci.gcp.services.eventarc.EventarcService> eventarcServiceInstance;

    @Inject
    public PubSubService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, GrpcServerManager grpcServerManager, IamService iamService) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
        this.iamService = iamService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.pushDeliveryExecutor = Executors.newCachedThreadPool();
        this.pushRetryExecutor = Executors.newScheduledThreadPool(1);
        this.leaseExpiryExecutor = Executors.newSingleThreadScheduledExecutor();
        this.clock = Clock.systemUTC();
        this.topicStore = storageFactory.createGlobal("pubsub-topics", "pubsub-topics.json",
                new TypeReference<Map<String, StoredTopic>>() {});
        this.subStore = storageFactory.createGlobal("pubsub-subs", "pubsub-subs.json",
                new TypeReference<Map<String, StoredSubscription>>() {});
        this.snapshotStore = storageFactory.createGlobal("pubsub-snapshots", "pubsub-snapshots.json",
                new TypeReference<Map<String, StoredSnapshot>>() {});
    }

    PubSubService(StorageBackend<String, StoredTopic> topicStore,
            StorageBackend<String, StoredSubscription> subStore,
            StorageBackend<String, StoredSnapshot> snapshotStore,
            IamService iamService) {
        this(topicStore, subStore, snapshotStore, iamService, Clock.systemUTC());
    }

    PubSubService(StorageBackend<String, StoredTopic> topicStore,
            StorageBackend<String, StoredSubscription> subStore,
            StorageBackend<String, StoredSnapshot> snapshotStore,
            IamService iamService,
            Clock clock) {
        this.topicStore = topicStore;
        this.subStore = subStore;
        this.snapshotStore = snapshotStore;
        this.iamService = iamService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.pushDeliveryExecutor = Executors.newCachedThreadPool();
        this.pushRetryExecutor = Executors.newScheduledThreadPool(1);
        this.leaseExpiryExecutor = Executors.newSingleThreadScheduledExecutor();
        this.clock = clock;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
        registerPolicyResolvers();
    }

    @PreDestroy
    void shutdownExecutors() {
        leaseExpiryExecutor.shutdownNow();
        pushRetryExecutor.shutdownNow();
        pushDeliveryExecutor.shutdownNow();
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("pubsub")
                .enabled(config.services().pubsub().enabled())
                .storageKey("pubsub")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(PubSubPublisherController.class, PubSubSubscriberController.class,
                        PubSubRestController.class)
                .api("pubsub.googleapis.com", "Cloud Pub/Sub API")
                .build());
        grpcServerManager.bind(new PubSubPublisherController(this));
        grpcServerManager.bind(new PubSubSubscriberController(this));
        registerPolicyResolvers();
        leaseExpiryExecutor.scheduleWithFixedDelay(this::expireLeasesSafely,
                LEASE_EXPIRY_INTERVAL.toMillis(), LEASE_EXPIRY_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void registerPolicyResolvers() {
        iamService.registerPolicyResourceResolver("projects/*/topics/*", this::getTopic);
        iamService.registerPolicyResourceResolver("projects/*/subscriptions/*", this::getSubscription);
        iamService.registerPolicyResourceResolver("projects/*/snapshots/*", this::getSnapshot);
    }

    // ── Topics ─────────────────────────────────────────────────────────────────

    public StoredTopic createTopic(String name) {
        return createTopic(name, null, null);
    }

    public StoredTopic createTopic(String name, Map<String, String> labels, String messageRetentionDuration) {
        LOG.infof("createTopic name=%s", name);
        if (topicStore.get(name).isPresent()) {
            LOG.warnf("createTopic failed: topic already exists name=%s", name);
            throw GcpException.alreadyExists("Topic already exists: " + name);
        }
        StoredTopic topic = new StoredTopic(name);
        topic.setLabels(emptyToNull(labels));
        topic.setMessageRetentionDuration(blankToNull(messageRetentionDuration));
        topicStore.put(name, topic);
        return topic;
    }

    public StoredTopic getTopic(String name) {
        LOG.debugf("getTopic name=%s", name);
        return topicStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Topic not found: " + name));
    }

    public List<StoredTopic> listTopics(String project) {
        LOG.debugf("listTopics project=%s", project);
        String prefix = "projects/" + project + "/topics/";
        List<StoredTopic> topics = topicStore.scan(k -> k.startsWith(prefix));
        LOG.debugf("listTopics project=%s count=%d", project, topics.size());
        return topics;
    }

    public StoredTopic updateTopic(String name, com.google.pubsub.v1.Topic topicProto,
            com.google.protobuf.FieldMask updateMask) {
        LOG.infof("updateTopic name=%s", name);
        StoredTopic stored = getTopic(name);
        for (String path : updateMask.getPathsList()) {
            switch (path) {
                case "labels" -> stored.setLabels(topicProto.getLabelsMap().isEmpty() ? null
                        : new java.util.HashMap<>(topicProto.getLabelsMap()));
                case "message_retention_duration" -> {
                    if (topicProto.hasMessageRetentionDuration()) {
                        stored.setMessageRetentionDuration(
                                topicProto.getMessageRetentionDuration().getSeconds() + "s");
                    }
                }
            }
        }
        topicStore.put(name, stored);
        return stored;
    }

    public StoredTopic updateTopic(String name, Map<String, String> labels,
            String messageRetentionDuration, List<String> updateMaskPaths) {
        LOG.infof("updateTopic name=%s", name);
        StoredTopic stored = getTopic(name);
        boolean replaceAll = updateMaskPaths == null || updateMaskPaths.isEmpty();
        if (replaceAll || masked(updateMaskPaths, "labels")) {
            stored.setLabels(emptyToNull(labels));
        }
        if (replaceAll || masked(updateMaskPaths, "message_retention_duration")) {
            stored.setMessageRetentionDuration(blankToNull(messageRetentionDuration));
        }
        topicStore.put(name, stored);
        return stored;
    }

    public void deleteTopic(String name) {
        LOG.infof("deleteTopic name=%s", name);
        if (topicStore.get(name).isEmpty()) {
            LOG.warnf("deleteTopic failed: topic not found name=%s", name);
            throw GcpException.notFound("Topic not found: " + name);
        }
        iamService.deleteResourceAndPolicy(name, () -> topicStore.delete(name));
    }

    // ── Subscriptions ──────────────────────────────────────────────────────────

    public StoredSubscription createSubscription(String name, String topicName, int ackDeadlineSeconds) {
        return createSubscription(name, topicName, ackDeadlineSeconds, null);
    }

    public StoredSubscription createSubscription(String name, String topicName, int ackDeadlineSeconds,
            String filter) {
        return createSubscription(name, topicName, ackDeadlineSeconds, null, false, null,
                filter, null, null, null, null, null, 0, false, false);
    }

    public StoredSubscription createSubscription(String name,
            String topicName,
            int ackDeadlineSeconds,
            Map<String, String> labels,
            boolean retainAckedMessages,
            String messageRetentionDuration,
            String filter,
            String pushEndpoint,
            Map<String, Object> bigQueryConfig,
            Map<String, Object> expirationPolicy,
            Map<String, Object> retryPolicy,
            String deadLetterTopic,
            int maxDeliveryAttempts,
            boolean enableMessageOrdering,
            boolean enableExactlyOnceDelivery) {
        LOG.infof("createSubscription name=%s topic=%s ackDeadline=%d", name, topicName, ackDeadlineSeconds);
        if (subStore.get(name).isPresent()) {
            LOG.warnf("createSubscription failed: subscription already exists name=%s", name);
            throw GcpException.alreadyExists("Subscription already exists: " + name);
        }
        if (topicStore.get(topicName).isEmpty()) {
            LOG.warnf("createSubscription failed: topic not found topic=%s", topicName);
            throw GcpException.notFound("Topic not found: " + topicName);
        }
        SubscriptionFilter.validate(filter);
        int deadline = ackDeadlineSeconds > 0 ? ackDeadlineSeconds : 10;
        StoredSubscription sub = new StoredSubscription(name, topicName, deadline);
        sub.setLabels(emptyToNull(labels));
        sub.setRetainAckedMessages(retainAckedMessages);
        sub.setMessageRetentionDuration(blankToNull(messageRetentionDuration));
        sub.setFilter(blankToNull(filter));
        sub.setPushEndpoint(blankToNull(pushEndpoint));
        sub.setBigQueryConfig(emptyObjectMapToNull(bigQueryConfig));
        sub.setExpirationPolicy(emptyObjectMapToNull(expirationPolicy));
        sub.setRetryPolicy(emptyObjectMapToNull(retryPolicy));
        sub.setDeadLetterTopic(blankToNull(deadLetterTopic));
        sub.setMaxDeliveryAttempts(maxDeliveryAttempts(sub.getDeadLetterTopic(), maxDeliveryAttempts));
        sub.setEnableMessageOrdering(enableMessageOrdering);
        sub.setEnableExactlyOnceDelivery(enableExactlyOnceDelivery);
        subStore.put(name, sub);
        queues.put(name, new ConcurrentLinkedDeque<>());
        delivered.put(name, new ConcurrentHashMap<>());
        listeners.put(name, new CopyOnWriteArrayList<>());
        return sub;
    }

    public StoredSubscription getSubscription(String name) {
        LOG.debugf("getSubscription name=%s", name);
        return subStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Subscription not found: " + name));
    }

    public List<StoredSubscription> listSubscriptions(String project) {
        LOG.debugf("listSubscriptions project=%s", project);
        String prefix = "projects/" + project + "/subscriptions/";
        List<StoredSubscription> subs = subStore.scan(k -> k.startsWith(prefix));
        LOG.debugf("listSubscriptions project=%s count=%d", project, subs.size());
        return subs;
    }

    public StoredSubscription updateSubscription(com.google.pubsub.v1.Subscription subProto,
            com.google.protobuf.FieldMask updateMask) {
        LOG.infof("updateSubscription name=%s", subProto.getName());
        StoredSubscription stored = getSubscription(subProto.getName());
        if (updateMask.getPathsList().contains("filter")) {
            throw filterNotMutable(stored.getName());
        }
        for (String path : updateMask.getPathsList()) {
            switch (path) {
                case "ack_deadline_seconds" -> stored.setAckDeadlineSeconds(subProto.getAckDeadlineSeconds());
                case "labels" -> stored.setLabels(subProto.getLabelsMap().isEmpty() ? null
                        : new java.util.HashMap<>(subProto.getLabelsMap()));
                case "retain_acked_messages" -> stored.setRetainAckedMessages(subProto.getRetainAckedMessages());
                case "message_retention_duration" -> {
                    if (subProto.hasMessageRetentionDuration()) {
                        stored.setMessageRetentionDuration(
                                subProto.getMessageRetentionDuration().getSeconds() + "s");
                    }
                }
                case "enable_message_ordering" -> stored.setEnableMessageOrdering(
                        subProto.getEnableMessageOrdering());
                case "enable_exactly_once_delivery" -> stored.setEnableExactlyOnceDelivery(
                        subProto.getEnableExactlyOnceDelivery());
                case "push_config" -> stored.setPushEndpoint(
                        subProto.getPushConfig().getPushEndpoint().isEmpty() ? null
                                : subProto.getPushConfig().getPushEndpoint());
                case "dead_letter_policy" -> {
                    DeadLetterPolicy policy = subProto.getDeadLetterPolicy();
                    stored.setDeadLetterTopic(blankToNull(policy.getDeadLetterTopic()));
                    stored.setMaxDeliveryAttempts(
                            maxDeliveryAttempts(stored.getDeadLetterTopic(), policy.getMaxDeliveryAttempts()));
                }
            }
        }
        subStore.put(subProto.getName(), stored);
        return stored;
    }

    public StoredSubscription updateSubscription(String name,
            int ackDeadlineSeconds,
            Map<String, String> labels,
            Boolean retainAckedMessages,
            String messageRetentionDuration,
            String filter,
            String pushEndpoint,
            Map<String, Object> bigQueryConfig,
            Map<String, Object> expirationPolicy,
            Map<String, Object> retryPolicy,
            String deadLetterTopic,
            Integer maxDeliveryAttempts,
            Boolean enableMessageOrdering,
            Boolean enableExactlyOnceDelivery,
            List<String> updateMaskPaths) {
        LOG.infof("updateSubscription name=%s", name);
        StoredSubscription stored = getSubscription(name);
        boolean replaceAll = updateMaskPaths == null || updateMaskPaths.isEmpty();

        if (filterChangeRequested(stored.getFilter(), filter, updateMaskPaths)) {
            throw filterNotMutable(stored.getName());
        }

        if (replaceAll || masked(updateMaskPaths, "ack_deadline_seconds")) {
            if (ackDeadlineSeconds > 0) {
                stored.setAckDeadlineSeconds(ackDeadlineSeconds);
            }
        }
        if (replaceAll || masked(updateMaskPaths, "labels")) {
            stored.setLabels(emptyToNull(labels));
        }
        if (replaceAll || masked(updateMaskPaths, "retain_acked_messages")) {
            if (retainAckedMessages != null) {
                stored.setRetainAckedMessages(retainAckedMessages);
            }
        }
        if (replaceAll || masked(updateMaskPaths, "message_retention_duration")) {
            stored.setMessageRetentionDuration(blankToNull(messageRetentionDuration));
        }
        if (replaceAll || masked(updateMaskPaths, "push_config")) {
            stored.setPushEndpoint(blankToNull(pushEndpoint));
        }
        if (replaceAll || masked(updateMaskPaths, "bigquery_config")) {
            stored.setBigQueryConfig(emptyObjectMapToNull(bigQueryConfig));
        }
        if (replaceAll || masked(updateMaskPaths, "expiration_policy")) {
            stored.setExpirationPolicy(emptyObjectMapToNull(expirationPolicy));
        }
        if (replaceAll || masked(updateMaskPaths, "retry_policy")) {
            stored.setRetryPolicy(emptyObjectMapToNull(retryPolicy));
        }
        if (replaceAll || masked(updateMaskPaths, "dead_letter_policy")) {
            stored.setDeadLetterTopic(blankToNull(deadLetterTopic));
            stored.setMaxDeliveryAttempts(maxDeliveryAttempts(stored.getDeadLetterTopic(),
                    maxDeliveryAttempts == null ? 0 : maxDeliveryAttempts));
        }
        if (replaceAll || masked(updateMaskPaths, "enable_message_ordering")) {
            if (enableMessageOrdering != null) {
                stored.setEnableMessageOrdering(enableMessageOrdering);
            }
        }
        if (replaceAll || masked(updateMaskPaths, "enable_exactly_once_delivery")) {
            if (enableExactlyOnceDelivery != null) {
                stored.setEnableExactlyOnceDelivery(enableExactlyOnceDelivery);
            }
        }

        subStore.put(name, stored);
        return stored;
    }

    public void modifyPushConfig(String subName, com.google.pubsub.v1.PushConfig pushConfig) {
        LOG.infof("modifyPushConfig subscription=%s", subName);
        StoredSubscription stored = getSubscription(subName);
        stored.setPushEndpoint(pushConfig.getPushEndpoint().isEmpty() ? null : pushConfig.getPushEndpoint());
        subStore.put(subName, stored);
    }

    public void deleteSubscription(String name) {
        LOG.infof("deleteSubscription name=%s", name);
        if (subStore.get(name).isEmpty()) {
            LOG.warnf("deleteSubscription failed: subscription not found name=%s", name);
            throw GcpException.notFound("Subscription not found: " + name);
        }
        iamService.deleteResourceAndPolicy(name, () -> subStore.delete(name));
        queues.remove(name);
        delivered.remove(name);
        listeners.remove(name);
        pendingPush.remove(name);
    }

    public void detachSubscription(String name) {
        LOG.infof("detachSubscription name=%s", name);
        StoredSubscription sub = subStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Subscription not found: " + name));
        sub.setDetached(true);
        subStore.put(name, sub);
        queues.remove(name);
        delivered.remove(name);
        listeners.remove(name);
        pendingPush.remove(name);
    }

    public List<String> listTopicSubscriptions(String topicName) {
        LOG.debugf("listTopicSubscriptions topic=%s", topicName);
        return subStore.scan(k -> true).stream()
                .filter(sub -> topicName.equals(sub.getTopic()))
                .map(StoredSubscription::getName)
                .toList();
    }

    // ── Publish ────────────────────────────────────────────────────────────────

    public List<String> publish(String topicName, List<PubsubMessage> messages) {
        LOG.infof("publish topic=%s count=%d", topicName, messages.size());
        if (topicStore.get(topicName).isEmpty()) {
            LOG.warnf("publish failed: topic not found topic=%s", topicName);
            throw GcpException.notFound("Topic not found: " + topicName);
        }

        List<String> messageIds = new ArrayList<>();
        Instant publishedAt = clock.instant();
        String now = publishedAt.toString();

        for (PubsubMessage msg : messages) {
            String messageId = String.valueOf(messageIdCounter.incrementAndGet());
            messageIds.add(messageId);

            StoredMessage stored = new StoredMessage();
            stored.setMessageId(messageId);
            stored.setData(msg.getData().toByteArray());
            stored.setPublishTime(now);
            stored.setOrderingKey(msg.getOrderingKey().isEmpty() ? null : msg.getOrderingKey());
            if (!msg.getAttributesMap().isEmpty()) {
                stored.setAttributes(msg.getAttributesMap());
            }

            Map<String, String> attributes = stored.getAttributes() == null
                    ? Map.of() : stored.getAttributes();

            int fanOut = 0;
            List<String> queued = new ArrayList<>();
            synchronized (topicLock(topicName)) {
                appendToBacklogs(topicName, stored, publishedAt);
                for (String entry : subStore.keys()) {
                    StoredSubscription sub = subStore.get(entry).orElse(null);
                    if (sub != null && !sub.isDetached() && topicName.equals(sub.getTopic())
                            && matchesFilter(sub, attributes)) {
                        if (sub.getPushEndpoint() != null) {
                            submitPush(sub, stored);
                        } else {
                            queues.computeIfAbsent(entry, k -> new ConcurrentLinkedDeque<>())
                                    .add(new QueuedMessage(stored, 0));
                            queued.add(entry);
                        }
                        fanOut++;
                    }
                }
            }
            queued.forEach(this::notifyListeners);
            LOG.debugf("publish messageId=%s topic=%s fanOut=%d", messageId, topicName, fanOut);
            if (eventarcServiceInstance != null && eventarcServiceInstance.isResolvable()) {
                try {
                    eventarcServiceInstance.get().onPubSubPublish(topicName, stored);
                } catch (Exception e) {
                    LOG.warnf(e, "Eventarc publish dispatch failed for topic=%s messageId=%s", topicName, messageId);
                }
            }
        }
        return messageIds;
    }

    private void deliverPushMessage(StoredSubscription subscription, StoredMessage message) {
        Map<String, Object> messageBody = new LinkedHashMap<>();
        messageBody.put("data", Base64.getEncoder().encodeToString(message.getData()));
        messageBody.put("attributes",
                message.getAttributes() == null ? Map.of() : message.getAttributes());
        messageBody.put("messageId", message.getMessageId());
        messageBody.put("message_id", message.getMessageId());
        messageBody.put("publishTime", message.getPublishTime());
        messageBody.put("publish_time", message.getPublishTime());
        if (message.getOrderingKey() != null) {
            messageBody.put("orderingKey", message.getOrderingKey());
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("message", messageBody);
        envelope.put("subscription", subscription.getName());

        try {
            String body = OBJECT_MAPPER.writeValueAsString(envelope);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(subscription.getPushEndpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            if (Set.of(102, 200, 201, 202, 204).contains(response.statusCode())) {
                pushSettled(subscription.getName(), message);
                LOG.debugf("Push delivery succeeded subscription=%s messageId=%s status=%d",
                        subscription.getName(), message.getMessageId(), response.statusCode());
            } else {
                LOG.warnf("Push delivery failed subscription=%s messageId=%s status=%d; retrying",
                        subscription.getName(), message.getMessageId(), response.statusCode());
                schedulePushRetry(subscription, message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debugf("Push delivery interrupted subscription=%s messageId=%s",
                    subscription.getName(), message.getMessageId());
        } catch (Exception e) {
            LOG.warnf(e, "Push delivery failed subscription=%s messageId=%s; retrying",
                    subscription.getName(), message.getMessageId());
            schedulePushRetry(subscription, message);
        }
    }

    private void submitPush(StoredSubscription subscription, StoredMessage message) {
        pendingPush.computeIfAbsent(subscription.getName(), key -> new ConcurrentHashMap<>())
                .merge(message, 1, Integer::sum);
        pushDeliveryExecutor.submit(() -> deliverPushMessage(subscription, message));
    }

    /** How many push deliveries of a message are still unacknowledged, for tests. */
    int pendingPushDeliveries(String subscriptionName, String messageId) {
        ConcurrentHashMap<StoredMessage, Integer> pending = pendingPush.get(subscriptionName);
        return pending == null ? 0 : pending.entrySet().stream()
                .filter(entry -> entry.getKey().getMessageId().equals(messageId))
                .mapToInt(Map.Entry::getValue).sum();
    }

    /** One delivery of {@code message} ended (acknowledged or abandoned); it stays pending while others run. */
    private void pushSettled(String subscriptionName, StoredMessage message) {
        ConcurrentHashMap<StoredMessage, Integer> pending = pendingPush.get(subscriptionName);
        if (pending != null) {
            pending.computeIfPresent(message, (key, count) -> count > 1 ? count - 1 : null);
        }
    }

    private void schedulePushRetry(StoredSubscription subscription, StoredMessage message) {
        pushRetryExecutor.schedule(() -> {
            Optional<StoredSubscription> current = subStore.get(subscription.getName());

            if (current.isEmpty()
                    || !Objects.equals(current.get().getCreationId(), subscription.getCreationId())
                    || current.get().isDetached()
                    || current.get().getPushEndpoint() == null) {
                pushSettled(subscription.getName(), message);
                return;
            }

            pushDeliveryExecutor.submit(
                    () -> deliverPushMessage(current.get(), message));
        }, 1, TimeUnit.SECONDS);
    }

    // A mask makes "filter" itself the trigger, as in GCP. Without a mask GCP rejects the request
    // outright, so only a value that actually differs is treated as a change here.
    private static boolean filterChangeRequested(String current, String requested,
            List<String> updateMaskPaths) {
        if (updateMaskPaths != null && !updateMaskPaths.isEmpty()) {
            return masked(updateMaskPaths, "filter");
        }
        return requested != null && !Objects.equals(blankToNull(requested), current);
    }

    private static GcpException filterNotMutable(String name) {
        LOG.warnf("updateSubscription rejected: filter is not mutable name=%s", name);
        return GcpException.invalidArgument("Invalid update_mask provided in the "
                + "UpdateSubscriptionRequest: the 'filter' field in the Subscription is not mutable.");
    }

    private boolean matchesFilter(StoredSubscription sub, Map<String, String> attributes) {
        String filter = sub.getFilter();
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return compiledFilters.computeIfAbsent(filter, PubSubService::compileFilter).test(attributes);
    }

    private static Predicate<Map<String, String>> compileFilter(String filter) {
        try {
            return SubscriptionFilter.parse(filter);
        } catch (GcpException e) {
            LOG.warnf("unparseable subscription filter, no message will match it filter=%s error=%s",
                    filter, e.getMessage());
            return NEVER_MATCHES;
        }
    }

    // ── Pull ───────────────────────────────────────────────────────────────────

    public List<ReceivedMessage> pull(String subName, int maxMessages) {
        return pull(subName, maxMessages, 0);
    }

    // GCP gives messages delivered on a stream the stream's deadline, not the subscription's,
    // and treats a stream deadline under 10 seconds as 10.
    List<ReceivedMessage> pull(String subName, int maxMessages, int streamAckDeadlineSeconds) {
        LOG.debugf("pull subscription=%s maxMessages=%d", subName, maxMessages);
        StoredSubscription sub = subStore.get(subName).orElseThrow(() -> {
            LOG.warnf("pull failed: subscription not found name=%s", subName);
            return GcpException.notFound("Subscription not found: " + subName);
        });
        int ackDeadlineSeconds = streamAckDeadlineSeconds > 0
                ? Math.clamp(streamAckDeadlineSeconds, MIN_STREAM_ACK_DEADLINE_SECONDS, MAX_ACK_DEADLINE_SECONDS)
                : sub.getAckDeadlineSeconds();

        ConcurrentLinkedDeque<QueuedMessage> queue = queues.computeIfAbsent(subName, k -> new ConcurrentLinkedDeque<>());
        ConcurrentHashMap<String, Lease> deliveredMap = delivered.computeIfAbsent(subName, k -> new ConcurrentHashMap<>());

        List<ReceivedMessage> result = new ArrayList<>();
        int count = 0;
        while (count < maxMessages) {
            String ackId = UUID.randomUUID().toString();
            Lease lease;
            synchronized (deliveredMap) {
                QueuedMessage queued = queue.poll();
                if (queued == null) {
                    break;
                }
                lease = new Lease(queued.message(), queued.deliveryCount() + 1,
                        clock.instant().plusSeconds(ackDeadlineSeconds));
                deliveredMap.put(ackId, lease);
            }
            StoredMessage msg = lease.message();

            PubsubMessage.Builder msgBuilder = PubsubMessage.newBuilder()
                    .setMessageId(msg.getMessageId())
                    .setData(msg.getData() != null ? ByteString.copyFrom(msg.getData()) : ByteString.EMPTY)
                    .setPublishTime(parseTimestamp(msg.getPublishTime()));

            if (msg.getAttributes() != null) {
                msgBuilder.putAllAttributes(msg.getAttributes());
            }
            if (msg.getOrderingKey() != null) {
                msgBuilder.setOrderingKey(msg.getOrderingKey());
            }

            ReceivedMessage.Builder received = ReceivedMessage.newBuilder()
                    .setAckId(ackId)
                    .setMessage(msgBuilder.build());
            // GCP leaves delivery_attempt at 0 on a subscription without a dead-letter policy.
            if (sub.getDeadLetterTopic() != null) {
                received.setDeliveryAttempt(lease.deliveryCount());
            }
            result.add(received.build());
            count++;
        }
        LOG.debugf("pull subscription=%s returned=%d", subName, result.size());
        return result;
    }

    // ── Acknowledge ────────────────────────────────────────────────────────────

    public void acknowledge(String subName, List<String> ackIds) {
        LOG.debugf("acknowledge subscription=%s ackIds=%d", subName, ackIds.size());
        ConcurrentHashMap<String, Lease> deliveredMap = delivered.get(subName);
        if (deliveredMap == null) {
            LOG.warnf("acknowledge: no delivered map for subscription=%s", subName);
            return;
        }
        Instant now = clock.instant();
        for (String ackId : ackIds) {
            // GCP returns success for an expired ack ID but still redelivers the message.
            deliveredMap.computeIfPresent(ackId, (id, lease) -> lease.isExpired(now) ? lease : null);
        }
    }

    // ── ModifyAckDeadline ──────────────────────────────────────────────────────

    public void modifyAckDeadline(String subName, List<String> ackIds, int ackDeadlineSeconds) {
        modifyAckDeadlineWithoutNotification(subName, ackIds, ackDeadlineSeconds).run();
    }

    // The returned follow-up wakes streams and publishes dead letters. Run it once no lock is held.
    Runnable modifyAckDeadlineWithoutNotification(String subName, List<String> ackIds, int ackDeadlineSeconds) {
        LOG.debugf("modifyAckDeadline subscription=%s ackIds=%d deadline=%d",
                subName, ackIds.size(), ackDeadlineSeconds);
        if (ackDeadlineSeconds < 0 || ackDeadlineSeconds > MAX_ACK_DEADLINE_SECONDS) {
            throw GcpException.invalidArgument("Invalid ack deadline given (ack_deadline=" + ackDeadlineSeconds
                    + "). The ack deadline must be between 0 and " + MAX_ACK_DEADLINE_SECONDS + " seconds.");
        }
        if (ackIds.isEmpty()) {
            throw GcpException.invalidArgument("You have not specified an ack ID in the request.");
        }
        StoredSubscription sub = subStore.get(subName).orElseThrow(() -> {
            LOG.warnf("modifyAckDeadline failed: subscription not found name=%s", subName);
            return GcpException.notFound("Subscription not found: " + subName);
        });
        if (ackDeadlineSeconds > 0) {
            extendLeases(subName, ackIds, ackDeadlineSeconds);
            return NO_FOLLOW_UP;
        }
        return nack(sub, ackIds);
    }

    private void extendLeases(String subName, List<String> ackIds, int ackDeadlineSeconds) {
        ConcurrentHashMap<String, Lease> deliveredMap = delivered.get(subName);
        if (deliveredMap == null) {
            return;
        }
        Instant now = clock.instant();
        Instant expiry = now.plusSeconds(ackDeadlineSeconds);
        for (String ackId : ackIds) {
            // An expired lease is not revived, matching GCP.
            deliveredMap.computeIfPresent(ackId,
                    (id, lease) -> lease.isExpired(now) ? lease : lease.withExpiry(expiry));
        }
    }

    private Runnable nack(StoredSubscription sub, List<String> ackIds) {
        String subName = sub.getName();
        ConcurrentHashMap<String, Lease> deliveredMap = delivered.get(subName);
        ConcurrentLinkedDeque<QueuedMessage> queue = queues.get(subName);
        if (deliveredMap == null || queue == null) {
            // Deleted or detached in the meantime: there is no live queue to return the messages to.
            return NO_FOLLOW_UP;
        }
        int deadLetterThreshold = deadLetterThreshold(sub);
        List<Lease> released = new ArrayList<>();
        List<Lease> deadLetters;
        synchronized (deliveredMap) {
            for (String ackId : ackIds) {
                Lease lease = deliveredMap.remove(ackId);
                if (lease != null) {
                    released.add(lease);
                }
            }
            deadLetters = requeueOrDeadLetter(queue, released, deadLetterThreshold);
        }
        if (released.isEmpty()) {
            return NO_FOLLOW_UP;
        }
        LOG.debugf("nack subscription=%s requeued=%d deadLettered=%d",
                subName, released.size() - deadLetters.size(), deadLetters.size());
        return followUp(sub, deliveredMap, queue, released.size() > deadLetters.size(), deadLetters);
    }

    private void expireLeasesSafely() {
        try {
            expireLeases();
        } catch (Exception e) {
            // An exception would cancel the fixed-delay schedule and stop all redelivery.
            LOG.warnf(e, "lease expiry sweep failed");
        }
    }

    void expireLeases() {
        for (Map.Entry<String, ConcurrentHashMap<String, Lease>> entry : delivered.entrySet()) {
            requeueExpiredLeases(entry.getKey(), entry.getValue()).run();
        }
        dropExpiredBacklogs(clock.instant());
    }

    private Runnable requeueExpiredLeases(String subName, ConcurrentHashMap<String, Lease> deliveredMap) {
        if (deliveredMap.isEmpty()) {
            return NO_FOLLOW_UP;
        }
        ConcurrentLinkedDeque<QueuedMessage> queue = queues.get(subName);
        Optional<StoredSubscription> sub = subStore.get(subName);
        if (queue == null || sub.isEmpty() || delivered.get(subName) != deliveredMap) {
            // Deleted, detached or recreated since the sweep started: these messages belong to no live queue.
            return NO_FOLLOW_UP;
        }
        int deadLetterThreshold = deadLetterThreshold(sub.get());
        List<Lease> expired = new ArrayList<>();
        List<Lease> deadLetters;
        synchronized (deliveredMap) {
            Instant now = clock.instant();
            for (Map.Entry<String, Lease> entry : deliveredMap.entrySet()) {
                Lease lease = entry.getValue();
                // remove(key, value) loses to a concurrent ack or extension of the same lease.
                if (lease.isExpired(now) && deliveredMap.remove(entry.getKey(), lease)) {
                    expired.add(lease);
                }
            }
            deadLetters = requeueOrDeadLetter(queue, expired, deadLetterThreshold);
        }
        if (expired.isEmpty()) {
            return NO_FOLLOW_UP;
        }
        LOG.debugf("lease expiry subscription=%s requeued=%d deadLettered=%d",
                subName, expired.size() - deadLetters.size(), deadLetters.size());
        return followUp(sub.get(), deliveredMap, queue, expired.size() > deadLetters.size(), deadLetters);
    }

    // Called under the lease lock. Returns the leases to dead-letter and puts the rest back in the queue.
    private static List<Lease> requeueOrDeadLetter(ConcurrentLinkedDeque<QueuedMessage> queue, List<Lease> released,
            int deadLetterThreshold) {
        List<QueuedMessage> requeued = new ArrayList<>();
        List<Lease> deadLetters = new ArrayList<>();
        for (Lease lease : released) {
            if (lease.deliveryCount() >= deadLetterThreshold) {
                deadLetters.add(lease);
            } else {
                requeued.add(lease.requeued());
            }
        }
        requeueAtFront(queue, requeued);
        return deadLetters;
    }

    // GCP does not forward while the dead-letter topic is gone. It keeps redelivering the message instead,
    // and the delivery attempt keeps counting past the maximum.
    private int deadLetterThreshold(StoredSubscription sub) {
        if (sub.getDeadLetterTopic() == null || topicStore.get(sub.getDeadLetterTopic()).isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return maxDeliveryAttempts(sub.getDeadLetterTopic(), sub.getMaxDeliveryAttempts());
    }

    // GCP stores 5 when a dead-letter policy leaves max_delivery_attempts at 0. Subscriptions saved before
    // this default was applied still hold 0, so delivery reads the value through here too.
    private static int maxDeliveryAttempts(String deadLetterTopic, int requested) {
        if (deadLetterTopic == null) {
            return 0;
        }
        return requested == 0 ? DEFAULT_MAX_DELIVERY_ATTEMPTS : requested;
    }

    private Runnable followUp(StoredSubscription sub, ConcurrentHashMap<String, Lease> deliveredMap,
            ConcurrentLinkedDeque<QueuedMessage> queue, boolean requeued, List<Lease> deadLetters) {
        return () -> {
            if (requeued) {
                notifyListeners(sub.getName());
            }
            if (!deadLetters.isEmpty()) {
                forwardToDeadLetterTopic(sub, deliveredMap, queue, deadLetters);
            }
        };
    }

    private void forwardToDeadLetterTopic(StoredSubscription sub, ConcurrentHashMap<String, Lease> deliveredMap,
            ConcurrentLinkedDeque<QueuedMessage> queue, List<Lease> deadLetters) {
        List<PubsubMessage> messages = deadLetters.stream()
                .map(lease -> deadLetterMessage(sub, lease))
                .toList();
        try {
            publish(sub.getDeadLetterTopic(), messages);
        } catch (GcpException e) {
            // The topic was deleted after the threshold check. Redeliver rather than lose the messages.
            LOG.warnf("dead-letter forwarding failed subscription=%s topic=%s: %s",
                    sub.getName(), sub.getDeadLetterTopic(), e.getMessage());
            if (delivered.get(sub.getName()) != deliveredMap) {
                // Deleted, detached or recreated in the meantime: these messages belong to no live queue.
                return;
            }
            List<QueuedMessage> requeued = new ArrayList<>(deadLetters.stream().map(Lease::requeued).toList());
            synchronized (deliveredMap) {
                requeueAtFront(queue, requeued);
            }
            notifyListeners(sub.getName());
        }
    }

    private static PubsubMessage deadLetterMessage(StoredSubscription sub, Lease lease) {
        StoredMessage message = lease.message();
        PubsubMessage.Builder builder = PubsubMessage.newBuilder()
                .setData(message.getData() != null ? ByteString.copyFrom(message.getData()) : ByteString.EMPTY);
        if (message.getAttributes() != null) {
            builder.putAllAttributes(message.getAttributes());
        }
        if (message.getOrderingKey() != null) {
            builder.setOrderingKey(message.getOrderingKey());
        }
        builder.putAttributes("CloudPubSubDeadLetterSourceDeliveryCount", String.valueOf(lease.deliveryCount()))
                .putAttributes("CloudPubSubDeadLetterSourceSubscription", GcpResourceNames.lastSegment(sub.getName()))
                .putAttributes("CloudPubSubDeadLetterSourceSubscriptionProject",
                        GcpResourceNames.parseProject(sub.getName()))
                .putAttributes("CloudPubSubDeadLetterSourceTopicPublishTime",
                        deadLetterPublishTime(message.getPublishTime()));
        return builder.build();
    }

    // GCP writes this attribute with milliseconds and a "+00:00" offset, unlike publishTime.
    private static String deadLetterPublishTime(String publishTime) {
        try {
            return DEAD_LETTER_PUBLISH_TIME.format(Instant.parse(publishTime));
        } catch (DateTimeParseException e) {
            // publish() always stores Instant.toString(), so this only happens with corrupt state.
            LOG.warnf("dead-letter: cannot parse publishTime=%s, passing it through: %s", publishTime, e.getMessage());
            return publishTime;
        }
    }

    // A message waiting in one subscription's queue. The count is per subscription, which a shared StoredMessage
    // can't hold, and it survives nacks, expiry and seeks like GCP's delivery attempt does.
    private record QueuedMessage(StoredMessage message, int deliveryCount) {}

    // A snapshot's retained messages. Guarded by the topic's lock; the expiry follows UpdateSnapshot.
    private static final class Backlog {
        final String topic;
        final List<StoredMessage> messages;
        volatile Instant expiry;

        Backlog(String topic, List<StoredMessage> messages, Instant expiry) {
            this.topic = topic;
            this.messages = messages;
            this.expiry = expiry;
        }
    }

    private record Lease(StoredMessage message, int deliveryCount, Instant expiry) {
        boolean isExpired(Instant now) {
            return !expiry.isAfter(now);
        }

        Lease withExpiry(Instant newExpiry) {
            return new Lease(message, deliveryCount, newExpiry);
        }

        QueuedMessage requeued() {
            return new QueuedMessage(message, deliveryCount);
        }
    }

    Runnable registerMessageListener(String subName, MessageListener listener) {
        getSubscription(subName);
        CopyOnWriteArrayList<MessageListener> subscriptionListeners =
                listeners.computeIfAbsent(subName, k -> new CopyOnWriteArrayList<>());
        subscriptionListeners.add(listener);
        return () -> subscriptionListeners.remove(listener);
    }

    void notifyListeners(String subName) {
        CopyOnWriteArrayList<MessageListener> subscriptionListeners = listeners.get(subName);
        if (subscriptionListeners == null) {
            return;
        }
        for (MessageListener listener : subscriptionListeners) {
            try {
                listener.onMessagesAvailable();
            } catch (Exception e) {
                LOG.warnf("message listener failed for subscription=%s: %s", subName, e.getMessage());
            }
        }
    }

    @FunctionalInterface
    interface MessageListener {
        void onMessagesAvailable();
    }

    // ── Snapshots ──────────────────────────────────────────────────────────────

    public StoredSnapshot createSnapshot(String snapshotName, String subscriptionName,
            Map<String, String> labels) {
        LOG.infof("createSnapshot name=%s subscription=%s", snapshotName, subscriptionName);
        if (snapshotStore.get(snapshotName).isPresent()) {
            throw GcpException.alreadyExists("Snapshot already exists: " + snapshotName);
        }
        StoredSubscription sub = getSubscription(subscriptionName);
        synchronized (topicLock(sub.getTopic())) {
            List<StoredMessage> unacked = unackedMessages(subscriptionName);
            Instant now = clock.instant();
            // The snapshot lives 7 days from its oldest unacked message, and GCP refuses one that would expire
            // within the hour (pubsub.proto, Snapshot.expire_time and rpc CreateSnapshot).
            Instant oldest = unacked.isEmpty() ? now : publishInstant(unacked.getFirst().getPublishTime());
            Instant expiry = (oldest.isAfter(now) ? now : oldest).plus(SNAPSHOT_LIFETIME);
            if (expiry.isBefore(now.plus(MIN_SNAPSHOT_LIFETIME))) {
                throw GcpException.failedPrecondition("Snapshot " + snapshotName + " would expire in less than"
                        + " 1 hour: the oldest unacked message in " + subscriptionName + " is too old");
            }
            StoredSnapshot snapshot = new StoredSnapshot(snapshotName, sub.getTopic(), expiry.toString());
            if (labels != null && !labels.isEmpty()) {
                snapshot.setLabels(labels);
            }
            snapshotStore.put(snapshotName, snapshot);
            backlogs.put(snapshotName, new Backlog(sub.getTopic(), new ArrayList<>(unacked), expiry));
            return snapshot;
        }
    }

    public List<String> listTopicSnapshots(String topicName) {
        LOG.debugf("listTopicSnapshots topic=%s", topicName);
        if (topicStore.get(topicName).isEmpty()) {
            throw GcpException.notFound("Topic not found: " + topicName);
        }
        return snapshotStore.scan(k -> true).stream()
                .filter(snapshot -> topicName.equals(snapshot.getTopic()))
                .map(StoredSnapshot::getName)
                .toList();
    }

    public StoredSnapshot getSnapshot(String snapshotName) {
        LOG.debugf("getSnapshot name=%s", snapshotName);
        return snapshotStore.get(snapshotName)
                .orElseThrow(() -> GcpException.notFound("Snapshot not found: " + snapshotName));
    }

    public List<StoredSnapshot> listSnapshots(String project) {
        LOG.debugf("listSnapshots project=%s", project);
        String prefix = "projects/" + project + "/snapshots/";
        return snapshotStore.scan(k -> k.startsWith(prefix));
    }

    public StoredSnapshot updateSnapshot(String snapshotName, Map<String, String> labels,
            String expireTime, List<String> updateMaskPaths) {
        LOG.infof("updateSnapshot name=%s", snapshotName);
        StoredSnapshot stored = getSnapshot(snapshotName);
        for (String path : updateMaskPaths) {
            switch (jsonMaskToProtoMask(path)) {
                case "labels" -> stored.setLabels(labels);
                case "expire_time" -> { if (expireTime != null) stored.setExpireTime(expireTime); }
            }
        }
        snapshotStore.put(snapshotName, stored);
        Backlog backlog = backlogs.get(snapshotName);
        if (backlog != null) {
            backlog.expiry = expiryOf(stored);
        }
        return stored;
    }

    public void deleteSnapshot(String snapshotName) {
        LOG.infof("deleteSnapshot name=%s", snapshotName);
        snapshotStore.get(snapshotName)
                .orElseThrow(() -> GcpException.notFound("Snapshot not found: " + snapshotName));
        // Read before the metadata goes: a snapshot recreated under this name afterwards has a new backlog,
        // which this delete must leave alone.
        Backlog backlog = backlogs.get(snapshotName);
        iamService.deleteResourceAndPolicy(snapshotName, () -> snapshotStore.delete(snapshotName));
        if (backlog != null) {
            backlogs.remove(snapshotName, backlog);
        }
    }

    public void seek(String subscriptionName, String snapshotName, Timestamp time) {
        LOG.infof("seek subscription=%s snapshot=%s time=%s", subscriptionName, snapshotName, describe(time));
        StoredSubscription sub = getSubscription(subscriptionName);
        if (sub.isDetached()) {
            throw GcpException.failedPrecondition("Subscription " + subscriptionName + " is detached");
        }
        if (snapshotName != null) {
            StoredSnapshot snapshot = getSnapshot(snapshotName);
            if (!Objects.equals(snapshot.getTopic(), sub.getTopic())) {
                throw GcpException.invalidArgument("The snapshot's topic must be the same as that of the subscription:"
                        + " " + snapshotName + " is on " + snapshot.getTopic() + ", " + subscriptionName
                        + " on " + sub.getTopic());
            }
            if (expiryOf(snapshot).isBefore(clock.instant())) {
                throw GcpException.notFound("Snapshot not found: " + snapshotName + " has expired");
            }
            seekToSnapshot(sub, snapshotName);
            return;
        }
        // Validate before touching either collection, so a bad time can't strand in-flight messages.
        Instant cutoff = null;
        if (time != null) {
            if (!Timestamps.isValid(time)) {
                throw GcpException.invalidArgument("Invalid seek time: " + describe(time));
            }
            cutoff = Instant.ofEpochSecond(time.getSeconds(), time.getNanos());
        }
        if (cutoff == null) {
            throw GcpException.invalidArgument("Seek needs a time or a snapshot");
        }
        ConcurrentHashMap<String, Lease> deliveredMap =
                delivered.computeIfAbsent(subscriptionName, k -> new ConcurrentHashMap<>());
        // Seek to a time: messages published before it count as acknowledged and are dropped,
        // messages published at or after it become unacknowledged again, including in-flight ones.
        ConcurrentLinkedDeque<QueuedMessage> queue =
                queues.computeIfAbsent(subscriptionName, k -> new ConcurrentLinkedDeque<>());
        Instant seekTime = cutoff;
        synchronized (deliveredMap) {
            queue.removeIf(queued -> isBefore(queued.message().getPublishTime(), seekTime));
            List<QueuedMessage> requeued = new ArrayList<>();
            for (String ackId : List.copyOf(deliveredMap.keySet())) {
                Lease lease = deliveredMap.remove(ackId);
                if (lease != null && !isBefore(lease.message().getPublishTime(), seekTime)) {
                    requeued.add(lease.requeued());
                }
            }
            requeueAtFront(queue, requeued);
        }
        if (!queue.isEmpty()) {
            notifyListeners(subscriptionName);
        }
    }

    /**
     * Puts the subscription where the snapshot left it: every message the snapshot retains is unacknowledged and
     * nothing else is (pubsub.proto, rpc Seek). A message still in the subscription keeps its delivery count;
     * one acked since the snapshot starts again at 0. A push subscription gets them pushed again.
     */
    private void seekToSnapshot(StoredSubscription sub, String snapshotName) {
        String subscriptionName = sub.getName();
        List<StoredMessage> restored;
        ConcurrentLinkedDeque<QueuedMessage> queue = null;
        synchronized (topicLock(sub.getTopic())) {
            Backlog backlog = backlogs.get(snapshotName);
            restored = (backlog == null ? List.<StoredMessage>of() : List.copyOf(backlog.messages)).stream()
                    .filter(message -> matchesFilter(sub, message.getAttributes() == null
                            ? Map.of() : message.getAttributes()))
                    .toList();
            if (sub.getPushEndpoint() == null) {
                queue = queues.computeIfAbsent(subscriptionName, k -> new ConcurrentLinkedDeque<>());
                ConcurrentHashMap<String, Lease> deliveredMap =
                        delivered.computeIfAbsent(subscriptionName, k -> new ConcurrentHashMap<>());
                synchronized (deliveredMap) {
                    Map<String, Integer> counts = new HashMap<>();
                    queue.forEach(queued -> counts.merge(queued.message().getMessageId(), queued.deliveryCount(),
                            Math::max));
                    deliveredMap.values().forEach(lease -> counts.merge(lease.message().getMessageId(),
                            lease.deliveryCount(), Math::max));
                    queue.clear();
                    deliveredMap.clear();
                    for (StoredMessage message : restored) {
                        queue.add(new QueuedMessage(message, counts.getOrDefault(message.getMessageId(), 0)));
                    }
                }
            }
        }
        LOG.debugf("seek subscription=%s snapshot=%s restored=%d", subscriptionName, snapshotName, restored.size());
        if (queue == null) {
            restored.forEach(message -> submitPush(sub, message));
        } else if (!queue.isEmpty()) {
            notifyListeners(subscriptionName);
        }
    }

    /** The subscription's queued, in-flight and unacknowledged push messages, in publish order. */
    private List<StoredMessage> unackedMessages(String subscriptionName) {
        List<QueuedMessage> unacked = new ArrayList<>();
        // The lease map is the lock for moving messages between queue and leases, so take it even when no pull
        // has created it yet; a first pull racing this copy then waits instead of hiding a message.
        ConcurrentHashMap<String, Lease> deliveredMap =
                delivered.computeIfAbsent(subscriptionName, k -> new ConcurrentHashMap<>());
        synchronized (deliveredMap) {
            ConcurrentLinkedDeque<QueuedMessage> queue = queues.get(subscriptionName);
            if (queue != null) { unacked.addAll(queue); }
            deliveredMap.values().forEach(lease -> unacked.add(lease.requeued()));
        }
        ConcurrentHashMap<StoredMessage, Integer> pushing = pendingPush.get(subscriptionName);
        if (pushing != null) {
            pushing.keySet().forEach(message -> unacked.add(new QueuedMessage(message, 0)));
        }
        unacked.sort(PUBLISH_ORDER);
        Map<String, StoredMessage> byId = new LinkedHashMap<>();
        unacked.forEach(queued -> byId.putIfAbsent(queued.message().getMessageId(), queued.message()));
        return List.copyOf(byId.values());
    }

    /** Appends a published message to the live backlog of every snapshot on its topic. Holds the topic lock. */
    private void appendToBacklogs(String topicName, StoredMessage message, Instant publishedAt) {
        dropExpiredBacklogs(publishedAt);
        backlogs.values().stream()
                .filter(backlog -> backlog.topic.equals(topicName))
                .forEach(backlog -> backlog.messages.add(message));
    }

    /** Frees the messages of expired snapshots; runs on publish and with the periodic lease sweep. */
    private void dropExpiredBacklogs(Instant now) {
        backlogs.entrySet().removeIf(entry -> entry.getValue().expiry.isBefore(now));
    }

    Object topicLock(String topicName) {
        return topicLocks.computeIfAbsent(topicName, key -> new Object());
    }

    private static Instant expiryOf(StoredSnapshot snapshot) {
        try {
            return Instant.parse(snapshot.getExpireTime());
        } catch (RuntimeException e) {
            return Instant.MAX;
        }
    }

    // deliveredMap iterates in ack id hash order; put redelivered messages back in publish order.
    private static void requeueAtFront(ConcurrentLinkedDeque<QueuedMessage> queue, List<QueuedMessage> messages) {
        messages.sort(PUBLISH_ORDER);
        for (int i = messages.size() - 1; i >= 0; i--) {
            queue.addFirst(messages.get(i));
        }
    }

    private static final Comparator<QueuedMessage> PUBLISH_ORDER = Comparator
            .comparing((QueuedMessage q) -> publishInstant(q.message().getPublishTime()))
            .thenComparingLong(q -> messageIdOrder(q.message().getMessageId()));

    private static Instant publishInstant(String publishTime) {
        if (publishTime == null) {
            return Instant.MAX;
        }
        try {
            return Instant.parse(publishTime);
        } catch (DateTimeParseException e) {
            return Instant.MAX;
        }
    }

    private static long messageIdOrder(String messageId) {
        try {
            return Long.parseLong(messageId);
        } catch (NumberFormatException e) {
            // Ids come from messageIdCounter, so this only orders corrupt state; sort it last.
            return Long.MAX_VALUE;
        }
    }

    private static String describe(Timestamp time) {
        return time == null ? "none" : "seconds=" + time.getSeconds() + " nanos=" + time.getNanos();
    }

    private static boolean isBefore(String publishTime, Instant time) {
        if (publishTime == null) {
            return false;
        }
        try {
            return Instant.parse(publishTime).isBefore(time);
        } catch (DateTimeParseException e) {
            // publish() always stores Instant.toString(), so this only happens with corrupt state.
            // Keeping the message is the safe side: a seek must never lose data it can't date.
            LOG.warnf("seek: cannot parse publishTime=%s, keeping message: %s", publishTime, e.getMessage());
            return false;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static Timestamp parseTimestamp(String isoTime) {
        if (isoTime == null) return Timestamp.getDefaultInstance();
        try {
            Instant instant = Instant.parse(isoTime);
            return Timestamp.newBuilder()
                    .setSeconds(instant.getEpochSecond())
                    .setNanos(instant.getNano())
                    .build();
        } catch (Exception e) {
            return Timestamp.getDefaultInstance();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, String> emptyToNull(Map<String, String> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return new LinkedHashMap<>(value);
    }

    private static Map<String, Object> emptyObjectMapToNull(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return new LinkedHashMap<>(value);
    }

    private static boolean masked(List<String> updateMaskPaths, String path) {
        return updateMaskPaths.stream()
                .map(PubSubService::jsonMaskToProtoMask)
                .anyMatch(mask -> mask.equals(path) || mask.startsWith(path + "."));
    }

    private static String jsonMaskToProtoMask(String path) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}

package io.floci.gcp.services.pubsub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.ReceivedMessage;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.pubsub.model.StoredMessage;
import io.floci.gcp.services.pubsub.model.StoredSnapshot;
import io.floci.gcp.services.pubsub.model.StoredSubscription;
import io.floci.gcp.services.pubsub.model.StoredTopic;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class PubSubService {

    private static final Logger LOG = Logger.getLogger(PubSubService.class);

    private final StorageBackend<String, StoredTopic> topicStore;
    private final StorageBackend<String, StoredSubscription> subStore;
    private final StorageBackend<String, StoredSnapshot> snapshotStore;

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<StoredMessage>> queues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, StoredMessage>> delivered = new ConcurrentHashMap<>();
    private final AtomicLong messageIdCounter = new AtomicLong(0);

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final GrpcServerManager grpcServerManager;

    @Inject
    public PubSubService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, GrpcServerManager grpcServerManager) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
        this.topicStore = storageFactory.createGlobal("pubsub-topics", "pubsub-topics.json",
                new TypeReference<Map<String, StoredTopic>>() {});
        this.subStore = storageFactory.createGlobal("pubsub-subs", "pubsub-subs.json",
                new TypeReference<Map<String, StoredSubscription>>() {});
        this.snapshotStore = storageFactory.createGlobal("pubsub-snapshots", "pubsub-snapshots.json",
                new TypeReference<Map<String, StoredSnapshot>>() {});
    }

    PubSubService(StorageBackend<String, StoredTopic> topicStore,
            StorageBackend<String, StoredSubscription> subStore,
            StorageBackend<String, StoredSnapshot> snapshotStore) {
        this.topicStore = topicStore;
        this.subStore = subStore;
        this.snapshotStore = snapshotStore;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("pubsub")
                .enabled(config.services().pubsub().enabled())
                .storageKey("pubsub")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(PubSubPublisherController.class, PubSubSubscriberController.class)
                .build());
        grpcServerManager.bind(new PubSubPublisherController(this));
        grpcServerManager.bind(new PubSubSubscriberController(this));
    }

    // ── Topics ─────────────────────────────────────────────────────────────────

    public StoredTopic createTopic(String name) {
        LOG.infof("createTopic name=%s", name);
        if (topicStore.get(name).isPresent()) {
            LOG.warnf("createTopic failed: topic already exists name=%s", name);
            throw GcpException.alreadyExists("Topic already exists: " + name);
        }
        StoredTopic topic = new StoredTopic(name);
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

    public void deleteTopic(String name) {
        LOG.infof("deleteTopic name=%s", name);
        if (topicStore.get(name).isEmpty()) {
            LOG.warnf("deleteTopic failed: topic not found name=%s", name);
            throw GcpException.notFound("Topic not found: " + name);
        }
        topicStore.delete(name);
    }

    // ── Subscriptions ──────────────────────────────────────────────────────────

    public StoredSubscription createSubscription(String name, String topicName, int ackDeadlineSeconds) {
        LOG.infof("createSubscription name=%s topic=%s ackDeadline=%d", name, topicName, ackDeadlineSeconds);
        if (subStore.get(name).isPresent()) {
            LOG.warnf("createSubscription failed: subscription already exists name=%s", name);
            throw GcpException.alreadyExists("Subscription already exists: " + name);
        }
        if (topicStore.get(topicName).isEmpty()) {
            LOG.warnf("createSubscription failed: topic not found topic=%s", topicName);
            throw GcpException.notFound("Topic not found: " + topicName);
        }
        int deadline = ackDeadlineSeconds > 0 ? ackDeadlineSeconds : 10;
        StoredSubscription sub = new StoredSubscription(name, topicName, deadline);
        subStore.put(name, sub);
        queues.put(name, new ConcurrentLinkedDeque<>());
        delivered.put(name, new ConcurrentHashMap<>());
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
        for (String path : updateMask.getPathsList()) {
            switch (path) {
                case "ack_deadline_seconds" -> stored.setAckDeadlineSeconds(subProto.getAckDeadlineSeconds());
                case "filter" -> stored.setFilter(subProto.getFilter().isEmpty() ? null : subProto.getFilter());
                case "retain_acked_messages" -> stored.setRetainAckedMessages(subProto.getRetainAckedMessages());
                case "message_retention_duration" -> {
                    if (subProto.hasMessageRetentionDuration()) {
                        stored.setMessageRetentionDuration(
                                subProto.getMessageRetentionDuration().getSeconds() + "s");
                    }
                }
                case "push_config" -> stored.setPushEndpoint(
                        subProto.getPushConfig().getPushEndpoint().isEmpty() ? null
                                : subProto.getPushConfig().getPushEndpoint());
                case "dead_letter_policy" -> {
                    if (subProto.hasDeadLetterPolicy()) {
                        stored.setDeadLetterTopic(subProto.getDeadLetterPolicy().getDeadLetterTopic());
                        stored.setMaxDeliveryAttempts(subProto.getDeadLetterPolicy().getMaxDeliveryAttempts());
                    }
                }
            }
        }
        subStore.put(subProto.getName(), stored);
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
        subStore.delete(name);
        queues.remove(name);
        delivered.remove(name);
    }

    public void detachSubscription(String name) {
        LOG.infof("detachSubscription name=%s", name);
        StoredSubscription sub = subStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Subscription not found: " + name));
        sub.setDetached(true);
        subStore.put(name, sub);
        queues.remove(name);
        delivered.remove(name);
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
        String now = Instant.now().toString();

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

            int fanOut = 0;
            for (var entry : subStore.keys()) {
                StoredSubscription sub = subStore.get(entry).orElse(null);
                if (sub != null && !sub.isDetached() && topicName.equals(sub.getTopic())) {
                    queues.computeIfAbsent(entry, k -> new ConcurrentLinkedDeque<>()).add(stored);
                    fanOut++;
                }
            }
            LOG.debugf("publish messageId=%s topic=%s fanOut=%d", messageId, topicName, fanOut);
        }
        return messageIds;
    }

    // ── Pull ───────────────────────────────────────────────────────────────────

    public List<ReceivedMessage> pull(String subName, int maxMessages) {
        LOG.debugf("pull subscription=%s maxMessages=%d", subName, maxMessages);
        if (subStore.get(subName).isEmpty()) {
            LOG.warnf("pull failed: subscription not found name=%s", subName);
            throw GcpException.notFound("Subscription not found: " + subName);
        }

        ConcurrentLinkedDeque<StoredMessage> queue = queues.computeIfAbsent(subName, k -> new ConcurrentLinkedDeque<>());
        ConcurrentHashMap<String, StoredMessage> deliveredMap = delivered.computeIfAbsent(subName, k -> new ConcurrentHashMap<>());

        List<ReceivedMessage> result = new ArrayList<>();
        int count = 0;
        while (count < maxMessages) {
            StoredMessage msg = queue.poll();
            if (msg == null) break;

            String ackId = UUID.randomUUID().toString();
            deliveredMap.put(ackId, msg);

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

            result.add(ReceivedMessage.newBuilder()
                    .setAckId(ackId)
                    .setMessage(msgBuilder.build())
                    .build());
            count++;
        }
        LOG.debugf("pull subscription=%s returned=%d", subName, result.size());
        return result;
    }

    // ── Acknowledge ────────────────────────────────────────────────────────────

    public void acknowledge(String subName, List<String> ackIds) {
        LOG.debugf("acknowledge subscription=%s ackIds=%d", subName, ackIds.size());
        ConcurrentHashMap<String, StoredMessage> deliveredMap = delivered.get(subName);
        if (deliveredMap == null) {
            LOG.warnf("acknowledge: no delivered map for subscription=%s", subName);
            return;
        }
        for (String ackId : ackIds) {
            deliveredMap.remove(ackId);
        }
    }

    // ── Snapshots ──────────────────────────────────────────────────────────────

    public StoredSnapshot createSnapshot(String snapshotName, String subscriptionName,
            Map<String, String> labels) {
        LOG.infof("createSnapshot name=%s subscription=%s", snapshotName, subscriptionName);
        if (snapshotStore.get(snapshotName).isPresent()) {
            throw GcpException.alreadyExists("Snapshot already exists: " + snapshotName);
        }
        StoredSubscription sub = getSubscription(subscriptionName);
        String expireTime = Instant.now().plus(7, java.time.temporal.ChronoUnit.DAYS).toString();
        StoredSnapshot snapshot = new StoredSnapshot(snapshotName, sub.getTopic(), expireTime);
        if (labels != null && !labels.isEmpty()) {
            snapshot.setLabels(labels);
        }
        snapshotStore.put(snapshotName, snapshot);
        return snapshot;
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
            switch (path) {
                case "labels" -> stored.setLabels(labels);
                case "expire_time" -> { if (expireTime != null) stored.setExpireTime(expireTime); }
            }
        }
        snapshotStore.put(snapshotName, stored);
        return stored;
    }

    public void deleteSnapshot(String snapshotName) {
        LOG.infof("deleteSnapshot name=%s", snapshotName);
        snapshotStore.get(snapshotName)
                .orElseThrow(() -> GcpException.notFound("Snapshot not found: " + snapshotName));
        snapshotStore.delete(snapshotName);
    }

    public void seek(String subscriptionName, String snapshotName) {
        LOG.infof("seek subscription=%s snapshot=%s", subscriptionName, snapshotName);
        getSubscription(subscriptionName);
        if (snapshotName != null) {
            getSnapshot(snapshotName);
        }
        // Minimal: clear delivered to simulate seeking back to snapshot position
        ConcurrentHashMap<String, StoredMessage> deliveredMap = delivered.get(subscriptionName);
        if (deliveredMap != null) {
            deliveredMap.clear();
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
}

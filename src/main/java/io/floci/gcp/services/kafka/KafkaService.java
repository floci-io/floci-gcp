package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.kafka.model.ClusterState;
import io.floci.gcp.services.kafka.model.StoredCluster;
import io.floci.gcp.services.kafka.model.StoredConsumerGroup;
import io.floci.gcp.services.kafka.model.StoredTopic;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class KafkaService {

    private static final Logger LOG = Logger.getLogger(KafkaService.class);

    private final StorageBackend<String, StoredCluster> clusterStore;
    private final StorageBackend<String, StoredTopic> topicStore;
    private final StorageBackend<String, StoredConsumerGroup> consumerGroupStore;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;
    private final RedpandaManager redpandaManager;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public KafkaService(StorageFactory storageFactory,
                        EmulatorConfig config,
                        ServiceRegistry serviceRegistry,
                        RedpandaManager redpandaManager) {
        this.clusterStore = storageFactory.createGlobal("kafka", "kafka-clusters.json",
                new TypeReference<Map<String, StoredCluster>>() {});
        this.topicStore = storageFactory.createGlobal("kafka", "kafka-topics.json",
                new TypeReference<Map<String, StoredTopic>>() {});
        this.consumerGroupStore = storageFactory.createGlobal("kafka", "kafka-consumer-groups.json",
                new TypeReference<Map<String, StoredConsumerGroup>>() {});
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.redpandaManager = redpandaManager;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("kafka")
                .enabled(config.services().kafka().enabled())
                .storageKey("kafka")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(KafkaController.class)
                .build());
        if (!config.services().kafka().mock()) {
            startReadinessPoller();
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdown();
        if (!config.services().kafka().mock()) {
            for (StoredCluster cluster : clusterStore.scan(k -> true)) {
                redpandaManager.stopContainer(cluster);
            }
        }
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    public StoredCluster createCluster(String project, String location, String clusterId,
                                       Map<String, Object> body) {
        String name = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        if (clusterStore.get(name).isPresent()) {
            throw GcpException.alreadyExists("Cluster already exists: " + name);
        }

        StoredCluster cluster = new StoredCluster(name);
        cluster.setVolumeId(String.format("%06x", new SecureRandom().nextInt(0xFFFFFF)));
        cluster.setVolumeName(ContainerStorageHelper.resourceName(
                config, "kafka", cluster.getVolumeId(), clusterId));

        applyCapacityFromBody(cluster, body);

        if (config.services().kafka().mock()) {
            cluster.setState(ClusterState.ACTIVE);
            cluster.setBootstrapAddress("localhost:9092");
            clusterStore.put(name, cluster);
        } else {
            cluster.setState(ClusterState.CREATING);
            clusterStore.put(name, cluster);
            redpandaManager.startContainer(cluster);
            awaitReady(cluster, 90);
        }
        return cluster;
    }

    public StoredCluster getCluster(String project, String location, String clusterId) {
        String name = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        return clusterStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Cluster not found: " + name));
    }

    public List<StoredCluster> listClusters(String project, String location) {
        String prefix = "projects/" + project + "/locations/" + location + "/clusters/";
        return clusterStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteCluster(String project, String location, String clusterId) {
        String name = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        StoredCluster cluster = clusterStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Cluster not found: " + name));

        cluster.setState(ClusterState.DELETING);
        if (!config.services().kafka().mock()) {
            redpandaManager.stopContainer(cluster);
            redpandaManager.removeClusterStorage(cluster);
        }

        // Remove all topics and consumer groups for this cluster
        String topicPrefix = name + "/topics/";
        topicStore.scan(k -> k.startsWith(topicPrefix))
                .forEach(t -> topicStore.delete(t.getName()));

        String groupPrefix = name + "/consumerGroups/";
        consumerGroupStore.scan(k -> k.startsWith(groupPrefix))
                .forEach(g -> consumerGroupStore.delete(g.getName()));

        clusterStore.delete(name);
    }

    // ── Topics ────────────────────────────────────────────────────────────────

    public StoredTopic createTopic(String project, String location, String clusterId,
                                   String topicId, Map<String, Object> body) {
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        if (clusterStore.get(clusterName).isEmpty()) {
            throw GcpException.notFound("Cluster not found: " + clusterName);
        }

        String topicName = clusterName + "/topics/" + topicId;
        if (topicStore.get(topicName).isPresent()) {
            throw GcpException.alreadyExists("Topic already exists: " + topicName);
        }

        int partitionCount = extractInt(body, "partitionCount", 1);
        int replicationFactor = extractInt(body, "replicationFactor", 1);

        StoredTopic topic = new StoredTopic(topicName, partitionCount, replicationFactor);
        topicStore.put(topicName, topic);
        return topic;
    }

    public StoredTopic getTopic(String project, String location, String clusterId, String topicId) {
        String topicName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId + "/topics/" + topicId;
        return topicStore.get(topicName)
                .orElseThrow(() -> GcpException.notFound("Topic not found: " + topicName));
    }

    public List<StoredTopic> listTopics(String project, String location, String clusterId) {
        String prefix = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId + "/topics/";
        return topicStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteTopic(String project, String location, String clusterId, String topicId) {
        String topicName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId + "/topics/" + topicId;
        topicStore.get(topicName)
                .orElseThrow(() -> GcpException.notFound("Topic not found: " + topicName));
        topicStore.delete(topicName);
    }

    public StoredCluster updateCluster(String project, String location, String clusterId,
                                       Map<String, Object> body) {
        String name = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        StoredCluster cluster = clusterStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Cluster not found: " + name));
        applyCapacityFromBody(cluster, body);
        cluster.setUpdateTime(java.time.Instant.now());
        clusterStore.put(name, cluster);
        return cluster;
    }

    public StoredTopic updateTopic(String project, String location, String clusterId,
                                   String topicId, Map<String, Object> body) {
        String topicName = "projects/" + project + "/locations/" + location
                + "/clusters/" + clusterId + "/topics/" + topicId;
        StoredTopic topic = topicStore.get(topicName)
                .orElseThrow(() -> GcpException.notFound("Topic not found: " + topicName));
        if (body != null) {
            if (body.containsKey("partitionCount")) {
                int newCount = ((Number) body.get("partitionCount")).intValue();
                if (newCount < topic.getPartitionCount()) {
                    throw GcpException.invalidArgument("Cannot reduce partition count from "
                            + topic.getPartitionCount() + " to " + newCount);
                }
                topic.setPartitionCount(newCount);
            }
            if (body.containsKey("configs")) {
                @SuppressWarnings("unchecked")
                Map<String, String> configs = (Map<String, String>) body.get("configs");
                topic.setConfigs(configs);
            }
        }
        topicStore.put(topicName, topic);
        return topic;
    }

    // ── Consumer Groups ───────────────────────────────────────────────────────

    public List<StoredConsumerGroup> listConsumerGroups(String project, String location, String clusterId) {
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        StoredCluster cluster = clusterStore.get(clusterName)
                .orElseThrow(() -> GcpException.notFound("Cluster not found: " + clusterName));
        if (!config.services().kafka().mock() && cluster.getContainerId() != null) {
            return redpandaManager.listConsumerGroups(cluster, clusterName);
        }
        String prefix = clusterName + "/consumerGroups/";
        return consumerGroupStore.scan(k -> k.startsWith(prefix));
    }

    public StoredConsumerGroup getConsumerGroup(String project, String location, String clusterId,
                                                String groupId) {
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        String groupName = clusterName + "/consumerGroups/" + groupId;
        StoredCluster cluster = clusterStore.get(clusterName)
                .orElseThrow(() -> GcpException.notFound("Cluster not found: " + clusterName));
        if (!config.services().kafka().mock() && cluster.getContainerId() != null) {
            return redpandaManager.getConsumerGroup(cluster, clusterName, groupId)
                    .orElseThrow(() -> GcpException.notFound("Consumer group not found: " + groupName));
        }
        return consumerGroupStore.get(groupName)
                .orElseThrow(() -> GcpException.notFound("Consumer group not found: " + groupName));
    }

    public StoredConsumerGroup updateConsumerGroup(String project, String location, String clusterId,
                                                   String groupId, StoredConsumerGroup body) {
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        clusterStore.get(clusterName)
                .orElseThrow(() -> GcpException.notFound("Cluster not found: " + clusterName));
        String groupName = clusterName + "/consumerGroups/" + groupId;
        StoredConsumerGroup group = consumerGroupStore.get(groupName)
                .orElseGet(() -> new StoredConsumerGroup(groupName));
        if (body != null && body.getTopics() != null) {
            group.setTopics(body.getTopics());
        }
        consumerGroupStore.put(groupName, group);
        return group;
    }

    public void deleteConsumerGroup(String project, String location, String clusterId, String groupId) {
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        String groupName = clusterName + "/consumerGroups/" + groupId;
        StoredCluster cluster = clusterStore.get(clusterName)
                .orElseThrow(() -> GcpException.notFound("Cluster not found: " + clusterName));
        if (!config.services().kafka().mock() && cluster.getContainerId() != null) {
            redpandaManager.deleteConsumerGroup(cluster, groupId);
            return;
        }
        consumerGroupStore.get(groupName)
                .orElseThrow(() -> GcpException.notFound("Consumer group not found: " + groupName));
        consumerGroupStore.delete(groupName);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void awaitReady(StoredCluster cluster, int timeoutSeconds) {
        LOG.infov("Waiting for Kafka cluster {0} to become ready (timeout={1}s)", cluster.getName(), timeoutSeconds);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (redpandaManager.isReady(cluster)) {
                cluster.setState(ClusterState.ACTIVE);
                cluster.setUpdateTime(java.time.Instant.now());
                clusterStore.put(cluster.getName(), cluster);
                LOG.infov("Kafka cluster {0} is now ACTIVE", cluster.getName());
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.warnv("Kafka cluster {0} did not become ready within {1}s", cluster.getName(), timeoutSeconds);
    }

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                for (StoredCluster cluster : clusterStore.scan(k -> true)) {
                    if (cluster.getState() == ClusterState.CREATING) {
                        if (redpandaManager.isReady(cluster)) {
                            LOG.infov("Kafka cluster {0} is now ACTIVE", cluster.getName());
                            cluster.setState(ClusterState.ACTIVE);
                            cluster.setUpdateTime(java.time.Instant.now());
                            clusterStore.put(cluster.getName(), cluster);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.error("Error in Kafka readiness poller", e);
            }
        }, 1, 2, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private static void applyCapacityFromBody(StoredCluster cluster, Map<String, Object> body) {
        if (body == null) {
            return;
        }
        Map<String, Object> capacity = (Map<String, Object>) body.get("capacityConfig");
        if (capacity != null) {
            if (capacity.containsKey("vcpuCount")) {
                cluster.setVcpuCount(((Number) capacity.get("vcpuCount")).longValue());
            }
            if (capacity.containsKey("memoryBytes")) {
                cluster.setMemoryBytes(((Number) capacity.get("memoryBytes")).longValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static int extractInt(Map<String, Object> body, String key, int defaultValue) {
        if (body == null || !body.containsKey(key)) {
            return defaultValue;
        }
        return ((Number) body.get(key)).intValue();
    }
}

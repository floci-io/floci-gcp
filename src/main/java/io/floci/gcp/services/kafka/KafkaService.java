package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.kafka.model.AclEntry;
import io.floci.gcp.services.kafka.model.ClusterState;
import io.floci.gcp.services.kafka.model.StoredAcl;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class KafkaService {

    private static final Logger LOG = Logger.getLogger(KafkaService.class);

    private final StorageBackend<String, StoredCluster> clusterStore;
    private final StorageBackend<String, StoredTopic> topicStore;
    private final StorageBackend<String, StoredConsumerGroup> consumerGroupStore;
    private final StorageBackend<String, StoredAcl> aclStore;
    private final EmulatorConfig config;
    private final ServiceRegistry serviceRegistry;
    private final RedpandaManager redpandaManager;
    private final KafkaConnectDataPlane connectDataPlane;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    @Inject
    public KafkaService(StorageFactory storageFactory,
                        EmulatorConfig config,
                        ServiceRegistry serviceRegistry,
                        RedpandaManager redpandaManager,
                        KafkaConnectContainerDataPlane connectDataPlane) {
        this.clusterStore = storageFactory.createGlobal("kafka", "kafka-clusters.json",
                new TypeReference<Map<String, StoredCluster>>() {});
        this.topicStore = storageFactory.createGlobal("kafka", "kafka-topics.json",
                new TypeReference<Map<String, StoredTopic>>() {});
        this.consumerGroupStore = storageFactory.createGlobal("kafka", "kafka-consumer-groups.json",
                new TypeReference<Map<String, StoredConsumerGroup>>() {});
        this.aclStore = storageFactory.createGlobal("kafka", "kafka-acls.json",
                new TypeReference<Map<String, StoredAcl>>() {});
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.redpandaManager = redpandaManager;
        this.connectDataPlane = connectDataPlane;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("kafka")
                .enabled(config.services().kafka().enabled())
                .storageKey("kafka")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(KafkaController.class, KafkaConnectController.class)
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

    /** The Kafka cluster under its full resource name; used by the Connect service to attach workers to it. */
    Optional<StoredCluster> findCluster(String name) {
        return clusterStore.get(name);
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
            // Connect workers attached to this broker cannot outlive it; the Connect clusters that
            // reference it stay, and report their connectors FAILED until reconfigured or deleted.
            connectDataPlane.stopWorkersOn(cluster);
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

        String aclPrefix = name + "/acls/";
        aclStore.scan(k -> k.startsWith(aclPrefix))
                .forEach(a -> aclStore.delete(a.getName()));

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

    // ── ACLs ──────────────────────────────────────────────────────────────────

    /** The proto caps {@code acl_entries} at 100 per ACL. */
    static final int MAX_ACL_ENTRIES = 100;
    private static final Set<String> PERMISSION_TYPES = Set.of("ALLOW", "DENY");
    private static final Set<String> OPERATIONS = Set.of("ALL", "READ", "WRITE", "CREATE", "DELETE", "ALTER",
            "DESCRIBE", "CLUSTER_ACTION", "DESCRIBE_CONFIGS", "ALTER_CONFIGS", "IDEMPOTENT_WRITE");

    /**
     * ACLs are kept as control-plane metadata, like topics are in this emulator: the Redpanda
     * container runs without an authorizer, so entries describe intent for tooling to read back
     * rather than gate the data plane. Every field the proto derives from the id is derived here.
     */
    public StoredAcl createAcl(String project, String location, String clusterId, String aclId,
                               StoredAcl body) {
        String clusterName = requireCluster(project, location, clusterId);
        AclResourcePattern pattern = AclResourcePattern.parse(aclId);
        String name = clusterName + "/acls/" + aclId;
        if (aclStore.get(name).isPresent()) {
            throw GcpException.alreadyExists("Acl already exists: " + name);
        }
        List<AclEntry> entries = normalizeEntries(body == null ? null : body.getAclEntries());
        if (entries.isEmpty()) {
            throw GcpException.invalidArgument("aclEntries is required and must not be empty");
        }
        StoredAcl acl = new StoredAcl(name, pattern.resourceType(), pattern.resourceName(), pattern.patternType());
        acl.setAclEntries(entries);
        acl.setEtag(newEtag());
        aclStore.put(name, acl);
        return acl;
    }

    public StoredAcl getAcl(String project, String location, String clusterId, String aclId) {
        String clusterName = requireCluster(project, location, clusterId);
        String name = clusterName + "/acls/" + aclId;
        return aclStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Acl not found: " + name));
    }

    public PageToken.Page<StoredAcl> listAcls(String project, String location, String clusterId,
                                              int pageSize, String pageToken) {
        String prefix = requireCluster(project, location, clusterId) + "/acls/";
        List<StoredAcl> all = new ArrayList<>(aclStore.scan(k -> k.startsWith(prefix)));
        all.sort(java.util.Comparator.comparing(StoredAcl::getName));
        return PageToken.paginate(all, pageSize <= 0 ? 500 : Math.min(pageSize, 1000), pageToken);
    }

    /**
     * {@code acl_entries} is the only mutable field. An {@code etag} on the request must match
     * the stored one (AIP-154: mismatch is {@code ABORTED}); absent, the update is unconditional.
     */
    public StoredAcl updateAcl(String project, String location, String clusterId, String aclId,
                               StoredAcl body, String updateMask) {
        StoredAcl acl = getAcl(project, location, clusterId, aclId);
        if (updateMask != null && !updateMask.isBlank()) {
            boolean touchesEntries = false;
            for (String path : updateMask.split(",")) {
                String field = path.strip();
                if (field.equals("*") || field.equals("aclEntries") || field.equals("acl_entries")) {
                    touchesEntries = true;
                } else if (!field.isEmpty()) {
                    throw GcpException.invalidArgument("update_mask may only name acl_entries, got: " + field);
                }
            }
            if (!touchesEntries) {
                return acl;
            }
        }
        if (body != null && body.getEtag() != null && !body.getEtag().isBlank()
                && !body.getEtag().equals(acl.getEtag())) {
            throw GcpException.aborted("Acl etag mismatch: the acl was modified since it was read");
        }
        List<AclEntry> entries = normalizeEntries(body == null ? null : body.getAclEntries());
        if (entries.isEmpty()) {
            throw GcpException.invalidArgument("aclEntries is required and must not be empty");
        }
        acl.setAclEntries(entries);
        acl.setEtag(newEtag());
        aclStore.put(acl.getName(), acl);
        return acl;
    }

    public void deleteAcl(String project, String location, String clusterId, String aclId) {
        StoredAcl acl = getAcl(project, location, clusterId, aclId);
        aclStore.delete(acl.getName());
    }

    /** Incremental add; creates the ACL when it does not exist yet, which the response reports. */
    public AddAclEntryResult addAclEntry(String project, String location, String clusterId, String aclId,
                                         AclEntry entry) {
        String clusterName = requireCluster(project, location, clusterId);
        AclEntry normalized = normalizeEntry(entry);
        String name = clusterName + "/acls/" + aclId;
        StoredAcl existing = aclStore.get(name).orElse(null);
        if (existing == null) {
            AclResourcePattern pattern = AclResourcePattern.parse(aclId);
            StoredAcl acl = new StoredAcl(name, pattern.resourceType(), pattern.resourceName(), pattern.patternType());
            acl.setAclEntries(new ArrayList<>(List.of(normalized)));
            acl.setEtag(newEtag());
            aclStore.put(name, acl);
            return new AddAclEntryResult(acl, true);
        }
        if (!existing.getAclEntries().contains(normalized)) {
            if (existing.getAclEntries().size() >= MAX_ACL_ENTRIES) {
                throw GcpException.invalidArgument("An acl may hold at most " + MAX_ACL_ENTRIES + " entries");
            }
            List<AclEntry> entries = new ArrayList<>(existing.getAclEntries());
            entries.add(normalized);
            existing.setAclEntries(entries);
            existing.setEtag(newEtag());
            aclStore.put(name, existing);
        }
        return new AddAclEntryResult(existing, false);
    }

    /** Incremental remove; deletes the ACL when the removed entry was its last, which the response reports. */
    public RemoveAclEntryResult removeAclEntry(String project, String location, String clusterId, String aclId,
                                               AclEntry entry) {
        StoredAcl acl = getAcl(project, location, clusterId, aclId);
        AclEntry normalized = normalizeEntry(entry);
        if (!acl.getAclEntries().contains(normalized)) {
            throw GcpException.notFound("Acl entry not found on " + acl.getName());
        }
        List<AclEntry> entries = new ArrayList<>(acl.getAclEntries());
        entries.remove(normalized);
        if (entries.isEmpty()) {
            aclStore.delete(acl.getName());
            return new RemoveAclEntryResult(null, true);
        }
        acl.setAclEntries(entries);
        acl.setEtag(newEtag());
        aclStore.put(acl.getName(), acl);
        return new RemoveAclEntryResult(acl, false);
    }

    public record AddAclEntryResult(StoredAcl acl, boolean aclCreated) {}

    public record RemoveAclEntryResult(StoredAcl acl, boolean aclDeleted) {}

    private String requireCluster(String project, String location, String clusterId) {
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        if (clusterStore.get(clusterName).isEmpty()) {
            throw GcpException.notFound("Cluster not found: " + clusterName);
        }
        return clusterName;
    }

    /** Validates and canonicalises entries; identical entries collapse to one. */
    private static List<AclEntry> normalizeEntries(List<AclEntry> entries) {
        if (entries == null) {
            return List.of();
        }
        if (entries.size() > MAX_ACL_ENTRIES) {
            throw GcpException.invalidArgument("An acl may hold at most " + MAX_ACL_ENTRIES + " entries");
        }
        Set<AclEntry> unique = new LinkedHashSet<>();
        for (AclEntry entry : entries) {
            unique.add(normalizeEntry(entry));
        }
        return new ArrayList<>(unique);
    }

    /**
     * Applies the {@code AclEntry} field rules from resources.proto: the principal carries the
     * StandardAuthorizer {@code User:} prefix (or is {@code User:*}), permission and operation are
     * matched case-insensitively and stored upper-case, and the host must be {@code *}.
     */
    private static AclEntry normalizeEntry(AclEntry entry) {
        if (entry == null) {
            throw GcpException.invalidArgument("aclEntry is required");
        }
        String principal = entry.getPrincipal();
        if (principal == null || !principal.startsWith("User:") || principal.length() <= "User:".length()) {
            throw GcpException.invalidArgument("aclEntry.principal must be \"User:<account>\" or \"User:*\"");
        }
        String permission = upper(entry.getPermissionType());
        if (!PERMISSION_TYPES.contains(permission)) {
            throw GcpException.invalidArgument("aclEntry.permissionType must be ALLOW or DENY");
        }
        String operation = upper(entry.getOperation());
        if (!OPERATIONS.contains(operation)) {
            throw GcpException.invalidArgument("aclEntry.operation must be one of " + String.join(", ", OPERATIONS));
        }
        if (!"*".equals(entry.getHost())) {
            throw GcpException.invalidArgument("aclEntry.host must be \"*\"");
        }
        return new AclEntry(principal, permission, operation, "*");
    }

    private static String upper(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String newEtag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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

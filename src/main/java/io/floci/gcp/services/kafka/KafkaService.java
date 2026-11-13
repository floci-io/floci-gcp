package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.LocationCatalog;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final LocationCatalog locations;
    private final KafkaOperations operations;
    private final Executor lifecycle;
    private final Duration readinessTimeout;
    /**
     * Guards each cluster's state and its children. A cluster leaves ACTIVE only under its lock,
     * and every child write checks ACTIVE under the same lock, so teardown's purge is complete.
     */
    private final ConcurrentHashMap<String, Object> clusterLocks = new ConcurrentHashMap<>();

    @Inject
    public KafkaService(StorageFactory storageFactory,
                        EmulatorConfig config,
                        ServiceRegistry serviceRegistry,
                        RedpandaManager redpandaManager,
                        KafkaConnectContainerDataPlane connectDataPlane,
                        LocationCatalog locations,
                        KafkaOperations operations) {
        this(storageFactory.createGlobal("kafka", "kafka-clusters.json",
                        new TypeReference<Map<String, StoredCluster>>() {}),
                storageFactory.createGlobal("kafka", "kafka-topics.json",
                        new TypeReference<Map<String, StoredTopic>>() {}),
                storageFactory.createGlobal("kafka", "kafka-consumer-groups.json",
                        new TypeReference<Map<String, StoredConsumerGroup>>() {}),
                storageFactory.createGlobal("kafka", "kafka-acls.json",
                        new TypeReference<Map<String, StoredAcl>>() {}),
                config, serviceRegistry, redpandaManager, connectDataPlane, locations, operations,
                Executors.newVirtualThreadPerTaskExecutor(), Duration.ofSeconds(90));
    }

    KafkaService(StorageBackend<String, StoredCluster> clusterStore,
                 StorageBackend<String, StoredTopic> topicStore,
                 StorageBackend<String, StoredConsumerGroup> consumerGroupStore,
                 StorageBackend<String, StoredAcl> aclStore,
                 EmulatorConfig config,
                 ServiceRegistry serviceRegistry,
                 RedpandaManager redpandaManager,
                 KafkaConnectDataPlane connectDataPlane,
                 LocationCatalog locations,
                 KafkaOperations operations,
                 Executor lifecycle,
                 Duration readinessTimeout) {
        this.clusterStore = clusterStore;
        this.topicStore = topicStore;
        this.consumerGroupStore = consumerGroupStore;
        this.aclStore = aclStore;
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.redpandaManager = redpandaManager;
        this.connectDataPlane = connectDataPlane;
        this.locations = locations;
        this.operations = operations;
        this.lifecycle = lifecycle;
        this.readinessTimeout = readinessTimeout;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("kafka")
                .enabled(config.services().kafka().enabled())
                .storageKey("kafka")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(KafkaController.class, KafkaConnectController.class, SchemaRegistryController.class)
                .api("managedkafka.googleapis.com", "Managed Service for Apache Kafka API")
                .build());
        if (!config.services().kafka().mock()) {
            recoverInterruptedOperations();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (lifecycle instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
        if (!config.services().kafka().mock()) {
            for (StoredCluster cluster : clusterStore.scan(k -> true)) {
                redpandaManager.stopContainer(cluster);
            }
        }
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    /**
     * Mock mode answers a finished operation with the cluster ACTIVE. With Docker the cluster is
     * stored CREATING and the operation returned running; Redpanda starts in the background and the
     * operation finishes when the broker is ready, or fails and the cluster is removed.
     */
    public ObjectNode createCluster(String project, String location, String clusterId,
                                    Map<String, Object> body) {
        locations.requireLocation(location, LocationCatalog.Kind.REGION);
        String name = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        synchronized (lock(name)) {
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
                return operations.done(name, "create", operations.clusterResponse(cluster));
            }
            cluster.setState(ClusterState.CREATING);
            clusterStore.put(name, cluster);
            ObjectNode operation = operations.start(name, "create");
            String operationName = operation.get("name").asText();
            lifecycle.execute(() -> provision(name, operationName));
            return operation;
        }
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
        locations.requireListLocation(location, LocationCatalog.Kind.REGION);
        String prefix = "projects/" + project + "/locations/" + location + "/clusters/";
        return clusterStore.scan(k -> k.startsWith(prefix));
    }

    /**
     * Mock mode removes the cluster at once. With Docker an ACTIVE cluster turns DELETING, the
     * operation is returned running, and the broker is torn down in the background.
     */
    public ObjectNode deleteCluster(String project, String location, String clusterId) {
        String name = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        synchronized (lock(name)) {
            StoredCluster cluster = clusterStore.get(name)
                    .orElseThrow(() -> GcpException.notFound("Cluster not found: " + name));
            if (config.services().kafka().mock()) {
                purge(cluster);
                return operations.done(name, "delete", operations.emptyResponse());
            }
            requireActive(cluster);
            cluster.setState(ClusterState.DELETING);
            cluster.setUpdateTime(Instant.now());
            clusterStore.put(name, cluster);
            ObjectNode operation = operations.start(name, "delete");
            String operationName = operation.get("name").asText();
            lifecycle.execute(() -> teardown(name, operationName));
            return operation;
        }
    }

    /** Removes the cluster's record and children; the caller holds the cluster lock. */
    private void purge(StoredCluster cluster) {
        String name = cluster.getName();
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
        synchronized (lock(clusterName)) {
            requireActiveCluster(clusterName);

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
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        String topicName = clusterName + "/topics/" + topicId;
        synchronized (lock(clusterName)) {
            requireActiveCluster(clusterName);
            topicStore.get(topicName)
                    .orElseThrow(() -> GcpException.notFound("Topic not found: " + topicName));
            topicStore.delete(topicName);
        }
    }

    /** Capacity is metadata only (the broker is not resized), so the operation is finished at once. */
    public ObjectNode updateCluster(String project, String location, String clusterId,
                                    Map<String, Object> body) {
        String name = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        synchronized (lock(name)) {
            StoredCluster cluster = clusterStore.get(name)
                    .orElseThrow(() -> GcpException.notFound("Cluster not found: " + name));
            requireActive(cluster);
            applyCapacityFromBody(cluster, body);
            cluster.setUpdateTime(Instant.now());
            clusterStore.put(name, cluster);
            return operations.done(name, "update", operations.clusterResponse(cluster));
        }
    }

    public StoredTopic updateTopic(String project, String location, String clusterId,
                                   String topicId, Map<String, Object> body) {
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        String topicName = clusterName + "/topics/" + topicId;
        synchronized (lock(clusterName)) {
            requireActiveCluster(clusterName);
            return updateTopic(topicName, body);
        }
    }

    private StoredTopic updateTopic(String topicName, Map<String, Object> body) {
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
        StoredCluster cluster = requireActiveCluster(clusterName);
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
        StoredCluster cluster = requireActiveCluster(clusterName);
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
        synchronized (lock(clusterName)) {
            requireActiveCluster(clusterName);
            String groupName = clusterName + "/consumerGroups/" + groupId;
            StoredConsumerGroup group = consumerGroupStore.get(groupName)
                    .orElseGet(() -> new StoredConsumerGroup(groupName));
            if (body != null && body.getTopics() != null) {
                group.setTopics(body.getTopics());
            }
            consumerGroupStore.put(groupName, group);
            return group;
        }
    }

    public void deleteConsumerGroup(String project, String location, String clusterId, String groupId) {
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        synchronized (lock(clusterName)) {
            StoredCluster cluster = requireActiveCluster(clusterName);
            String groupName = clusterName + "/consumerGroups/" + groupId;
            if (!config.services().kafka().mock() && cluster.getContainerId() != null) {
                redpandaManager.deleteConsumerGroup(cluster, groupId);
                return;
            }
            consumerGroupStore.get(groupName)
                    .orElseThrow(() -> GcpException.notFound("Consumer group not found: " + groupName));
            consumerGroupStore.delete(groupName);
        }
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
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        synchronized (lock(clusterName)) {
            requireActiveCluster(clusterName);
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
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        synchronized (lock(clusterName)) {
            requireActiveCluster(clusterName);
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
    }

    public void deleteAcl(String project, String location, String clusterId, String aclId) {
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        synchronized (lock(clusterName)) {
            requireActiveCluster(clusterName);
            StoredAcl acl = getAcl(project, location, clusterId, aclId);
            aclStore.delete(acl.getName());
        }
    }

    /** Incremental add; creates the ACL when it does not exist yet, which the response reports. */
    public AddAclEntryResult addAclEntry(String project, String location, String clusterId, String aclId,
                                         AclEntry entry) {
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        synchronized (lock(clusterName)) {
            requireActiveCluster(clusterName);
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
    }

    /** Incremental remove; deletes the ACL when the removed entry was its last, which the response reports. */
    public RemoveAclEntryResult removeAclEntry(String project, String location, String clusterId, String aclId,
                                               AclEntry entry) {
        String clusterName = "projects/" + project + "/locations/" + location + "/clusters/" + clusterId;
        synchronized (lock(clusterName)) {
            requireActiveCluster(clusterName);
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

    private Object lock(String clusterName) {
        return clusterLocks.computeIfAbsent(clusterName, k -> new Object());
    }

    private StoredCluster requireActiveCluster(String clusterName) {
        StoredCluster cluster = clusterStore.get(clusterName)
                .orElseThrow(() -> GcpException.notFound("Cluster not found: " + clusterName));
        requireActive(cluster);
        return cluster;
    }

    private static void requireActive(StoredCluster cluster) {
        if (cluster.getState() != ClusterState.ACTIVE) {
            throw GcpException.failedPrecondition("Cluster " + cluster.getName() + " is "
                    + cluster.getState() + "; it must be ACTIVE");
        }
    }

    /** Starts the broker of a CREATING cluster and finishes its create operation. */
    void provision(String name, String operationName) {
        StoredCluster cluster;
        synchronized (lock(name)) {
            cluster = clusterStore.get(name).orElse(null);
            if (cluster == null || cluster.getState() != ClusterState.CREATING) {
                operations.fail(operationName, KafkaOperations.ABORTED, "Cluster " + name + " is no longer being created");
                return;
            }
        }
        boolean ready;
        try {
            redpandaManager.startContainer(cluster);
            synchronized (lock(name)) {
                // Tracked before the readiness wait, so shutdown stops the container if floci exits meanwhile.
                clusterStore.put(name, cluster);
            }
            ready = awaitReady(cluster);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Kafka cluster %s failed to start", name);
            abandon(cluster, operationName, KafkaOperations.INTERNAL, "Failed to start the Kafka broker: " + e.getMessage());
            return;
        }
        if (!ready && Thread.currentThread().isInterrupted()) {
            abandon(cluster, operationName, KafkaOperations.ABORTED, "The emulator stopped before the Kafka broker was ready");
            return;
        }
        if (!ready) {
            abandon(cluster, operationName, KafkaOperations.DEADLINE_EXCEEDED,
                    "The Kafka broker did not become ready within " + readinessTimeout.toSeconds() + "s");
            return;
        }
        synchronized (lock(name)) {
            cluster.setState(ClusterState.ACTIVE);
            cluster.setUpdateTime(Instant.now());
            clusterStore.put(name, cluster);
            operations.complete(operationName, operations.clusterResponse(cluster));
        }
        LOG.infof("Kafka cluster %s is now ACTIVE", name);
    }

    /**
     * A cluster whose broker could not start is removed, as a failed create leaves nothing on GCP. If
     * Docker cannot remove the broker, the cluster becomes ACTIVE instead, as in restart recovery: it
     * stays tracked, so shutdown stops its broker and a delete can retry the removal.
     */
    private void abandon(StoredCluster cluster, String operationName, int code, String message) {
        String name = cluster.getName();
        try {
            redpandaManager.removeBroker(cluster);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Kafka cluster %s is ACTIVE: its create failed and its broker could not be removed", name);
            synchronized (lock(name)) {
                cluster.setState(ClusterState.ACTIVE);
                cluster.setUpdateTime(Instant.now());
                clusterStore.put(name, cluster);
                operations.fail(operationName, code, message
                        + "; the broker could not be removed, so the cluster was kept: delete it to retry");
            }
            return;
        }
        synchronized (lock(name)) {
            purge(cluster);
            operations.fail(operationName, code, message);
        }
    }

    /** Stops the broker of a DELETING cluster, then removes the cluster and finishes its delete operation. */
    void teardown(String name, String operationName) {
        StoredCluster cluster;
        synchronized (lock(name)) {
            cluster = clusterStore.get(name).orElse(null);
        }
        if (cluster == null) {
            operations.complete(operationName, operations.emptyResponse());
            return;
        }
        try {
            // Connect workers attached to this broker cannot outlive it; the Connect clusters that
            // reference it stay, and report their connectors FAILED until reconfigured or deleted.
            connectDataPlane.stopWorkersOn(cluster);
            redpandaManager.removeBroker(cluster);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Kafka cluster %s could not be deleted", name);
            synchronized (lock(name)) {
                cluster.setState(ClusterState.ACTIVE);
                cluster.setUpdateTime(Instant.now());
                clusterStore.put(name, cluster);
                operations.fail(operationName, KafkaOperations.INTERNAL, "Failed to stop the Kafka broker: " + e.getMessage());
            }
            return;
        }
        synchronized (lock(name)) {
            purge(cluster);
            operations.complete(operationName, operations.emptyResponse());
        }
    }

    /**
     * Work a previous run left unfinished died with it. Recovery starts from the stored clusters, not
     * from the operations, since a caller may have deleted the operation: a cluster left CREATING or
     * DELETING has its broker removed (a broker the run left running included) and is purged, as a
     * failed create or a finished delete leaves nothing. If Docker cannot remove the broker, the
     * cluster becomes ACTIVE again, so it stays tracked and a delete can be retried. The operations
     * still running are then finished to match.
     */
    void recoverInterruptedOperations() {
        for (StoredCluster cluster : clusterStore.scan(k -> true)) {
            if (cluster.getState() == ClusterState.CREATING || cluster.getState() == ClusterState.DELETING) {
                recoverCluster(cluster);
            }
        }
        for (ObjectNode operation : operations.unfinished()) {
            String operationName = operation.path("name").asText();
            String target = operation.path("metadata").path("target").asText();
            String verb = operation.path("metadata").path("verb").asText();
            synchronized (lock(target)) {
                boolean clusterLeft = clusterStore.get(target).isPresent();
                if ("delete".equals(verb) && !clusterLeft) {
                    operations.complete(operationName, operations.emptyResponse());
                } else if ("delete".equals(verb)) {
                    operations.fail(operationName, KafkaOperations.INTERNAL,
                            "The emulator restarted and could not remove the Kafka broker; delete the cluster again");
                } else {
                    operations.fail(operationName, KafkaOperations.ABORTED,
                            "The operation was interrupted by an emulator restart");
                }
            }
        }
    }

    private void recoverCluster(StoredCluster cluster) {
        String name = cluster.getName();
        try {
            redpandaManager.removeBroker(cluster);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Kafka cluster %s is ACTIVE again: its broker could not be removed after a restart", name);
            synchronized (lock(name)) {
                cluster.setState(ClusterState.ACTIVE);
                cluster.setUpdateTime(Instant.now());
                clusterStore.put(name, cluster);
            }
            return;
        }
        synchronized (lock(name)) {
            purge(cluster);
        }
    }

    private boolean awaitReady(StoredCluster cluster) {
        LOG.infof("Waiting for Kafka cluster %s to become ready (timeout=%ss)", cluster.getName(),
                readinessTimeout.toSeconds());
        Instant deadline = Instant.now().plus(readinessTimeout);
        while (true) {
            if (redpandaManager.isReady(cluster)) {
                return true;
            }
            if (!Instant.now().isBefore(deadline)) {
                return false;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
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

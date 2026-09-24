package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.kafka.model.ClusterState;
import io.floci.gcp.services.kafka.model.ConnectorState;
import io.floci.gcp.services.kafka.model.StoredCluster;
import io.floci.gcp.services.kafka.model.StoredConnectCluster;
import io.floci.gcp.services.kafka.model.StoredConnector;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code google.cloud.managedkafka.v1.ManagedKafkaConnect}: Connect clusters and their connectors.
 *
 * <p>With the Kafka data plane on ({@code kafka.mock=false}), each Connect cluster runs a Kafka
 * Connect worker attached to the Kafka cluster it references ({@link KafkaConnectDataPlane}):
 * connector writes go to the worker first and are stored only once it accepts them, and a
 * connector's {@code state} is read back from the worker. A Connect cluster whose worker is not
 * running (its Kafka cluster was deleted, or the emulator restarted) reports its connectors
 * {@code FAILED}, since nothing is processing their records. In mock mode nothing is started, a
 * Connect cluster is {@code ACTIVE} as soon as it is created, and connectors report the state the
 * lifecycle methods moved them to.
 *
 * <p>Invariant: a connector exists only under a Connect cluster that exists, and a running worker
 * exists only for a stored Connect cluster. Every operation on a Connect cluster or one of its
 * connectors, worker calls included, runs under that cluster's lock (striped on the cluster name),
 * so a delete cannot interleave with a connector mutation or a worker start and leave an orphan,
 * and two mutations of one connector cannot lose each other's write.
 */
@ApplicationScoped
public class KafkaConnectService {

    private static final Logger LOG = Logger.getLogger(KafkaConnectService.class);

    private static final Pattern KAFKA_CLUSTER_NAME =
            Pattern.compile("^projects/([^/]+)/locations/([^/]+)/clusters/([^/]+)$");
    /** The resource id grammar the API documents for {@code connect_cluster_id} and {@code connector_id}. */
    private static final Pattern RESOURCE_ID = Pattern.compile("^[a-z]([-a-z0-9]{0,61}[a-z0-9])?$");
    private static final java.math.BigDecimal MAX_INT64 = java.math.BigDecimal.valueOf(Long.MAX_VALUE);
    /** {@code ConnectCluster} fields {@code update_mask} may name; {@code kafka_cluster} is IMMUTABLE. */
    private static final Set<String> CONNECT_CLUSTER_MUTABLE = Set.of("labels", "capacityConfig", "gcpConfig", "config");
    private static final Set<String> CONNECTOR_MUTABLE = Set.of("configs", "taskRestartPolicy");
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int LOCK_STRIPES = 256;

    private final Object[] clusterLocks = createLocks();
    private final StorageBackend<String, StoredConnectCluster> connectClusterStore;
    private final StorageBackend<String, StoredConnector> connectorStore;
    private final Function<String, Optional<StoredCluster>> kafkaClusters;
    private final KafkaConnectDataPlane dataPlane;
    private final boolean dataPlaneEnabled;

    @Inject
    public KafkaConnectService(StorageFactory storageFactory, KafkaService kafkaService,
                               KafkaConnectContainerDataPlane dataPlane, EmulatorConfig config) {
        this(storageFactory.createGlobal("kafka", "kafka-connect-clusters.json",
                        new TypeReference<Map<String, StoredConnectCluster>>() {}),
                storageFactory.createGlobal("kafka", "kafka-connectors.json",
                        new TypeReference<Map<String, StoredConnector>>() {}),
                kafkaService::findCluster, dataPlane, !config.services().kafka().mock());
    }

    KafkaConnectService(StorageBackend<String, StoredConnectCluster> connectClusterStore,
                        StorageBackend<String, StoredConnector> connectorStore,
                        Predicate<String> kafkaClusterExists) {
        this(connectClusterStore, connectorStore,
                name -> kafkaClusterExists.test(name) ? Optional.of(new StoredCluster(name)) : Optional.empty(),
                KafkaConnectDataPlane.noop(), false);
    }

    KafkaConnectService(StorageBackend<String, StoredConnectCluster> connectClusterStore,
                        StorageBackend<String, StoredConnector> connectorStore,
                        Function<String, Optional<StoredCluster>> kafkaClusters,
                        KafkaConnectDataPlane dataPlane,
                        boolean dataPlaneEnabled) {
        this.connectClusterStore = connectClusterStore;
        this.connectorStore = connectorStore;
        this.kafkaClusters = kafkaClusters;
        this.dataPlane = dataPlane;
        this.dataPlaneEnabled = dataPlaneEnabled;
    }

    /** Workers do not outlive the process; their state stays in the Kafka cluster's internal topics. */
    @PreDestroy
    void shutdown() {
        if (!dataPlaneEnabled) {
            return;
        }
        for (StoredConnectCluster cluster : connectClusterStore.scan(k -> true)) {
            synchronized (clusterLock(cluster.getName())) {
                dataPlane.stopWorker(cluster.getName(), null, false);
            }
        }
    }

    // ── Connect clusters ──────────────────────────────────────────────────────

    public StoredConnectCluster createConnectCluster(String project, String location, String connectClusterId,
                                                     Map<String, Object> body) {
        requireResourceId(connectClusterId, "connectClusterId");
        String name = connectClusterName(project, location, connectClusterId);
        if (body == null) {
            throw GcpException.invalidArgument("Missing connectCluster body");
        }
        synchronized (clusterLock(name)) {
            if (connectClusterStore.get(name).isPresent()) {
                throw GcpException.alreadyExists("ConnectCluster already exists: " + name);
            }
            StoredCluster kafkaCluster = requireKafkaCluster(project, location, body.get("kafkaCluster"));
            StoredConnectCluster cluster = new StoredConnectCluster(name, kafkaCluster.getName());
            cluster.setCapacityConfig(requireCapacityConfig(body.get("capacityConfig")));
            cluster.setGcpConfig(requireGcpConfig(body.get("gcpConfig")));
            cluster.setLabels(stringMap(body.get("labels"), "labels"));
            cluster.setConfig(stringMap(body.get("config"), "config"));
            connectClusterStore.put(name, cluster);
            if (dataPlaneEnabled) {
                // CREATING stays visible to ListConnectClusters, which does not take the lock,
                // until the worker serves its REST API. A worker that cannot start leaves nothing.
                try {
                    dataPlane.startWorker(name, kafkaCluster, cluster.getConfig());
                } catch (RuntimeException e) {
                    connectClusterStore.delete(name);
                    try {
                        dataPlane.stopWorker(name, kafkaCluster, true);
                    } catch (RuntimeException cleanup) {
                        e.addSuppressed(cleanup);
                    }
                    throw e;
                }
            }
            cluster.setState(ClusterState.ACTIVE);
            connectClusterStore.put(name, cluster);
            LOG.infov("Created Kafka Connect cluster {0} on {1}", name, kafkaCluster.getName());
            return cluster;
        }
    }

    public StoredConnectCluster getConnectCluster(String project, String location, String connectClusterId) {
        String name = connectClusterName(project, location, connectClusterId);
        synchronized (clusterLock(name)) {
            return requireConnectCluster(name);
        }
    }

    public PageToken.Page<StoredConnectCluster> listConnectClusters(String project, String location,
                                                                    Integer pageSize, String pageToken) {
        String prefix = "projects/" + project + "/locations/" + location + "/connectClusters/";
        List<StoredConnectCluster> all = connectClusterStore.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(StoredConnectCluster::getName))
                .toList();
        return PageToken.paginate(all, pageSize(pageSize), pageToken);
    }

    public StoredConnectCluster updateConnectCluster(String project, String location, String connectClusterId,
                                                     String updateMask, Map<String, Object> body) {
        String name = connectClusterName(project, location, connectClusterId);
        Map<String, Object> update = body == null ? Map.of() : body;
        FieldMask mask = FieldMask.of(updateMask, update, CONNECT_CLUSTER_MUTABLE, "ConnectCluster");
        synchronized (clusterLock(name)) {
            StoredConnectCluster cluster = requireConnectCluster(name);
            Object requestedKafkaCluster = update.get("kafkaCluster");
            if (requestedKafkaCluster != null && !requestedKafkaCluster.equals(cluster.getKafkaCluster())) {
                throw GcpException.invalidArgument("kafkaCluster is immutable");
            }
            // Validate everything before the first setter, so a rejected body leaves the stored
            // object (the live reference in memory mode) exactly as it was.
            Map<String, String> labels = mask.touches("labels")
                    ? stringMap(mask.apply("labels", cluster.getLabels()), "labels") : null;
            Map<String, Object> capacity = mask.touches("capacityConfig")
                    ? requireCapacityConfig(mask.apply("capacityConfig", cluster.getCapacityConfig())) : null;
            Map<String, Object> gcpConfig = mask.touches("gcpConfig")
                    ? requireGcpConfig(mask.apply("gcpConfig", cluster.getGcpConfig())) : null;
            Map<String, String> config = mask.touches("config")
                    ? stringMap(mask.apply("config", cluster.getConfig()), "config") : null;
            if (dataPlaneEnabled && mask.touches("config") && !Objects.equals(config, cluster.getConfig())) {
                restartWorker(cluster, config);
            }
            if (mask.touches("labels")) {
                cluster.setLabels(labels);
            }
            if (mask.touches("capacityConfig")) {
                cluster.setCapacityConfig(capacity);
            }
            if (mask.touches("gcpConfig")) {
                cluster.setGcpConfig(gcpConfig);
            }
            if (mask.touches("config")) {
                cluster.setConfig(config);
            }
            cluster.setUpdateTime(Instant.now());
            connectClusterStore.put(name, cluster);
            return cluster;
        }
    }

    /** Connectors go first and the cluster last, all under the cluster lock, so no interleaving can
     * observe a cluster without its connectors' parent or a connector whose parent is gone. */
    public void deleteConnectCluster(String project, String location, String connectClusterId) {
        String name = connectClusterName(project, location, connectClusterId);
        synchronized (clusterLock(name)) {
            StoredConnectCluster cluster = requireConnectCluster(name);
            cluster.setState(ClusterState.DELETING);
            if (dataPlaneEnabled) {
                // Deleting a Connect cluster erases the connector configs it keeps in its primary
                // Kafka cluster, so the worker's internal topics go with it.
                dataPlane.stopWorker(name, kafkaClusters.apply(cluster.getKafkaCluster()).orElse(null), true);
            }
            String connectorPrefix = name + "/connectors/";
            connectorStore.scan(k -> k.startsWith(connectorPrefix))
                    .forEach(c -> connectorStore.delete(c.getName()));
            connectClusterStore.delete(name);
            LOG.infov("Deleted Kafka Connect cluster {0}", name);
        }
    }

    // ── Connectors ────────────────────────────────────────────────────────────

    public StoredConnector createConnector(String project, String location, String connectClusterId,
                                           String connectorId, Map<String, Object> body) {
        requireResourceId(connectorId, "connectorId");
        String clusterName = connectClusterName(project, location, connectClusterId);
        String name = clusterName + "/connectors/" + connectorId;
        Map<String, Object> request = body == null ? Map.of() : body;
        StoredConnector connector = new StoredConnector(name);
        connector.setConfigs(stringMap(request.get("configs"), "configs"));
        connector.setTaskRestartPolicy(taskRestartPolicy(request.get("taskRestartPolicy")));
        synchronized (clusterLock(clusterName)) {
            requireConnectCluster(clusterName);
            if (connectorStore.get(name).isPresent()) {
                throw GcpException.alreadyExists("Connector already exists: " + name);
            }
            if (dataPlaneEnabled) {
                dataPlane.createConnector(clusterName, connectorId, connector.getConfigs());
            }
            connectorStore.put(name, connector);
            return connector;
        }
    }

    public StoredConnector getConnector(String project, String location, String connectClusterId, String connectorId) {
        String clusterName = connectClusterName(project, location, connectClusterId);
        synchronized (clusterLock(clusterName)) {
            StoredConnector connector = requireConnector(clusterName + "/connectors/" + connectorId);
            if (dataPlaneEnabled) {
                if (!dataPlane.isRunning(clusterName)) {
                    refreshState(connector, ConnectorState.FAILED);
                } else {
                    dataPlane.connectorState(clusterName, connectorId).ifPresent(state -> refreshState(connector, state));
                }
            }
            return connector;
        }
    }

    public PageToken.Page<StoredConnector> listConnectors(String project, String location, String connectClusterId,
                                                          Integer pageSize, String pageToken) {
        String clusterName = connectClusterName(project, location, connectClusterId);
        String prefix = clusterName + "/connectors/";
        synchronized (clusterLock(clusterName)) {
            requireConnectCluster(clusterName);
            List<StoredConnector> all = connectorStore.scan(k -> k.startsWith(prefix)).stream()
                    .sorted(Comparator.comparing(StoredConnector::getName))
                    .toList();
            if (dataPlaneEnabled && !dataPlane.isRunning(clusterName)) {
                all.forEach(connector -> refreshState(connector, ConnectorState.FAILED));
            } else if (dataPlaneEnabled) {
                dataPlane.connectorStates(clusterName).ifPresent(states -> all.forEach(connector -> {
                    ConnectorState state = states.get(connector.getName().substring(prefix.length()));
                    // A stored connector the worker does not list has not been assigned yet.
                    refreshState(connector, state == null ? ConnectorState.UNASSIGNED : state);
                }));
            }
            return PageToken.paginate(all, pageSize(pageSize), pageToken);
        }
    }

    public StoredConnector updateConnector(String project, String location, String connectClusterId,
                                           String connectorId, String updateMask, Map<String, Object> body) {
        String clusterName = connectClusterName(project, location, connectClusterId);
        String name = clusterName + "/connectors/" + connectorId;
        Map<String, Object> update = body == null ? Map.of() : body;
        FieldMask mask = FieldMask.of(updateMask, update, CONNECTOR_MUTABLE, "Connector");
        synchronized (clusterLock(clusterName)) {
            StoredConnector connector = requireConnector(name);
            Map<String, String> configs = mask.touches("configs")
                    ? stringMap(mask.apply("configs", connector.getConfigs()), "configs") : null;
            Map<String, Object> policy = mask.touches("taskRestartPolicy")
                    ? taskRestartPolicy(mask.apply("taskRestartPolicy", connector.getTaskRestartPolicy())) : null;
            if (dataPlaneEnabled && mask.touches("configs")) {
                dataPlane.updateConnector(clusterName, connectorId, configs);
            }
            if (mask.touches("configs")) {
                connector.setConfigs(configs);
            }
            if (mask.touches("taskRestartPolicy")) {
                connector.setTaskRestartPolicy(policy);
            }
            connectorStore.put(name, connector);
            return connector;
        }
    }

    public void deleteConnector(String project, String location, String connectClusterId, String connectorId) {
        String clusterName = connectClusterName(project, location, connectClusterId);
        String name = clusterName + "/connectors/" + connectorId;
        synchronized (clusterLock(clusterName)) {
            requireConnector(name);
            if (dataPlaneEnabled) {
                dataPlane.deleteConnector(clusterName, connectorId);
            }
            connectorStore.delete(name);
        }
    }

    /** {@code PauseConnector}, {@code ResumeConnector} and {@code StopConnector}, named by the state each moves to. */
    public StoredConnector transitionConnector(String project, String location, String connectClusterId,
                                               String connectorId, ConnectorState target) {
        String clusterName = connectClusterName(project, location, connectClusterId);
        String name = clusterName + "/connectors/" + connectorId;
        synchronized (clusterLock(clusterName)) {
            StoredConnector connector = requireConnector(name);
            if (dataPlaneEnabled) {
                dataPlane.transitionConnector(clusterName, connectorId, target);
            }
            connector.setState(target);
            connectorStore.put(name, connector);
            return connector;
        }
    }

    /**
     * {@code RestartConnector}. The worker passes the connector through {@code RESTARTING}, which a
     * later read reports; in mock mode there is nothing to restart, so it is {@code RUNNING} again
     * by the time the response is written.
     */
    public StoredConnector restartConnector(String project, String location, String connectClusterId,
                                            String connectorId) {
        String clusterName = connectClusterName(project, location, connectClusterId);
        String name = clusterName + "/connectors/" + connectorId;
        synchronized (clusterLock(clusterName)) {
            StoredConnector connector = requireConnector(name);
            if (dataPlaneEnabled) {
                dataPlane.restartConnector(clusterName, connectorId);
            }
            connector.setState(ConnectorState.RUNNING);
            connectorStore.put(name, connector);
            return connector;
        }
    }

    /** Callers hold {@link #clusterLock} for the connector's cluster. */
    private void refreshState(StoredConnector connector, ConnectorState state) {
        if (connector.getState() != state) {
            connector.setState(state);
            connectorStore.put(connector.getName(), connector);
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static String connectClusterName(String project, String location, String connectClusterId) {
        return "projects/" + project + "/locations/" + location + "/connectClusters/" + connectClusterId;
    }

    /** Callers hold {@link #clusterLock} for the cluster. */
    private StoredConnectCluster requireConnectCluster(String name) {
        return connectClusterStore.get(name)
                .orElseThrow(() -> GcpException.notFound("ConnectCluster not found: " + name));
    }

    /** Callers hold {@link #clusterLock} for the connector's cluster. */
    private StoredConnector requireConnector(String name) {
        return connectorStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Connector not found: " + name));
    }

    /** One lock per Connect cluster (striped), the only lock this service takes, so there is no order to preserve. */
    private Object clusterLock(String connectClusterName) {
        return clusterLocks[Math.floorMod(connectClusterName.hashCode(), clusterLocks.length)];
    }

    private static Object[] createLocks() {
        Object[] locks = new Object[LOCK_STRIPES];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    /**
     * {@code kafka_cluster} is REQUIRED and must name a Kafka cluster in the same project and
     * location; the real service pins a Connect cluster to a Kafka cluster in its own region.
     */
    private StoredCluster requireKafkaCluster(String project, String location, Object value) {
        if (!(value instanceof String kafkaCluster) || kafkaCluster.isBlank()) {
            throw GcpException.invalidArgument("kafkaCluster is required");
        }
        Matcher m = KAFKA_CLUSTER_NAME.matcher(kafkaCluster);
        if (!m.matches()) {
            throw GcpException.invalidArgument("kafkaCluster must be a Kafka cluster resource name "
                    + "(projects/{project}/locations/{location}/clusters/{cluster}): " + kafkaCluster);
        }
        if (!project.equals(m.group(1)) || !location.equals(m.group(2))) {
            throw GcpException.invalidArgument("kafkaCluster must be in the same project and location "
                    + "as the ConnectCluster: " + kafkaCluster);
        }
        return kafkaClusters.apply(kafkaCluster)
                .orElseThrow(() -> GcpException.notFound("Cluster not found: " + kafkaCluster));
    }

    /**
     * Worker properties are read once, at start, so a changed {@code config} restarts the worker.
     * Connectors survive: their configs and offsets live in the Kafka cluster's internal topics. If
     * the new config does not come up, the previous one is restored before the error is returned.
     * Callers hold {@link #clusterLock} for the cluster.
     */
    private void restartWorker(StoredConnectCluster cluster, Map<String, String> config) {
        StoredCluster kafkaCluster = kafkaClusters.apply(cluster.getKafkaCluster())
                .orElseThrow(() -> GcpException.failedPrecondition("Kafka cluster " + cluster.getKafkaCluster()
                        + " no longer exists, so the Connect cluster's worker cannot be restarted"));
        dataPlane.stopWorker(cluster.getName(), kafkaCluster, false);
        try {
            dataPlane.startWorker(cluster.getName(), kafkaCluster, config);
        } catch (RuntimeException e) {
            LOG.warnf("Worker for %s did not start with the new config, restoring the previous one: %s",
                    cluster.getName(), e.getMessage());
            try {
                dataPlane.startWorker(cluster.getName(), kafkaCluster, cluster.getConfig());
            } catch (RuntimeException restore) {
                e.addSuppressed(restore);
            }
            throw e;
        }
    }

    /** {@code capacity_config} is REQUIRED with both {@code vcpu_count} and {@code memory_bytes}. */
    private static Map<String, Object> requireCapacityConfig(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw GcpException.invalidArgument("capacityConfig is required");
        }
        Map<String, Object> capacity = new LinkedHashMap<>();
        capacity.put("vcpuCount", requirePositiveLong(map.get("vcpuCount"), "capacityConfig.vcpuCount"));
        capacity.put("memoryBytes", requirePositiveLong(map.get("memoryBytes"), "capacityConfig.memoryBytes"));
        return capacity;
    }

    /**
     * {@code gcp_config.access_config.network_configs} is REQUIRED and each entry needs a
     * {@code primary_subnet}; the rest of the config is kept verbatim for read-back fidelity.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireGcpConfig(Object value) {
        if (!(value instanceof Map<?, ?> gcpConfig)) {
            throw GcpException.invalidArgument("gcpConfig is required");
        }
        Object accessConfig = gcpConfig.get("accessConfig");
        Object networkConfigs = accessConfig instanceof Map<?, ?> a ? a.get("networkConfigs") : null;
        if (!(networkConfigs instanceof List<?> configs) || configs.isEmpty()) {
            throw GcpException.invalidArgument("gcpConfig.accessConfig.networkConfigs must have at least one entry");
        }
        for (Object entry : configs) {
            Object primarySubnet = entry instanceof Map<?, ?> e ? e.get("primarySubnet") : null;
            if (!(primarySubnet instanceof String s) || s.isBlank()) {
                throw GcpException.invalidArgument("gcpConfig.accessConfig.networkConfigs[].primarySubnet is required");
            }
        }
        return new LinkedHashMap<>((Map<String, Object>) gcpConfig);
    }

    /** A proto {@code map<string, string>}: absent is {@code null}, anything but string values is a 400. */
    private static Map<String, String> stringMap(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw GcpException.invalidArgument(field + " must be an object of string values");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof String s)) {
                throw GcpException.invalidArgument(field + "." + entry.getKey() + " must be a string");
            }
            result.put(String.valueOf(entry.getKey()), s);
        }
        return result;
    }

    /** {@code TaskRetryPolicy}: two optional {@code google.protobuf.Duration} fields, {@code "60s"} on the wire. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> taskRestartPolicy(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw GcpException.invalidArgument("taskRestartPolicy must be an object");
        }
        for (String field : List.of("minimumBackoff", "maximumBackoff")) {
            Object duration = map.get(field);
            if (duration != null && !(duration instanceof String s && s.matches("^-?\\d+(\\.\\d+)?s$"))) {
                throw GcpException.invalidArgument("taskRestartPolicy." + field
                        + " must be a duration such as \"60s\"");
            }
        }
        return new LinkedHashMap<>((Map<String, Object>) map);
    }

    /**
     * An int64 that must be a positive whole number. Accepts a JSON number or the string form
     * proto3 JSON uses for int64 (what the SDKs send); {@code 12.5}, a value outside the signed
     * 64-bit range and anything unparseable are 400s rather than a truncated value or a 500.
     */
    private static long requirePositiveLong(Object value, String field) {
        java.math.BigDecimal number;
        try {
            if (value instanceof Number n) {
                number = new java.math.BigDecimal(n.toString());
            } else if (value instanceof String s && s.strip().matches("^-?\\d+$")) {
                // proto3 JSON spells int64 as a plain decimal string; no exponent or fraction.
                number = new java.math.BigDecimal(s.strip());
            } else if (value instanceof String) {
                throw GcpException.invalidArgument(field + " must be an integer");
            } else {
                throw GcpException.invalidArgument(field + " is required");
            }
        } catch (NumberFormatException e) {
            throw GcpException.invalidArgument(field + " must be an integer");
        }
        if (number.stripTrailingZeros().scale() > 0) {
            throw GcpException.invalidArgument(field + " must be an integer");
        }
        if (number.compareTo(MAX_INT64) > 0) {
            throw GcpException.invalidArgument(field + " exceeds the int64 range");
        }
        if (number.signum() <= 0) {
            throw GcpException.invalidArgument(field + " must be positive");
        }
        return number.longValueExact();
    }

    /**
     * {@code connect_cluster_id} and {@code connector_id} follow the resource id grammar the API
     * documents: 1 to 63 characters, lowercase letters, digits and hyphens, starting with a letter
     * and ending with a letter or digit. Anything else (a slash, a colon, upper case) would become
     * a stored resource the declared routes cannot address.
     */
    private static void requireResourceId(String id, String field) {
        if (id == null || !RESOURCE_ID.matcher(id).matches()) {
            throw GcpException.invalidArgument(field + " must match [a-z]([-a-z0-9]*[a-z0-9])? and be at most 63 characters");
        }
    }

    /**
     * The fields an update applies. With an {@code update_mask}, exactly the paths it names; a path
     * naming a field outside the mutable set is rejected rather than silently ignored, so a client
     * asking to change {@code kafkaCluster} learns that it is immutable. Without a mask, whichever
     * mutable top-level fields the body carries (the leniency the Kafka cluster update has).
     *
     * <p>Nested paths ({@code capacityConfig.vcpuCount}, {@code taskRestartPolicy.minimumBackoff},
     * {@code labels.env}) replace only that leaf in a copy of the stored value: the body's value
     * at the path is written, or the leaf is cleared when the body does not carry it, and every
     * sibling outside the mask keeps its stored value. The merged value is then validated as a
     * whole, so a masked change can never leave a field in a shape create would have refused.
     */
    private record FieldMask(Set<String> paths, Map<String, Object> body, boolean explicit) {

        static FieldMask of(String updateMask, Map<String, Object> body, Set<String> mutable, String resource) {
            if (updateMask == null || updateMask.isBlank()) {
                Set<String> present = body.keySet().stream().filter(mutable::contains)
                        .collect(java.util.stream.Collectors.toSet());
                return new FieldMask(present, body, false);
            }
            Set<String> paths = Arrays.stream(updateMask.split(","))
                    .map(String::trim)
                    .filter(f -> !f.isEmpty())
                    .map(FieldMask::lowerCamel)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            for (String path : paths) {
                String top = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
                if (!mutable.contains(top)) {
                    throw GcpException.invalidArgument("updateMask names a field that cannot be updated on a "
                            + resource + ": " + path);
                }
            }
            return new FieldMask(paths, body, true);
        }

        boolean touches(String field) {
            return paths.contains(field) || paths.stream().anyMatch(p -> p.startsWith(field + "."));
        }

        /** The value {@code field} should take: the body's whole value, or the stored value with the masked leaves replaced. */
        @SuppressWarnings("unchecked")
        Object apply(String field, Object stored) {
            if (!explicit || paths.contains(field)) {
                return body.get(field);
            }
            Map<String, Object> merged = deepCopy(stored instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
            for (String path : paths) {
                if (!path.startsWith(field + ".")) {
                    continue;
                }
                String[] segments = path.substring(field.length() + 1).split("\\.");
                Object source = body.get(field);
                Map<String, Object> target = merged;
                for (int i = 0; i < segments.length - 1; i++) {
                    source = source instanceof Map<?, ?> sm ? sm.get(segments[i]) : null;
                    Object next = target.get(segments[i]);
                    if (!(next instanceof Map<?, ?>)) {
                        next = new LinkedHashMap<String, Object>();
                        target.put(segments[i], next);
                    }
                    target = (Map<String, Object>) next;
                }
                String leaf = segments[segments.length - 1];
                Object value = source instanceof Map<?, ?> sm ? sm.get(leaf) : null;
                if (value == null) {
                    target.remove(leaf);
                } else {
                    target.put(leaf, value);
                }
            }
            return merged;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> deepCopy(Map<String, Object> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(k, v instanceof Map<?, ?> m ? deepCopy((Map<String, Object>) m) : v));
            return copy;
        }

        /** FieldMask paths are lowerCamelCase in proto3 JSON, but gcloud sends the proto spelling ({@code capacity_config}). */
        private static String lowerCamel(String path) {
            StringBuilder out = new StringBuilder();
            boolean upper = false;
            for (char c : path.toCharArray()) {
                if (c == '_') {
                    upper = true;
                } else {
                    out.append(upper ? Character.toUpperCase(c) : c);
                    upper = false;
                }
            }
            return out.toString();
        }
    }

    private static int pageSize(Integer pageSize) {
        return pageSize == null || pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize;
    }
}

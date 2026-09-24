package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.services.kafka.model.ClusterState;
import io.floci.gcp.services.kafka.model.ConnectorState;
import io.floci.gcp.services.kafka.model.StoredCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Runs one Apache Kafka Connect worker (distributed mode, a single member) per Connect cluster, in
 * a container beside the referenced Kafka cluster's Redpanda container, and drives connectors
 * through the worker's REST API.
 *
 * <p>The worker bootstraps through Redpanda's sidecar listener
 * ({@link RedpandaManager#BROKER_ALIAS}), mapped to the broker container's address with an extra
 * host entry, and keeps its connector configs, offsets and status in three internal topics on that
 * cluster, which is what makes the Kafka cluster the Connect cluster's primary cluster.
 *
 * <p>Workers are tracked in memory only: like the Redpanda containers, they do not outlive the
 * emulator process.
 */
@ApplicationScoped
public class KafkaConnectContainerDataPlane implements KafkaConnectDataPlane {

    private static final Logger LOG = Logger.getLogger(KafkaConnectContainerDataPlane.class);
    static final int REST_PORT = 8083;
    private static final String WORKER_PROPERTIES_ENV = "FLOCI_CONNECT_WORKER_PROPERTIES";
    private static final String WORKER_PROPERTIES_FILE = "/tmp/connect-worker.properties";
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REBALANCE_RETRY = Duration.ofSeconds(20);
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    /** {@code brokerContainerId} is the Redpanda container the worker attached to at start. */
    private record Worker(String containerId, String restBase, String brokerContainerId) {}

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final RedpandaManager redpandaManager;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final Map<String, Worker> workers = new ConcurrentHashMap<>();

    @Inject
    public KafkaConnectContainerDataPlane(ContainerBuilder containerBuilder,
                                          ContainerLifecycleManager lifecycleManager,
                                          ContainerDetector containerDetector,
                                          RedpandaManager redpandaManager,
                                          EmulatorConfig config,
                                          ObjectMapper mapper) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.redpandaManager = redpandaManager;
        this.config = config;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** For tests: drives workers registered with {@link #register}, with no Docker behind them. */
    KafkaConnectContainerDataPlane(ObjectMapper mapper) {
        this(null, null, null, null, null, mapper);
    }

    /** For tests: a worker already serving its REST API at {@code restBase}. */
    void register(String connectCluster, String restBase, String brokerContainerId) {
        workers.put(connectCluster, new Worker(null, restBase, brokerContainerId));
    }

    @Override
    public void startWorker(String connectCluster, StoredCluster kafkaCluster, Map<String, String> workerConfig) {
        if (kafkaCluster.getState() != ClusterState.ACTIVE || kafkaCluster.getContainerId() == null) {
            throw GcpException.failedPrecondition("Kafka cluster " + kafkaCluster.getName()
                    + " is not ACTIVE with a running broker for the Connect cluster to attach to");
        }
        String image = config.services().kafka().connectImage();
        String containerName = containerName(connectCluster);
        LOG.infof("Starting Kafka Connect worker for %s on %s using image %s",
                connectCluster, kafkaCluster.getName(), image);
        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder spec = containerBuilder.newContainer(image)
                .withName(containerName)
                .withLogRotation()
                .withDockerNetwork(config.services().kafka().dockerNetwork())
                .withExtraHost(RedpandaManager.BROKER_ALIAS, redpandaManager.brokerAddress(kafkaCluster))
                .withEnv(WORKER_PROPERTIES_ENV, workerProperties(connectCluster, workerConfig))
                .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx512m")
                // The properties travel in an environment variable, so nothing from the request is
                // ever interpreted by the shell.
                .withEntrypoint(List.of("sh", "-c"))
                .withCmd(List.of("printf '%s' \"$" + WORKER_PROPERTIES_ENV + "\" > " + WORKER_PROPERTIES_FILE
                        + " && exec /opt/kafka/bin/connect-distributed.sh " + WORKER_PROPERTIES_FILE));
        if (!containerDetector.isRunningInContainer()) {
            // The Connect REST API is unauthenticated and only the emulator should drive it, so
            // the published port listens on loopback, which is where the emulator reaches it.
            spec.withLoopbackDynamicPort(REST_PORT);
        } else {
            spec.withExposedPort(REST_PORT);
        }

        ContainerInfo info = lifecycleManager.createAndStart(spec.build());
        EndpointInfo rest = info.getEndpoint(REST_PORT);
        Worker worker = new Worker(info.containerId(), "http://" + rest.host() + ":" + rest.port(),
                kafkaCluster.getContainerId());
        workers.put(connectCluster, worker);
        awaitReady(connectCluster, worker);
    }

    @Override
    public void stopWorker(String connectCluster, StoredCluster kafkaCluster, boolean eraseState) {
        Worker worker = workers.remove(connectCluster);
        if (worker != null) {
            lifecycleManager.stopAndRemove(worker.containerId(), null);
            LOG.infof("Kafka Connect worker for %s stopped", connectCluster);
        }
        if (eraseState && kafkaCluster != null) {
            redpandaManager.deleteTopics(kafkaCluster, internalTopics(connectCluster));
        }
    }

    @Override
    public void createConnector(String connectCluster, String connectorId, Map<String, String> configs) {
        Map<String, String> config = connectorConfig(connectorId, configs);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", connectorId);
        body.put("config", config);
        write(connectCluster, "POST", "/connectors", body,
                () -> configMatches(connectCluster, connectorId, config));
    }

    @Override
    public void updateConnector(String connectCluster, String connectorId, Map<String, String> configs) {
        Map<String, String> config = connectorConfig(connectorId, configs);
        write(connectCluster, "PUT", connectorPath(connectorId) + "/config", config,
                () -> configMatches(connectCluster, connectorId, config));
    }

    @Override
    public void deleteConnector(String connectCluster, String connectorId) {
        try {
            write(connectCluster, "DELETE", connectorPath(connectorId), null,
                    () -> isGone(connectCluster, connectorId));
        } catch (GcpException e) {
            if (e.getHttpStatus() != 404) {
                throw e;
            }
            LOG.debugf("Connector %s was already gone from the %s worker", connectorId, connectCluster);
        }
    }

    @Override
    public void transitionConnector(String connectCluster, String connectorId, ConnectorState target) {
        String action = switch (target) {
            case PAUSED -> "/pause";
            case RUNNING -> "/resume";
            case STOPPED -> "/stop";
            default -> throw new IllegalArgumentException("No Connect REST method moves a connector to " + target);
        };
        write(connectCluster, "PUT", connectorPath(connectorId) + action, null,
                () -> connectorState(send(connectCluster, "GET", connectorPath(connectorId) + "/status", null)) == target);
    }

    @Override
    public void restartConnector(String connectCluster, String connectorId) {
        String path = connectorPath(connectorId) + "/restart?includeTasks=true";
        // A restart leaves nothing to read back, but repeating one is harmless, so an unanswered
        // restart is sent once more rather than guessed at.
        write(connectCluster, "POST", path, null, () -> {
            send(connectCluster, "POST", path, null);
            return true;
        });
    }

    @Override
    public Optional<ConnectorState> connectorState(String connectCluster, String connectorId) {
        try {
            JsonNode status = read(connectCluster, connectorPath(connectorId) + "/status");
            return Optional.of(connectorState(status));
        } catch (GcpException e) {
            if (e.getHttpStatus() == 404) {
                // Accepted by the worker but not yet assigned, which is what UNASSIGNED means.
                return Optional.of(ConnectorState.UNASSIGNED);
            }
            LOG.debugf("Could not read connector %s from the %s worker: %s", connectorId, connectCluster, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Map<String, ConnectorState>> connectorStates(String connectCluster) {
        try {
            JsonNode all = read(connectCluster, "/connectors?expand=status");
            Map<String, ConnectorState> states = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = all.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                states.put(entry.getKey(), connectorState(entry.getValue().path("status")));
            }
            return Optional.of(states);
        } catch (GcpException e) {
            LOG.debugf("Could not list connectors from the %s worker: %s", connectCluster, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean isRunning(String connectCluster) {
        return workers.containsKey(connectCluster);
    }

    @Override
    public void stopWorkersOn(StoredCluster kafkaCluster) {
        if (kafkaCluster.getContainerId() == null) {
            return;
        }
        workers.forEach((connectCluster, worker) -> {
            if (kafkaCluster.getContainerId().equals(worker.brokerContainerId())
                    && workers.remove(connectCluster, worker)) {
                lifecycleManager.stopAndRemove(worker.containerId(), null);
                LOG.infof("Kafka Connect worker for %s stopped: its Kafka cluster %s was deleted",
                        connectCluster, kafkaCluster.getName());
            }
        });
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void awaitReady(String connectCluster, Worker worker) {
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request(worker, "GET", "/connectors", null).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    LOG.infof("Kafka Connect worker for %s is ready at %s", connectCluster, worker.restBase());
                    return;
                }
            } catch (IOException e) {
                LOG.tracef("Kafka Connect worker for %s not reachable yet: %s", connectCluster, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        workers.remove(connectCluster);
        lifecycleManager.stopAndRemove(worker.containerId(), null);
        throw GcpException.unavailable("The Kafka Connect worker for " + connectCluster
                + " did not become ready within " + READY_TIMEOUT.toSeconds() + "s");
    }

    /**
     * A write whose response never arrived may still have been applied: the worker can finish the
     * change after the request timed out. Before reporting {@code UNAVAILABLE}, which makes the
     * service skip its own store update, ask the worker whether the change is in place, so the
     * store and the worker do not diverge in either direction.
     */
    private void write(String connectCluster, String method, String path, Object body, BooleanSupplier applied) {
        try {
            send(connectCluster, method, path, body);
        } catch (WorkerUnreachable e) {
            if (confirmed(applied)) {
                LOG.infof("%s %s on the %s worker got no response but is applied", method, path, connectCluster);
                return;
            }
            throw GcpException.unavailable(e.getMessage());
        }
    }

    /** The outcome is unknown when the check itself cannot be answered, which counts as not applied. */
    private static boolean confirmed(BooleanSupplier applied) {
        try {
            return applied.getAsBoolean();
        } catch (GcpException | WorkerUnreachable e) {
            LOG.debugf("Could not confirm an unanswered Kafka Connect write: %s", e.getMessage());
            return false;
        }
    }

    private boolean configMatches(String connectCluster, String connectorId, Map<String, String> expected) {
        try {
            JsonNode config = send(connectCluster, "GET", connectorPath(connectorId) + "/config", null);
            return expected.equals(mapper.convertValue(config, STRING_MAP));
        } catch (GcpException e) {
            if (e.getHttpStatus() == 404) {
                return false;
            }
            throw e;
        }
    }

    private boolean isGone(String connectCluster, String connectorId) {
        try {
            send(connectCluster, "GET", connectorPath(connectorId), null);
            return false;
        } catch (GcpException e) {
            if (e.getHttpStatus() == 404) {
                return true;
            }
            throw e;
        }
    }

    /** A read: nothing to reconcile, so an unreachable worker is simply {@code UNAVAILABLE}. */
    private JsonNode read(String connectCluster, String path) {
        try {
            return send(connectCluster, "GET", path, null);
        } catch (WorkerUnreachable e) {
            throw GcpException.unavailable(e.getMessage());
        }
    }

    /**
     * One request to the worker. An HTTP error comes back as a {@code GcpException}; a request that
     * got no response (connection refused, reset, or timed out) is a {@link WorkerUnreachable},
     * because for a write the worker may or may not have applied it.
     */
    private JsonNode send(String connectCluster, String method, String path, Object body) {
        Worker worker = workers.get(connectCluster);
        if (worker == null) {
            throw GcpException.failedPrecondition("ConnectCluster " + connectCluster
                    + " has no running Kafka Connect worker");
        }
        long retryDeadline = System.nanoTime() + REBALANCE_RETRY.toNanos();
        while (true) {
            HttpResponse<String> response;
            try {
                response = httpClient.send(request(worker, method, path, body).build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new WorkerUnreachable("The Kafka Connect worker for " + connectCluster
                        + " did not answer " + method + " " + path + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WorkerUnreachable("Interrupted while calling the Kafka Connect worker for "
                        + connectCluster);
            }
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return readTree(response.body());
            }
            String message = errorMessage(response.body());
            if (isRebalance(status, message) && System.nanoTime() < retryDeadline) {
                sleepQuietly();
                continue;
            }
            throw toGcpException(status, message);
        }
    }

    /** A request that got no response, so its effect on the worker is unknown. */
    private static final class WorkerUnreachable extends RuntimeException {
        WorkerUnreachable(String message) {
            super(message);
        }
    }

    private HttpRequest.Builder request(Worker worker, String method, String path, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(worker.restBase() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json");
        if (body == null) {
            return builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw GcpException.internal("Unreadable response from the Kafka Connect worker: " + e.getMessage());
        }
    }

    private String errorMessage(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            if (node != null && node.hasNonNull("message")) {
                return node.get("message").asText();
            }
        } catch (IOException e) {
            LOG.tracef("Kafka Connect error body is not JSON: %s", e.getMessage());
        }
        return body == null ? "" : body;
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Connect REST errors in the GCP error shape. A connector config the worker refuses is the
     * request's fault ({@code INVALID_ARGUMENT}); Kafka 3.x reports an unknown connector class as a
     * 500, which is recognised by its message so it maps the same way as 4.x's 400.
     */
    static GcpException toGcpException(int status, String message) {
        String detail = "Kafka Connect: " + message;
        if (isRebalance(status, message)) {
            // Still rebalancing after the retry window: the worker cannot take the write yet,
            // which is not a name conflict.
            return GcpException.unavailable(detail);
        }
        if (status == 400 || status == 422 || message.startsWith("Failed to find any class that implements Connector")) {
            return GcpException.invalidArgument(detail);
        }
        return switch (status) {
            case 404 -> GcpException.notFound(detail);
            case 409 -> GcpException.alreadyExists(detail);
            case 503 -> GcpException.unavailable(detail);
            default -> GcpException.internal(detail);
        };
    }

    /** A worker answers a write with 409 while its group rebalances, which it does right after start. */
    private static boolean isRebalance(int status, String message) {
        return status == 409 && message.toLowerCase(Locale.ROOT).contains("rebalanc");
    }

    /**
     * The state reported for a Connect status document. Connect reports the connector instance and
     * each task separately, and a connector whose instance runs while a task has failed is not
     * processing that task's records, so any failed task makes the connector {@code FAILED}.
     * Otherwise the connector's own state is reported; its values are the proto's member names.
     */
    static ConnectorState connectorState(JsonNode status) {
        for (JsonNode task : status.path("tasks")) {
            if ("FAILED".equals(task.path("state").asText())) {
                return ConnectorState.FAILED;
            }
        }
        String state = status.path("connector").path("state").asText("");
        try {
            return state.isEmpty() ? ConnectorState.UNASSIGNED : ConnectorState.valueOf(state);
        } catch (IllegalArgumentException e) {
            return ConnectorState.STATE_UNSPECIFIED;
        }
    }

    /**
     * The worker's properties file: defaults a single-broker cluster needs, then the Connect
     * cluster's {@code config} overrides, then the properties the emulator owns, which always win.
     */
    static String workerProperties(String connectCluster, Map<String, String> overrides) {
        Properties props = new Properties();
        props.setProperty("key.converter", "org.apache.kafka.connect.json.JsonConverter");
        props.setProperty("value.converter", "org.apache.kafka.connect.json.JsonConverter");
        props.setProperty("config.storage.replication.factor", "1");
        props.setProperty("offset.storage.replication.factor", "1");
        props.setProperty("status.storage.replication.factor", "1");
        if (overrides != null) {
            overrides.forEach(props::setProperty);
        }
        List<String> topics = internalTopics(connectCluster);
        props.setProperty("bootstrap.servers", RedpandaManager.BROKER_ALIAS + ":" + RedpandaManager.SIDECAR_KAFKA_PORT);
        props.setProperty("group.id", "floci-connect-" + resourceId(connectCluster));
        props.setProperty("listeners", "http://0.0.0.0:" + REST_PORT);
        props.setProperty("config.storage.topic", topics.get(0));
        props.setProperty("offset.storage.topic", topics.get(1));
        props.setProperty("status.storage.topic", topics.get(2));
        StringWriter out = new StringWriter();
        try {
            props.store(out, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /**
     * Config, offset and status topics, in that order. A Connect cluster shares its project and
     * location with its Kafka cluster, so the id alone is unique among the Connect clusters that
     * can attach to one broker.
     */
    static List<String> internalTopics(String connectCluster) {
        String prefix = "_floci-connect-" + resourceId(connectCluster);
        return List.of(prefix + "-configs", prefix + "-offsets", prefix + "-status");
    }

    /** Connect requires {@code name} inside the config to match the connector's name. */
    private static Map<String, String> connectorConfig(String connectorId, Map<String, String> configs) {
        Map<String, String> config = new LinkedHashMap<>(configs == null ? Map.of() : configs);
        config.put("name", connectorId);
        return config;
    }

    private static String connectorPath(String connectorId) {
        return "/connectors/" + URLEncoder.encode(connectorId, StandardCharsets.UTF_8);
    }

    /**
     * Unique per Connect cluster across projects and locations, which the id alone is not: two
     * projects may both have a {@code connectClusters/cc}.
     */
    private String containerName(String connectCluster) {
        return ContainerStorageHelper.dockerName(config, "kafka-connect-" + resourceId(connectCluster)
                + "-" + Integer.toHexString(connectCluster.hashCode()));
    }

    private static String resourceId(String name) {
        return name.substring(name.lastIndexOf('/') + 1);
    }
}

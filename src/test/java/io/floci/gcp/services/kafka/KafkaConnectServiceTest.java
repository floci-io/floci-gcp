package io.floci.gcp.services.kafka;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.kafka.model.ClusterState;
import io.floci.gcp.services.kafka.model.ConnectorState;
import io.floci.gcp.services.kafka.model.StoredCluster;
import io.floci.gcp.services.kafka.model.StoredConnectCluster;
import io.floci.gcp.services.kafka.model.StoredConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaConnectServiceTest {

    private static final String PROJECT = "p";
    private static final String LOCATION = "us-central1";
    private static final String KAFKA = "projects/p/locations/us-central1/clusters/main";
    private static final Map<String, Object> CAPACITY = Map.of("vcpuCount", 12, "memoryBytes", 21474836480L);
    private static final Map<String, Object> GCP_CONFIG = Map.of("accessConfig", Map.of("networkConfigs",
            List.of(Map.of("primarySubnet", "projects/p/regions/us-central1/subnetworks/default"))));

    private final Set<String> kafkaClusters = Set.of(KAFKA, "projects/other/locations/us-central1/clusters/main",
            "projects/p/locations/europe-west1/clusters/main");
    private KafkaConnectService service;

    @BeforeEach
    void setUp() {
        service = new KafkaConnectService(new InMemoryStorage<String, StoredConnectCluster>(),
                new InMemoryStorage<String, StoredConnector>(), kafkaClusters::contains);
    }

    private Map<String, Object> validBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("kafkaCluster", KAFKA);
        body.put("capacityConfig", CAPACITY);
        body.put("gcpConfig", GCP_CONFIG);
        return body;
    }

    @Test
    void createStoresTheWireShapeAndIsActiveImmediately() {
        Map<String, Object> body = validBody();
        body.put("labels", Map.of("env", "dev"));
        body.put("config", Map.of("group.id", "connect-main"));

        StoredConnectCluster cluster = service.createConnectCluster(PROJECT, LOCATION, "cc", body);

        assertEquals("projects/p/locations/us-central1/connectClusters/cc", cluster.getName());
        assertEquals(KAFKA, cluster.getKafkaCluster());
        assertEquals(ClusterState.ACTIVE, cluster.getState());
        assertEquals(Map.of("vcpuCount", 12L, "memoryBytes", 21474836480L), cluster.getCapacityConfig());
        assertEquals(GCP_CONFIG, cluster.getGcpConfig());
        assertEquals(Map.of("env", "dev"), cluster.getLabels());
        assertEquals(Map.of("group.id", "connect-main"), cluster.getConfig());
        assertEquals(cluster.getCreateTime(), cluster.getUpdateTime());
        assertEquals(cluster.getName(), service.getConnectCluster(PROJECT, LOCATION, "cc").getName());

        assertEquals("ALREADY_EXISTS", assertThrows(GcpException.class,
                () -> service.createConnectCluster(PROJECT, LOCATION, "cc", validBody())).getGcpStatus());
    }

    @Test
    void int64CapacityFieldsAcceptTheProto3JsonStringSpelling() {
        Map<String, Object> body = validBody();
        body.put("capacityConfig", Map.of("vcpuCount", "12", "memoryBytes", "21474836480"));

        StoredConnectCluster cluster = service.createConnectCluster(PROJECT, LOCATION, "cc", body);

        assertEquals(Map.of("vcpuCount", 12L, "memoryBytes", 21474836480L), cluster.getCapacityConfig());
    }

    @Test
    void kafkaClusterMustExistInTheSameProjectAndLocation() {
        for (Object bad : new Object[] {null, "", 7, "main", "projects/p/locations/us-central1/topics/t",
                "projects/other/locations/us-central1/clusters/main",
                "projects/p/locations/europe-west1/clusters/main"}) {
            Map<String, Object> body = validBody();
            if (bad == null) {
                body.remove("kafkaCluster");
            } else {
                body.put("kafkaCluster", bad);
            }
            GcpException error = assertThrows(GcpException.class,
                    () -> service.createConnectCluster(PROJECT, LOCATION, "cc", body), String.valueOf(bad));
            assertEquals("INVALID_ARGUMENT", error.getGcpStatus(), String.valueOf(bad));
        }
        Map<String, Object> missing = validBody();
        missing.put("kafkaCluster", "projects/p/locations/us-central1/clusters/nope");
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.createConnectCluster(PROJECT, LOCATION, "cc", missing)).getGcpStatus());
        assertEquals(0, service.listConnectClusters(PROJECT, LOCATION, null, null).items().size());
    }

    @Test
    void capacityAndNetworkConfigAreRequired() {
        Map<String, Object> noCapacity = validBody();
        noCapacity.remove("capacityConfig");
        Map<String, Object> zeroCpu = validBody();
        zeroCpu.put("capacityConfig", Map.of("vcpuCount", 0, "memoryBytes", 1));
        Map<String, Object> noMemory = validBody();
        noMemory.put("capacityConfig", Map.of("vcpuCount", 3));
        Map<String, Object> noGcp = validBody();
        noGcp.remove("gcpConfig");
        Map<String, Object> noNetworks = validBody();
        noNetworks.put("gcpConfig", Map.of("accessConfig", Map.of("networkConfigs", List.of())));
        Map<String, Object> noSubnet = validBody();
        noSubnet.put("gcpConfig", Map.of("accessConfig", Map.of("networkConfigs", List.of(Map.of("dnsDomainNames", List.of("a"))))));
        Map<String, Object> badLabels = validBody();
        badLabels.put("labels", Map.of("env", 1));

        for (Map<String, Object> body : List.of(noCapacity, zeroCpu, noMemory, noGcp, noNetworks, noSubnet, badLabels)) {
            GcpException error = assertThrows(GcpException.class,
                    () -> service.createConnectCluster(PROJECT, LOCATION, "cc", body), body.toString());
            assertEquals("INVALID_ARGUMENT", error.getGcpStatus(), body.toString());
        }
    }

    @Test
    void updateHonoursTheMaskAndKeepsKafkaClusterImmutable() {
        service.createConnectCluster(PROJECT, LOCATION, "cc", validBody());

        // Only the masked field moves, even though the body carries more.
        Map<String, Object> update = new HashMap<>();
        update.put("labels", Map.of("env", "prod"));
        update.put("capacityConfig", Map.of("vcpuCount", 24, "memoryBytes", 42949672960L));
        StoredConnectCluster masked = service.updateConnectCluster(PROJECT, LOCATION, "cc", "labels", update);
        assertEquals(Map.of("env", "prod"), masked.getLabels());
        assertEquals(12L, masked.getCapacityConfig().get("vcpuCount"));

        // gcloud spells the mask in proto form.
        StoredConnectCluster snake = service.updateConnectCluster(PROJECT, LOCATION, "cc", "capacity_config", update);
        assertEquals(24L, snake.getCapacityConfig().get("vcpuCount"));
        assertTrue(snake.getUpdateTime().isAfter(snake.getCreateTime()) || snake.getUpdateTime().equals(snake.getCreateTime()));

        // Without a mask, whatever mutable fields the body carries apply (as UpdateCluster does).
        StoredConnectCluster unmasked = service.updateConnectCluster(PROJECT, LOCATION, "cc", null,
                Map.of("config", Map.of("offset.flush.interval.ms", "1000")));
        assertEquals(Map.of("offset.flush.interval.ms", "1000"), unmasked.getConfig());
        assertEquals(Map.of("env", "prod"), unmasked.getLabels());

        // kafka_cluster is IMMUTABLE: naming it in the mask or changing it in the body is a 400.
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class, () -> service.updateConnectCluster(
                PROJECT, LOCATION, "cc", "kafkaCluster", Map.of("kafkaCluster", KAFKA))).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class, () -> service.updateConnectCluster(
                PROJECT, LOCATION, "cc", null,
                Map.of("kafkaCluster", "projects/p/locations/us-central1/clusters/other"))).getGcpStatus());
        // Echoing the current value back, as a full-resource PATCH does, is fine.
        service.updateConnectCluster(PROJECT, LOCATION, "cc", "labels", Map.of("kafkaCluster", KAFKA, "labels", Map.of()));
        assertEquals(Map.of(), service.getConnectCluster(PROJECT, LOCATION, "cc").getLabels());
    }

    @Test
    void nestedMaskPathsReplaceOnlyTheNamedLeaf() {
        Map<String, Object> body = validBody();
        body.put("labels", Map.of("env", "dev", "team", "data"));
        service.createConnectCluster(PROJECT, LOCATION, "cc", body);

        // capacityConfig.vcpuCount with only vcpuCount in the body: memoryBytes is kept, not required again.
        StoredConnectCluster cpu = service.updateConnectCluster(PROJECT, LOCATION, "cc", "capacityConfig.vcpuCount",
                Map.of("capacityConfig", Map.of("vcpuCount", 24)));
        assertEquals(Map.of("vcpuCount", 24L, "memoryBytes", 21474836480L), cpu.getCapacityConfig());

        // labels.env replaces one key and keeps the other; a masked leaf absent from the body is cleared.
        StoredConnectCluster env = service.updateConnectCluster(PROJECT, LOCATION, "cc", "labels.env",
                Map.of("labels", Map.of("env", "prod", "team", "ignored")));
        assertEquals(Map.of("env", "prod", "team", "data"), env.getLabels());
        StoredConnectCluster cleared = service.updateConnectCluster(PROJECT, LOCATION, "cc", "labels.team", Map.of());
        assertEquals(Map.of("env", "prod"), cleared.getLabels());

        // A deeper path merges into the stored structure and the whole is re-validated.
        StoredConnectCluster secrets = service.updateConnectCluster(PROJECT, LOCATION, "cc", "gcp_config.secret_paths",
                Map.of("gcpConfig", Map.of("secretPaths", List.of("projects/p/secrets/s/versions/1"))));
        assertEquals(List.of("projects/p/secrets/s/versions/1"), secrets.getGcpConfig().get("secretPaths"));
        assertEquals(GCP_CONFIG.get("accessConfig"), secrets.getGcpConfig().get("accessConfig"));
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class, () -> service.updateConnectCluster(
                PROJECT, LOCATION, "cc", "capacityConfig.memoryBytes", Map.of())).getGcpStatus());
        assertEquals(24L, service.getConnectCluster(PROJECT, LOCATION, "cc").getCapacityConfig().get("vcpuCount"));

        // The connector side: one backoff replaced, the other kept.
        service.createConnector(PROJECT, LOCATION, "cc", "sink", Map.of(
                "taskRestartPolicy", Map.of("minimumBackoff", "60s", "maximumBackoff", "1800s")));
        StoredConnector connector = service.updateConnector(PROJECT, LOCATION, "cc", "sink", "taskRestartPolicy.minimumBackoff",
                Map.of("taskRestartPolicy", Map.of("minimumBackoff", "5s")));
        assertEquals(Map.of("minimumBackoff", "5s", "maximumBackoff", "1800s"), connector.getTaskRestartPolicy());
    }

    @Test
    void int64FieldsMustBeWholeNumbersInRange() {
        for (Object bad : new Object[] {12.5, "12.5", "1e3", "abc", " ", "99999999999999999999", 9.223372036854776E18,
                -1, "0", Map.of()}) {
            Map<String, Object> body = validBody();
            body.put("capacityConfig", Map.of("vcpuCount", bad, "memoryBytes", 1));
            GcpException error = assertThrows(GcpException.class,
                    () -> service.createConnectCluster(PROJECT, LOCATION, "cc", body), String.valueOf(bad));
            assertEquals("INVALID_ARGUMENT", error.getGcpStatus(), String.valueOf(bad));
        }
        Map<String, Object> max = validBody();
        max.put("capacityConfig", Map.of("vcpuCount", "9223372036854775807", "memoryBytes", 12.0));
        assertEquals(Map.of("vcpuCount", Long.MAX_VALUE, "memoryBytes", 12L),
                service.createConnectCluster(PROJECT, LOCATION, "cc", max).getCapacityConfig());
    }

    @Test
    void resourceIdsFollowTheDocumentedGrammar() {
        service.createConnectCluster(PROJECT, LOCATION, "ok-1", validBody());
        for (String bad : List.of("", "Upper", "-lead", "trail-", "a/b", "a:b", "a.b", "1abc", "a".repeat(64))) {
            assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                    () -> service.createConnectCluster(PROJECT, LOCATION, bad, validBody())).getGcpStatus(), bad);
            assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                    () -> service.createConnector(PROJECT, LOCATION, "ok-1", bad, Map.of())).getGcpStatus(), bad);
        }
        service.createConnector(PROJECT, LOCATION, "ok-1", "a".repeat(63), Map.of());
        assertEquals(1, service.listConnectClusters(PROJECT, LOCATION, null, null).items().size());
        assertEquals(1, service.listConnectors(PROJECT, LOCATION, "ok-1", null, null).items().size());
    }

    @Test
    void listsArePagedAndDeleteCascadesToConnectors() {
        for (String id : List.of("b", "a", "c")) {
            service.createConnectCluster(PROJECT, LOCATION, id, validBody());
        }
        PageToken.Page<StoredConnectCluster> first = service.listConnectClusters(PROJECT, LOCATION, 2, null);
        assertEquals(List.of("a", "b"), first.items().stream().map(c -> c.getName().substring(c.getName().lastIndexOf('/') + 1)).toList());
        PageToken.Page<StoredConnectCluster> second = service.listConnectClusters(PROJECT, LOCATION, 2, first.nextPageToken());
        assertEquals(1, second.items().size());
        assertNull(second.nextPageToken());
        // Another location is another parent.
        assertEquals(0, service.listConnectClusters(PROJECT, "europe-west1", null, null).items().size());

        service.createConnector(PROJECT, LOCATION, "a", "sink", Map.of("configs", Map.of("connector.class", "GcsSink")));
        service.createConnector(PROJECT, LOCATION, "a", "source", Map.of());
        assertEquals(2, service.listConnectors(PROJECT, LOCATION, "a", null, null).items().size());

        service.deleteConnectCluster(PROJECT, LOCATION, "a");
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getConnectCluster(PROJECT, LOCATION, "a")).getGcpStatus());
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getConnector(PROJECT, LOCATION, "a", "sink")).getGcpStatus());
        assertEquals(2, service.listConnectClusters(PROJECT, LOCATION, null, null).items().size());
    }

    @Test
    void deleteSerializesConnectorMutationsAndLeavesNoOrphan() throws Exception {
        // Invariant: a connector exists only under a Connect cluster that exists. The delete holds
        // the cluster lock while it cascades; every competing operation must wait for it and then
        // see the cluster gone, and nothing submitted during the window may be stored.
        BlockingDeleteStorage<String, StoredConnector> connectors = new BlockingDeleteStorage<>();
        KafkaConnectService tested = new KafkaConnectService(new InMemoryStorage<String, StoredConnectCluster>(),
                connectors, kafkaClusters::contains);
        tested.createConnectCluster(PROJECT, LOCATION, "cc", validBody());
        tested.createConnector(PROJECT, LOCATION, "cc", "sink", Map.of());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> delete = executor.submit(() -> tested.deleteConnectCluster(PROJECT, LOCATION, "cc"));
            assertTrue(connectors.awaitEntered(), "delete never reached the connector cascade");
            List<Future<?>> blocked = List.of(
                    executor.submit(() -> tested.createConnector(PROJECT, LOCATION, "cc", "late", Map.of())),
                    executor.submit(() -> tested.updateConnector(PROJECT, LOCATION, "cc", "sink", "configs",
                            Map.of("configs", Map.of("topics", "x")))),
                    executor.submit(() -> tested.transitionConnector(PROJECT, LOCATION, "cc", "sink", ConnectorState.PAUSED)),
                    executor.submit(() -> tested.deleteConnector(PROJECT, LOCATION, "cc", "sink")),
                    executor.submit(() -> tested.getConnector(PROJECT, LOCATION, "cc", "sink")),
                    executor.submit(() -> tested.listConnectors(PROJECT, LOCATION, "cc", null, null)),
                    executor.submit(() -> tested.getConnectCluster(PROJECT, LOCATION, "cc")),
                    executor.submit(() -> tested.updateConnectCluster(PROJECT, LOCATION, "cc", "labels",
                            Map.of("labels", Map.of("env", "x")))));
            assertTimeoutPreemptively(Duration.ofMillis(200),
                    () -> assertTrue(blocked.stream().noneMatch(Future::isDone)));

            connectors.release();
            delete.get(5, TimeUnit.SECONDS);
            for (Future<?> operation : blocked) {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> operation.get(5, TimeUnit.SECONDS));
                assertInstanceOf(GcpException.class, failure.getCause());
                assertEquals("NOT_FOUND", ((GcpException) failure.getCause()).getGcpStatus());
            }
            assertTrue(connectors.scan(k -> true).isEmpty(), "a connector survived or was orphaned");
            // The name is free again: the cluster and its connectors can be recreated.
            tested.createConnectCluster(PROJECT, LOCATION, "cc", validBody());
            assertEquals(0, tested.listConnectors(PROJECT, LOCATION, "cc", null, null).items().size());
        }
    }

    /** Blocks the first {@code delete} until released, so a test can hold a cascade open mid-way. */
    private static final class BlockingDeleteStorage<K, V> extends InMemoryStorage<K, V> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private boolean first = true;

        @Override
        public void delete(K key) {
            if (first) {
                first = false;
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release delete");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            super.delete(key);
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    @Test
    void connectorLifecycleFlipsStateAndValidatesInput() {
        service.createConnectCluster(PROJECT, LOCATION, "cc", validBody());
        assertEquals("NOT_FOUND", assertThrows(GcpException.class, () -> service.createConnector(
                PROJECT, LOCATION, "missing", "sink", Map.of())).getGcpStatus());

        StoredConnector connector = service.createConnector(PROJECT, LOCATION, "cc", "sink", Map.of(
                "configs", Map.of("connector.class", "GcsSink", "topics", "orders"),
                "taskRestartPolicy", Map.of("minimumBackoff", "60s", "maximumBackoff", "1800s")));
        assertEquals("projects/p/locations/us-central1/connectClusters/cc/connectors/sink", connector.getName());
        assertEquals(ConnectorState.RUNNING, connector.getState());
        assertEquals("orders", connector.getConfigs().get("topics"));
        assertEquals("60s", connector.getTaskRestartPolicy().get("minimumBackoff"));
        assertEquals("ALREADY_EXISTS", assertThrows(GcpException.class, () -> service.createConnector(
                PROJECT, LOCATION, "cc", "sink", Map.of())).getGcpStatus());

        // pause -> PAUSED, resume -> RUNNING, stop -> STOPPED, restart -> RUNNING
        assertEquals(ConnectorState.PAUSED, service.transitionConnector(PROJECT, LOCATION, "cc", "sink", ConnectorState.PAUSED).getState());
        assertEquals(ConnectorState.PAUSED, service.getConnector(PROJECT, LOCATION, "cc", "sink").getState());
        assertEquals(ConnectorState.RUNNING, service.transitionConnector(PROJECT, LOCATION, "cc", "sink", ConnectorState.RUNNING).getState());
        assertEquals(ConnectorState.STOPPED, service.transitionConnector(PROJECT, LOCATION, "cc", "sink", ConnectorState.STOPPED).getState());
        assertEquals(ConnectorState.RUNNING, service.transitionConnector(PROJECT, LOCATION, "cc", "sink", ConnectorState.RUNNING).getState());

        // update: masked configs replace the map; a bad duration or non-string config is a 400
        StoredConnector updated = service.updateConnector(PROJECT, LOCATION, "cc", "sink", "configs",
                Map.of("configs", Map.of("topics", "orders,returns"), "taskRestartPolicy", Map.of("minimumBackoff", "5s")));
        assertEquals(Map.of("topics", "orders,returns"), updated.getConfigs());
        assertEquals("60s", updated.getTaskRestartPolicy().get("minimumBackoff"));
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class, () -> service.updateConnector(
                PROJECT, LOCATION, "cc", "sink", null, Map.of("taskRestartPolicy", Map.of("minimumBackoff", "1m")))).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class, () -> service.updateConnector(
                PROJECT, LOCATION, "cc", "sink", null, Map.of("configs", Map.of("tasks.max", 2)))).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class, () -> service.updateConnector(
                PROJECT, LOCATION, "cc", "sink", "state", Map.of("state", "PAUSED"))).getGcpStatus());

        service.deleteConnector(PROJECT, LOCATION, "cc", "sink");
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.transitionConnector(PROJECT, LOCATION, "cc", "sink", ConnectorState.PAUSED)).getGcpStatus());
    }

    // ── With the data plane on ────────────────────────────────────────────────

    private final RecordingDataPlane dataPlane = new RecordingDataPlane();

    private KafkaConnectService withDataPlane() {
        return new KafkaConnectService(new InMemoryStorage<String, StoredConnectCluster>(),
                new InMemoryStorage<String, StoredConnector>(),
                name -> kafkaClusters.contains(name) ? Optional.of(new StoredCluster(name)) : Optional.empty(),
                dataPlane, true);
    }

    @Test
    void createStartsAWorkerOnTheReferencedKafkaClusterWithTheWorkerConfig() {
        KafkaConnectService tested = withDataPlane();
        Map<String, Object> body = validBody();
        body.put("config", Map.of("exactly.once.source.support", "enabled"));

        StoredConnectCluster cluster = tested.createConnectCluster(PROJECT, LOCATION, "cc", body);

        assertEquals(ClusterState.ACTIVE, cluster.getState());
        assertEquals(List.of("start " + CC + " on " + KAFKA + " with {exactly.once.source.support=enabled}"),
                dataPlane.calls);
    }

    @Test
    void aWorkerThatCannotStartLeavesNoConnectClusterBehind() {
        KafkaConnectService tested = withDataPlane();
        dataPlane.failStart = GcpException.unavailable("worker did not become ready");

        assertEquals("UNAVAILABLE", assertThrows(GcpException.class,
                () -> tested.createConnectCluster(PROJECT, LOCATION, "cc", validBody())).getGcpStatus());

        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> tested.getConnectCluster(PROJECT, LOCATION, "cc")).getGcpStatus());
        assertEquals("stop " + CC + " erase=true", dataPlane.calls.get(dataPlane.calls.size() - 1));
        dataPlane.failStart = null;
        assertEquals(ClusterState.ACTIVE, tested.createConnectCluster(PROJECT, LOCATION, "cc", validBody()).getState());
    }

    @Test
    void aConnectorTheWorkerRefusesIsNotStored() {
        KafkaConnectService tested = withDataPlane();
        tested.createConnectCluster(PROJECT, LOCATION, "cc", validBody());
        dataPlane.failConnectorWrite = GcpException.invalidArgument("Kafka Connect: no connector type");

        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class, () -> tested.createConnector(
                PROJECT, LOCATION, "cc", "sink", Map.of("configs", Map.of("topics", "orders")))).getGcpStatus());

        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> tested.getConnector(PROJECT, LOCATION, "cc", "sink")).getGcpStatus());
    }

    @Test
    void aConfigUpdateTheWorkerRefusesLeavesTheStoredConfigs() {
        KafkaConnectService tested = withDataPlane();
        tested.createConnectCluster(PROJECT, LOCATION, "cc", validBody());
        tested.createConnector(PROJECT, LOCATION, "cc", "sink", Map.of("configs", Map.of("topics", "orders")));
        dataPlane.failConnectorWrite = GcpException.invalidArgument("Kafka Connect: bad config");

        assertThrows(GcpException.class, () -> tested.updateConnector(PROJECT, LOCATION, "cc", "sink", "configs",
                Map.of("configs", Map.of("topics", "returns"))));

        assertEquals(Map.of("topics", "orders"), tested.getConnector(PROJECT, LOCATION, "cc", "sink").getConfigs());
    }

    @Test
    void connectorWritesAndLifecycleMethodsReachTheWorker() {
        KafkaConnectService tested = withDataPlane();
        tested.createConnectCluster(PROJECT, LOCATION, "cc", validBody());
        dataPlane.calls.clear();

        tested.createConnector(PROJECT, LOCATION, "cc", "sink", Map.of("configs", Map.of("topics", "orders")));
        tested.updateConnector(PROJECT, LOCATION, "cc", "sink", "configs", Map.of("configs", Map.of("topics", "returns")));
        // taskRestartPolicy has no Kafka Connect counterpart, so it stays control-plane metadata.
        tested.updateConnector(PROJECT, LOCATION, "cc", "sink", "taskRestartPolicy",
                Map.of("taskRestartPolicy", Map.of("minimumBackoff", "5s")));
        tested.transitionConnector(PROJECT, LOCATION, "cc", "sink", ConnectorState.PAUSED);
        tested.transitionConnector(PROJECT, LOCATION, "cc", "sink", ConnectorState.RUNNING);
        tested.transitionConnector(PROJECT, LOCATION, "cc", "sink", ConnectorState.STOPPED);
        tested.restartConnector(PROJECT, LOCATION, "cc", "sink");
        tested.deleteConnector(PROJECT, LOCATION, "cc", "sink");

        assertEquals(List.of("create sink {topics=orders}", "update sink {topics=returns}",
                "transition sink PAUSED", "transition sink RUNNING", "transition sink STOPPED",
                "restart sink", "delete sink"), dataPlane.calls);
    }

    @Test
    void connectorStateIsReadBackFromTheWorker() {
        KafkaConnectService tested = withDataPlane();
        tested.createConnectCluster(PROJECT, LOCATION, "cc", validBody());
        tested.createConnector(PROJECT, LOCATION, "cc", "sink", Map.of("configs", Map.of("topics", "orders")));
        tested.createConnector(PROJECT, LOCATION, "cc", "source", Map.of("configs", Map.of("topics", "orders")));

        dataPlane.states.put("sink", ConnectorState.FAILED);
        assertEquals(ConnectorState.FAILED, tested.getConnector(PROJECT, LOCATION, "cc", "sink").getState());

        // A stored connector the worker does not list yet has not been assigned.
        PageToken.Page<StoredConnector> page = tested.listConnectors(PROJECT, LOCATION, "cc", null, null);
        assertEquals(List.of(ConnectorState.FAILED, ConnectorState.UNASSIGNED),
                page.items().stream().map(StoredConnector::getState).toList());

        // An unreachable worker leaves the last known state rather than failing the read.
        dataPlane.reachable = false;
        assertEquals(ConnectorState.FAILED, tested.getConnector(PROJECT, LOCATION, "cc", "sink").getState());
    }

    @Test
    void connectorsOnAWorkerThatIsNotRunningReportFailed() {
        KafkaConnectService tested = withDataPlane();
        tested.createConnectCluster(PROJECT, LOCATION, "cc", validBody());
        tested.createConnector(PROJECT, LOCATION, "cc", "sink", Map.of("configs", Map.of("topics", "orders")));
        tested.createConnector(PROJECT, LOCATION, "cc", "source", Map.of("configs", Map.of("topics", "orders")));

        // The worker was stopped because its Kafka cluster was deleted, or the emulator restarted:
        // nothing is processing records, so the last state the worker reported no longer holds.
        dataPlane.running = false;

        assertEquals(ConnectorState.FAILED, tested.getConnector(PROJECT, LOCATION, "cc", "sink").getState());
        assertEquals(List.of(ConnectorState.FAILED, ConnectorState.FAILED),
                tested.listConnectors(PROJECT, LOCATION, "cc", null, null).items().stream()
                        .map(StoredConnector::getState).toList());
    }

    @Test
    void onlyAChangedWorkerConfigRestartsTheWorker() {
        KafkaConnectService tested = withDataPlane();
        Map<String, Object> body = validBody();
        body.put("config", Map.of("offset.flush.interval.ms", "10000"));
        tested.createConnectCluster(PROJECT, LOCATION, "cc", body);
        dataPlane.calls.clear();

        tested.updateConnectCluster(PROJECT, LOCATION, "cc", "labels", Map.of("labels", Map.of("env", "dev")));
        tested.updateConnectCluster(PROJECT, LOCATION, "cc", "config",
                Map.of("config", Map.of("offset.flush.interval.ms", "10000")));
        assertEquals(List.of(), dataPlane.calls);

        tested.updateConnectCluster(PROJECT, LOCATION, "cc", "config",
                Map.of("config", Map.of("offset.flush.interval.ms", "5000")));
        assertEquals(List.of("stop " + CC + " erase=false",
                "start " + CC + " on " + KAFKA + " with {offset.flush.interval.ms=5000}"), dataPlane.calls);
    }

    @Test
    void aWorkerConfigThatDoesNotStartIsRolledBack() {
        KafkaConnectService tested = withDataPlane();
        Map<String, Object> body = validBody();
        body.put("config", Map.of("offset.flush.interval.ms", "10000"));
        tested.createConnectCluster(PROJECT, LOCATION, "cc", body);
        dataPlane.calls.clear();
        dataPlane.failStartWith = "offset.flush.interval.ms=-1";

        assertThrows(GcpException.class, () -> tested.updateConnectCluster(PROJECT, LOCATION, "cc", "config",
                Map.of("config", Map.of("offset.flush.interval.ms", "-1"))));

        assertEquals("start " + CC + " on " + KAFKA + " with {offset.flush.interval.ms=10000}",
                dataPlane.calls.get(dataPlane.calls.size() - 1));
        assertEquals(Map.of("offset.flush.interval.ms", "10000"),
                tested.getConnectCluster(PROJECT, LOCATION, "cc").getConfig());
    }

    @Test
    void deletingAConnectClusterStopsItsWorkerAndErasesItsState() {
        KafkaConnectService tested = withDataPlane();
        tested.createConnectCluster(PROJECT, LOCATION, "cc", validBody());
        tested.createConnector(PROJECT, LOCATION, "cc", "sink", Map.of("configs", Map.of("topics", "orders")));
        dataPlane.calls.clear();

        tested.deleteConnectCluster(PROJECT, LOCATION, "cc");

        assertEquals(List.of("stop " + CC + " erase=true"), dataPlane.calls);
    }

    @Test
    void mockModeNeverReachesTheDataPlane() {
        KafkaConnectService tested = new KafkaConnectService(new InMemoryStorage<String, StoredConnectCluster>(),
                new InMemoryStorage<String, StoredConnector>(),
                name -> kafkaClusters.contains(name) ? Optional.of(new StoredCluster(name)) : Optional.empty(),
                dataPlane, false);

        tested.createConnectCluster(PROJECT, LOCATION, "cc", validBody());
        tested.updateConnectCluster(PROJECT, LOCATION, "cc", "config", Map.of("config", Map.of("a", "b")));
        tested.createConnector(PROJECT, LOCATION, "cc", "sink", Map.of("configs", Map.of("topics", "orders")));
        tested.getConnector(PROJECT, LOCATION, "cc", "sink");
        tested.listConnectors(PROJECT, LOCATION, "cc", null, null);
        tested.restartConnector(PROJECT, LOCATION, "cc", "sink");
        tested.deleteConnectCluster(PROJECT, LOCATION, "cc");
        tested.shutdown();

        assertEquals(List.of(), dataPlane.calls);
    }

    private static final String CC = "projects/p/locations/us-central1/connectClusters/cc";

    /** Records each call as a line; states and failures are set by the test. */
    private static final class RecordingDataPlane implements KafkaConnectDataPlane {
        final List<String> calls = new ArrayList<>();
        final Map<String, ConnectorState> states = new LinkedHashMap<>();
        GcpException failStart;
        String failStartWith;
        GcpException failConnectorWrite;
        boolean reachable = true;
        boolean running = true;

        @Override
        public void startWorker(String connectCluster, StoredCluster kafkaCluster, Map<String, String> workerConfig) {
            calls.add("start " + connectCluster + " on " + kafkaCluster.getName() + " with "
                    + (workerConfig == null ? "{}" : new TreeMap<>(workerConfig)));
            if (failStart != null) {
                throw failStart;
            }
            if (failStartWith != null && workerConfig != null
                    && workerConfig.entrySet().stream().anyMatch(e -> failStartWith.equals(e.getKey() + "=" + e.getValue()))) {
                throw GcpException.unavailable("worker did not become ready");
            }
        }

        @Override
        public void stopWorker(String connectCluster, StoredCluster kafkaCluster, boolean eraseState) {
            calls.add("stop " + connectCluster + " erase=" + eraseState);
        }

        @Override
        public void createConnector(String connectCluster, String connectorId, Map<String, String> configs) {
            calls.add("create " + connectorId + " " + configs);
            if (failConnectorWrite != null) {
                throw failConnectorWrite;
            }
            states.put(connectorId, ConnectorState.RUNNING);
        }

        @Override
        public void updateConnector(String connectCluster, String connectorId, Map<String, String> configs) {
            calls.add("update " + connectorId + " " + configs);
            if (failConnectorWrite != null) {
                throw failConnectorWrite;
            }
        }

        @Override
        public void deleteConnector(String connectCluster, String connectorId) {
            calls.add("delete " + connectorId);
        }

        @Override
        public void transitionConnector(String connectCluster, String connectorId, ConnectorState target) {
            calls.add("transition " + connectorId + " " + target);
        }

        @Override
        public void restartConnector(String connectCluster, String connectorId) {
            calls.add("restart " + connectorId);
        }

        @Override
        public Optional<ConnectorState> connectorState(String connectCluster, String connectorId) {
            return reachable ? Optional.ofNullable(states.get(connectorId)) : Optional.empty();
        }

        @Override
        public Optional<Map<String, ConnectorState>> connectorStates(String connectCluster) {
            return reachable ? Optional.of(Map.of("sink", states.getOrDefault("sink", ConnectorState.RUNNING)))
                    : Optional.empty();
        }

        @Override
        public boolean isRunning(String connectCluster) {
            return running;
        }

        @Override
        public void stopWorkersOn(StoredCluster kafkaCluster) {
            calls.add("stop workers on " + kafkaCluster.getName());
        }
    }
}

package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.LocationCatalog;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.kafka.model.AclEntry;
import io.floci.gcp.services.kafka.model.ClusterState;
import io.floci.gcp.services.kafka.model.StoredAcl;
import io.floci.gcp.services.kafka.model.StoredCluster;
import io.floci.gcp.services.kafka.model.StoredConsumerGroup;
import io.floci.gcp.services.kafka.model.StoredTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Docker path of the cluster operations. Background work is queued on a hand-run executor, so
 * each test decides exactly when provisioning or teardown happens relative to other calls.
 */
class KafkaClusterLifecycleServiceTest {

    private static final String P = "p";
    private static final String L = "us-central1";
    private static final String NAME = "projects/p/locations/us-central1/clusters/c1";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final List<Runnable> background = new ArrayList<>();
    private InMemoryStorage<String, StoredCluster> clusters;
    private InMemoryStorage<String, StoredTopic> topics;
    private InMemoryStorage<String, StoredAcl> acls;
    private InMemoryStorage<String, String> operationStore;
    private KafkaOperations operations;
    private RedpandaManager redpanda;
    private KafkaConnectDataPlane connect;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        clusters = new InMemoryStorage<>();
        topics = new InMemoryStorage<>();
        acls = new InMemoryStorage<>();
        operationStore = new InMemoryStorage<>();
        operations = new KafkaOperations(operationStore, MAPPER);
        redpanda = mock(RedpandaManager.class);
        connect = mock(KafkaConnectDataPlane.class);
        config = mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(config.services().kafka().mock()).thenReturn(false);
    }

    private KafkaService service(Duration readinessTimeout) {
        return new KafkaService(clusters, topics, new InMemoryStorage<String, StoredConsumerGroup>(), acls, config,
                new ServiceRegistry(), redpanda, connect, LocationCatalog.lenient(), operations,
                background::add, readinessTimeout);
    }

    private void runBackground() {
        List<Runnable> queued = new ArrayList<>(background);
        background.clear();
        queued.forEach(Runnable::run);
    }

    private ObjectNode operation(ObjectNode returned) {
        return (ObjectNode) readTree(operations.find(returned.get("name").asText()).orElseThrow());
    }

    private static ObjectNode readTree(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertFailedPrecondition(Runnable call) {
        GcpException e = assertThrows(GcpException.class, call::run);
        assertEquals(400, e.getHttpStatus());
        assertEquals("FAILED_PRECONDITION", e.getGcpStatus());
    }

    private KafkaService activeCluster() {
        when(redpanda.isReady(any())).thenReturn(true);
        KafkaService service = service(Duration.ofSeconds(5));
        service.createCluster(P, L, "c1", Map.of());
        runBackground();
        assertEquals(ClusterState.ACTIVE, service.getCluster(P, L, "c1").getState());
        return service;
    }

    @Test
    void createAnswersARunningOperationAndFinishesItOnceTheBrokerIsReady() {
        when(redpanda.isReady(any())).thenReturn(true);
        KafkaService service = service(Duration.ofSeconds(5));

        ObjectNode returned = service.createCluster(P, L, "c1", Map.of());

        assertFalse(returned.path("done").asBoolean());
        assertTrue(returned.get("name").asText().startsWith("projects/p/locations/us-central1/operations/"));
        assertEquals(KafkaOperations.METADATA_TYPE, returned.at("/metadata/@type").asText());
        assertEquals(NAME, returned.at("/metadata/target").asText());
        assertEquals("create", returned.at("/metadata/verb").asText());
        assertEquals(ClusterState.CREATING, service.getCluster(P, L, "c1").getState());
        assertFailedPrecondition(() -> service.createTopic(P, L, "c1", "t", Map.of()));

        runBackground();

        ObjectNode finished = operation(returned);
        assertTrue(finished.get("done").asBoolean());
        assertTrue(finished.at("/metadata/endTime").isTextual());
        assertEquals(KafkaOperations.CLUSTER_TYPE, finished.at("/response/@type").asText());
        assertEquals(NAME, finished.at("/response/name").asText());
        assertEquals("ACTIVE", finished.at("/response/state").asText());
        assertEquals(ClusterState.ACTIVE, service.getCluster(P, L, "c1").getState());
        verify(redpanda).startContainer(any());
        service.createTopic(P, L, "c1", "t", Map.of());
    }

    @Test
    void aBrokerThatNeverBecomesReadyFailsTheOperationAndRemovesTheCluster() {
        when(redpanda.isReady(any())).thenReturn(false);
        KafkaService service = service(Duration.ZERO);
        ObjectNode returned = service.createCluster(P, L, "c1", Map.of());

        runBackground();

        ObjectNode finished = operation(returned);
        assertTrue(finished.get("done").asBoolean());
        assertEquals(KafkaOperations.DEADLINE_EXCEEDED, finished.at("/error/code").asInt());
        assertFalse(finished.has("response"));
        assertTrue(clusters.get(NAME).isEmpty());
        verify(redpanda).removeBroker(any());
        service.createCluster(P, L, "c1", Map.of());
    }

    @Test
    void aBrokerThatFailsToStartFailsTheOperationAndRemovesTheCluster() {
        doThrow(new IllegalStateException("no such image")).when(redpanda).startContainer(any());
        KafkaService service = service(Duration.ofSeconds(5));
        ObjectNode returned = service.createCluster(P, L, "c1", Map.of());

        runBackground();

        ObjectNode finished = operation(returned);
        assertEquals(KafkaOperations.INTERNAL, finished.at("/error/code").asInt());
        assertTrue(finished.at("/error/message").asText().contains("no such image"));
        assertTrue(clusters.get(NAME).isEmpty());
    }

    @Test
    void aFailedCreateWhoseBrokerCannotBeRemovedKeepsTheClusterToDeleteAgain() {
        when(redpanda.isReady(any())).thenReturn(false);
        doThrow(new IllegalStateException("docker is gone")).when(redpanda).removeBroker(any());
        KafkaService service = service(Duration.ZERO);
        ObjectNode returned = service.createCluster(P, L, "c1", Map.of());

        runBackground();

        ObjectNode finished = operation(returned);
        assertEquals(KafkaOperations.DEADLINE_EXCEEDED, finished.at("/error/code").asInt());
        assertTrue(finished.at("/error/message").asText().contains("delete it to retry"));
        assertEquals(ClusterState.ACTIVE, service.getCluster(P, L, "c1").getState());

        service.shutdown();
        verify(redpanda).stopContainer(any());

        doNothing().when(redpanda).removeBroker(any());
        service.deleteCluster(P, L, "c1");
        runBackground();
        assertTrue(clusters.get(NAME).isEmpty());
    }

    @Test
    void aClusterStillBeingCreatedCannotBeUpdatedOrDeleted() {
        when(redpanda.isReady(any())).thenReturn(true);
        KafkaService service = service(Duration.ofSeconds(5));
        service.createCluster(P, L, "c1", Map.of());

        assertFailedPrecondition(() -> service.updateCluster(P, L, "c1", Map.of()));
        assertFailedPrecondition(() -> service.deleteCluster(P, L, "c1"));
        assertEquals("ALREADY_EXISTS",
                assertThrows(GcpException.class, () -> service.createCluster(P, L, "c1", Map.of())).getGcpStatus());
    }

    @Test
    void updateIsMetadataOnlyAndFinishesAtOnce() {
        KafkaService service = activeCluster();

        ObjectNode returned = service.updateCluster(P, L, "c1", Map.of("capacityConfig", Map.of("vcpuCount", 6)));

        assertTrue(returned.get("done").asBoolean());
        assertEquals("update", returned.at("/metadata/verb").asText());
        assertEquals(6, returned.at("/response/vcpuCount").asLong());
        assertTrue(operations.find(returned.get("name").asText()).isPresent());
        assertTrue(background.isEmpty());
    }

    @Test
    void deleteAnswersARunningOperationThenRemovesTheClusterAndEveryChild() {
        KafkaService service = activeCluster();
        service.createTopic(P, L, "c1", "t", Map.of());
        StoredAcl acl = new StoredAcl();
        acl.setAclEntries(List.of(new AclEntry("User:*", "ALLOW", "READ", "*")));
        service.createAcl(P, L, "c1", "topic/t", acl);

        ObjectNode returned = service.deleteCluster(P, L, "c1");

        assertFalse(returned.path("done").asBoolean());
        assertEquals("delete", returned.at("/metadata/verb").asText());
        assertEquals(ClusterState.DELETING, service.getCluster(P, L, "c1").getState());
        assertFailedPrecondition(() -> service.createTopic(P, L, "c1", "t2", Map.of()));
        assertFailedPrecondition(() -> service.deleteTopic(P, L, "c1", "t"));
        assertFailedPrecondition(() -> service.addAclEntry(P, L, "c1", "topic/t",
                new AclEntry("User:*", "ALLOW", "WRITE", "*")));
        assertFailedPrecondition(() -> service.updateCluster(P, L, "c1", Map.of()));
        assertFailedPrecondition(() -> service.deleteCluster(P, L, "c1"));

        runBackground();

        ObjectNode finished = operation(returned);
        assertTrue(finished.get("done").asBoolean());
        assertEquals(KafkaOperations.EMPTY_TYPE, finished.at("/response/@type").asText());
        assertTrue(clusters.get(NAME).isEmpty());
        assertTrue(topics.scan(k -> true).isEmpty());
        assertTrue(acls.scan(k -> true).isEmpty());
        verify(connect).stopWorkersOn(any());
        verify(redpanda).removeBroker(any());
    }

    @Test
    void aFailedTeardownLeavesTheClusterActiveSoTheDeleteCanBeRetried() {
        KafkaService service = activeCluster();
        doThrow(new IllegalStateException("docker is gone")).when(redpanda).removeBroker(any());
        ObjectNode returned = service.deleteCluster(P, L, "c1");

        runBackground();

        assertEquals(KafkaOperations.INTERNAL, operation(returned).at("/error/code").asInt());
        assertEquals(ClusterState.ACTIVE, service.getCluster(P, L, "c1").getState());
        service.createTopic(P, L, "c1", "t", Map.of());
    }

    @Test
    void anOperationDeletedWhileRunningStaysDeleted() {
        when(redpanda.isReady(any())).thenReturn(true);
        KafkaService service = service(Duration.ofSeconds(5));
        ObjectNode returned = service.createCluster(P, L, "c1", Map.of());
        operations.delete(returned.get("name").asText());

        runBackground();

        assertTrue(operations.find(returned.get("name").asText()).isEmpty());
        assertEquals(ClusterState.ACTIVE, service.getCluster(P, L, "c1").getState());
    }

    @Test
    void aRestartRemovesInterruptedClustersAndFinishesTheirOperations() {
        KafkaService first = activeCluster();
        ObjectNode delete = first.deleteCluster(P, L, "c1");
        ObjectNode create = first.createCluster(P, L, "c2", Map.of());
        background.clear();

        service(Duration.ofSeconds(5)).recoverInterruptedOperations();

        ObjectNode deleted = operation(delete);
        assertTrue(deleted.get("done").asBoolean());
        assertEquals(KafkaOperations.EMPTY_TYPE, deleted.at("/response/@type").asText());
        assertTrue(clusters.get(NAME).isEmpty());

        ObjectNode created = operation(create);
        assertTrue(created.get("done").asBoolean());
        assertEquals(KafkaOperations.ABORTED, created.at("/error/code").asInt());
        assertTrue(clusters.get("projects/p/locations/us-central1/clusters/c2").isEmpty());
        assertTrue(operations.unfinished().isEmpty());
        verify(redpanda, times(2)).removeBroker(any());
    }

    @Test
    void aRestartRecoversAClusterWhoseOperationWasDeleted() {
        KafkaService first = activeCluster();
        operations.delete(first.deleteCluster(P, L, "c1").get("name").asText());
        operations.delete(first.createCluster(P, L, "c2", Map.of()).get("name").asText());
        background.clear();

        service(Duration.ofSeconds(5)).recoverInterruptedOperations();

        assertTrue(clusters.scan(k -> true).isEmpty());
        verify(redpanda, times(2)).removeBroker(any());
    }

    @Test
    void aRestartThatCannotRemoveTheBrokerLeavesTheClusterActiveToDeleteAgain() {
        KafkaService first = activeCluster();
        ObjectNode delete = first.deleteCluster(P, L, "c1");
        background.clear();
        doThrow(new IllegalStateException("docker is gone")).when(redpanda).removeBroker(any());

        KafkaService restarted = service(Duration.ofSeconds(5));
        restarted.recoverInterruptedOperations();

        assertEquals(ClusterState.ACTIVE, restarted.getCluster(P, L, "c1").getState());
        assertEquals(KafkaOperations.INTERNAL, operation(delete).at("/error/code").asInt());
        assertTrue(operations.unfinished().isEmpty());

        doNothing().when(redpanda).removeBroker(any());
        restarted.deleteCluster(P, L, "c1");
        runBackground();
        assertTrue(clusters.get(NAME).isEmpty());
    }
}

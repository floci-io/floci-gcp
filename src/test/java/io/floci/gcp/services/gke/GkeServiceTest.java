package io.floci.gcp.services.gke;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.gke.model.StoredCluster;
import io.floci.gcp.services.gke.operations.GkeOperationService;
import io.floci.gcp.services.gke.operations.OperationType;
import io.floci.gcp.services.gke.operations.StoredOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GkeServiceTest {

    private static final String PROJECT = "test-project";
    private static final String LOCATION = "us-central1";

    @Mock
    EmulatorConfig config;
    @Mock
    EmulatorConfig.ServicesConfig services;
    @Mock
    EmulatorConfig.GkeServiceConfig gkeConfig;
    @Mock
    GkeClusterManager clusterManager;

    private GkeService service;

    @BeforeEach
    void setUp() {
        when(config.services()).thenReturn(services);
        when(services.gke()).thenReturn(gkeConfig);
        when(gkeConfig.mock()).thenReturn(true);
        when(config.baseUrl()).thenReturn("http://localhost:4588");

        GkeOperationService operationService =
                new GkeOperationService(new InMemoryStorage<String, StoredOperation>());
        service = new GkeService(new InMemoryStorage<String, StoredCluster>(), config,
                clusterManager, operationService, null);
    }

    @Test
    void createClusterReturnsDoneOperationAndClusterIsRunning() {
        StoredOperation op = service.createCluster(PROJECT, LOCATION, Map.of("name", "my-cluster"));

        assertNotNull(op);
        assertEquals(OperationType.CREATE_CLUSTER, op.getOperationType());
        assertEquals("DONE", op.getStatus());
        assertTrue(op.getName().startsWith("operation-"));

        StoredCluster cluster = service.getCluster(PROJECT, LOCATION, "my-cluster");
        assertEquals("RUNNING", cluster.getStatus());
        assertEquals(LOCATION, cluster.getLocation());
        assertNotNull(cluster.getCurrentMasterVersion());
    }

    @Test
    void createClusterRejectsMissingName() {
        assertThrows(GcpException.class,
                () -> service.createCluster(PROJECT, LOCATION, Map.of()));
    }

    @Test
    void createClusterRejectsDuplicate() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "dup"));
        assertThrows(GcpException.class,
                () -> service.createCluster(PROJECT, LOCATION, Map.of("name", "dup")));
    }

    @Test
    void listClustersIsScopedToProjectAndLocation() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "a"));
        service.createCluster(PROJECT, LOCATION, Map.of("name", "b"));
        service.createCluster(PROJECT, "europe-west1", Map.of("name", "c"));

        List<StoredCluster> central = service.listClusters(PROJECT, LOCATION);
        assertEquals(2, central.size());
        assertTrue(service.listClusters("other-project", LOCATION).isEmpty());
    }

    @Test
    void getOperationResolvesByName() {
        StoredOperation op = service.createCluster(PROJECT, LOCATION, Map.of("name", "with-op"));
        assertEquals(op.getName(), service.getOperation(op.getName()).getName());
    }

    @Test
    void deleteClusterRemovesItAndReturnsOperation() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "to-delete"));
        StoredOperation op = service.deleteCluster(PROJECT, LOCATION, "to-delete");

        assertEquals(OperationType.DELETE_CLUSTER, op.getOperationType());
        assertThrows(GcpException.class, () -> service.getCluster(PROJECT, LOCATION, "to-delete"));
    }
}

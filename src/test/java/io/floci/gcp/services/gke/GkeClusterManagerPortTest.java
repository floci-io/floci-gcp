package io.floci.gcp.services.gke;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import io.floci.gcp.core.common.docker.PortAllocator;
import io.floci.gcp.services.gke.model.StoredCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The k3s API server host port comes from a single-port range here, so a second allocation only
 * succeeds when the cluster manager has released the first one.
 */
class GkeClusterManagerPortTest {

    private ContainerLifecycleManager lifecycleManager;
    private EmulatorConfig config;
    private PortAllocator portAllocator;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        lifecycleManager = mock(ContainerLifecycleManager.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().gke().defaultImage()).thenReturn("rancher/k3s:test");
        when(config.services().gke().apiServerBasePort()).thenReturn(port);
        when(config.services().gke().apiServerMaxPort()).thenReturn(port);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        portAllocator = new PortAllocator();
    }

    @Test
    void failedStartReleasesTheHostPort() {
        doThrow(new RuntimeException("docker unavailable")).when(lifecycleManager).removeIfExists(anyString());
        StoredCluster cluster = cluster();

        assertThrows(RuntimeException.class, () -> manager().startCluster(cluster));

        assertEquals(0, cluster.getHostPort());
        assertEquals(port, allocate());
    }

    @Test
    void startFailingAfterTheContainerExistsRemovesItAndReleasesTheHostPort() {
        when(lifecycleManager.create(org.mockito.ArgumentMatchers.any())).thenReturn("container-1");
        when(lifecycleManager.startCreated(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("endpoint inspection timed out"));
        StoredCluster cluster = cluster();

        assertThrows(RuntimeException.class,
                () -> manager(mock(ContainerBuilder.class, RETURNS_DEEP_STUBS)).startCluster(cluster));

        verify(lifecycleManager).stopAndRemove("container-1", null);
        assertEquals(null, cluster.getContainerId());
        assertEquals(0, cluster.getHostPort());
        assertEquals(port, allocate());
    }

    @Test
    void stopClusterReleasesTheHostPort() {
        StoredCluster cluster = runningCluster();
        when(config.services().gke().keepRunningOnShutdown()).thenReturn(false);

        manager().stopCluster(cluster);

        verify(lifecycleManager).stopAndRemove("container-1", null);
        assertEquals(port, allocate());
    }

    @Test
    void clusterLeftRunningKeepsTheHostPort() {
        StoredCluster cluster = runningCluster();
        when(config.services().gke().keepRunningOnShutdown()).thenReturn(true);

        manager().stopCluster(cluster);

        assertThrows(RuntimeException.class, this::allocate);
    }

    private StoredCluster runningCluster() {
        assertEquals(port, allocate());
        StoredCluster cluster = cluster();
        cluster.setHostPort(port);
        cluster.setContainerId("container-1");
        cluster.setVolumeName("floci-gcp-gke-p1-c1");
        return cluster;
    }

    private int allocate() {
        return portAllocator.allocate(port, port);
    }

    private GkeClusterManager manager() {
        return manager(mock(ContainerBuilder.class));
    }

    private GkeClusterManager manager(ContainerBuilder containerBuilder) {
        return new GkeClusterManager(containerBuilder, lifecycleManager,
                mock(ContainerDetector.class), portAllocator, mock(DockerHostResolver.class), config);
    }

    private static StoredCluster cluster() {
        StoredCluster cluster = new StoredCluster();
        cluster.setName("c1");
        cluster.setProject("p1");
        return cluster;
    }
}

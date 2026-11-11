package io.floci.gcp.services.kafka;

import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.services.kafka.model.StoredCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RedpandaManager#removeBroker} reports Docker failures, unlike the shared cleanup helpers
 * that only log them, so a cluster delete cannot finish over a broker that is still there.
 */
class RedpandaBrokerRemovalServiceTest {

    private ContainerLifecycleManager lifecycleManager;
    private EmulatorConfig config;
    private RedpandaManager manager;
    private StoredCluster cluster;

    @BeforeEach
    void setUp() {
        lifecycleManager = mock(ContainerLifecycleManager.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.storage().mode()).thenReturn("memory");
        manager = new RedpandaManager(mock(ContainerBuilder.class), lifecycleManager, mock(ContainerDetector.class),
                config);
        cluster = new StoredCluster("projects/p/locations/us-central1/clusters/c1");
        cluster.setVolumeName("floci-gcp-kafka-c1");
    }

    @Test
    void aDockerFailureRemovingTheContainerIsThrown() throws Exception {
        when(lifecycleManager.runDockerApi(startsWith("remove Redpanda container"), any()))
                .thenThrow(new IllegalStateException("Cannot connect to the Docker daemon"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> manager.removeBroker(cluster));

        assertEquals("Cannot connect to the Docker daemon", e.getMessage());
        verify(lifecycleManager, never()).runDockerApi(startsWith("remove Docker volume"), any());
    }

    @Test
    void aDockerFailureRemovingTheVolumeIsThrown() throws Exception {
        when(lifecycleManager.runDockerApi(startsWith("remove Docker volume"), any()))
                .thenThrow(new IllegalStateException("volume is in use"));

        assertThrows(IllegalStateException.class, () -> manager.removeBroker(cluster));
    }

    @Test
    void aContainerAndVolumeThatAreAlreadyGoneAreNotAnError() throws Exception {
        when(lifecycleManager.runDockerApi(anyString(), any())).thenThrow(new NotFoundException("No such container"));

        assertDoesNotThrow(() -> manager.removeBroker(cluster));

        verify(lifecycleManager).runDockerApi(startsWith("remove Docker volume floci-gcp-kafka-c1"),
                any(Callable.class));
    }

    @Test
    void aRetainedVolumeIsLeftInPlace() throws Exception {
        when(config.storage().mode()).thenReturn("persistent");
        when(config.storage().pruneVolumesOnDelete()).thenReturn(false);

        manager.removeBroker(cluster);

        verify(lifecycleManager).runDockerApi(startsWith("remove Redpanda container"), any());
        verify(lifecycleManager, never()).runDockerApi(startsWith("remove Docker volume"), any());
    }
}

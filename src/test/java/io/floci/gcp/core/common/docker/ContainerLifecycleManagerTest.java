package io.floci.gcp.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveVolumeCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Closeable;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerTest {

    @Mock
    DockerClientProducer dockerClients;

    @Mock
    DockerClient dockerClient;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    PortAllocator portAllocator;

    @Mock
    ImageCacheService imageCacheService;

    @Mock
    RemoveContainerCmd removeContainerCmd;

    @Mock
    InspectVolumeCmd inspectVolumeCmd;

    @Mock
    CreateVolumeCmd createVolumeCmd;

    @Mock
    RemoveVolumeCmd removeVolumeCmd;

    @Mock
    Closeable logStream;

    @Test
    void forceRemoveClosesLogsAndRemovesContainerWithoutGracefulStop() throws Exception {
        when(dockerClient.removeContainerCmd("container-1")).thenReturn(removeContainerCmd);
        when(removeContainerCmd.withForce(true)).thenReturn(removeContainerCmd);

        manager().forceRemove("container-1", logStream);

        InOrder inOrder = inOrder(logStream, dockerClient, removeContainerCmd);
        inOrder.verify(logStream).close();
        inOrder.verify(dockerClient).removeContainerCmd("container-1");
        inOrder.verify(removeContainerCmd).withForce(true);
        inOrder.verify(removeContainerCmd).exec();
        verify(dockerClient, never()).stopContainerCmd(anyString());
    }

    @Test
    void forceRemoveToleratesDockerRemovalFailures() {
        when(dockerClient.removeContainerCmd("container-1")).thenReturn(removeContainerCmd);
        when(removeContainerCmd.withForce(true)).thenReturn(removeContainerCmd);
        when(removeContainerCmd.exec()).thenThrow(new RuntimeException("docker unavailable"));

        assertDoesNotThrow(() -> manager().forceRemove("container-1", null));
    }

    @Test
    void ensureVolumeCreatesMissingVolume() {
        when(dockerClient.inspectVolumeCmd("volume-1")).thenReturn(inspectVolumeCmd);
        when(inspectVolumeCmd.exec()).thenThrow(new NotFoundException("missing"));
        when(dockerClient.createVolumeCmd()).thenReturn(createVolumeCmd);
        when(createVolumeCmd.withName("volume-1")).thenReturn(createVolumeCmd);
        when(createVolumeCmd.withLabels(Map.of("floci-gcp", "true"))).thenReturn(createVolumeCmd);

        manager().ensureVolume("volume-1");

        verify(createVolumeCmd).exec();
    }

    @Test
    void volumeInspectTimeoutResetsDockerClient() {
        when(dockerClient.inspectVolumeCmd("volume-1")).thenReturn(inspectVolumeCmd);
        when(inspectVolumeCmd.exec()).thenAnswer(invocation -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        assertThrows(ContainerLifecycleManager.DockerApiTimeoutException.class,
                () -> manager(Duration.ofMillis(50)).volumeExists("volume-1"));

        verify(dockerClients).reset(contains("inspect Docker volume volume-1"));
    }

    @Test
    void removeVolumeUsesBoundedDockerApiCall() {
        when(dockerClient.removeVolumeCmd("volume-1")).thenReturn(removeVolumeCmd);

        manager().removeVolume("volume-1");

        verify(removeVolumeCmd).exec();
    }

    private ContainerLifecycleManager manager() {
        return manager(Duration.ofSeconds(5));
    }

    private ContainerLifecycleManager manager(Duration apiTimeout) {
        when(dockerClients.client()).thenReturn(dockerClient);
        when(dockerClients.apiTimeout()).thenReturn(apiTimeout);
        return new ContainerLifecycleManager(dockerClients, containerDetector, portAllocator, imageCacheService);
    }
}

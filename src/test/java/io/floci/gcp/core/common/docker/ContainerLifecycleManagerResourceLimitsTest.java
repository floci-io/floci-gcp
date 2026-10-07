package io.floci.gcp.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Info;
import io.floci.gcp.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerResourceLimitsTest {

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
    EmulatorConfig config;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
    }

    @Test
    void memoryLimitAlsoCapsSwapAtTheSameValue() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(spec(268_435_456L, null));

        HostConfig hostConfig = capturedHostConfig(createCmd);
        assertEquals(268_435_456L, hostConfig.getMemory());
        assertEquals(268_435_456L, hostConfig.getMemorySwap());
    }

    @Test
    void cpuLimitWithinHostCpuCountPassesThrough() {
        CreateContainerCmd createCmd = stubCreateContainer();
        stubHostCpus(2);

        manager().create(spec(null, 1_500_000_000L));

        assertEquals(1_500_000_000L, capturedHostConfig(createCmd).getNanoCPUs());
    }

    @Test
    void cpuLimitAboveHostCpuCountIsClampedToHostCpuCount() {
        CreateContainerCmd createCmd = stubCreateContainer();
        stubHostCpus(2);

        manager().create(spec(null, 8_000_000_000L));

        assertEquals(2_000_000_000L, capturedHostConfig(createCmd).getNanoCPUs());
    }

    @Test
    void noLimitsLeavesMemoryAndCpuUnsetWithoutQueryingDockerInfo() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(new ContainerSpec("busybox:stable"));

        HostConfig hostConfig = capturedHostConfig(createCmd);
        assertNull(hostConfig.getMemory());
        assertNull(hostConfig.getMemorySwap());
        assertNull(hostConfig.getNanoCPUs());
        verify(dockerClient, never()).infoCmd();
    }

    private static ContainerSpec spec(Long memoryBytes, Long nanoCpus) {
        return new ContainerSpec("busybox:stable", null, List.of(), null, null, memoryBytes, Map.of(), List.of(), null,
                List.of(), List.of(), List.of(), Map.of(), null, false, null, List.of(), null, null, List.of(),
                List.of(), nanoCpus);
    }

    private ContainerLifecycleManager manager() {
        when(dockerClients.client()).thenReturn(dockerClient);
        when(dockerClients.apiTimeout()).thenReturn(Duration.ofSeconds(5));
        return new ContainerLifecycleManager(dockerClients, containerDetector, portAllocator, imageCacheService, config);
    }

    private void stubHostCpus(int ncpu) {
        InfoCmd infoCmd = mock(InfoCmd.class);
        when(dockerClient.infoCmd()).thenReturn(infoCmd);
        Info info = mock(Info.class);
        when(info.getNCPU()).thenReturn(ncpu);
        when(infoCmd.exec()).thenReturn(info);
    }

    private CreateContainerCmd stubCreateContainer() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("container-id");
        when(createCmd.exec()).thenReturn(response);
        return createCmd;
    }

    private static HostConfig capturedHostConfig(CreateContainerCmd createCmd) {
        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        return hostConfig.getValue();
    }
}

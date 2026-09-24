package io.floci.gcp.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Host port bindings as {@link ContainerLifecycleManager#create} hands them to Docker: ports listed
 * in {@code loopbackPorts} are published on {@code 127.0.0.1} only, every other port on all host
 * interfaces as before.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerPortBindingTest {

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
    void aLoopbackPortIsPublishedOnLoopbackOnlyAndOtherPortsOnAllInterfaces() {
        CreateContainerCmd createCmd = stubCreateContainer();
        when(portAllocator.allocateAny()).thenReturn(40001, 40002);
        ContainerSpec spec = new ContainerBuilder(config, null, null)
                .newContainer("busybox:stable")
                .withDynamicPort(9092)
                .withLoopbackDynamicPort(8083)
                .build();

        manager().create(spec);

        Map<ExposedPort, Ports.Binding[]> bindings = capturedHostConfig(createCmd).getPortBindings().getBindings();
        assertEquals("127.0.0.1", bindings.get(ExposedPort.tcp(8083))[0].getHostIp());
        assertNull(bindings.get(ExposedPort.tcp(9092))[0].getHostIp());
        assertEquals(List.of(8083), spec.loopbackPorts());
    }

    private ContainerLifecycleManager manager() {
        when(dockerClients.client()).thenReturn(dockerClient);
        when(dockerClients.apiTimeout()).thenReturn(Duration.ofSeconds(5));
        return new ContainerLifecycleManager(dockerClients, containerDetector, portAllocator, imageCacheService, config);
    }

    private CreateContainerCmd stubCreateContainer() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("container-id");
        when(createCmd.exec()).thenReturn(response);
        return createCmd;
    }

    private HostConfig capturedHostConfig(CreateContainerCmd createCmd) {
        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        return hostConfig.getValue();
    }
}

package io.floci.gcp.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.exception.NotFoundException;
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
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Asserts the default Docker labels at the single choke point every emulator-created
 * container and volume passes through. All five container families funnel into
 * {@link ContainerLifecycleManager#create}, so asserting here covers every service
 * by construction.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerLabelsTest {

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
    void createAppliesDefaultLabelsToEveryContainer() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(new ContainerSpec("busybox:stable"));

        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-gcp"),
                capturedLabels(createCmd));
    }

    @Test
    void createMergesSpecLabelsOverDefaults() {
        CreateContainerCmd createCmd = stubCreateContainer();
        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", null, List.of(), null, null, null, Map.of(), List.of(), null,
                List.of(), List.of(), List.of(), Map.of("floci_service", "cloudrun"), null, false,
                null, List.of(), null, null, List.of());

        manager().create(spec);

        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-gcp", "floci_service", "cloudrun"),
                capturedLabels(createCmd));
    }

    @Test
    void createIncludesNamespaceLabelWhenConfigured() {
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.of("run-one"));
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(new ContainerSpec("busybox:stable"));

        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-gcp", "floci_namespace", "run-one"),
                capturedLabels(createCmd));
    }

    @Test
    void ensureVolumeAppliesTheSameDefaultLabels() {
        InspectVolumeCmd inspectVolumeCmd = mock(InspectVolumeCmd.class);
        when(dockerClient.inspectVolumeCmd("volume-1")).thenReturn(inspectVolumeCmd);
        when(inspectVolumeCmd.exec()).thenThrow(new NotFoundException("missing"));
        CreateVolumeCmd createVolumeCmd = mock(CreateVolumeCmd.class, RETURNS_SELF);
        when(dockerClient.createVolumeCmd()).thenReturn(createVolumeCmd);

        manager().ensureVolume("volume-1");

        ArgumentCaptor<Map<String, String>> labels = labelsCaptor();
        verify(createVolumeCmd).withLabels(labels.capture());
        assertEquals(Map.of("floci", "true", "floci_emulator", "floci-gcp"), labels.getValue());
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

    private Map<String, String> capturedLabels(CreateContainerCmd createCmd) {
        ArgumentCaptor<Map<String, String>> labels = labelsCaptor();
        verify(createCmd).withLabels(labels.capture());
        return labels.getValue();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> labelsCaptor() {
        return ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
    }
}

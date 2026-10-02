package io.floci.gcp.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import io.floci.gcp.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void createStampsLegacyAliasesNextToResourceIdentityLabels() {
        CreateContainerCmd createCmd = stubCreateContainer();
        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", null, List.of(), null, null, null, Map.of(), List.of(), null,
                List.of(), List.of(), List.of(),
                ContainerStorageHelper.resourceIdentityLabels("cloudsql", "pg-main", "p1", "us-central1"), null, false,
                null, List.of(), null, null, List.of());

        manager().create(spec);

        Map<String, String> expected = new HashMap<>();
        expected.put("floci", "true");
        expected.put("floci_emulator", "floci-gcp");
        expected.put("io.floci", "gcp");
        expected.put("io.floci.service", "cloudsql");
        expected.put("io.floci.resource-id", "pg-main");
        expected.put("io.floci.project", "p1");
        expected.put("io.floci.location", "us-central1");
        expected.put("floci_service", "cloudsql");
        expected.put("floci_resource", "pg-main");
        expected.put("floci_project", "p1");
        expected.put("floci_location", "us-central1");
        assertEquals(expected, capturedLabels(createCmd));
    }

    @Test
    void listContainersByLabelsQueriesNewAndLegacyKeysAndMergesById() {
        ListContainersCmd newQuery = mock(ListContainersCmd.class, RETURNS_SELF);
        ListContainersCmd legacyQuery = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(newQuery, legacyQuery);
        Container both = container("both");
        List<Container> newResults = List.of(both, container("new-only"));
        List<Container> legacyResults = List.of(container("both"), container("legacy-only"));
        when(newQuery.exec()).thenReturn(newResults);
        when(legacyQuery.exec()).thenReturn(legacyResults);

        List<Container> containers = manager().listContainersByLabels("list",
                Map.of("floci", "true", "io.floci.service", "cloudrun"));

        assertEquals(List.of("both", "new-only", "legacy-only"), containers.stream().map(Container::getId).toList());
        assertSame(both, containers.getFirst());
        verify(newQuery).withLabelFilter(Map.of("floci", "true", "io.floci.service", "cloudrun"));
        verify(legacyQuery).withLabelFilter(Map.of("floci", "true", "floci_service", "cloudrun"));
    }

    @Test
    void listContainersByLabelsKeepsNewKeyResultsWhenTheLegacyQueryFails() {
        ListContainersCmd newQuery = mock(ListContainersCmd.class, RETURNS_SELF);
        ListContainersCmd legacyQuery = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(newQuery, legacyQuery);
        List<Container> newResults = List.of(container("new-only"));
        when(newQuery.exec()).thenReturn(newResults);
        when(legacyQuery.exec()).thenThrow(new IllegalStateException("docker unavailable"));

        List<Container> containers = manager().listContainersByLabels("list",
                Map.of("floci", "true", "io.floci.service", "cloudrun"));

        assertEquals(List.of("new-only"), containers.stream().map(Container::getId).toList());
    }

    @Test
    void listContainersByLabelsRunsOneQueryWithoutAliasedKeys() {
        ListContainersCmd query = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(query);
        List<Container> results = List.of(container("c1"));
        when(query.exec()).thenReturn(results);

        List<Container> containers = manager().listContainersByLabels("list", Map.of("floci", "true"));

        assertEquals(1, containers.size());
        verify(dockerClient).listContainersCmd();
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

    private static Container container(String id) {
        Container container = mock(Container.class);
        lenient().when(container.getId()).thenReturn(id);
        return container;
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

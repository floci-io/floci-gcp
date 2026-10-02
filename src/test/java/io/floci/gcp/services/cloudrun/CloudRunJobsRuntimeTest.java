package io.floci.gcp.services.cloudrun;

import com.github.dockerjava.api.model.Container;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudRunJobsRuntimeTest {

    private static final String TASK = "projects/p/locations/l/jobs/j/executions/j-abc/tasks/j-abc-task0";

    private ContainerLifecycleManager lifecycleManager;
    private EmulatorConfig config;
    private CloudRunJobsRuntime runtime;

    @BeforeEach
    void setUp() {
        lifecycleManager = mock(ContainerLifecycleManager.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        runtime = new CloudRunJobsRuntime(mock(CloudRunRuntimeService.class), lifecycleManager,
                mock(ImageCacheService.class), config);
    }

    @Test
    void unnamespacedInstanceLeavesNamespacedTaskContainerAlone() {
        namespace(null);
        listed(taskContainer("other-ns", "other"));

        runtime.removeOrphanedContainers();

        verify(lifecycleManager, never()).forceRemove(any(), any());
    }

    @Test
    void namespacedInstanceLeavesUnnamespacedAndForeignTaskContainersAlone() {
        namespace("mine");
        listed(taskContainer("no-ns", null), taskContainer("other-ns", "other"));

        runtime.removeOrphanedContainers();

        verify(lifecycleManager, never()).forceRemove(any(), any());
    }

    @Test
    void namespacedInstanceRemovesItsOwnTaskContainer() {
        namespace("mine");
        listed(taskContainer("mine-ns", "mine"), taskContainer("other-ns", "other"));

        runtime.removeOrphanedContainers();

        verify(lifecycleManager).forceRemove(eq("mine-ns"), isNull());
        verify(lifecycleManager, never()).forceRemove(eq("other-ns"), any());
    }

    @Test
    void unnamespacedInstanceRemovesUnnamespacedTaskContainer() {
        namespace(null);
        listed(taskContainer("no-ns", null), taskContainer("other-ns", "other"));

        runtime.removeOrphanedContainers();

        verify(lifecycleManager).forceRemove(eq("no-ns"), isNull());
        verify(lifecycleManager, never()).forceRemove(eq("other-ns"), any());
    }

    @Test
    void nonTaskContainerIsNotRemoved() {
        namespace(null);
        Container service = mock(Container.class);
        when(service.getId()).thenReturn("service");
        when(service.getLabels()).thenReturn(Map.of("floci_resource", "projects/p/locations/l/services/s"));
        listed(service);

        runtime.removeOrphanedContainers();

        verify(lifecycleManager, never()).forceRemove(any(), any());
    }

    private void namespace(String namespace) {
        when(config.docker().resourceNamespace()).thenReturn(Optional.ofNullable(namespace));
    }

    private void listed(Container... containers) {
        doReturn(List.of(containers)).when(lifecycleManager).runDockerApi(anyString(), any());
    }

    private static Container taskContainer(String id, String namespace) {
        Map<String, String> labels = new HashMap<>();
        labels.put("floci", "true");
        labels.put("floci_emulator", "floci-gcp");
        labels.put("floci_service", "cloudrun");
        labels.put("floci_resource", TASK);
        if (namespace != null) {
            labels.put("floci_namespace", namespace);
        }
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(labels);
        return container;
    }
}

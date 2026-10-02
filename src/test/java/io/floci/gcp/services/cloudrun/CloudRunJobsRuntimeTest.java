package io.floci.gcp.services.cloudrun;

import com.github.dockerjava.api.model.Container;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudRunJobsRuntimeTest {

    private static final String EXECUTION = "projects/p/locations/l/jobs/j/executions/j-abc";

    private ContainerLifecycleManager lifecycleManager;
    private EmulatorConfig config;
    private CloudRunJobsRuntime runtime;

    @BeforeEach
    void setUp() {
        lifecycleManager = mock(ContainerLifecycleManager.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        namespace(null);
        runtime = new CloudRunJobsRuntime(mock(CloudRunRuntimeService.class), lifecycleManager,
                mock(ImageCacheService.class), config);
    }

    @Test
    void orphanSweepQueriesTheNewServiceKey() {
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenReturn(List.of());

        runtime.removeOrphanedContainers();

        verify(lifecycleManager).listContainersByLabels(anyString(), eq(Map.of(
                "floci", "true", "floci_emulator", "floci-gcp", "io.floci.service", "cloudrun")));
    }

    @Test
    void orphanSweepRemovesTaskContainersLabelledWithNewKeysOnly() {
        sweep(container("new-only", CloudRunRuntimeService.workloadLabels("p", "l", task(0))));

        verify(lifecycleManager).forceRemove(eq("new-only"), isNull());
    }

    @Test
    void orphanSweepRemovesTaskContainersLabelledWithLegacyKeysOnly() {
        sweep(container("legacy-only", Map.of("floci_service", "cloudrun", "floci_resource", task(1))));

        verify(lifecycleManager).forceRemove(eq("legacy-only"), isNull());
    }

    @Test
    void orphanSweepRemovesTaskContainersWhoseNewAndLegacyKeysAgree() {
        sweep(container("both-agree", ContainerStorageHelper.withLegacyAliases(
                CloudRunRuntimeService.workloadLabels("p", "l", task(2)))));

        verify(lifecycleManager).forceRemove(eq("both-agree"), isNull());
    }

    @Test
    void orphanSweepLeavesContainersWhoseNewAndLegacyKeysDisagree() {
        Map<String, String> labels = new HashMap<>(ContainerStorageHelper.withLegacyAliases(
                CloudRunRuntimeService.workloadLabels("p", "l", task(3))));
        labels.put("floci_resource", "someone-else");
        Map<String, String> service = new HashMap<>(ContainerStorageHelper.withLegacyAliases(
                CloudRunRuntimeService.workloadLabels("p", "l", task(4))));
        service.put("io.floci.service", "gke");

        sweep(container("resource-disagrees", labels), container("service-disagrees", service));

        verify(lifecycleManager, never()).forceRemove(anyString(), any());
    }

    @Test
    void orphanSweepLeavesServiceRevisionContainers() {
        String revision = "projects/p/locations/l/services/s/revisions/s-00001";
        sweep(container("revision-new", ContainerStorageHelper.withLegacyAliases(
                        CloudRunRuntimeService.workloadLabels("p", "l", revision))),
                container("revision-legacy", Map.of("floci_service", "cloudrun", "floci_resource", revision)));

        verify(lifecycleManager, never()).forceRemove(anyString(), any());
    }

    @Test
    void unnamespacedInstanceLeavesNamespacedTaskContainerAlone() {
        sweep(namespacedTask("other-ns", "other"));

        verify(lifecycleManager, never()).forceRemove(any(), any());
    }

    @Test
    void namespacedInstanceLeavesUnnamespacedAndForeignTaskContainersAlone() {
        namespace("mine");
        sweep(namespacedTask("no-ns", null), namespacedTask("other-ns", "other"));

        verify(lifecycleManager, never()).forceRemove(any(), any());
    }

    @Test
    void namespacedInstanceRemovesItsOwnTaskContainer() {
        namespace("mine");
        sweep(namespacedTask("mine-ns", "mine"), namespacedTask("other-ns", "other"));

        verify(lifecycleManager).forceRemove(eq("mine-ns"), isNull());
        verify(lifecycleManager, never()).forceRemove(eq("other-ns"), any());
    }

    @Test
    void unnamespacedInstanceRemovesUnnamespacedTaskContainer() {
        sweep(namespacedTask("no-ns", null), namespacedTask("other-ns", "other"));

        verify(lifecycleManager).forceRemove(eq("no-ns"), isNull());
        verify(lifecycleManager, never()).forceRemove(eq("other-ns"), any());
    }

    private void namespace(String namespace) {
        when(config.docker().resourceNamespace()).thenReturn(Optional.ofNullable(namespace));
    }

    private void sweep(Container... containers) {
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenReturn(List.of(containers));
        runtime.removeOrphanedContainers();
    }

    private static String task(int index) {
        return EXECUTION + "/tasks/j-abc-task" + index;
    }

    private static Container namespacedTask(String id, String namespace) {
        Map<String, String> labels = new HashMap<>(ContainerStorageHelper.withLegacyAliases(
                CloudRunRuntimeService.workloadLabels("p", "l", task(0))));
        if (namespace != null) {
            labels.put("floci_namespace", namespace);
        }
        return container(id, labels);
    }

    private static Container container(String id, Map<String, String> labels) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(labels);
        return container;
    }
}

package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.Revision;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.services.cloudrun.CloudRunWorkerPoolRuntime.DesiredWorkers;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeVolumeMount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudRunWorkerPoolRuntimeTest {

    private static final String POOL = "projects/p/locations/l/workerPools/wp";
    private static final DesiredWorkers ONE_REPLICA = new DesiredWorkers("p", "l", POOL, 1,
            Revision.newBuilder()
                    .setName(POOL + "/revisions/wp-00001-abc")
                    .addContainers(Container.newBuilder().setImage("busybox"))
                    .build(),
            1);

    private ContainerLifecycleManager lifecycleManager;
    private CloudRunRuntimeService runtimeService;
    private CloudRunWorkerPoolRuntime runtime;

    @BeforeEach
    void setUp() {
        lifecycleManager = mock(ContainerLifecycleManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudrun().execution().maxWorkerInstances()).thenReturn(2);
        runtimeService = mock(CloudRunRuntimeService.class);
        runtime = new CloudRunWorkerPoolRuntime(runtimeService, lifecycleManager, config);
    }

    @Test
    void replicaWhoseSpecCannotBeBuiltReleasesItsVolumes() {
        List<CloudRunRuntimeVolumeMount> mounts = List.of(new CloudRunRuntimeVolumeMount(
                "bucket", "", "wp-volume", "/root", null, "/data", false));
        when(runtimeService.prepareGcsVolumeMounts(anyString(), anyList(), any())).thenReturn(mounts);
        when(runtimeService.buildWorkloadSpec(anyString(), anyString(), anyString(), any(), any(), anyMap(), isNull(),
                anyList())).thenThrow(GcpException.invalidArgument("Invalid value for resources.limits.memory: abc"));

        assertThrows(GcpException.class, () -> runtime.apply(ONE_REPLICA));

        verify(runtimeService).releaseGcsVolumeMounts(mounts);
        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void shutdownRemovesStartedReplicas() {
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                "c1", Map.of()));
        runtime.apply(ONE_REPLICA);

        runtime.shutdown();

        verify(lifecycleManager).forceRemove(eq("c1"), isNull());
    }

    @Test
    void replicaWhoseStartCompletesDuringShutdownRemovesItsContainer() {
        when(lifecycleManager.createAndStart(any())).thenAnswer(invocation -> {
            runtime.shutdown();
            return new ContainerLifecycleManager.ContainerInfo("late", Map.of());
        });

        assertThrows(IllegalStateException.class, () -> runtime.apply(ONE_REPLICA));

        verify(lifecycleManager).forceRemove(eq("late"), isNull());
    }

    @Test
    void noReplicaStartsAfterShutdown() {
        runtime.shutdown();

        assertThrows(IllegalStateException.class, () -> runtime.apply(ONE_REPLICA));

        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void leftoverSweepFindsNewAndLegacyLabelledContainersAndSkipsDisagreeingOnes() {
        String newRevision = POOL + "/revisions/wp-00002-new";
        String legacyRevision = POOL + "/revisions/wp-00001-old";
        Map<String, String> disagreeing = new HashMap<>(ContainerStorageHelper.withLegacyAliases(
                CloudRunRuntimeService.workloadLabels("p", "l", POOL + "/revisions/wp-00003-odd")));
        disagreeing.put("floci_project", "other");
        List<com.github.dockerjava.api.model.Container> containers = List.of(
                container("new", ContainerStorageHelper.withLegacyAliases(
                        CloudRunRuntimeService.workloadLabels("p", "l", newRevision))),
                container("legacy", Map.of("floci_service", "cloudrun", "floci_resource", legacyRevision)),
                container("disagreeing", disagreeing),
                container("service", ContainerStorageHelper.withLegacyAliases(CloudRunRuntimeService.workloadLabels(
                        "p", "l", "projects/p/locations/l/services/s/revisions/s-00001"))));
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenReturn(containers);

        Set<String> removed = runtime.removeLeftoverContainers();

        assertEquals(Set.of(newRevision, legacyRevision), removed);
        verify(lifecycleManager).forceRemove(eq("new"), isNull());
        verify(lifecycleManager).forceRemove(eq("legacy"), isNull());
        verify(lifecycleManager, never()).forceRemove(eq("disagreeing"), any());
        verify(lifecycleManager, never()).forceRemove(eq("service"), any());
    }

    // com.github.dockerjava.api.model.Container is written fully qualified: it collides with the imported
    // com.google.cloud.run.v2.Container.
    private static com.github.dockerjava.api.model.Container container(String id, Map<String, String> labels) {
        com.github.dockerjava.api.model.Container container = mock(com.github.dockerjava.api.model.Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(labels);
        return container;
    }
}

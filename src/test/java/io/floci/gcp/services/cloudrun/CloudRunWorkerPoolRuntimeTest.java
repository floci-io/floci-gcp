package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.Revision;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.services.cloudrun.CloudRunWorkerPoolRuntime.DesiredWorkers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
    private CloudRunWorkerPoolRuntime runtime;

    @BeforeEach
    void setUp() {
        lifecycleManager = mock(ContainerLifecycleManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudrun().execution().maxWorkerInstances()).thenReturn(2);
        runtime = new CloudRunWorkerPoolRuntime(mock(CloudRunRuntimeService.class), lifecycleManager, config);
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
}

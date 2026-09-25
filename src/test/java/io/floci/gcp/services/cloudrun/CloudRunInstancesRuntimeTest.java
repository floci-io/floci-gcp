package io.floci.gcp.services.cloudrun;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeVolumeMount;
import io.floci.gcp.services.gcs.GcsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CloudRunInstancesRuntimeTest {

    private static final String INSTANCE = "projects/p1/locations/us-central1/instances/inst";
    private static final String REVISION = "projects/p1/locations/us-central1/services/svc/revisions/svc-00001";

    private ContainerLifecycleManager lifecycleManager;
    private GcsService gcsService;
    private InMemoryStorage<String, CloudRunRuntimeInstance> runtimeStore;
    private CloudRunInstancesRuntime runtime;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4588");
        lifecycleManager = mock(ContainerLifecycleManager.class);
        gcsService = mock(GcsService.class);
        runtimeStore = new InMemoryStorage<>();
        CloudRunRuntimeService runtimeService = new CloudRunRuntimeService(runtimeStore,
                mock(ContainerBuilder.class), lifecycleManager, config, gcsService);
        runtime = new CloudRunInstancesRuntime(runtimeService, lifecycleManager, config);
    }

    @Test
    void readyKeepsTheRecordOfAContainerThatExitedOnItsOwn() {
        CloudRunRuntimeInstance record = record(INSTANCE, "exited", 1, List.of());
        runtimeStore.put(INSTANCE, record);
        when(lifecycleManager.isContainerRunning("exited")).thenReturn(false);

        assertTrue(runtime.ready(INSTANCE).isEmpty());
        assertEquals(Optional.of(record), runtimeStore.get(INSTANCE));
    }

    @Test
    void readyReturnsTheResolvedEndpointWithoutRewritingTheRecord() {
        CloudRunRuntimeInstance record = record(INSTANCE, "running", 1, List.of());
        runtimeStore.put(INSTANCE, record);
        when(lifecycleManager.isContainerRunning("running")).thenReturn(true);
        when(lifecycleManager.resolveEndpoint("running", 8080, null))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo("10.0.0.5", 8080));

        CloudRunRuntimeInstance ready = runtime.ready(INSTANCE).orElseThrow();

        assertEquals("10.0.0.5", ready.endpointHost());
        assertEquals(8080, ready.endpointPort());
        assertEquals(Optional.of(record), runtimeStore.get(INSTANCE));
    }

    @Test
    void stopAllRemovesInstanceContainersAndWritesTheirGcsVolumesBack(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("volume"));
        Files.writeString(root.resolve("out.txt"), "written", StandardCharsets.UTF_8);
        CloudRunRuntimeVolumeMount mount = new CloudRunRuntimeVolumeMount("bucket", root.toString(),
                root.toString(), "/data", false);
        runtimeStore.put(INSTANCE, record(INSTANCE, "instance-container", 1, List.of(mount)));
        runtimeStore.put(REVISION, record(REVISION, "service-container", 1, List.of()));

        runtime.stopAll();

        verify(lifecycleManager).forceRemove("instance-container", null);
        verify(lifecycleManager, never()).forceRemove(eq("service-container"), any());
        verify(gcsService).putObject(eq("bucket"), eq("out.txt"), anyString(), any(byte[].class), anyString());
        assertFalse(Files.exists(root));
        assertTrue(runtimeStore.get(INSTANCE).isEmpty());
        assertTrue(runtimeStore.get(REVISION).isPresent());
    }

    @Test
    void onlyInstanceNamesAreInstanceRuntimeKeys() {
        assertTrue(CloudRunInstancesRuntime.isInstanceKey(INSTANCE));
        assertFalse(CloudRunInstancesRuntime.isInstanceKey(REVISION));
        assertFalse(CloudRunInstancesRuntime.isInstanceKey(
                "projects/p1/locations/us-central1/jobs/instances/executions/e1"));
    }

    private static CloudRunRuntimeInstance record(String key, String containerId, long createTime,
                                                  List<CloudRunRuntimeVolumeMount> mounts) {
        return new CloudRunRuntimeInstance("p1", "us-central1", key, key, "busybox", containerId, 8080, null,
                "127.0.0.1", 32768, "http://inst", "READY", createTime, createTime, null, 1000, mounts);
    }
}

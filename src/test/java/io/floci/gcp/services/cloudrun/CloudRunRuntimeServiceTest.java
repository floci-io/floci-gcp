package io.floci.gcp.services.cloudrun;

import com.github.dockerjava.api.model.MountType;
import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.ContainerPort;
import com.google.cloud.run.v2.EmptyDirVolumeSource;
import com.google.cloud.run.v2.EnvVar;
import com.google.cloud.run.v2.GCSVolumeSource;
import com.google.cloud.run.v2.Revision;
import com.google.cloud.run.v2.Service;
import com.google.cloud.run.v2.Volume;
import com.google.cloud.run.v2.VolumeMount;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.dns.EmbeddedDnsServer;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeVolumeMount;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudRunRuntimeServiceTest {

    private EmulatorConfig config;
    private ContainerLifecycleManager lifecycleManager;
    private CloudRunRuntimeService runtimeService;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(config.services().cloudrun().execution().mock()).thenReturn(false);
        when(config.services().cloudrun().execution().defaultPort()).thenReturn(8080);
        when(config.services().cloudrun().execution().startupTimeout()).thenReturn(Duration.ofSeconds(1));
        when(config.services().cloudrun().execution().requestTimeout()).thenReturn(Duration.ofSeconds(300));
        when(config.services().cloudrun().execution().containerNamePrefix()).thenReturn("floci-cloudrun");
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4588");
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("3");

        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.isLinuxHost()).thenReturn(false);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        ContainerBuilder containerBuilder = new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer);
        lifecycleManager = mock(ContainerLifecycleManager.class);
        runtimeService = new CloudRunRuntimeService(new InMemoryStorage<>(), containerBuilder,
                lifecycleManager, config);
    }

    @Test
    void buildSpecMapsCloudRunContainerAndSystemEnvWins() {
        Service service = Service.newBuilder()
                .setName("projects/p1/locations/us-central1/services/svc")
                .build();
        Revision revision = Revision.newBuilder()
                .setName(service.getName() + "/revisions/svc-00001")
                .addContainers(Container.newBuilder()
                        .setImage("gcr.io/p1/svc:latest")
                        .addEnv(EnvVar.newBuilder().setName("USER_ENV").setValue("value"))
                        .addEnv(EnvVar.newBuilder().setName("PORT").setValue("9999"))
                        .addCommand("/app/server")
                        .addArgs("--debug")
                        .setWorkingDir("/workspace")
                        .addPorts(ContainerPort.newBuilder().setContainerPort(9090)))
                .build();

        ContainerSpec spec = runtimeService.buildSpec("p1", "us-central1", service, revision,
                revision.getContainers(0), 9090, "container-name");

        assertEquals("gcr.io/p1/svc:latest", spec.image());
        assertEquals("container-name", spec.name());
        assertEquals(0, spec.portBindings().get(9090));
        assertEquals("/workspace", spec.workingDir());
        assertEquals("/app/server", spec.entrypoint().get(0));
        assertEquals("--debug", spec.cmd().get(0));
        assertTrue(spec.env().contains("USER_ENV=value"));
        assertTrue(spec.env().contains("PORT=9090"));
        assertFalse(spec.env().contains("PORT=9999"));
        assertTrue(spec.env().contains("K_SERVICE=svc"));
        assertTrue(spec.env().contains("K_REVISION=svc-00001"));
        assertEquals("cloudrun", spec.labels().get("floci-gcp.service"));
    }

    @Test
    void buildSpecMountsReadOnlyGcsVolumeSnapshot() {
        CloudRunRuntimeService service = new CloudRunRuntimeService(new InMemoryStorage<>(), containerBuilder(),
                lifecycleManager, config);
        Service cloudRunService = Service.newBuilder()
                .setName("projects/p1/locations/us-central1/services/svc")
                .build();
        Revision revision = Revision.newBuilder()
                .setName(cloudRunService.getName() + "/revisions/svc-00001")
                .addContainers(Container.newBuilder()
                        .setImage("nginx:latest"))
                .build();
        List<CloudRunRuntimeVolumeMount> mounts = List.of(new CloudRunRuntimeVolumeMount(
                "site-bucket", "", "floci-gcp-cloudrun-gcs-test", null, null,
                "/usr/share/nginx/html", true));

        ContainerSpec spec = service.buildSpec("p1", "us-central1", cloudRunService, revision,
                revision.getContainers(0), 8080, "container-name", mounts);

        assertTrue(spec.binds().isEmpty());
        assertEquals(1, spec.mounts().size());
        assertEquals(MountType.VOLUME, spec.mounts().get(0).getType());
        assertEquals("floci-gcp-cloudrun-gcs-test", spec.mounts().get(0).getSource());
        assertEquals("/usr/share/nginx/html", spec.mounts().get(0).getTarget());
        assertEquals(Boolean.TRUE, spec.mounts().get(0).getReadOnly());
        assertEquals(Boolean.TRUE, spec.mounts().get(0).getVolumeOptions().getNoCopy());
    }

    @Test
    void stopInstancesSyncsWritableGcsVolumeBeforeDeletingSnapshot() throws Exception {
        GcsService gcsService = mock(GcsService.class);
        when(gcsService.listObjects("site-bucket")).thenReturn(List.of(object("old.txt")));
        CloudRunRuntimeService service = new CloudRunRuntimeService(new InMemoryStorage<>(), containerBuilder(),
                lifecycleManager, config, gcsService);
        Path root = Files.createTempDirectory("cloudrun-gcs-test-");
        Files.writeString(root.resolve("new.txt"), "new content");
        List<CloudRunRuntimeVolumeMount> mounts = List.of(new CloudRunRuntimeVolumeMount(
                "site-bucket", root.toString(), root.toString(), "/data", false));

        service.stopInstances(List.of(instance("projects/p1/locations/us-central1/services/svc/revisions/svc-00001",
                12345, "container-id", 1, mounts)));

        verify(gcsService).putObject(eq("site-bucket"), eq("new.txt"), anyString(),
                argThat(bytes -> Arrays.equals(bytes, "new content".getBytes(StandardCharsets.UTF_8))),
                eq("http://localhost:4588"));
        verify(gcsService).deleteObject("site-bucket", "old.txt");
        assertFalse(Files.exists(root));
    }

    @Test
    void unsupportedContainerShapesFailBeforeDocker() {
        Service service = Service.newBuilder()
                .setName("projects/p1/locations/us-central1/services/svc")
                .build();
        Revision revision = Revision.newBuilder()
                .setName(service.getName() + "/revisions/svc-00001")
                .addContainers(Container.newBuilder().setImage("gcr.io/p1/one"))
                .addContainers(Container.newBuilder().setImage("gcr.io/p1/two"))
                .build();

        GcpException ex = assertThrows(GcpException.class,
                () -> runtimeService.start("p1", "us-central1", service, revision));

        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void unsupportedVolumeShapesFailBeforeDocker() {
        Revision nonGcsVolume = Revision.newBuilder()
                .addVolumes(Volume.newBuilder()
                        .setName("cache")
                        .setEmptyDir(EmptyDirVolumeSource.newBuilder()))
                .addContainers(Container.newBuilder()
                        .setImage("gcr.io/p1/svc:latest")
                        .addVolumeMounts(VolumeMount.newBuilder()
                                .setName("cache")
                                .setMountPath("/cache")))
                .build();
        Revision unknownMount = Revision.newBuilder()
                .addVolumes(Volume.newBuilder()
                        .setName("site")
                        .setGcs(GCSVolumeSource.newBuilder().setBucket("site-bucket")))
                .addContainers(Container.newBuilder()
                        .setImage("gcr.io/p1/svc:latest")
                        .addVolumeMounts(VolumeMount.newBuilder()
                                .setName("missing")
                                .setMountPath("/site")))
                .build();
        Revision mountOptions = Revision.newBuilder()
                .addVolumes(Volume.newBuilder()
                        .setName("site")
                        .setGcs(GCSVolumeSource.newBuilder()
                                .setBucket("site-bucket")
                                .addMountOptions("implicit-dirs")))
                .addContainers(Container.newBuilder()
                        .setImage("gcr.io/p1/svc:latest")
                        .addVolumeMounts(VolumeMount.newBuilder()
                                .setName("site")
                                .setMountPath("/site")))
                .build();

        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> CloudRunRuntimeService.validateSupported(nonGcsVolume)).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> CloudRunRuntimeService.validateSupported(unknownMount)).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> CloudRunRuntimeService.validateSupported(mountOptions)).getGcpStatus());
    }

    @Test
    void startUsesRevisionUidInContainerNameAndDoesNotRemoveStaleNameSynchronously() {
        when(config.services().cloudrun().execution().startupTimeout()).thenReturn(Duration.ofMillis(1));
        when(lifecycleManager.createAndStart(argThat(spec -> spec.name().endsWith("-revision-uid"))))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("new-container-id",
                        Map.of(8080, new ContainerLifecycleManager.EndpointInfo("127.0.0.1", 1))));
        Service service = Service.newBuilder()
                .setName("projects/p1/locations/us-central1/services/svc")
                .build();
        Revision revision = Revision.newBuilder()
                .setName(service.getName() + "/revisions/svc-00001")
                .setUid("revision-uid")
                .addContainers(Container.newBuilder().setImage("gcr.io/p1/svc:latest"))
                .build();

        assertThrows(GcpException.class, () -> runtimeService.start("p1", "us-central1", service, revision));

        verify(lifecycleManager, never()).removeIfExists(anyString());
        verify(lifecycleManager).forceRemove("new-container-id", null);
    }

    @Test
    void getReadyDropsStaleRuntimeRecordWhenContainerIsGone() {
        InMemoryStorage<String, CloudRunRuntimeInstance> store = new InMemoryStorage<>();
        String revision = "projects/p1/locations/us-central1/services/svc/revisions/svc-00001";
        store.put(revision, instance(revision, 12345));
        CloudRunRuntimeService service = new CloudRunRuntimeService(store, mock(ContainerBuilder.class),
                lifecycleManager, config);
        when(lifecycleManager.isContainerRunning("container-id")).thenReturn(false);

        assertTrue(service.getReady(revision).isEmpty());
        assertTrue(store.get(revision).isEmpty());
    }

    @Test
    void stopInstancesOnlyDeletesMatchingRuntimeSnapshot() {
        InMemoryStorage<String, CloudRunRuntimeInstance> store = new InMemoryStorage<>();
        String revision = "projects/p1/locations/us-central1/services/svc/revisions/svc-00001";
        CloudRunRuntimeInstance oldInstance = instance(revision, 12345, "old-container-id", 1);
        CloudRunRuntimeInstance replacement = instance(revision, 23456, "new-container-id", 2);
        store.put(revision, oldInstance);
        CloudRunRuntimeService service = new CloudRunRuntimeService(store, mock(ContainerBuilder.class),
                lifecycleManager, config);
        List<CloudRunRuntimeInstance> snapshot = service.serviceInstances("projects/p1/locations/us-central1/services/svc");
        store.put(revision, replacement);

        service.stopInstances(snapshot);

        verify(lifecycleManager).forceRemove("old-container-id", null);
        assertEquals("new-container-id", store.get(revision).orElseThrow().containerId());
    }

    @Test
    void stopInstancesDeletesMatchingRuntimeSnapshotWhenDockerCleanupFails() {
        InMemoryStorage<String, CloudRunRuntimeInstance> store = new InMemoryStorage<>();
        String revision = "projects/p1/locations/us-central1/services/svc/revisions/svc-00001";
        CloudRunRuntimeInstance instance = instance(revision, 12345, "container-id", 1);
        store.put(revision, instance);
        CloudRunRuntimeService service = new CloudRunRuntimeService(store, mock(ContainerBuilder.class),
                lifecycleManager, config);
        doThrow(new RuntimeException("docker cleanup failed"))
                .when(lifecycleManager).forceRemove("container-id", null);

        service.stopInstances(List.of(instance));

        assertTrue(store.get(revision).isEmpty());
    }

    @Test
    void getReadyRefreshesEndpointFromDockerBeforeReturningRuntime() {
        InMemoryStorage<String, CloudRunRuntimeInstance> store = new InMemoryStorage<>();
        String revision = "projects/p1/locations/us-central1/services/svc/revisions/svc-00001";
        store.put(revision, instance(revision, 12345));
        CloudRunRuntimeService service = new CloudRunRuntimeService(store, mock(ContainerBuilder.class),
                lifecycleManager, config);
        when(lifecycleManager.isContainerRunning("container-id")).thenReturn(true);
        when(lifecycleManager.resolveEndpoint("container-id", 8080, null))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo("localhost", 23456));

        CloudRunRuntimeInstance ready = service.getReady(revision).orElseThrow();

        assertEquals("localhost", ready.endpointHost());
        assertEquals(23456, ready.endpointPort());
        assertEquals(23456, store.get(revision).orElseThrow().endpointPort());
    }

    @Test
    void getReadyRefreshesEndpointUsingStoredDockerNetwork() {
        InMemoryStorage<String, CloudRunRuntimeInstance> store = new InMemoryStorage<>();
        String revision = "projects/p1/locations/us-central1/services/svc/revisions/svc-00001";
        store.put(revision, new CloudRunRuntimeInstance("p1", "us-central1",
                "projects/p1/locations/us-central1/services/svc", revision,
                "gcr.io/p1/svc:latest", "container-id", 8080, "compat-net", "172.18.0.4", 80,
                "http://floci-gcp:4588/run/v2/projects/p1/locations/us-central1/services/svc",
                "READY", 1, 1, null, 300_000));
        CloudRunRuntimeService service = new CloudRunRuntimeService(store, mock(ContainerBuilder.class),
                lifecycleManager, config);
        when(lifecycleManager.isContainerRunning("container-id")).thenReturn(true);
        when(lifecycleManager.resolveEndpoint("container-id", 8080, "compat-net"))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo("172.18.0.4", 80));

        CloudRunRuntimeInstance ready = service.getReady(revision).orElseThrow();

        assertEquals("172.18.0.4", ready.endpointHost());
        verify(lifecycleManager).resolveEndpoint("container-id", 8080, "compat-net");
    }

    private static CloudRunRuntimeInstance instance(String revision, int endpointPort) {
        return instance(revision, endpointPort, "container-id", 1);
    }

    private static CloudRunRuntimeInstance instance(String revision, int endpointPort,
                                                   String containerId, long createTimeMillis) {
        return instance(revision, endpointPort, containerId, createTimeMillis, List.of());
    }

    private static CloudRunRuntimeInstance instance(String revision, int endpointPort,
                                                   String containerId, long createTimeMillis,
                                                   List<CloudRunRuntimeVolumeMount> mounts) {
        return new CloudRunRuntimeInstance("p1", "us-central1",
                "projects/p1/locations/us-central1/services/svc", revision,
                "gcr.io/p1/svc:latest", containerId, 8080, null, "127.0.0.1", endpointPort,
                "http://localhost:4588/run/v2/projects/p1/locations/us-central1/services/svc",
                "READY", createTimeMillis, createTimeMillis, null, 300_000, mounts);
    }

    private ContainerBuilder containerBuilder() {
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.isLinuxHost()).thenReturn(false);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        return new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer);
    }

    private static GcsObjectMeta object(String name) {
        GcsObjectMeta object = new GcsObjectMeta();
        object.setName(name);
        return object;
    }
}

package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.ContainerPort;
import com.google.cloud.run.v2.EnvVar;
import com.google.cloud.run.v2.Revision;
import com.google.cloud.run.v2.Service;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.dns.EmbeddedDnsServer;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
        when(config.services().cloudrun().execution().runtime()).thenReturn("docker");
        when(config.services().cloudrun().execution().defaultPort()).thenReturn(8080);
        when(config.services().cloudrun().execution().startupTimeout()).thenReturn(Duration.ofSeconds(1));
        when(config.services().cloudrun().execution().requestTimeout()).thenReturn(Duration.ofSeconds(300));
        when(config.services().cloudrun().execution().containerNamePrefix()).thenReturn("floci-cloudrun");
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
    void getReadyRefreshesEndpointFromDockerBeforeReturningRuntime() {
        InMemoryStorage<String, CloudRunRuntimeInstance> store = new InMemoryStorage<>();
        String revision = "projects/p1/locations/us-central1/services/svc/revisions/svc-00001";
        store.put(revision, instance(revision, 12345));
        CloudRunRuntimeService service = new CloudRunRuntimeService(store, mock(ContainerBuilder.class),
                lifecycleManager, config);
        when(lifecycleManager.isContainerRunning("container-id")).thenReturn(true);
        when(lifecycleManager.resolveEndpoint("container-id", 8080))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo("localhost", 23456));

        CloudRunRuntimeInstance ready = service.getReady(revision).orElseThrow();

        assertEquals("localhost", ready.endpointHost());
        assertEquals(23456, ready.endpointPort());
        assertEquals(23456, store.get(revision).orElseThrow().endpointPort());
    }

    private static CloudRunRuntimeInstance instance(String revision, int endpointPort) {
        return new CloudRunRuntimeInstance("p1", "us-central1",
                "projects/p1/locations/us-central1/services/svc", revision,
                "gcr.io/p1/svc:latest", "container-id", 8080, "127.0.0.1", endpointPort,
                "http://localhost:4588/run/v2/projects/p1/locations/us-central1/services/svc",
                "READY", 1, 1, null, 300_000);
    }
}

package io.floci.gcp.services.gke;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import io.floci.gcp.core.common.docker.PortAllocator;
import io.floci.gcp.services.gke.model.StoredCluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GkeClusterManagerVolumeTest {

    @Mock
    ContainerBuilder containerBuilder;

    @Mock
    ContainerLifecycleManager lifecycleManager;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    PortAllocator portAllocator;

    @Mock
    DockerHostResolver dockerHostResolver;

    @Mock
    EmulatorConfig config;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @Test
    void persistedVolumeNamesAreUsedVerbatim() {
        StoredCluster cluster = cluster();
        cluster.setVolumeName("floci-gcp-gke-p1-c1");

        assertEquals("floci-gcp-gke-p1-c1", manager().volumeName(cluster));
    }

    @Test
    void existingLegacyVolumesAreAdoptedEvenWithNamespaceConfigured() {
        when(lifecycleManager.volumeExists("floci-gke-c1")).thenReturn(true);

        assertEquals("floci-gke-c1", manager().volumeName(cluster()));
    }

    @Test
    void newVolumesAreProjectScopedAndNamespaced() {
        when(lifecycleManager.volumeExists("floci-gke-c1")).thenReturn(false);
        when(config.docker()).thenReturn(dockerConfig);
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.of("run-one"));

        assertEquals("floci-gcp-run-one-gke-p1-c1", manager().volumeName(cluster()));
    }

    private GkeClusterManager manager() {
        return new GkeClusterManager(containerBuilder, lifecycleManager, containerDetector,
                portAllocator, dockerHostResolver, config);
    }

    private static StoredCluster cluster() {
        StoredCluster cluster = new StoredCluster();
        cluster.setName("c1");
        cluster.setProject("p1");
        return cluster;
    }
}

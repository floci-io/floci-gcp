package io.floci.gcp.services.kafka;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.gcp.services.kafka.model.StoredCluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedpandaManagerVolumeMigrationTest {

    @Mock
    ContainerBuilder containerBuilder;

    @Mock
    ContainerLifecycleManager lifecycleManager;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    EmulatorConfig config;

    @Mock
    EmulatorConfig.StorageConfig storageConfig;

    @Mock
    EmulatorConfig.ServicesConfig servicesConfig;

    @Mock
    EmulatorConfig.KafkaServiceConfig kafkaConfig;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @Test
    void preUpgradeClustersMountTheirLegacyVolumeEvenWithNamespaceConfigured() {
        ContainerBuilder.Builder specBuilder = stubStartContainerPath("run-one");
        StoredCluster cluster = cluster("abc123", null);

        manager().startContainer(cluster);

        verify(specBuilder).withName("floci-gcp-run-one-kafka-c1");
        verify(lifecycleManager).ensureVolume("floci-gcp-kafka-abc123");
        verify(specBuilder).withNamedVolume("floci-gcp-kafka-abc123", "/var/lib/redpanda/data");
        assertEquals("floci-gcp-kafka-abc123", cluster.getVolumeName());
    }

    @Test
    void persistedVolumeNamesAreUsedVerbatim() {
        ContainerBuilder.Builder specBuilder = stubStartContainerPath("run-one");
        StoredCluster cluster = cluster("abc123", "floci-gcp-run-one-kafka-abc123");

        manager().startContainer(cluster);

        verify(lifecycleManager).ensureVolume("floci-gcp-run-one-kafka-abc123");
        verify(specBuilder).withNamedVolume("floci-gcp-run-one-kafka-abc123", "/var/lib/redpanda/data");
    }

    @Test
    void removeClusterStorageResolvesTheSameLegacyName() {
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.mode()).thenReturn("memory");

        manager().removeClusterStorage(cluster("abc123", null));

        verify(lifecycleManager).removeVolume("floci-gcp-kafka-abc123");
    }

    private RedpandaManager manager() {
        return new RedpandaManager(containerBuilder, lifecycleManager, containerDetector, config);
    }

    private static StoredCluster cluster(String volumeId, String volumeName) {
        StoredCluster cluster = new StoredCluster("projects/p1/locations/us-central1/clusters/c1");
        cluster.setVolumeId(volumeId);
        cluster.setVolumeName(volumeName);
        return cluster;
    }

    private ContainerBuilder.Builder stubStartContainerPath(String namespace) {
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.kafka()).thenReturn(kafkaConfig);
        when(kafkaConfig.defaultImage()).thenReturn("redpanda:test");
        when(kafkaConfig.dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker()).thenReturn(dockerConfig);
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.of(namespace));
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.hostPersistentPath()).thenReturn("./data");
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer("redpanda:test")).thenReturn(specBuilder);
        lenient().when(specBuilder.build()).thenReturn(null);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo(
                "container-1", Map.of(9092, new EndpointInfo("localhost", 19092))));
        return specBuilder;
    }
}

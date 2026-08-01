package io.floci.gcp.services.cloudsql;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.gcp.core.common.docker.ContainerSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloudSqlPostgresDataPlaneTest {

    @Mock
    ContainerBuilder containerBuilder;

    @Mock
    ContainerBuilder.Builder specBuilder;

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
    EmulatorConfig.CloudSqlServiceConfig cloudSqlConfig;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @Test
    void startInstanceCleansUpCreatedContainerAndNewVolumeWhenStartupFails() {
        ContainerSpec spec = new ContainerSpec("postgres:18.4-alpine");
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.hostPersistentPath()).thenReturn("./data");
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudsql()).thenReturn(cloudSqlConfig);
        when(servicesConfig.dockerNetwork()).thenReturn(Optional.empty());
        when(cloudSqlConfig.postgres18Image()).thenReturn("postgres:18.4-alpine");
        when(cloudSqlConfig.startupTimeoutSeconds()).thenReturn(0);
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(containerBuilder.newContainer("postgres:18.4-alpine")).thenReturn(specBuilder);
        when(specBuilder.withName("floci-gcp-cloudsql-project-a-pg-main")).thenReturn(specBuilder);
        when(specBuilder.withEnv(any(), any())).thenReturn(specBuilder);
        when(specBuilder.withLogRotation()).thenReturn(specBuilder);
        when(specBuilder.withDockerNetwork(Optional.empty())).thenReturn(specBuilder);
        when(specBuilder.withDynamicPort(5432)).thenReturn(specBuilder);
        when(specBuilder.withNamedVolume(argThat(name -> name.startsWith("floci-gcp-cloudsql-project-a-pg-main-")),
                eq("/var/lib/postgresql"))).thenReturn(specBuilder);
        when(specBuilder.build()).thenReturn(spec);
        when(lifecycleManager.create(spec)).thenReturn("container-1");
        when(lifecycleManager.startCreated("container-1", spec)).thenReturn(new ContainerInfo(
                "container-1",
                Map.of(5432, new EndpointInfo("localhost", 15432))));

        CloudSqlPostgresDataPlane dataPlane = new CloudSqlPostgresDataPlane(
                containerBuilder, lifecycleManager, containerDetector, config);

        assertThrows(GcpException.class, () -> dataPlane.startInstance(
                "project-a", "pg-main", Map.of("name", "pg-main", "databaseVersion", "POSTGRES_18")));

        verify(lifecycleManager).stopAndRemove("container-1", null);
        verify(lifecycleManager).removeVolume(argThat(name -> name.startsWith(
                "floci-gcp-cloudsql-project-a-pg-main-")));
    }

    @Test
    void preUpgradeInstancesMountTheirLegacyVolumeEvenWithNamespaceConfigured() {
        ContainerSpec spec = new ContainerSpec("postgres:18.4-alpine");
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.hostPersistentPath()).thenReturn("./data");
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudsql()).thenReturn(cloudSqlConfig);
        when(servicesConfig.dockerNetwork()).thenReturn(Optional.empty());
        when(cloudSqlConfig.postgres18Image()).thenReturn("postgres:18.4-alpine");
        when(cloudSqlConfig.startupTimeoutSeconds()).thenReturn(0);
        when(config.docker()).thenReturn(dockerConfig);
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.of("run-one"));
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(containerBuilder.newContainer("postgres:18.4-alpine")).thenReturn(specBuilder);
        when(specBuilder.withName("floci-gcp-run-one-cloudsql-project-a-pg-main")).thenReturn(specBuilder);
        when(specBuilder.withEnv(any(), any())).thenReturn(specBuilder);
        when(specBuilder.withLogRotation()).thenReturn(specBuilder);
        when(specBuilder.withDockerNetwork(Optional.empty())).thenReturn(specBuilder);
        when(specBuilder.withDynamicPort(5432)).thenReturn(specBuilder);
        when(specBuilder.withNamedVolume("floci-gcp-cloudsql-abc123", "/var/lib/postgresql"))
                .thenReturn(specBuilder);
        when(specBuilder.build()).thenReturn(spec);
        when(lifecycleManager.create(spec)).thenReturn("container-1");
        when(lifecycleManager.startCreated("container-1", spec)).thenReturn(new ContainerInfo(
                "container-1",
                Map.of(5432, new EndpointInfo("localhost", 15432))));

        CloudSqlPostgresDataPlane dataPlane = new CloudSqlPostgresDataPlane(
                containerBuilder, lifecycleManager, containerDetector, config);

        assertThrows(GcpException.class, () -> dataPlane.startInstance(
                "project-a", "pg-main", Map.of(
                        "name", "pg-main",
                        "databaseVersion", "POSTGRES_18",
                        "flociDataPlane", Map.of("volumeId", "abc123"))));

        verify(specBuilder).withNamedVolume("floci-gcp-cloudsql-abc123", "/var/lib/postgresql");
        verify(lifecycleManager, never()).removeVolume(any());
    }

    @Test
    void freshInstancesGetNamespacedVolumeNamesAndRollbackRemovesTheSameName() {
        ContainerSpec spec = new ContainerSpec("postgres:18.4-alpine");
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.hostPersistentPath()).thenReturn("./data");
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudsql()).thenReturn(cloudSqlConfig);
        when(servicesConfig.dockerNetwork()).thenReturn(Optional.empty());
        when(cloudSqlConfig.postgres18Image()).thenReturn("postgres:18.4-alpine");
        when(cloudSqlConfig.startupTimeoutSeconds()).thenReturn(0);
        when(config.docker()).thenReturn(dockerConfig);
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.of("run-one"));
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(containerBuilder.newContainer("postgres:18.4-alpine")).thenReturn(specBuilder);
        when(specBuilder.withName("floci-gcp-run-one-cloudsql-project-a-pg-main")).thenReturn(specBuilder);
        when(specBuilder.withEnv(any(), any())).thenReturn(specBuilder);
        when(specBuilder.withLogRotation()).thenReturn(specBuilder);
        when(specBuilder.withDockerNetwork(Optional.empty())).thenReturn(specBuilder);
        when(specBuilder.withDynamicPort(5432)).thenReturn(specBuilder);
        when(specBuilder.withNamedVolume(
                argThat(name -> name.startsWith("floci-gcp-run-one-cloudsql-project-a-pg-main-")),
                eq("/var/lib/postgresql"))).thenReturn(specBuilder);
        when(specBuilder.build()).thenReturn(spec);
        when(lifecycleManager.create(spec)).thenReturn("container-1");
        when(lifecycleManager.startCreated("container-1", spec)).thenReturn(new ContainerInfo(
                "container-1",
                Map.of(5432, new EndpointInfo("localhost", 15432))));

        CloudSqlPostgresDataPlane dataPlane = new CloudSqlPostgresDataPlane(
                containerBuilder, lifecycleManager, containerDetector, config);

        assertThrows(GcpException.class, () -> dataPlane.startInstance(
                "project-a", "pg-main", Map.of("name", "pg-main", "databaseVersion", "POSTGRES_18")));

        verify(lifecycleManager).removeVolume(argThat(name -> name.startsWith(
                "floci-gcp-run-one-cloudsql-project-a-pg-main-")));
    }

    @Test
    void stopInstanceRemovesTheLegacyVolumeForPreUpgradeInstances() {
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.mode()).thenReturn("memory");
        when(config.docker()).thenReturn(dockerConfig);
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.of("run-one"));

        CloudSqlPostgresDataPlane dataPlane = new CloudSqlPostgresDataPlane(
                containerBuilder, lifecycleManager, containerDetector, config);

        dataPlane.stopInstance("project-a", "pg-main",
                Map.of("flociDataPlane", Map.of("volumeId", "abc123")), true);

        verify(lifecycleManager).removeIfExists("floci-gcp-run-one-cloudsql-project-a-pg-main");
        verify(lifecycleManager).removeVolume("floci-gcp-cloudsql-abc123");
    }
}

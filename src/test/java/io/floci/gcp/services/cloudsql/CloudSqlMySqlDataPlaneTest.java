package io.floci.gcp.services.cloudsql;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ExecResult;
import io.floci.gcp.core.common.docker.ContainerSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CloudSqlMySqlDataPlaneTest {

    private static final Map<String, Object> RUNNING = Map.of(
            "name", "my-main",
            "databaseVersion", "MYSQL_8_0",
            "flociDataPlane", Map.of("containerId", "container-1"));

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

    private CloudSqlMySqlDataPlane dataPlane() {
        return new CloudSqlMySqlDataPlane(containerBuilder, lifecycleManager, containerDetector, config);
    }

    private void stubStartup(String image) {
        ContainerSpec spec = new ContainerSpec(image);
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.hostPersistentPath()).thenReturn("./data");
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudsql()).thenReturn(cloudSqlConfig);
        when(servicesConfig.dockerNetwork()).thenReturn(Optional.empty());
        when(cloudSqlConfig.mysql80Image()).thenReturn("mysql:8.0.46");
        when(cloudSqlConfig.mysql84Image()).thenReturn("mysql:8.4.11");
        when(cloudSqlConfig.startupTimeoutSeconds()).thenReturn(0);
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(containerBuilder.newContainer(image)).thenReturn(specBuilder);
        when(specBuilder.withName(any())).thenReturn(specBuilder);
        when(specBuilder.withLabels(any())).thenReturn(specBuilder);
        when(specBuilder.withEnv(any(), any())).thenReturn(specBuilder);
        when(specBuilder.withLogRotation()).thenReturn(specBuilder);
        when(specBuilder.withDockerNetwork(Optional.empty())).thenReturn(specBuilder);
        when(specBuilder.withDynamicPort(3306)).thenReturn(specBuilder);
        when(specBuilder.withNamedVolume(any(), eq("/var/lib/mysql"))).thenReturn(specBuilder);
        when(specBuilder.build()).thenReturn(spec);
        when(lifecycleManager.create(spec)).thenReturn("container-1");
        when(lifecycleManager.startCreated("container-1", spec)).thenReturn(new ContainerInfo(
                "container-1",
                Map.of(3306, new EndpointInfo("localhost", 13306))));
    }

    @Test
    void startInstanceUsesTheMysqlImageEnvPortAndDataDirectory() {
        stubStartup("mysql:8.0.46");
        when(cloudSqlConfig.startupTimeoutSeconds()).thenReturn(5);
        when(lifecycleManager.exec(eq("container-1"), anyList(), anyList()))
                .thenReturn(new ExecResult(0, "mysqld is alive", ""));

        Map<String, Object> updated = dataPlane().startInstance("project-a", "my-main",
                Map.of("name", "my-main", "databaseVersion", "MYSQL_8_0", "region", "us-central1"));

        verify(specBuilder).withName("floci-gcp-cloudsql-project-a-my-main");
        verify(specBuilder).withLabels(Map.of(
                "io.floci", "gcp",
                "io.floci.service", "cloudsql",
                "io.floci.resource-id", "my-main",
                "io.floci.project", "project-a",
                "io.floci.location", "us-central1"));
        verify(specBuilder).withEnv("MYSQL_ROOT_PASSWORD", "root");
        verify(specBuilder).withDynamicPort(3306);
        verify(specBuilder).withNamedVolume(argThat(name -> name.startsWith("floci-gcp-cloudsql-project-a-my-main-")),
                eq("/var/lib/mysql"));
        // Readiness is probed over TCP, not the socket the image's init-time server listens on.
        verify(lifecycleManager).exec(eq("container-1"), eq(List.of()),
                argThat(cmd -> cmd.get(0).equals("mysqladmin") && cmd.contains("ping")
                        && cmd.contains("127.0.0.1") && cmd.contains("3306")));

        @SuppressWarnings("unchecked")
        Map<String, Object> plane = (Map<String, Object>) updated.get("flociDataPlane");
        assertEquals("mysql", plane.get("engine"));
        assertEquals("mysql:8.0.46", plane.get("image"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ip = ((List<Map<String, Object>>) updated.get("ipAddresses")).get(0);
        assertEquals(13306, ip.get("port"));
    }

    @Test
    void minorPinnedAndMajorVersionsMapToTheirImages() {
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.cloudsql()).thenReturn(cloudSqlConfig);
        when(cloudSqlConfig.mysql80Image()).thenReturn("mysql:8.0.46");
        when(cloudSqlConfig.mysql84Image()).thenReturn("mysql:8.4.11");
        CloudSqlMySqlDataPlane plane = dataPlane();

        assertEquals("mysql:8.0.46", plane.imageFor("MYSQL_8_0"));
        assertEquals("mysql:8.0.46", plane.imageFor("MYSQL_8_0_36"));
        assertEquals("mysql:8.4.11", plane.imageFor("MYSQL_8_4"));
        for (String bad : List.of("MYSQL_5_7", "POSTGRES_16", "MYSQL_8_00", "MYSQL_8_0foo", "MYSQL_8_0_", "MYSQL_8_4_1")) {
            assertThrows(GcpException.class, () -> plane.imageFor(bad), bad);
        }
    }

    @Test
    void startupFailureRemovesTheContainerAndTheFreshVolume() {
        stubStartup("mysql:8.4.11");
        when(lifecycleManager.exec(eq("container-1"), anyList(), anyList()))
                .thenReturn(new ExecResult(1, "", "mysqld is not alive"));

        assertThrows(GcpException.class, () -> dataPlane().startInstance("project-a", "my-main",
                Map.of("name", "my-main", "databaseVersion", "MYSQL_8_4")));

        verify(lifecycleManager).stopAndRemove("container-1", null);
        verify(lifecycleManager).removeVolume(argThat(name -> name.startsWith("floci-gcp-cloudsql-project-a-my-main-")));
    }

    @Test
    void databaseAndUserOperationsIssueMysqlStatementsAsRoot() {
        when(lifecycleManager.exec(eq("container-1"), anyList(), anyList()))
                .thenReturn(new ExecResult(0, "", ""));
        CloudSqlMySqlDataPlane plane = dataPlane();

        plane.createDatabase(RUNNING, "app`db", "utf8mb4", "utf8mb4_bin");
        plane.createDatabase(RUNNING, "plain", null, null);
        plane.createOrUpdateUser(RUNNING, "app", "%", "it's");
        plane.grantDatabaseAccess(RUNNING, "appdb", "app", "10.0.0.5");
        plane.deleteUser(RUNNING, "app", "%", List.of("appdb"));
        plane.deleteDatabase(RUNNING, "appdb");
        // charset/collation are spliced into DDL, so anything but a bare name is refused first.
        assertThrows(GcpException.class, () -> plane.createDatabase(RUNNING, "x", "utf8mb4; DROP DATABASE mysql", null));

        ArgumentCaptor<List<String>> commands = ArgumentCaptor.captor();
        ArgumentCaptor<List<String>> envs = ArgumentCaptor.captor();
        verify(lifecycleManager, org.mockito.Mockito.times(6)).exec(eq("container-1"), envs.capture(), commands.capture());
        List<String> sql = commands.getAllValues().stream().map(cmd -> cmd.get(cmd.size() - 1)).toList();
        assertEquals(List.of(
                "CREATE DATABASE IF NOT EXISTS `app``db` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin",
                "CREATE DATABASE IF NOT EXISTS `plain`",
                "CREATE USER IF NOT EXISTS 'app'@'%' IDENTIFIED BY 'it''s'; ALTER USER 'app'@'%' IDENTIFIED BY 'it''s'",
                "GRANT ALL PRIVILEGES ON `appdb`.* TO 'app'@'10.0.0.5'",
                "DROP USER IF EXISTS 'app'@'%'",
                "DROP DATABASE IF EXISTS `appdb`"), sql);
        // Every call goes through the mysql client over the Unix socket (root@localhost, never
        // root@% which the API may drop) with the password in the environment, so the client's
        // insecure-password warning never lands in stderr.
        assertTrue(commands.getAllValues().stream().allMatch(cmd -> cmd.get(0).equals("mysql") && cmd.contains("root")
                && cmd.contains("--protocol=socket") && cmd.contains("--socket=/var/run/mysqld/mysqld.sock")
                && !cmd.contains("127.0.0.1")));
        assertTrue(envs.getAllValues().stream().allMatch(env -> env.equals(List.of("MYSQL_PWD=root"))));
    }

    @Test
    void systemSchemasAndTheAdminLoginAreNeverTouched() {
        CloudSqlMySqlDataPlane plane = dataPlane();

        for (String system : List.of("mysql", "sys", "information_schema", "performance_schema")) {
            plane.createDatabase(RUNNING, system, "utf8mb4", "utf8mb4_0900_ai_ci");
            plane.deleteDatabase(RUNNING, system);
            plane.grantDatabaseAccess(RUNNING, system, "app", "%");
        }
        // root@localhost is what the socket login authenticates as; it is the one identity kept
        // out of reach.
        plane.createOrUpdateUser(RUNNING, "root", "localhost", "new-root-password");
        plane.deleteUser(RUNNING, "root", "localhost", List.of());
        plane.grantDatabaseAccess(RUNNING, "appdb", "root", "localhost");

        verify(lifecycleManager, never()).exec(any(), anyList(), anyList());

        // root@% (the provisioned account the Terraform provider drops and recreates) and root at
        // any other host are managed like any user.
        when(lifecycleManager.exec(eq("container-1"), anyList(), anyList())).thenReturn(new ExecResult(0, "", ""));
        plane.deleteUser(RUNNING, "root", "%", List.of());
        plane.createOrUpdateUser(RUNNING, "root", null, "new-root-password");
        plane.grantDatabaseAccess(RUNNING, "appdb", "root", "%");
        plane.createOrUpdateUser(RUNNING, "root", "10.0.0.5", "pw");
        ArgumentCaptor<List<String>> commands = ArgumentCaptor.captor();
        verify(lifecycleManager, org.mockito.Mockito.times(4)).exec(eq("container-1"), anyList(), commands.capture());
        assertEquals(List.of(
                "DROP USER IF EXISTS 'root'@'%'",
                "CREATE USER IF NOT EXISTS 'root'@'%' IDENTIFIED BY 'new-root-password'; "
                        + "ALTER USER 'root'@'%' IDENTIFIED BY 'new-root-password'",
                "GRANT ALL PRIVILEGES ON `appdb`.* TO 'root'@'%'",
                "CREATE USER IF NOT EXISTS 'root'@'10.0.0.5' IDENTIFIED BY 'pw'; ALTER USER 'root'@'10.0.0.5' IDENTIFIED BY 'pw'"),
                commands.getAllValues().stream().map(cmd -> cmd.get(cmd.size() - 1)).toList());
    }

    @Test
    void operationsWithoutARunningContainerFailBeforeExec() {
        GcpException error = assertThrows(GcpException.class,
                () -> dataPlane().createDatabase(Map.of("databaseVersion", "MYSQL_8_0"), "appdb", null, null));
        assertEquals("FAILED_PRECONDITION", error.getGcpStatus());
        verify(lifecycleManager, never()).exec(any(), anyList(), anyList());
    }
}

package io.floci.gcp.services.cloudsql;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.RequestContext;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.ProjectAwareStorageBackend;
import io.floci.gcp.core.storage.StorageBackend;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloudSqlServiceTest {

    @Mock
    Instance<RequestContext> contextInstance;

    @Mock
    RequestContext requestContext;

    private CloudSqlService service;

    @BeforeEach
    void setUp() {
        service = new CloudSqlService(
                projectAwareStore(),
                projectAwareStore(),
                projectAwareStore(),
                projectAwareStore(),
                new ObjectMapper(),
                "http://localhost:4588");
    }

    private StorageBackend<String, Map<String, Object>> projectAwareStore() {
        return new ProjectAwareStorageBackend<>(new InMemoryStorage<>(), contextInstance, "default-project");
    }

    private void withProject(String project) {
        when(contextInstance.get()).thenReturn(requestContext);
        when(requestContext.getProjectId()).thenReturn(project);
    }

    @Test
    void projectAwareStorageAllowsSameInstanceNameAcrossProjects() {
        withProject("project-a");
        service.createInstance("project-a", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_18"));

        withProject("project-b");
        assertThrows(GcpException.class, () -> service.getInstance("project-b", "pg-main"));

        service.createInstance("project-b", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_16"));

        assertEquals("POSTGRES_16", service.getInstance("project-b", "pg-main").get("databaseVersion"));

        withProject("project-a");
        assertEquals("POSTGRES_18", service.getInstance("project-a", "pg-main").get("databaseVersion"));
    }

    @Test
    void locationPreferenceZoneOutsideRegionIsAcceptedByDefault() {
        withProject("project-a");
        service.createInstance("project-a", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_18",
                "region", "us-central1",
                "settings", Map.of("locationPreference", Map.of("zone", "europe-west1-b"))));

        assertEquals("europe-west1-b", service.getInstance("project-a", "pg-main").get("gceZone"));
    }

    @Test
    void locationPreferenceWithoutRegionDerivesRegionFromZone() {
        withProject("project-a");
        service.createInstance("project-a", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_18",
                "settings", Map.of("locationPreference", Map.of("zone", "asia-east1-b"))));

        Map<String, Object> instance = service.getInstance("project-a", "pg-main");
        assertEquals("asia-east1", instance.get("region"));
        assertEquals("asia-east1-b", instance.get("gceZone"));
        assertEquals("project-a:asia-east1:pg-main", instance.get("connectionName"));
    }

    @Test
    void secondaryZoneIsOnlyServedForRegionalInstances() {
        withProject("project-a");
        service.createInstance("project-a", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_18",
                "settings", Map.of("availabilityType", "ZONAL",
                        "locationPreference", Map.of("zone", "us-central1-b", "secondaryZone", "us-central1-c"))));

        assertNull(service.getInstance("project-a", "pg-main").get("secondaryGceZone"));

        service.patchInstance("project-a", "pg-main", Map.of("settings", Map.of("availabilityType", "REGIONAL")));

        assertEquals("us-central1-c", service.getInstance("project-a", "pg-main").get("secondaryGceZone"));
    }

    @Test
    void strictLocationsRejectZonesOutsideTheInstanceRegion() {
        withProject("project-a");
        CloudSqlService strict = new CloudSqlService(projectAwareStore(), projectAwareStore(),
                projectAwareStore(), projectAwareStore(), new ObjectMapper(), "http://localhost:4588",
                CloudSqlDataPlane.noop(), false, true);

        GcpException zone = assertThrows(GcpException.class, () -> strict.createInstance("project-a", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_18",
                "region", "us-central1",
                "settings", Map.of("locationPreference", Map.of("zone", "europe-west1-b")))));
        assertEquals(400, zone.getHttpStatus());
        assertEquals("INVALID_ARGUMENT", zone.getGcpStatus());

        strict.createInstance("project-a", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_18",
                "region", "us-central1",
                "settings", Map.of("availabilityType", "REGIONAL",
                        "locationPreference", Map.of("zone", "us-central1-b"))));

        assertThrows(GcpException.class, () -> strict.patchInstance("project-a", "pg-main", Map.of(
                "settings", Map.of("locationPreference", Map.of("secondaryZone", "us-east1-b")))));
        assertEquals("us-central1-b", strict.getInstance("project-a", "pg-main").get("gceZone"));
        assertNull(strict.getInstance("project-a", "pg-main").get("secondaryGceZone"));
    }

    @Test
    void deleteInstanceCascadesDatabasesAndUsers() {
        withProject("project-a");
        service.createInstance("project-a", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_18"));
        service.createDatabase("project-a", "pg-main", Map.of("name", "appdb"));
        service.createUser("project-a", "pg-main", Map.of("name", "app", "password", "secret"));

        service.deleteInstance("project-a", "pg-main");

        assertThrows(GcpException.class, () -> service.getInstance("project-a", "pg-main"));
        assertThrows(GcpException.class, () -> service.getDatabase("project-a", "pg-main", "appdb"));
        assertThrows(GcpException.class, () -> service.getUser("project-a", "pg-main", "app", null));
    }

    @Test
    void updateInstanceAndPatchDatabaseReturnCompletedOperations() {
        withProject("project-a");
        service.createInstance("project-a", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_18",
                "settings", Map.of("tier", "db-custom-1-3840")));
        service.createDatabase("project-a", "pg-main", Map.of("name", "appdb"));

        Map<String, Object> instanceOperation = service.updateInstance("project-a", "pg-main",
                Map.of("settings", Map.of("userLabels", Map.of("env", "test"))));
        assertEquals("DONE", instanceOperation.get("status"));
        assertEquals("UPDATE", instanceOperation.get("operationType"));

        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) service.getInstance("project-a", "pg-main")
                .get("settings");
        assertEquals("db-custom-1-3840", settings.get("tier"));
        assertEquals(Map.of("env", "test"), settings.get("userLabels"));

        Map<String, Object> databaseOperation = service.patchDatabase("project-a", "pg-main", "appdb",
                Map.of("collation", "en_US.UTF8"));
        assertEquals("DONE", databaseOperation.get("status"));
        assertEquals("UPDATE_DATABASE", databaseOperation.get("operationType"));
    }

    @Test
    void staticDiscoveryEndpointsReturnGcpShapes() {
        Map<String, Object> tiers = service.listTiers("project-a");
        assertEquals("sql#tiersList", tiers.get("kind"));
        assertFalse(((List<?>) tiers.get("items")).isEmpty());

        Map<String, Object> flags = service.listFlags();
        assertEquals("sql#flagsList", flags.get("kind"));
        assertFalse(((List<?>) flags.get("items")).isEmpty());
    }

    @Test
    void dataPlaneReceivesInstanceDatabaseAndUserLifecycleEvents() {
        withProject("project-a");
        RecordingDataPlane dataPlane = new RecordingDataPlane();
        CloudSqlService dataPlaneService = new CloudSqlService(
                projectAwareStore(),
                projectAwareStore(),
                projectAwareStore(),
                projectAwareStore(),
                new ObjectMapper(),
                "http://localhost:4588",
                dataPlane,
                true);

        dataPlaneService.createInstance("project-a", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_18"));
        dataPlaneService.createDatabase("project-a", "pg-main", Map.of("name", "appdb"));
        dataPlaneService.createUser("project-a", "pg-main", Map.of("name", "app", "password", "secret"));
        dataPlaneService.updateUser("project-a", "pg-main", "app", null, Map.of("password", "new-secret"));
        dataPlaneService.deleteUser("project-a", "pg-main", "app", null);
        dataPlaneService.deleteDatabase("project-a", "pg-main", "appdb");
        dataPlaneService.deleteInstance("project-a", "pg-main");

        assertEquals(List.of(
                "start:project-a/pg-main",
                "create-db:appdb:UTF8/en_US.UTF8",
                // the built-in postgres user is listed, so it is offered the database like any
                // other; the PostgreSQL plane itself leaves the admin role alone.
                "grant:appdb:postgres",
                "create-user:app:secret",
                "grant:postgres:app",
                "grant:appdb:app",
                "create-user:app:new-secret",
                "grant:postgres:app",
                "grant:appdb:app",
                "delete-user:app:postgres,appdb",
                "delete-db:appdb",
                "stop:project-a/pg-main:true"), dataPlane.events);
    }

    @Test
    void dataPlaneShutdownStopsSameInstanceNameAcrossProjects() {
        RecordingDataPlane dataPlane = new RecordingDataPlane();
        CloudSqlService dataPlaneService = new CloudSqlService(
                projectAwareStore(),
                projectAwareStore(),
                projectAwareStore(),
                projectAwareStore(),
                new ObjectMapper(),
                "http://localhost:4588",
                dataPlane,
                true);

        withProject("project-a");
        dataPlaneService.createInstance("project-a", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_18"));
        withProject("project-b");
        dataPlaneService.createInstance("project-b", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_18"));

        dataPlane.events.clear();
        dataPlaneService.shutdown();

        assertEquals(2, dataPlane.events.size());
        assertTrue(dataPlane.events.containsAll(List.of(
                "stop:project-a/pg-main:false",
                "stop:project-b/pg-main:false")));
    }

    @Test
    void deleteDefaultPostgresDatabaseIsRejected() {
        withProject("project-a");
        service.createInstance("project-a", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_18"));

        GcpException error = assertThrows(GcpException.class,
                () -> service.deleteDatabase("project-a", "pg-main", "postgres"));

        assertEquals("FAILED_PRECONDITION", error.getGcpStatus());
        assertEquals(400, error.getHttpStatus());
        assertEquals("postgres", service.getDatabase("project-a", "pg-main", "postgres").get("name"));
    }

    @Test
    void deleteDefaultPostgresDatabaseOnMissingInstanceReturnsNotFound() {
        withProject("project-a");

        GcpException error = assertThrows(GcpException.class,
                () -> service.deleteDatabase("project-a", "missing", "postgres"));

        assertEquals("NOT_FOUND", error.getGcpStatus());
    }

    @Test
    void hostQualifiedPostgresUsersAreRejected() {
        withProject("project-a");
        service.createInstance("project-a", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_18"));

        GcpException createError = assertThrows(GcpException.class,
                () -> service.createUser("project-a", "pg-main",
                        Map.of("name", "app", "host", "%", "password", "secret")));
        assertEquals("INVALID_ARGUMENT", createError.getGcpStatus());

        service.createUser("project-a", "pg-main", Map.of("name", "app", "password", "secret"));

        GcpException getError = assertThrows(GcpException.class,
                () -> service.getUser("project-a", "pg-main", "app", "%"));
        assertEquals("INVALID_ARGUMENT", getError.getGcpStatus());

        GcpException updateError = assertThrows(GcpException.class,
                () -> service.updateUser("project-a", "pg-main", "app", "%",
                        Map.of("password", "new-secret")));
        assertEquals("INVALID_ARGUMENT", updateError.getGcpStatus());

        GcpException deleteError = assertThrows(GcpException.class,
                () -> service.deleteUser("project-a", "pg-main", "app", "%"));
        assertEquals("INVALID_ARGUMENT", deleteError.getGcpStatus());
    }

    @Test
    void mysqlInstanceListsSystemDatabasesAndRootAndUsesMysqlDefaults() {
        withProject("project-a");
        Map<String, Object> operation = service.createInstance("project-a", Map.of(
                "name", "my-main",
                "databaseVersion", "MYSQL_8_0",
                "rootPassword", "hunter2"));
        assertEquals("CREATE", operation.get("operationType"));

        Map<String, Object> instance = service.getInstance("project-a", "my-main");
        assertEquals("MYSQL_8_0", instance.get("databaseVersion"));
        // rootPassword is write-only in the Admin API and must never be echoed back.
        assertFalse(instance.containsKey("rootPassword"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> databases =
                (List<Map<String, Object>>) service.listDatabases("project-a", "my-main").get("items");
        assertEquals(List.of("information_schema", "mysql", "performance_schema", "sys"),
                databases.stream().map(d -> d.get("name")).toList());

        service.createDatabase("project-a", "my-main", Map.of("name", "appdb"));
        Map<String, Object> appdb = service.getDatabase("project-a", "my-main", "appdb");
        assertEquals("utf8mb4", appdb.get("charset"));
        assertEquals("utf8mb4_0900_ai_ci", appdb.get("collation"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users =
                (List<Map<String, Object>>) service.listUsers("project-a", "my-main").get("items");
        assertEquals(1, users.size());
        assertEquals("root", users.get(0).get("name"));
        assertEquals("%", users.get(0).get("host"));
        assertEquals("BUILT_IN", users.get(0).get("type"));
    }

    @Test
    void mysqlSystemDatabasesCannotBeDeletedButRootAtPercentCan() {
        withProject("project-a");
        service.createInstance("project-a", Map.of("name", "my-main", "databaseVersion", "MYSQL_8_4"));

        for (String system : List.of("mysql", "sys", "information_schema", "performance_schema")) {
            GcpException error = assertThrows(GcpException.class,
                    () -> service.deleteDatabase("project-a", "my-main", system), system);
            assertEquals("FAILED_PRECONDITION", error.getGcpStatus());
        }
        // The Terraform provider deletes root@% right after creating every MySQL instance, and
        // may insert it again with a password of its own; both must work as on Cloud SQL.
        assertEquals("DELETE_USER", service.deleteUser("project-a", "my-main", "root", null).get("operationType"));
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getUser("project-a", "my-main", "root", "%")).getGcpStatus());
        assertEquals(0, ((List<?>) service.listUsers("project-a", "my-main").get("items")).size());

        service.createUser("project-a", "my-main", Map.of("name", "root", "password", "new-root"));
        Map<String, Object> recreated = service.getUser("project-a", "my-main", "root", "%");
        assertEquals("%", recreated.get("host"));
        assertFalse(recreated.containsKey("password"));
    }

    @Test
    void mysqlUsersAreHostQualifiedAndDefaultToPercent() {
        withProject("project-a");
        RecordingDataPlane dataPlane = new RecordingDataPlane();
        CloudSqlService dataPlaneService = new CloudSqlService(
                projectAwareStore(), projectAwareStore(), projectAwareStore(), projectAwareStore(),
                new ObjectMapper(), "http://localhost:4588", dataPlane, true);
        dataPlaneService.createInstance("project-a", Map.of("name", "my-main", "databaseVersion", "MYSQL_8_0"));
        dataPlaneService.createDatabase("project-a", "my-main", Map.of("name", "appdb"));

        // No host on insert: stored and addressed as '%', which is what users.get?host=% must find.
        dataPlaneService.createUser("project-a", "my-main", Map.of("name", "app", "password", "secret"));
        assertEquals("%", dataPlaneService.getUser("project-a", "my-main", "app", null).get("host"));
        assertEquals("%", dataPlaneService.getUser("project-a", "my-main", "app", "%").get("host"));

        // An explicit host is a distinct identity from the same name at '%'.
        dataPlaneService.createUser("project-a", "my-main",
                Map.of("name", "app", "host", "10.0.0.5", "password", "secret2"));
        assertEquals("10.0.0.5", dataPlaneService.getUser("project-a", "my-main", "app", "10.0.0.5").get("host"));
        assertEquals(3, ((List<?>) dataPlaneService.listUsers("project-a", "my-main").get("items")).size());

        dataPlaneService.updateUser("project-a", "my-main", "app", null, Map.of("password", "rotated"));
        dataPlaneService.deleteUser("project-a", "my-main", "app", "10.0.0.5");
        dataPlaneService.createDatabase("project-a", "my-main", Map.of("name", "second"));

        // Grants are issued once per (database, user); their relative order follows store
        // iteration and is not part of the contract, so they are compared as a multiset.
        List<String> lifecycle = dataPlane.events.stream()
                .filter(e -> !e.startsWith("grant:") && !e.startsWith("delete-user:")).toList();
        List<String> grants = dataPlane.events.stream().filter(e -> e.startsWith("grant:")).sorted().toList();
        assertEquals(List.of(
                "start:project-a/my-main",
                "create-db:appdb:utf8mb4/utf8mb4_0900_ai_ci",
                "create-user:app@%:secret",
                "create-user:app@10.0.0.5:secret2",
                "create-user:app@%:rotated",
                "create-db:second:utf8mb4/utf8mb4_0900_ai_ci"), lifecycle);
        String deleted = dataPlane.events.stream().filter(e -> e.startsWith("delete-user:")).findFirst().orElseThrow();
        assertTrue(deleted.startsWith("delete-user:app@10.0.0.5:"), deleted);
        assertEquals(List.of("appdb", "information_schema", "mysql", "performance_schema", "sys"),
                java.util.Arrays.stream(deleted.substring(deleted.lastIndexOf(':') + 1).split(",")).sorted().toList());
        List<String> expectedGrants = new java.util.ArrayList<>();
        // root@% is listed as a user, so it is offered each new database like any other; the
        // MySQL plane itself declines to touch the admin account.
        expectedGrants.add("grant:appdb:root@%");
        expectedGrants.add("grant:second:root@%");
        for (String identity : List.of("app@%", "app@10.0.0.5", "app@%")) {
            for (String database : List.of("information_schema", "mysql", "performance_schema", "sys", "appdb")) {
                expectedGrants.add("grant:" + database + ":" + identity);
            }
        }
        expectedGrants.add("grant:second:app@%");
        assertEquals(expectedGrants.stream().sorted().toList(), grants);
    }

    @Test
    void unsupportedVersionsAreRejectedEvenWithoutADataPlane() {
        // Mock mode has no image lookup, so the version check must not live only in the plane.
        withProject("project-a");
        for (String version : List.of("MYSQL_5_7", "MYSQL_9_0", "MYSQL_8_00", "POSTGRES_14", "POSTGRES_99")) {
            GcpException error = assertThrows(GcpException.class,
                    () -> service.createInstance("project-a", Map.of("name", "v", "databaseVersion", version)), version);
            assertEquals("INVALID_ARGUMENT", error.getGcpStatus());
        }
        for (String version : List.of("POSTGRES_15", "POSTGRES_18", "MYSQL_8_0", "MYSQL_8_0_36", "MYSQL_8_4")) {
            service.createInstance("project-a", Map.of("name", "ok-" + version.toLowerCase(), "databaseVersion", version));
        }
    }

    @Test
    void unsupportedEnginesAreRejected() {
        for (String version : List.of("SQLSERVER_2019_STANDARD", "", "ORACLE")) {
            GcpException error = assertThrows(GcpException.class,
                    () -> service.createInstance("project-a", Map.of("name", "other", "databaseVersion", version)),
                    version);
            assertEquals("INVALID_ARGUMENT", error.getGcpStatus());
        }
        GcpException missing = assertThrows(GcpException.class,
                () -> service.createInstance("project-a", Map.of("name", "other")));
        assertEquals("INVALID_ARGUMENT", missing.getGcpStatus());
    }

    @Test
    void instanceEngineCannotBeChangedByPatchOrUpdate() {
        withProject("project-a");
        service.createInstance("project-a", Map.of("name", "pg-main", "databaseVersion", "POSTGRES_16"));

        GcpException patch = assertThrows(GcpException.class,
                () -> service.patchInstance("project-a", "pg-main", Map.of("databaseVersion", "MYSQL_8_4")));
        assertEquals("INVALID_ARGUMENT", patch.getGcpStatus());
        GcpException update = assertThrows(GcpException.class,
                () -> service.updateInstance("project-a", "pg-main", Map.of("databaseVersion", "MYSQL_8_0")));
        assertEquals("INVALID_ARGUMENT", update.getGcpStatus());
        assertEquals("POSTGRES_16", service.getInstance("project-a", "pg-main").get("databaseVersion"));

        // Same-engine version changes keep their pre-existing behaviour.
        service.patchInstance("project-a", "pg-main", Map.of("databaseVersion", "POSTGRES_17"));
        assertEquals("POSTGRES_17", service.getInstance("project-a", "pg-main").get("databaseVersion"));
    }

    @Test
    void patchDoesNotPersistRootPassword() {
        withProject("project-a");
        service.createInstance("project-a", Map.of("name", "my-main", "databaseVersion", "MYSQL_8_0"));

        service.patchInstance("project-a", "my-main", Map.of("rootPassword", "hunter2", "settings", Map.of("tier", "db-custom-2-7680")));

        Map<String, Object> instance = service.getInstance("project-a", "my-main");
        assertFalse(instance.containsKey("rootPassword"));
        assertEquals("db-custom-2-7680", ((Map<?, ?>) instance.get("settings")).get("tier"));
    }

    @Test
    void onlyTheProvisionedRootIdentityIsProtected() {
        withProject("project-a");
        RecordingDataPlane dataPlane = new RecordingDataPlane();
        CloudSqlService dataPlaneService = new CloudSqlService(
                projectAwareStore(), projectAwareStore(), projectAwareStore(), projectAwareStore(),
                new ObjectMapper(), "http://localhost:4588", dataPlane, true);
        dataPlaneService.createInstance("project-a", Map.of("name", "my-main", "databaseVersion", "MYSQL_8_0"));

        // root at another host is an ordinary account: created on the server and deletable.
        dataPlaneService.createUser("project-a", "my-main", Map.of("name", "root", "host", "10.0.0.5", "password", "s"));
        assertTrue(dataPlane.events.contains("create-user:root@10.0.0.5:s"));
        dataPlaneService.deleteUser("project-a", "my-main", "root", "10.0.0.5");
        assertTrue(dataPlane.events.stream().anyMatch(e -> e.startsWith("delete-user:root@10.0.0.5:")));

        // root@% is the provisioned account, not the plane's login: dropping and recreating it
        // reaches the server like any other user, so a Terraform apply on a MySQL instance works.
        dataPlaneService.deleteUser("project-a", "my-main", "root", "%");
        assertTrue(dataPlane.events.stream().anyMatch(e -> e.startsWith("delete-user:root@%:")));
        dataPlaneService.createUser("project-a", "my-main", Map.of("name", "root", "password", "new-root"));
        assertTrue(dataPlane.events.contains("create-user:root@%:new-root"));
        dataPlaneService.updateUser("project-a", "my-main", "root", "%", Map.of("password", "rotated"));
        assertTrue(dataPlane.events.contains("create-user:root@%:rotated"));
    }

    @Test
    void hostQualifiedUserKeysCannotCollide() {
        withProject("project-a");
        service.createInstance("project-a", Map.of("name", "my-main", "databaseVersion", "MYSQL_8_0"));

        // Without encoding, host "10.0.0.0/255.255.255.0" + user "app" and host "10.0.0.0" +
        // user "255.255.255.0/app" would share one storage key.
        service.createUser("project-a", "my-main", Map.of("name", "app", "host", "10.0.0.0/255.255.255.0", "password", "a"));
        service.createUser("project-a", "my-main", Map.of("name", "255.255.255.0/app", "host", "10.0.0.0", "password", "b"));

        assertEquals(3, ((List<?>) service.listUsers("project-a", "my-main").get("items")).size());
        service.deleteUser("project-a", "my-main", "255.255.255.0/app", "10.0.0.0");
        assertEquals("10.0.0.0/255.255.255.0",
                service.getUser("project-a", "my-main", "app", "10.0.0.0/255.255.255.0").get("host"));
    }

    @Test
    void rootAtLocalhostIsReservedOnMysql() {
        withProject("project-a");
        RecordingDataPlane dataPlane = new RecordingDataPlane();
        CloudSqlService dataPlaneService = new CloudSqlService(
                projectAwareStore(), projectAwareStore(), projectAwareStore(), projectAwareStore(),
                new ObjectMapper(), "http://localhost:4588", dataPlane, true);
        dataPlaneService.createInstance("project-a", Map.of("name", "my-main", "databaseVersion", "MYSQL_8_0"));

        // The plane logs in over the Unix socket, which the server authenticates as
        // root@localhost, so re-passwording that one identity would strand every later DDL call.
        for (String host : List.of("localhost", "LOCALHOST")) {
            GcpException error = assertThrows(GcpException.class, () -> dataPlaneService.createUser(
                    "project-a", "my-main", Map.of("name", "root", "host", host, "password", "x")), host);
            assertEquals("INVALID_ARGUMENT", error.getGcpStatus());
        }
        assertTrue(dataPlane.events.stream().noneMatch(e -> e.startsWith("create-user:root")));
        // root@% already exists from provisioning, so that spelling is a conflict rather than a reservation.
        assertEquals("ALREADY_EXISTS", assertThrows(GcpException.class, () -> dataPlaneService.createUser(
                "project-a", "my-main", Map.of("name", "root", "password", "x"))).getGcpStatus());
        // The image does not provision root at 127.0.0.1 or ::1 and the plane never connects as
        // them, so they are ordinary accounts.
        for (String host : List.of("127.0.0.1", "::1")) {
            dataPlaneService.createUser("project-a", "my-main", Map.of("name", "root", "host", host, "password", "x"));
            assertTrue(dataPlane.events.contains("create-user:root@" + host + ":x"), host);
        }
    }

    @Test
    void charsetAndCollationAreDefaultedTogetherOrNotAtAll() {
        withProject("project-a");
        RecordingDataPlane dataPlane = new RecordingDataPlane();
        CloudSqlService dataPlaneService = new CloudSqlService(
                projectAwareStore(), projectAwareStore(), projectAwareStore(), projectAwareStore(),
                new ObjectMapper(), "http://localhost:4588", dataPlane, true);
        dataPlaneService.createInstance("project-a", Map.of("name", "my-main", "databaseVersion", "MYSQL_8_0"));

        dataPlaneService.createDatabase("project-a", "my-main", Map.of("name", "latin", "charset", "latin1"));
        dataPlaneService.createDatabase("project-a", "my-main", Map.of("name", "bin", "collation", "utf8mb4_bin"));
        dataPlaneService.createDatabase("project-a", "my-main", Map.of("name", "plain"));

        // A charset-only request must not be paired with the utf8mb4 default collation.
        assertTrue(dataPlane.events.contains("create-db:latin:latin1/null"), dataPlane.events.toString());
        assertTrue(dataPlane.events.contains("create-db:bin:null/utf8mb4_bin"), dataPlane.events.toString());
        assertTrue(dataPlane.events.contains("create-db:plain:utf8mb4/utf8mb4_0900_ai_ci"), dataPlane.events.toString());
        Map<String, Object> latin = dataPlaneService.getDatabase("project-a", "my-main", "latin");
        assertEquals("latin1", latin.get("charset"));
        assertFalse(latin.containsKey("collation"));
    }

    @Test
    void postgresInstanceListsTheBuiltInPostgresUserAndProtectsIt() {
        withProject("project-a");
        service.createInstance("project-a", Map.of("name", "pg-main", "databaseVersion", "POSTGRES_18"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users =
                (List<Map<String, Object>>) service.listUsers("project-a", "pg-main").get("items");
        assertEquals(1, users.size());
        assertEquals("postgres", users.get(0).get("name"));
        assertEquals("BUILT_IN", users.get(0).get("type"));
        assertNull(users.get(0).get("host"));
        assertEquals("postgres", service.getUser("project-a", "pg-main", "postgres", null).get("name"));

        // users.update is accepted (real Cloud SQL lets the password be rotated) and stays password-free.
        service.updateUser("project-a", "pg-main", "postgres", null, Map.of("password", "rotated"));
        assertFalse(service.getUser("project-a", "pg-main", "postgres", null).containsKey("password"));

        GcpException error = assertThrows(GcpException.class,
                () -> service.deleteUser("project-a", "pg-main", "postgres", null));
        assertEquals("FAILED_PRECONDITION", error.getGcpStatus());
        assertEquals(1, ((List<?>) service.listUsers("project-a", "pg-main").get("items")).size());
    }

    @Test
    void startupBackfillsTheBuiltInUserOnInstancesPersistedWithoutIt() {
        withProject("project-a");
        StorageBackend<String, Map<String, Object>> instances = projectAwareStore();
        StorageBackend<String, Map<String, Object>> users = projectAwareStore();
        // An instance written by a build that predates built-in users: no users at all.
        instances.put("instances/legacy", new java.util.LinkedHashMap<>(Map.of(
                "name", "legacy", "project", "project-a", "databaseVersion", "POSTGRES_16")));
        CloudSqlService upgraded = new CloudSqlService(instances, projectAwareStore(), users,
                projectAwareStore(), new ObjectMapper(), "http://localhost:4588");

        upgraded.backfillBuiltInUsers();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed =
                (List<Map<String, Object>>) upgraded.listUsers("project-a", "legacy").get("items");
        assertEquals(List.of("postgres"), listed.stream().map(u -> u.get("name")).toList());

        // Idempotent, and never clobbers a user record that already exists.
        upgraded.updateUser("project-a", "legacy", "postgres", null, Map.of("etag", "keep-me"));
        upgraded.backfillBuiltInUsers();
        assertEquals("keep-me", upgraded.getUser("project-a", "legacy", "postgres", null).get("etag"));
        assertEquals(1, ((List<?>) upgraded.listUsers("project-a", "legacy").get("items")).size());
    }

    @Test
    void backfillSeedsOnceAndNeverResurrectsABuiltInUserTheApiDeleted() {
        withProject("project-a");
        StorageBackend<String, Map<String, Object>> instances = projectAwareStore();
        StorageBackend<String, Map<String, Object>> users = projectAwareStore();
        instances.put("instances/legacy-mysql", new java.util.LinkedHashMap<>(Map.of(
                "name", "legacy-mysql", "project", "project-a", "databaseVersion", "MYSQL_8_0")));
        CloudSqlService upgraded = new CloudSqlService(instances, projectAwareStore(), users,
                projectAwareStore(), new ObjectMapper(), "http://localhost:4588");

        upgraded.backfillBuiltInUsers();
        assertEquals("%", upgraded.getUser("project-a", "legacy-mysql", "root", "%").get("host"));

        // What the Terraform provider does right after every MySQL create. A presence check on
        // the next startup would bring root@% back; the per-instance marker must not.
        upgraded.deleteUser("project-a", "legacy-mysql", "root", "%");
        upgraded.createInstance("project-a", Map.of("name", "fresh", "databaseVersion", "MYSQL_8_4"));
        upgraded.deleteUser("project-a", "fresh", "root", null);
        CloudSqlService restarted = new CloudSqlService(instances, projectAwareStore(), users,
                projectAwareStore(), new ObjectMapper(), "http://localhost:4588");
        restarted.backfillBuiltInUsers();
        assertEquals(0, ((List<?>) restarted.listUsers("project-a", "legacy-mysql").get("items")).size());
        assertEquals(0, ((List<?>) restarted.listUsers("project-a", "fresh").get("items")).size());

        // The marker lives beside the instance record, never in it or in the instance listing.
        assertEquals(List.of("fresh", "legacy-mysql"), ((List<Map<String, Object>>) restarted.listInstances(10, null)
                .get("items")).stream().map(i -> i.get("name")).toList());
        assertTrue(restarted.getInstance("project-a", "fresh").keySet().stream()
                .noneMatch(k -> k.contains("built-in") || k.contains("seeded")));

        // Deleting the instance drops the marker, so a new instance of the same name is seeded again.
        restarted.deleteInstance("project-a", "fresh");
        restarted.createInstance("project-a", Map.of("name", "fresh", "databaseVersion", "MYSQL_8_4"));
        assertEquals(1, ((List<?>) restarted.listUsers("project-a", "fresh").get("items")).size());
    }

    private static class RecordingDataPlane implements CloudSqlDataPlane {
        private final List<String> events = new java.util.ArrayList<>();

        @Override
        public Map<String, Object> startInstance(String project, String instance, Map<String, Object> metadata) {
            events.add("start:" + project + "/" + instance);
            return metadata;
        }

        @Override
        public Map<String, Object> ensureInstance(String project, String instance, Map<String, Object> metadata) {
            events.add("ensure:" + project + "/" + instance);
            return metadata;
        }

        @Override
        public void stopInstance(String project, String instance, Map<String, Object> metadata, boolean removeStorage) {
            events.add("stop:" + project + "/" + instance + ":" + removeStorage);
        }

        @Override
        public void createDatabase(Map<String, Object> instanceMetadata, String database, String charset,
                                   String collation) {
            events.add("create-db:" + database + (charset == null && collation == null
                    ? "" : ":" + charset + "/" + collation));
        }

        @Override
        public void deleteDatabase(Map<String, Object> instanceMetadata, String database) {
            events.add("delete-db:" + database);
        }

        @Override
        public void createOrUpdateUser(Map<String, Object> instanceMetadata, String user, String host,
                                       String password) {
            events.add("create-user:" + identity(user, host) + ":" + password);
        }

        @Override
        public void deleteUser(Map<String, Object> instanceMetadata, String user, String host,
                               Iterable<String> databases) {
            events.add("delete-user:" + identity(user, host) + ":" + String.join(",", databases));
        }

        @Override
        public void grantDatabaseAccess(Map<String, Object> instanceMetadata, String database, String user,
                                        String host) {
            events.add("grant:" + database + ":" + identity(user, host));
        }

        private static String identity(String user, String host) {
            return host == null ? user : user + "@" + host;
        }
    }
}

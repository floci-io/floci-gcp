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
                "create-db:appdb",
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
        public void createDatabase(Map<String, Object> instanceMetadata, String database) {
            events.add("create-db:" + database);
        }

        @Override
        public void deleteDatabase(Map<String, Object> instanceMetadata, String database) {
            events.add("delete-db:" + database);
        }

        @Override
        public void createOrUpdateUser(Map<String, Object> instanceMetadata, String user, String password) {
            events.add("create-user:" + user + ":" + password);
        }

        @Override
        public void deleteUser(Map<String, Object> instanceMetadata, String user, Iterable<String> databases) {
            events.add("delete-user:" + user + ":" + String.join(",", databases));
        }

        @Override
        public void grantDatabaseAccess(Map<String, Object> instanceMetadata, String database, String user) {
            events.add("grant:" + database + ":" + user);
        }
    }
}

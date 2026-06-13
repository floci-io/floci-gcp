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
                "databaseVersion", "POSTGRES_15"));

        withProject("project-b");
        assertThrows(GcpException.class, () -> service.getInstance("project-b", "pg-main"));

        service.createInstance("project-b", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_16"));

        assertEquals("POSTGRES_16", service.getInstance("project-b", "pg-main").get("databaseVersion"));

        withProject("project-a");
        assertEquals("POSTGRES_15", service.getInstance("project-a", "pg-main").get("databaseVersion"));
    }

    @Test
    void deleteInstanceCascadesDatabasesAndUsers() {
        withProject("project-a");
        service.createInstance("project-a", Map.of(
                "name", "pg-main",
                "databaseVersion", "POSTGRES_15"));
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
                "databaseVersion", "POSTGRES_15",
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
}

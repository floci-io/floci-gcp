package io.floci.gcp.services.cloudrun;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CloudRunIamAuthorizationAdapterServiceTest {
    private final CloudRunIamAuthorizationAdapter adapter = new CloudRunIamAuthorizationAdapter();
    private final Map<String, String> path = Map.of("project", "p", "location", "us-central1",
            "serviceId", "connector-a", "revisionId", "connector-a-00001");

    @Test
    void everyServiceControllerHandlerHasAnExplicitPermissionMapping() {
        Map<String, String> permissions = Map.ofEntries(
                Map.entry("createService", "run.services.create"),
                Map.entry("listServices", "run.services.list"),
                Map.entry("getService", "run.services.get"),
                Map.entry("updateService", "run.services.update"),
                Map.entry("deleteService", "run.services.delete"),
                Map.entry("getIamPolicy", "run.services.getIamPolicy"),
                Map.entry("setIamPolicy", "run.services.setIamPolicy"),
                Map.entry("listRevisions", "run.revisions.list"),
                Map.entry("getRevision", "run.revisions.get"),
                Map.entry("deleteRevision", "run.revisions.delete"));
        Set<String> handlers = Set.of("createService", "listServices", "getService", "updateService",
                "deleteService", "getIamPolicy", "setIamPolicy", "testIamPermissions", "listRevisions",
                "getRevision", "deleteRevision");
        Set<String> controllerHandlers = Arrays.stream(CloudRunController.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) && method.getReturnType() == Response.class)
                .map(Method::getName).collect(Collectors.toSet());
        assertEquals(handlers, controllerHandlers);
        for (String handler : handlers) {
            IamOperation operation = adapter.restOperation(handler, path, JsonNodeFactory.instance.objectNode());
            if (handler.equals("testIamPermissions")) {
                assertTrue(adapter.checks(operation).isEmpty());
            } else {
                assertEquals(permissions.get(handler), adapter.checks(operation).getFirst().permission());
            }
        }
        assertThrows(RuntimeException.class, () -> adapter.restOperation("unknown", path,
                JsonNodeFactory.instance.objectNode()));
    }

    @Test
    void createUsesLocationParentAndExistingServiceUsesServiceName() {
        IamPermissionCheck create = adapter.checks(adapter.restOperation("createService", path,
                JsonNodeFactory.instance.objectNode())).getFirst();
        assertEquals("projects/p/locations/us-central1", create.resource().name());
        IamPermissionCheck update = adapter.checks(adapter.restOperation("updateService", path,
                JsonNodeFactory.instance.objectNode())).getFirst();
        assertEquals("projects/p/locations/us-central1/services/connector-a", update.resource().name());
        assertEquals("projects/p", update.resource().projectResource());
        IamResource revision = adapter.checks(adapter.restOperation("getRevision", path,
                JsonNodeFactory.instance.objectNode())).getFirst().resource();
        assertEquals(update.resource().name(), revision.policyResource());
        assertTrue(adapter.resource(revision.name()).isEmpty());
        assertTrue(adapter.resource("projects/p/locations/us-central1/jobs/a").isEmpty());
    }

    @Test
    void wildcardRevisionListUsesLocationParent() {
        Map<String, String> wildcardPath = Map.of("project", "p", "location", "us-central1",
                "serviceId", "-");
        IamPermissionCheck check = adapter.checks(adapter.restOperation("listRevisions", wildcardPath,
                JsonNodeFactory.instance.objectNode())).getFirst();
        assertEquals("run.revisions.list", check.permission());
        assertEquals("projects/p/locations/us-central1", check.resource().name());
        assertEquals("projects/p", check.resource().projectResource());
    }
}

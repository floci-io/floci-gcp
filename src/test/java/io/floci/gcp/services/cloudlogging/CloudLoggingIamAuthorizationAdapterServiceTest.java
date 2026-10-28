package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.logging.v2.CreateSinkRequest;
import com.google.logging.v2.DeleteSinkRequest;
import com.google.logging.v2.GetSinkRequest;
import com.google.logging.v2.ListSinksRequest;
import com.google.logging.v2.LogSink;
import com.google.logging.v2.UpdateSinkRequest;
import io.floci.gcp.services.iam.authorization.IamOperation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CloudLoggingIamAuthorizationAdapterServiceTest {
    private final CloudLoggingIamAuthorizationAdapter adapter = new CloudLoggingIamAuthorizationAdapter();

    @Test
    void everySinkHandlerAndRpcMapsToTheSamePermission() {
        Set<String> handlers = Arrays.stream(CloudLoggingConfigHttpController.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertEquals(Set.of("createSink", "listSinks", "getSink", "updateSink", "patchSink", "deleteSink"), handlers);
        Set<String> grpcHandlers = Arrays.stream(CloudLoggingConfigController.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet());
        assertEquals(Set.of("createSink", "listSinks", "getSink", "updateSink", "deleteSink"), grpcHandlers);

        Map<String, String> parent = Map.of("parentType", "projects", "parentId", "test-project");
        Map<String, String> sink = Map.of("parentType", "projects", "parentId", "test-project", "sinkId", "one");
        ObjectMapper mapper = new ObjectMapper();
        assertCheck(adapter.restOperation("createSink", parent, mapper.createObjectNode().put("name", "one")),
                "logging.sinks.create", "projects/test-project/sinks/one");
        assertCheck(adapter.restOperation("listSinks", parent, mapper.createObjectNode()), "logging.sinks.list",
                "projects/test-project");
        assertCheck(adapter.restOperation("getSink", sink, mapper.createObjectNode()), "logging.sinks.get",
                "projects/test-project/sinks/one");
        assertCheck(adapter.restOperation("updateSink", sink, mapper.createObjectNode()), "logging.sinks.update",
                "projects/test-project/sinks/one");
        assertCheck(adapter.restOperation("patchSink", sink, mapper.createObjectNode()), "logging.sinks.update",
                "projects/test-project/sinks/one");
        assertCheck(adapter.restOperation("deleteSink", sink, mapper.createObjectNode()), "logging.sinks.delete",
                "projects/test-project/sinks/one");

        assertCheck(adapter.grpcOperation("CreateSink", CreateSinkRequest.newBuilder()
                .setParent("organizations/123").setSink(LogSink.newBuilder().setName("one"))
                .build()), "logging.sinks.create", "organizations/123/sinks/one");
        assertCheck(adapter.grpcOperation("ListSinks", ListSinksRequest.newBuilder()
                .setParent("organizations/123").build()), "logging.sinks.list", "organizations/123");
        assertCheck(adapter.grpcOperation("GetSink", GetSinkRequest.newBuilder()
                .setSinkName("organizations/123/sinks/one").build()), "logging.sinks.get",
                "organizations/123/sinks/one");
        assertCheck(adapter.grpcOperation("UpdateSink", UpdateSinkRequest.newBuilder()
                .setSinkName("organizations/123/sinks/one").build()), "logging.sinks.update",
                "organizations/123/sinks/one");
        assertCheck(adapter.grpcOperation("DeleteSink", DeleteSinkRequest.newBuilder()
                .setSinkName("organizations/123/sinks/one").build()), "logging.sinks.delete",
                "organizations/123/sinks/one");
    }

    @Test
    void unsupportedParentsAndUnknownMethodsFailClosed() {
        assertTrue(adapter.resource("folders/123/sinks/one").isEmpty());
        assertTrue(adapter.resource("billingAccounts/123/sinks/one").isEmpty());
        assertThrows(RuntimeException.class,
                () -> adapter.checks(new IamOperation("CreateSink", "folders/123")));
        assertThrows(RuntimeException.class,
                () -> adapter.restOperation("futureMethod", Map.of(), new ObjectMapper().createObjectNode()));
        String sink = "projects/test-project/sinks/one";
        assertTrue(adapter.supportsIamPolicyMethod("TestIamPermissions", sink));
        assertFalse(adapter.supportsIamPolicyMethod("GetIamPolicy", sink));
        assertFalse(adapter.supportsIamPolicyMethod("SetIamPolicy", sink));
        assertTrue(adapter.resource("organizations/123").isEmpty());
        assertFalse(adapter.supportsIamPolicyMethod("SetIamPolicy", "organizations/123"));
        assertEquals("projects/example.com:my-project/sinks/one", adapter.requireResource(
                "projects/example.com:my-project/sinks/one").name());
        assertCheck(adapter.restOperation("createSink",
                        Map.of("parentType", "projects", "parentId", "example.com:my-project"),
                        new ObjectMapper().createObjectNode().put("name", "one")),
                "logging.sinks.create", "projects/example.com:my-project/sinks/one");
        assertThrows(RuntimeException.class,
                () -> adapter.checks(new IamOperation("SetIamPolicy", sink)));
    }

    private void assertCheck(IamOperation operation, String permission, String resource) {
        assertEquals(permission, adapter.checks(operation).getFirst().permission());
        assertEquals(resource, adapter.checks(operation).getFirst().resource().name());
        assertEquals("logging.googleapis.com", adapter.checks(operation).getFirst().resource().service());
        assertEquals("logging.googleapis.com/LogSink", adapter.checks(operation).getFirst().resource().type());
    }
}

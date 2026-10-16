package io.floci.gcp.services.secretmanager;

import com.google.cloud.secretmanager.v1.AccessSecretVersionRequest;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.UpdateSecretRequest;
import com.google.iam.v1.Policy;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretManagerIamAuthorizationAdapterServiceTest {
    private static final String PROJECT = "projects/p";
    private static final String SECRET = PROJECT + "/secrets/s";
    private static final String VERSION = SECRET + "/versions/1";
    private final SecretManagerIamAuthorizationAdapter adapter = new SecretManagerIamAuthorizationAdapter();

    @Test
    void grpcRequestsMapWithoutProtobufJsonReflection() {
        IamAuthorizationAdapter grpcAdapter = adapter;
        assertEquals(new IamOperation("CreateSecret", PROJECT), grpcAdapter.grpcOperation("CreateSecret",
                CreateSecretRequest.newBuilder().setParent(PROJECT).build()));
        assertEquals(new IamOperation("UpdateSecret", SECRET), grpcAdapter.grpcOperation("UpdateSecret",
                UpdateSecretRequest.newBuilder().setSecret(Secret.newBuilder().setName(SECRET)).build()));
        assertEquals(new IamOperation("AccessSecretVersion", VERSION), grpcAdapter.grpcOperation("AccessSecretVersion",
                AccessSecretVersionRequest.newBuilder().setName(VERSION).build()));
        GcpException unmapped = assertThrows(GcpException.class,
                () -> grpcAdapter.grpcOperation("UnknownMethod", Policy.getDefaultInstance()));
        assertEquals(Status.Code.FAILED_PRECONDITION, unmapped.getGrpcCode());
    }

    @Test
    void everyImplementedMethodHasAnExplicitPermissionOrPermissionQuery() {
        Map<String, String> permissions = Map.ofEntries(
                Map.entry("CreateSecret", "secretmanager.secrets.create"),
                Map.entry("ListSecrets", "secretmanager.secrets.list"),
                Map.entry("GetSecret", "secretmanager.secrets.get"),
                Map.entry("UpdateSecret", "secretmanager.secrets.update"),
                Map.entry("DeleteSecret", "secretmanager.secrets.delete"),
                Map.entry("AddSecretVersion", "secretmanager.versions.add"),
                Map.entry("ListSecretVersions", "secretmanager.versions.list"),
                Map.entry("GetSecretVersion", "secretmanager.versions.get"),
                Map.entry("AccessSecretVersion", "secretmanager.versions.access"),
                Map.entry("EnableSecretVersion", "secretmanager.versions.enable"),
                Map.entry("DisableSecretVersion", "secretmanager.versions.disable"),
                Map.entry("DestroySecretVersion", "secretmanager.versions.destroy"),
                Map.entry("GetIamPolicy", "secretmanager.secrets.getIamPolicy"),
                Map.entry("SetIamPolicy", "secretmanager.secrets.setIamPolicy"));
        for (Map.Entry<String, String> entry : permissions.entrySet()) {
            String resource = switch (entry.getKey()) {
                case "CreateSecret", "ListSecrets" -> PROJECT;
                case "GetSecretVersion", "AccessSecretVersion", "EnableSecretVersion",
                        "DisableSecretVersion", "DestroySecretVersion" -> VERSION;
                default -> SECRET;
            };
            List<IamPermissionCheck> checks = adapter.checks(new IamOperation(entry.getKey(), resource));
            assertEquals(1, checks.size(), entry.getKey());
            assertEquals(entry.getValue(), checks.getFirst().permission(), entry.getKey());
            assertEquals(resource, checks.getFirst().resource().name(), entry.getKey());
        }
        assertEquals(List.of(), adapter.checks(new IamOperation("TestIamPermissions", SECRET)));
        for (String method : List.of("GetIamPolicy", "SetIamPolicy", "TestIamPermissions")) {
            assertThrows(GcpException.class, () -> adapter.checks(new IamOperation(method, VERSION)));
        }
        assertThrows(GcpException.class, () -> adapter.checks(new IamOperation("UnknownMethod", SECRET)));

        Set<String> grpcMethods = Arrays.stream(SecretManagerController.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 2
                        && StreamObserver.class.isAssignableFrom(method.getParameterTypes()[1]))
                .map(method -> Character.toUpperCase(method.getName().charAt(0)) + method.getName().substring(1))
                .collect(Collectors.toSet());
        assertEquals(permissions.keySet(), grpcMethods.stream()
                .filter(method -> !method.equals("TestIamPermissions")).collect(Collectors.toSet()));

        Set<String> restMethods = Arrays.stream(SecretManagerHttpController.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getReturnType() == Response.class)
                .map(Method::getName)
                .map(method -> Character.toUpperCase(method.charAt(0)) + method.substring(1))
                .collect(Collectors.toSet());
        assertEquals(Set.of("GetIamPolicyWithPost", "SetIamPolicyWithGet", "TestIamPermissionsWithGet"),
                restMethods.stream().filter(method -> !permissions.containsKey(method)
                        && !method.equals("TestIamPermissions")).collect(Collectors.toSet()));
        assertEquals(permissions.keySet(), restMethods.stream()
                .filter(permissions::containsKey).collect(Collectors.toSet()));
    }

    @Test
    void versionsUseSecretPolicyAndProjectInheritance() {
        IamResource resource = adapter.requireResource(VERSION);
        assertEquals("secretmanager.googleapis.com", resource.service());
        assertEquals("secretmanager.googleapis.com/SecretVersion", resource.type());
        assertEquals(VERSION, resource.name());
        assertEquals(SECRET, resource.policyResource());
        assertEquals(PROJECT, resource.projectResource());
    }
}

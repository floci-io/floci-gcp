package io.floci.gcp.services.iam.grpc;

import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamServiceAccountPolicyGrpcIntegrationTest.EnforceProfile.class)
class IamServiceAccountPolicyGrpcIntegrationTest {

    @Inject
    CredentialTokenService tokens;
    @Inject
    IamService iam;
    @TestHTTPResource
    URI endpoint;

    private String project;
    private String target;
    private String sibling;
    private String callerEmail;
    private ManagedChannel channel;
    private IAMPolicyGrpc.IAMPolicyBlockingStub stub;

    @BeforeEach
    void setUp() {
        project = "iam-grpc-" + UUID.randomUUID().toString().substring(0, 8);
        callerEmail = "caller@" + project + ".iam.gserviceaccount.com";
        target = "projects/" + project + "/serviceAccounts/target@" + project + ".iam.gserviceaccount.com";
        sibling = "projects/" + project + "/serviceAccounts/sibling@" + project + ".iam.gserviceaccount.com";
        iam.createServiceAccount(project, "target", "target account", "");
        iam.createServiceAccount(project, "sibling", "sibling account", "");
        String authorization = "Bearer " + tokens.mintImpersonatedToken(callerEmail,
                Instant.now().plusSeconds(300)).getTokenValue();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), authorization);
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext()
                .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
                .build();
        stub = IAMPolicyGrpc.newBlockingStub(channel);
        setProjectPolicy(target);
    }

    @AfterEach
    void closeChannel() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void permissionTestsReturnEmptyAfterAccountDeletionEvenWithProjectGrants() {
        String id = iam.getServiceAccount(project, "target").getUniqueId();
        iam.deleteServiceAccount(project, "target");
        for (String resource : List.of(target, "projects/" + project + "/serviceAccounts/target",
                "projects/" + project + "/serviceAccounts/" + id)) {
            assertTrue(stub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .testIamPermissions(TestIamPermissionsRequest.newBuilder().setResource(resource)
                            .addPermissions("iam.serviceAccounts.get").build()).getPermissionsList().isEmpty());
        }
        StatusRuntimeException missing = assertThrows(StatusRuntimeException.class,
                () -> stub.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(target).build()));
        assertEquals(Status.Code.NOT_FOUND, missing.getStatus().getCode());
        StatusRuntimeException missingWrite = assertThrows(StatusRuntimeException.class,
                () -> stub.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(target)
                        .setPolicy(Policy.getDefaultInstance()).build()));
        assertEquals(Status.Code.NOT_FOUND, missingWrite.getStatus().getCode());
    }

    @Test
    void serviceAccountPolicyGrpcUsesProjectConditionAndFiltersTestPermissions() {
        assertEquals(0, stub.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(target).build())
                .getBindingsCount());

        StatusRuntimeException denied = assertThrows(StatusRuntimeException.class,
                () -> stub.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(sibling).build()));
        assertEquals(Status.Code.PERMISSION_DENIED, denied.getStatus().getCode());

        assertEquals(List.of("iam.serviceAccounts.get", "iam.serviceAccounts.update"),
                stub.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(target)
                        .addAllPermissions(List.of("iam.serviceAccounts.get", "iam.serviceAccounts.update",
                                "iam.serviceAccounts.signBlob"))
                        .build())
                        .getPermissionsList());
    }

    @Test
    void aliasesUseTheSamePolicyAndConditionResource() {
        String id = iam.getServiceAccount(project, "target").getUniqueId();
        for (String resource : List.of("projects/-/serviceAccounts/" + id,
                "projects/" + project + "/serviceAccounts/target")) {
            assertEquals(0, stub.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(resource).build())
                    .getBindingsCount());
            stub.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(resource)
                    .setPolicy(Policy.getDefaultInstance()).build());
            assertTrue(iam.getPolicy(target).getBindings().isEmpty());
        }
    }

    @Test
    void deniedSetIamPolicyLeavesSiblingPolicyUnchanged() {
        Policy requested = Policy.newBuilder().addBindings(Binding.newBuilder()
                .setRole("roles/iam.serviceAccountTokenCreator")
                .addMembers("serviceAccount:" + callerEmail)).build();
        StatusRuntimeException denied = assertThrows(StatusRuntimeException.class,
                () -> stub.setIamPolicy(SetIamPolicyRequest.newBuilder()
                        .setResource(sibling).setPolicy(requested).build()));
        assertEquals(Status.Code.PERMISSION_DENIED, denied.getStatus().getCode());
        assertTrue(iam.getPolicy(sibling).getBindings().isEmpty());

        assertEquals("roles/iam.serviceAccountTokenCreator", stub.setIamPolicy(SetIamPolicyRequest.newBuilder()
                .setResource(target).setPolicy(requested).build()).getBindings(0).getRole());
    }

    @Test
    void historicalAliasPoliciesMigrateOnlyAfterSuccessfulAuthorization() {
        String id = iam.getServiceAccount(project, "target").getUniqueId();
        iam.deletePolicy("projects/" + project);
        for (String alias : List.of("projects/" + project + "/serviceAccounts/target",
                "projects/" + project + "/serviceAccounts/" + id,
                "projects/-/serviceAccounts/target@" + project + ".iam.gserviceaccount.com",
                "projects/-/serviceAccounts/" + id)) {
            StoredPolicy legacy = new StoredPolicy();
            legacy.setBindings(List.of(Map.of("role", "roles/iam.serviceAccountAdmin",
                    "members", List.of("serviceAccount:" + callerEmail))));
            String etag = iam.setPolicy(alias, legacy).getEtag();
            Policy policy = stub.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(alias).build());
            assertEquals(1, policy.getBindingsCount());
            assertEquals(etag, iam.getPolicy(target).getEtag());
            assertTrue(iam.getPolicy(alias).getBindings().isEmpty());
            iam.deletePolicy(target);
        }
    }

    private void setProjectPolicy(String resourceName) {
        StoredPolicy policy = new StoredPolicy();
        policy.setVersion(3);
        policy.setBindings(List.of(Map.of(
                "role", "roles/iam.serviceAccountAdmin",
                "members", List.of("serviceAccount:" + callerEmail),
                "condition", Map.of("title", "target account only",
                        "expression", "resource.name == '" + resourceName + "'"))));
        iam.setPolicy("projects/" + project, policy);
    }

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }
}

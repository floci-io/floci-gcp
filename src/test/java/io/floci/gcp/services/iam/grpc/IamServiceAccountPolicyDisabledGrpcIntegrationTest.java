package io.floci.gcp.services.iam.grpc;

import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.iam.IamService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(IamServiceAccountPolicyDisabledGrpcIntegrationTest.DisabledProfile.class)
class IamServiceAccountPolicyDisabledGrpcIntegrationTest {
    @Inject
    CredentialTokenService tokens;
    @Inject
    IamService iam;
    @TestHTTPResource
    URI endpoint;

    @Test
    void recognizedCallerCanReadAndWritePoliciesWithoutGrantsWhenDisabled() throws InterruptedException {
        String project = "iam-grpc-disabled-" + UUID.randomUUID().toString().substring(0, 8);
        String resource = iam.createServiceAccount(project, "target", "target", "").getName();
        String email = "caller@" + project + ".iam.gserviceaccount.com";
        String token = tokens.mintImpersonatedToken(email, Instant.now().plusSeconds(300)).getTokenValue();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().intercept(MetadataUtils.newAttachHeadersInterceptor(headers)).build();
        try {
            IAMPolicyGrpc.IAMPolicyBlockingStub stub = IAMPolicyGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);
            assertEquals(0, stub.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(resource).build()).getBindingsCount());
            Policy policy = Policy.newBuilder().addBindings(Binding.newBuilder().setRole("roles/iam.serviceAccountAdmin")
                    .addMembers("serviceAccount:" + email)).build();
            assertEquals(1, stub.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(resource)
                    .setPolicy(policy).build()).getBindingsCount());
            List<String> permissions = List.of("iam.serviceAccounts.update", "iam.serviceAccounts.signBlob");
            assertEquals(permissions, stub.testIamPermissions(TestIamPermissionsRequest.newBuilder().setResource(resource)
                    .addAllPermissions(permissions).build()).getPermissionsList());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "disabled");
        }
    }
}

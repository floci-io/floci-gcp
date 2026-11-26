package io.floci.gcp.services.iam;

import com.google.iam.v1.Binding;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.testing.IamEnforceProfile;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforceProfile.class)
class IamWorkloadIdentityPoolEnforcementIntegrationTest {

    private static final String ADMIN_ROLE = "roles/iam.workloadIdentityPoolAdmin";
    private static final String VIEWER_ROLE = "roles/iam.workloadIdentityPoolViewer";

    @Inject
    CredentialTokenService tokens;
    @Inject
    IamService iam;
    @Inject
    IamRoleCatalog roles;
    @Inject
    IamServiceAccountAuthorizationAdapter adapter;
    @Inject
    IamAuthorizationService authorization;

    @TestHTTPResource
    URI endpoint;

    private String project;
    private String callerEmail;
    private String callerAuthorization;
    private String viewerEmail;
    private String viewerAuthorization;

    @BeforeEach
    void setUp() {
        project = "iam-wif-" + UUID.randomUUID().toString().substring(0, 8);
        callerEmail = "caller@" + project + ".iam.gserviceaccount.com";
        viewerEmail = "viewer@" + project + ".iam.gserviceaccount.com";
        callerAuthorization = bearer(callerEmail);
        viewerAuthorization = bearer(viewerEmail);
    }

    @Test
    void createPoolRequiresThePoolAdminPermission() {
        String poolId = "pool-" + suffix();
        String path = collection();

        given().header("Authorization", callerAuthorization).contentType("application/json").body(Map.of())
                .queryParam("workloadIdentityPoolId", poolId)
                .when().post(path)
                .then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));

        grant("projects/" + project, ADMIN_ROLE, callerEmail);

        given().header("Authorization", callerAuthorization).contentType("application/json").body(Map.of())
                .queryParam("workloadIdentityPoolId", poolId)
                .when().post(path)
                .then().statusCode(200)
                .body("done", equalTo(true));

        given().header("Authorization", callerAuthorization)
                .when().get(item(poolId))
                .then().statusCode(200)
                .body("state", equalTo("ACTIVE"));

        given().header("Authorization", callerAuthorization)
                .when().get(collection())
                .then().statusCode(200);
    }

    @Test
    void aPoolViewerCanReadButNotUpdateOrListAtProjectScope() {
        String poolId = "pool-" + suffix();
        grant("projects/" + project, ADMIN_ROLE, callerEmail);
        createPool(poolId);

        given().header("Authorization", viewerAuthorization).urlEncodingEnabled(false)
                .when().get(item(poolId) + ":listAttestationRules")
                .then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));

        grant(policyName(poolId), VIEWER_ROLE, viewerEmail);

        given().header("Authorization", viewerAuthorization)
                .when().get(item(poolId))
                .then().statusCode(200)
                .body("state", equalTo("ACTIVE"));

        given().header("Authorization", viewerAuthorization)
                .urlEncodingEnabled(false)
                .when().get(item(poolId) + ":listAttestationRules")
                .then().statusCode(200)
                .body("", equalTo(Map.of()));

        given().header("Authorization", viewerAuthorization).contentType("application/json")
                .body(Map.of("displayName", "viewer update"))
                .queryParam("updateMask", "displayName")
                .when().patch(item(poolId))
                .then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));

        given().header("Authorization", viewerAuthorization)
                .when().get(collection())
                .then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void providerPermissionsInheritFromThePool() {
        String poolId = "pool-" + suffix();
        String providerId = "provider-" + suffix();
        grant("projects/" + project, ADMIN_ROLE, callerEmail);
        createPool(poolId);

        given().header("Authorization", callerAuthorization).contentType("application/json")
                .body(Map.of("attributeMapping", Map.of("google.subject", "assertion.sub"),
                        "oidc", Map.of("issuerUri", "https://example.com")))
                .queryParam("workloadIdentityPoolProviderId", providerId)
                .when().post(item(poolId) + "/providers")
                .then().statusCode(200);

        grant(policyName(poolId), VIEWER_ROLE, viewerEmail);

        given().header("Authorization", viewerAuthorization)
                .when().get(item(poolId) + "/providers/" + providerId)
                .then().statusCode(200)
                .body("state", equalTo("ACTIVE"));

        given().header("Authorization", viewerAuthorization)
                .when().delete(item(poolId) + "/providers/" + providerId)
                .then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void sharedGrpcPoolPolicyChecksPoolPermissions() throws Exception {
        String poolId = "pool-" + suffix();
        String otherEmail = "other@" + project + ".iam.gserviceaccount.com";
        String otherAuthorization = bearer(otherEmail);
        grant("projects/" + project, ADMIN_ROLE, callerEmail);
        createPool(poolId);
        grant("projects/" + project, "roles/iam.serviceAccountAdmin", otherEmail);

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext()
                .build();
        try {
            IAMPolicyGrpc.IAMPolicyBlockingStub policy = IAMPolicyGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authorization(otherAuthorization)));
            Policy request = Policy.newBuilder().addBindings(Binding.newBuilder()
                    .setRole(VIEWER_ROLE)
                    .addMembers("serviceAccount:" + viewerEmail)).build();
            SetIamPolicyRequest set = SetIamPolicyRequest.newBuilder()
                    .setResource(name(poolId))
                    .setPolicy(request)
                    .build();

            StatusRuntimeException denied = assertThrows(StatusRuntimeException.class, () -> policy.setIamPolicy(set));
            assertEquals(Status.Code.PERMISSION_DENIED, denied.getStatus().getCode());

            grant(policyName(poolId), ADMIN_ROLE, otherEmail);
            assertEquals(ADMIN_ROLE,
                    iam.getPolicy(policyName(poolId)).getBindings().getFirst().get("role"));
            assertTrue(roles.grants(ADMIN_ROLE,
                    "iam.googleapis.com/workloadIdentityPools.setIamPolicy", policyName(poolId)));
            IamResource resource = adapter.requireResource(name(poolId));
            assertEquals(policyName(poolId), resource.policyResource());
            assertTrue(authorization.testPermissions(otherAuthorization, adapter, resource,
                    List.of("iam.googleapis.com/workloadIdentityPools.setIamPolicy"))
                    .contains("iam.googleapis.com/workloadIdentityPools.setIamPolicy"));
            policy.setIamPolicy(set);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }

        grant(policyName(poolId), VIEWER_ROLE, viewerEmail);
        given().header("Authorization", viewerAuthorization)
                .when().get(item(poolId))
                .then().statusCode(200);
    }

    private static Metadata authorization(String authorization) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), authorization);
        return metadata;
    }

    private String name(String poolId) {
        return "projects/" + ProjectNumbers.of(project)
                + "/locations/global/workloadIdentityPools/" + poolId;
    }

    private void createPool(String poolId) {
        given().header("Authorization", callerAuthorization).contentType("application/json").body(Map.of())
                .queryParam("workloadIdentityPoolId", poolId)
                .when().post(collection())
                .then().statusCode(200);
    }

    private void grant(String resource, String role, String email) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", role, "members", List.of("serviceAccount:" + email))));
        iam.setPolicy(resource, policy);
    }

    private String bearer(String email) {
        return "Bearer " + tokens.mintImpersonatedToken(email, Instant.now().plusSeconds(300)).getTokenValue();
    }

    private String collection() {
        return "/v1/projects/" + project + "/locations/global/workloadIdentityPools";
    }

    private String item(String poolId) {
        return collection() + "/" + poolId;
    }

    private String policyName(String poolId) {
        return "projects/" + project + "/locations/global/workloadIdentityPools/" + poolId;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}

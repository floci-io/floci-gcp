package io.floci.gcp.services.iam;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.testing.IamEnforceProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IamEnforceProfile.class)
class IamWorkloadIdentityPoolEnforcementIntegrationTest {

    private static final String ADMIN_ROLE = "roles/iam.workloadIdentityPoolAdmin";
    private static final String VIEWER_ROLE = "roles/iam.workloadIdentityPoolViewer";

    @Inject
    CredentialTokenService tokens;
    @Inject
    IamService iam;

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

        grant(policyName(poolId), VIEWER_ROLE, viewerEmail);

        given().header("Authorization", viewerAuthorization)
                .when().get(item(poolId))
                .then().statusCode(200)
                .body("state", equalTo("ACTIVE"));

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

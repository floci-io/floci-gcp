package io.floci.gcp.services.iam;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(IamBucketPolicyBootstrapRestIntegrationTest.EnforceAuthorizationProfile.class)
class IamBucketPolicyBootstrapRestIntegrationTest {

    private static final String BOOTSTRAP = "bootstrap@test-project.iam.gserviceaccount.com";
    private static final String CREATOR = "creator@test-project.iam.gserviceaccount.com";

    @Inject
    CredentialTokenService tokenService;

    @Test
    void grantsBucketCreatorStorageAdminWhenTheCallerHasAResolvableIdentity() {
        String authorization = bearer(CREATOR);
        String bucket = createBucket(authorization);

        given().header("Authorization", authorization)
                .when().get("/storage/v1/b/" + bucket).then().statusCode(200);
        given().when().get("/storage/v1/b/" + bucket).then().statusCode(403);
    }

    @Test
    void configuredBootstrapAdminCanManageBucketsCreatedWithoutAnIdentity() {
        String bucket = createBucket(null);
        String authorization = bearer(BOOTSTRAP);

        given().header("Authorization", authorization)
                .when().get("/storage/v1/b/" + bucket).then().statusCode(200);
        given().when().get("/storage/v1/b/" + bucket).then().statusCode(403);
    }

    private String createBucket(String authorization) {
        String bucket = "iam-bootstrap-" + UUID.randomUUID().toString().substring(0, 8);
        var request = given().contentType("application/json").body(Map.of("name", bucket));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        request.when().post("/storage/v1/b?project=test-project").then().statusCode(200);
        return bucket;
    }

    private String bearer(String serviceAccount) {
        return "Bearer " + tokenService.mintImpersonatedToken(serviceAccount, Instant.now().plusSeconds(600))
                .getTokenValue();
    }

    public static class EnforceAuthorizationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.services.iam.authorization-mode", "enforce",
                    "floci-gcp.services.iam.bootstrap-admin-member", "serviceAccount:" + BOOTSTRAP);
        }
    }
}

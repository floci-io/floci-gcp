package io.floci.gcp.core.common;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(TokenValidationIntegrationTest.ValidateTokensProfile.class)
class TokenValidationIntegrationTest {

    private static final String SERVICE_ACCOUNTS =
            "/v1/projects/test-project/serviceAccounts";

    @Test
    void missingAuthReturns401() {
        given()
                .when().get(SERVICE_ACCOUNTS)
                .then()
                .statusCode(401)
                .body("error.status", equalTo("UNAUTHENTICATED"))
                .body("error.code", equalTo(401));
    }

    @Test
    void badBearerTokenReturns401() {
        given()
                .header("Authorization", "Bearer bad-token")
                .when().get(SERVICE_ACCOUNTS)
                .then()
                .statusCode(401)
                .body("error.status", equalTo("UNAUTHENTICATED"))
                .body("error.code", equalTo(401));
    }

    @Test
    void rootBearerTokenSucceeds() {
        given()
                .header("Authorization", "Bearer root-token-test")
                .when().get(SERVICE_ACCOUNTS)
                .then()
                .statusCode(200);
    }

    @Test
    void healthStaysOpenWithoutAuth() {
        given()
                .when().get("/health")
                .then()
                .statusCode(200);
    }

    public static class ValidateTokensProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.auth.validate-tokens", "true",
                    "floci-gcp.auth.root-service-account",
                    "operator@test-project.iam.gserviceaccount.com",
                    "floci-gcp.auth.root-access-token", "root-token-test",
                    "floci-gcp.services.iam.enforcement-enabled", "false");
        }
    }
}

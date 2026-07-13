package io.floci.gcp;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Fail-closed: when IAM enforcement is on, missing principal is denied even if
 * validate-tokens and strict-enforcement are both off.
 */
@QuarkusTest
@TestProfile(IamEnforcementMissingPrincipalIntegrationTest.Profile.class)
class IamEnforcementMissingPrincipalIntegrationTest {

    private static final String LIST_PATH = "/v1/projects/test-project/serviceAccounts";

    @Test
    void missingAuthorizationIsDeniedWhenEnforcementOn() {
        given()
                .when().get(LIST_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.auth.validate-tokens", "false",
                    "floci-gcp.services.iam.enforcement-enabled", "true",
                    "floci-gcp.services.iam.strict-enforcement-enabled", "false");
        }
    }
}

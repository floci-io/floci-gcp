package io.floci.gcp.services.iamcredentials;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IamCredentialsDisabledRestIntegrationTest.DisabledIamCredentialsProfile.class)
class IamCredentialsDisabledRestIntegrationTest {

    @Test
    void disabledIamCredentialsReturnsUnavailableWrapper() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of("scope", List.of(IamCredentialsService.DEVSTORAGE_READ_WRITE_SCOPE)))
                .when().post("/v1/projects/-/serviceAccounts/test@test-project.iam.gserviceaccount.com:generateAccessToken")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Service iamcredentials is not enabled."));
    }

    @Test
    void disabledIamCredentialsAlsoCoversSignBlobAndSignJwt() {
        for (String method : List.of("signBlob", "signJwt")) {
            given()
                    .urlEncodingEnabled(false)
                    .contentType("application/json")
                    .body(Map.of("payload", "e30="))
                    .when().post("/v1/projects/-/serviceAccounts/test@test-project.iam.gserviceaccount.com:" + method)
                    .then()
                    .statusCode(503)
                    .body("error.status", equalTo("UNAVAILABLE"));
        }
    }

    @Test
    void disabledIamCredentialsAlsoCoversGenerateIdToken() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of("audience", "https://foo.bar"))
                .when().post("/v1/projects/-/serviceAccounts/test@test-project.iam.gserviceaccount.com:generateIdToken")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"));
    }

    public static class DisabledIamCredentialsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iamcredentials.enabled", "false");
        }
    }
}

package io.floci.gcp.services.credentials;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(OAuthTokenGcsDisabledRestIntegrationTest.DisabledGcsProfile.class)
class OAuthTokenGcsDisabledRestIntegrationTest {

    @Test
    void exchangesTokenWhenGcsIsDisabled() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .formParam("assertion", "header.payload.signature")
                .when().post("/token")
                .then()
                .statusCode(200)
                .body("token_type", equalTo("Bearer"));
    }

    public static class DisabledGcsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.gcs.enabled", "false");
        }
    }
}

package io.floci.gcp.services.secretmanager;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(SecretManagerDisabledRestIntegrationTest.DisabledSecretManagerProfile.class)
class SecretManagerDisabledRestIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/projects/sm-disabled/secrets",
            "/v1/projects/sm-disabled/locations/us-central1/secrets"
    })
    void disabledSecretManagerReturnsUnavailableOnGlobalAndRegionalRoutes(String path) {
        given()
                .when().get(path)
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Service secretmanager is not enabled."));
    }

    public static class DisabledSecretManagerProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.secretmanager.enabled", "false");
        }
    }
}

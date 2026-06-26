package io.floci.gcp.services.pubsub;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(PubSubDisabledRestIntegrationTest.DisabledPubSubProfile.class)
class PubSubDisabledRestIntegrationTest {

    @Test
    void disabledPubSubRestServiceReturnsUnavailableWrapper() {
        given()
                .when().get("/v1/projects/pubsub-disabled/topics")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Service pubsub is not enabled."));
    }

    public static class DisabledPubSubProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.pubsub.enabled", "false");
        }
    }
}

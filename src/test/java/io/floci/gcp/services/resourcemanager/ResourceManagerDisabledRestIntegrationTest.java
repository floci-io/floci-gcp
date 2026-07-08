package io.floci.gcp.services.resourcemanager;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(ResourceManagerDisabledRestIntegrationTest.DisabledResourceManagerProfile.class)
class ResourceManagerDisabledRestIntegrationTest {

    @Test
    void disabledResourceManagerReturnsUnavailableWrapper() {
        given()
                .when().get("/v1/projects/crm-disabled")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Service resourcemanager is not enabled."));
    }

    public static class DisabledResourceManagerProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.resourcemanager.enabled", "false");
        }
    }
}

package io.floci.gcp.services.serviceusage;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(ServiceUsageDisabledRestIntegrationTest.DisabledServiceUsageProfile.class)
class ServiceUsageDisabledRestIntegrationTest {

    @Test
    void disabledServiceUsageReturnsUnavailableWrapper() {
        given()
                .when().get("/v1/projects/su-disabled/services")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Service serviceusage is not enabled."));
    }

    public static class DisabledServiceUsageProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.serviceusage.enabled", "false");
        }
    }
}

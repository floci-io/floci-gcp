package io.floci.gcp.services.cloudmonitoring;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(CloudMonitoringDisabledRestIntegrationTest.DisabledMonitoringProfile.class)
class CloudMonitoringDisabledRestIntegrationTest {

    @Test
    void disabledMonitoringRestServiceReturnsUnavailableWrapper() {
        given()
                .when().get("/v3/projects/monitoring-disabled/metricDescriptors")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Service monitoring is not enabled."));
    }

    public static class DisabledMonitoringProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.monitoring.enabled", "false");
        }
    }
}

package io.floci.gcp.services.cloudsql;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(CloudSqlDisabledRestIntegrationTest.DisabledCloudSqlProfile.class)
class CloudSqlDisabledRestIntegrationTest {

    @Test
    void disabledCloudSqlServiceReturnsUnavailableWrapper() {
        given()
                .when().get("/v1/projects/sql-disabled/instances")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Service cloudsql is not enabled."));
    }

    public static class DisabledCloudSqlProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.cloudsql.enabled", "false");
        }
    }
}

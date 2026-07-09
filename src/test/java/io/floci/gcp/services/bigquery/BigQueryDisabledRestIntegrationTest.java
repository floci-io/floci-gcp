package io.floci.gcp.services.bigquery;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(BigQueryDisabledRestIntegrationTest.DisabledBigQueryProfile.class)
class BigQueryDisabledRestIntegrationTest {

    @Test
    void disabledBigQueryRestServiceReturnsUnavailableWrapper() {
        given()
                .when().get("/bigquery/v2/projects/bq-disabled/datasets")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Service bigquery is not enabled."));
    }

    public static class DisabledBigQueryProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.bigquery.enabled", "false");
        }
    }
}

package io.floci.gcp.services.operations;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * With every API on the shared v1 operations route disabled, an operation name nobody owns is
 * refused as a disabled service's own routes are, rather than reported missing.
 */
@QuarkusTest
@TestProfile(LocationOperationsV1DisabledRestIntegrationTest.AllDisabledProfile.class)
class LocationOperationsV1DisabledRestIntegrationTest {

    private static final String OPERATIONS = "/v1/projects/ops-disabled/locations/us-central1/operations";

    @Test
    void anUnknownOperationIsRefusedWhenEveryOwningServiceIsDisabled() {
        given()
                .when().get(OPERATIONS + "/unknown")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"));
        given()
                .when().delete(OPERATIONS + "/unknown")
                .then()
                .statusCode(503);
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post(OPERATIONS + "/unknown:cancel")
                .then()
                .statusCode(503);
    }

    public static class AllDisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.kafka.enabled", "false",
                    "floci-gcp.services.eventarc.enabled", "false");
        }
    }
}

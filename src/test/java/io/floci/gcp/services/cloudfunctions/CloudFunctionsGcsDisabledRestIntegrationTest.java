package io.floci.gcp.services.cloudfunctions;

import io.floci.gcp.testing.GcsDisabledProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(GcsDisabledProfile.class)
class CloudFunctionsGcsDisabledRestIntegrationTest {

    @Test
    void generateUploadUrlRequiresGcsService() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v2/projects/functions-gcs-disabled/locations/us-central1/functions:generateUploadUrl")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Cloud Functions source upload requires Cloud Storage to be enabled."));
    }
}

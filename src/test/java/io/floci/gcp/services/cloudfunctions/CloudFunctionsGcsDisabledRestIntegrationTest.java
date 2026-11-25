package io.floci.gcp.services.cloudfunctions;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(CloudFunctionsGcsDisabledRestIntegrationTest.DisabledGcsProfile.class)
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

    @Test
    void generateDownloadUrlRequiresGcsService() {
        String base = "/v2/projects/functions-gcs-disabled/locations/us-central1/functions";
        given()
                .contentType("application/json")
                .queryParam("functionId", "fn")
                .body("{\"buildConfig\":{\"source\":{\"storageSource\":{\"bucket\":\"b\",\"object\":\"o.zip\"}}}}")
                .when().post(base)
                .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post(base + "/fn:generateDownloadUrl")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"));
    }

    public static class DisabledGcsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.gcs.enabled", "false");
        }
    }
}

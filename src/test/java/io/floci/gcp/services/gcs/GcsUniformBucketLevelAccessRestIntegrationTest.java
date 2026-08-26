package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class GcsUniformBucketLevelAccessRestIntegrationTest {

    @Test
    void uniformBucketLevelAccessRoundTripsThroughJsonCreateAndPatch() {
        String bucket = "ubla-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .contentType("application/json")
                .body(Map.of(
                        "name", bucket,
                        "iamConfiguration", Map.of("uniformBucketLevelAccess", Map.of("enabled", true))))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200)
                .body("iamConfiguration.uniformBucketLevelAccess.enabled", equalTo(true));

        given()
                .contentType("application/json")
                .body(Map.of("iamConfiguration", Map.of("uniformBucketLevelAccess", Map.of("enabled", false))))
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("iamConfiguration.uniformBucketLevelAccess.enabled", equalTo(false));

        given()
                .when().get("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("iamConfiguration.uniformBucketLevelAccess.enabled", equalTo(false));
    }

    @Test
    void absentIamConfigurationRemainsAbsent() {
        String bucket = "ubla-absent-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .contentType("application/json")
                .body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200)
                .body("iamConfiguration", nullValue());
    }
}

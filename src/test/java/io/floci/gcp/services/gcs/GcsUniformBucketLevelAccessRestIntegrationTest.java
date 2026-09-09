package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GcsUniformBucketLevelAccessRestIntegrationTest {

    @Inject GcsService gcsService;

    @Test
    void uniformBucketLevelAccessRoundTripsThroughJsonCreateAndPatch() {
        String bucket = "ubla-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .contentType("application/json")
                .body(Map.of(
                        "name", bucket,
                        "iamConfiguration", Map.of(
                                "uniformBucketLevelAccess", Map.of("enabled", true,
                                        "lockedTime", "2100-01-01T00:00:00Z"),
                                "publicAccessPrevention", "enforced")))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200)
                .body("iamConfiguration.uniformBucketLevelAccess.enabled", equalTo(true));

        assertTrue(GcsGrpcMapper.toProto(gcsService.getBucket(bucket)).getIamConfig()
                .getUniformBucketLevelAccess().getEnabled());
        assertTrue(GcsGrpcMapper.toProto(gcsService.getBucket(bucket)).getIamConfig()
                .getUniformBucketLevelAccess().hasLockTime());
        assertNotEquals("2100-01-01T00:00:00Z",
                ((Map<?, ?>) gcsService.getBucket(bucket).getIamConfiguration()
                        .get("uniformBucketLevelAccess")).get("lockedTime"));
        assertEquals("enforced", GcsGrpcMapper.toProto(gcsService.getBucket(bucket)).getIamConfig()
                .getPublicAccessPrevention());

        given()
                .contentType("application/json")
                .body(Map.of("iamConfiguration", Map.of("uniformBucketLevelAccess", Map.of("enabled", false))))
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("iamConfiguration.uniformBucketLevelAccess.enabled", equalTo(false))
                .body("iamConfiguration.publicAccessPrevention", equalTo("enforced"));

        assertFalse(GcsGrpcMapper.toProto(gcsService.getBucket(bucket)).getIamConfig()
                .getUniformBucketLevelAccess().getEnabled());
        assertEquals("enforced", GcsGrpcMapper.toProto(gcsService.getBucket(bucket)).getIamConfig()
                .getPublicAccessPrevention());

        given()
                .when().get("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("iamConfiguration.uniformBucketLevelAccess.enabled", equalTo(false))
                .body("iamConfiguration.publicAccessPrevention", equalTo("enforced"));
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

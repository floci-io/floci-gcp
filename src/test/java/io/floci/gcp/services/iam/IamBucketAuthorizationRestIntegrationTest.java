package io.floci.gcp.services.iam;

import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(IamBucketAuthorizationRestIntegrationTest.EnforceAuthorizationProfile.class)
class IamBucketAuthorizationRestIntegrationTest {

    @Inject
    IamService iamService;

    @Test
    void bucketPolicyGrantsEveryMappedBucketOperation() {
        String bucket = createBucket();
        iamService.setPolicy("buckets/" + bucket, storageAdminPolicy());

        given().when().get("/storage/v1/b/" + bucket).then().statusCode(200);
        given().when().get("/storage/v1/b/" + bucket + "/storageLayout").then().statusCode(200);
        given().contentType("application/json").body(Map.of("location", "EU"))
                .when().patch("/storage/v1/b/" + bucket).then().statusCode(200);
        given().header("X-HTTP-Method-Override", "PATCH").contentType("application/json").body(Map.of())
                .when().post("/storage/v1/b/" + bucket).then().statusCode(200);
        given().when().post("/storage/v1/b/" + bucket + "/lockRetentionPolicy").then().statusCode(200);
        given().when().get("/storage/v1/b/" + bucket + "/iam").then().statusCode(200);
        given().contentType("application/json").body(policyBody())
                .when().put("/storage/v1/b/" + bucket + "/iam").then().statusCode(200);

        given().contentType("application/json").body(Map.of("topic", "//pubsub.googleapis.com/projects/test-project/topics/events"))
                .when().post("/storage/v1/b/" + bucket + "/notificationConfigs").then().statusCode(200);
        given().when().get("/storage/v1/b/" + bucket + "/notificationConfigs").then().statusCode(200);
        given().when().get("/storage/v1/b/" + bucket + "/notificationConfigs/1").then().statusCode(200);
        given().when().delete("/storage/v1/b/" + bucket + "/notificationConfigs/1").then().statusCode(204);
        given().when().delete("/storage/v1/b/" + bucket).then().statusCode(204);
    }

    @Test
    void bucketOperationsAreDeniedWithoutAnApplicablePolicy() {
        String bucket = createBucket();

        given().when().get("/storage/v1/b/" + bucket).then().statusCode(403);
        given().when().get("/storage/v1/b/" + bucket + "/storageLayout").then().statusCode(403);
        given().contentType("application/json").body(Map.of("location", "EU"))
                .when().patch("/storage/v1/b/" + bucket).then().statusCode(403);
        given().header("X-HTTP-Method-Override", "PATCH").contentType("application/json").body(Map.of())
                .when().post("/storage/v1/b/" + bucket).then().statusCode(403);
        given().when().post("/storage/v1/b/" + bucket + "/lockRetentionPolicy").then().statusCode(403);
        given().when().get("/storage/v1/b/" + bucket + "/iam").then().statusCode(403);
        given().contentType("application/json").body(policyBody())
                .when().put("/storage/v1/b/" + bucket + "/iam").then().statusCode(403);
        given().contentType("application/json").body(Map.of("topic", "//pubsub.googleapis.com/projects/test-project/topics/events"))
                .when().post("/storage/v1/b/" + bucket + "/notificationConfigs").then().statusCode(403);
        given().when().get("/storage/v1/b/" + bucket + "/notificationConfigs").then().statusCode(403);
        given().when().get("/storage/v1/b/" + bucket + "/notificationConfigs/1").then().statusCode(403);
        given().when().delete("/storage/v1/b/" + bucket + "/notificationConfigs/1").then().statusCode(403);
        given().when().delete("/storage/v1/b/" + bucket).then().statusCode(403);
    }

    private static String createBucket() {
        String bucket = "iam-bucket-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType("application/json").body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=test-project").then().statusCode(200);
        return bucket;
    }

    private static StoredPolicy storageAdminPolicy() {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/storage.admin", "members", List.of("allUsers"))));
        return policy;
    }

    private static Map<String, Object> policyBody() {
        return Map.of("version", 1, "bindings", List.of(Map.of(
                "role", "roles/storage.admin", "members", List.of("allUsers"))));
    }

    public static class EnforceAuthorizationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }
}

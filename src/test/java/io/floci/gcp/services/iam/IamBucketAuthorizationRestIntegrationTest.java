package io.floci.gcp.services.iam;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(IamBucketAuthorizationRestIntegrationTest.EnforceAuthorizationProfile.class)
class IamBucketAuthorizationRestIntegrationTest {

    @Inject
    IamService iamService;
    @Inject
    CredentialTokenService tokenService;

    @Test
    void bucketPolicyGrantsEveryMappedBucketOperation() {
        String bucket = createBucket();
        iamService.setPolicy("buckets/" + bucket, storageAdminPolicy());

        given().when().get("/storage/v1/b/" + bucket).then().statusCode(200);
        given().header("Authorization", "Bearer external-token")
                .when().get("/storage/v1/b/" + bucket).then().statusCode(200);
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
        given().header("Authorization", "Bearer external-token")
                .when().get("/storage/v1/b/" + bucket).then().statusCode(403);
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

    @Test
    void objectViewerCanReadStorageLayoutWithoutBucketMetadataPermission() {
        String bucket = createBucket();
        iamService.setPolicy("buckets/" + bucket, objectViewerPolicy());

        given().when().get("/storage/v1/b/" + bucket + "/storageLayout").then().statusCode(200);
        given().when().get("/storage/v1/b/" + bucket).then().statusCode(403);
    }

    @Test
    void projectStorageAdminGrantAppliesToItsBuckets() {
        String bucket = createBucket();
        String email = "project-admin@test-project.iam.gserviceaccount.com";
        iamService.setPolicy("projects/test-project", policy(
                "roles/storage.admin", "serviceAccount:" + email));
        String authorization = "Bearer " + tokenService
                .mintImpersonatedToken(email, Instant.now().plusSeconds(600)).getTokenValue();

        given().header("Authorization", authorization)
                .when().get("/storage/v1/b/" + bucket).then().statusCode(200);
    }

    @Test
    void uncataloguedBucketRoleDoesNotBlockKnownGrantOrPolicyRecovery() {
        String bucket = createBucket();
        iamService.setPolicy("buckets/" + bucket, policyWithUncataloguedRole("allUsers"));

        given().when().get("/storage/v1/b/" + bucket).then().statusCode(200);
        given().contentType("application/json").body(policyBody())
                .when().put("/storage/v1/b/" + bucket + "/iam").then().statusCode(200);

        StoredPolicy recovered = iamService.getPolicy("buckets/" + bucket);
        assertEquals(1, recovered.getBindings().size());
        assertEquals("roles/storage.admin", recovered.getBindings().get(0).get("role"));
    }

    @Test
    void uncataloguedProjectRoleDoesNotBlockInheritedBucketGrant() {
        String bucket = createBucket();
        String email = "project-admin@test-project.iam.gserviceaccount.com";
        iamService.setPolicy("projects/test-project", policyWithUncataloguedRole(
                "serviceAccount:" + email));
        String authorization = "Bearer " + tokenService
                .mintImpersonatedToken(email, Instant.now().plusSeconds(600)).getTokenValue();

        given().header("Authorization", authorization)
                .when().get("/storage/v1/b/" + bucket).then().statusCode(200);
        given().header("Authorization", authorization).contentType("application/json").body(policyBody())
                .when().put("/storage/v1/b/" + bucket + "/iam").then().statusCode(200);
    }

    @Test
    void missingBucketRemainsNotFoundWhenAuthorizationIsEnforced() {
        String bucket = "missing-iam-bucket-" + UUID.randomUUID().toString().substring(0, 8);

        given().when().get("/storage/v1/b/" + bucket).then().statusCode(404);
        given().contentType("application/json").body(Map.of("location", "EU"))
                .when().patch("/storage/v1/b/" + bucket).then().statusCode(404);
        given().contentType("application/json").body(policyBody())
                .when().put("/storage/v1/b/" + bucket + "/iam").then().statusCode(404);
    }

    @Test
    void deletingBucketRemovesPolicyBeforeNameIsReused() {
        String bucket = createBucket();
        iamService.setPolicy("buckets/" + bucket, storageAdminPolicy());

        given().when().delete("/storage/v1/b/" + bucket).then().statusCode(204);
        createBucket(bucket);

        given().when().get("/storage/v1/b/" + bucket).then().statusCode(403);
    }

    private static String createBucket() {
        String bucket = "iam-bucket-" + UUID.randomUUID().toString().substring(0, 8);
        createBucket(bucket);
        return bucket;
    }

    private static void createBucket(String bucket) {
        given().contentType("application/json").body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=test-project").then().statusCode(200);
    }

    private static StoredPolicy storageAdminPolicy() {
        return policy("roles/storage.admin", "allUsers");
    }

    private static StoredPolicy policy(String role, String member) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", role, "members", List.of(member))));
        return policy;
    }

    private static StoredPolicy policyWithUncataloguedRole(String member) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(
                Map.of("role", "roles/storage.admin", "members", List.of(member)),
                Map.of("role", "roles/storage.legacyBucketReader", "members", List.of(member))));
        return policy;
    }

    private static StoredPolicy objectViewerPolicy() {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/storage.objectViewer", "members", List.of("allUsers"))));
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

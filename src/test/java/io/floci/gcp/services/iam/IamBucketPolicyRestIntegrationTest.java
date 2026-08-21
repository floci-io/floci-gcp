package io.floci.gcp.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class IamBucketPolicyRestIntegrationTest {

    @Test
    void rejectsConditionalPolicyWithoutUniformBucketLevelAccessAndLeavesPolicyUnchanged() {
        String bucket = createBucket(false);

        setPolicy(bucket, conditionalPolicy("resource.name.startsWith('projects/_/buckets/" + bucket + "/objects/reports/')"))
                .statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));

        given().when().get("/storage/v1/b/" + bucket + "/iam")
                .then().statusCode(200).body("bindings.size()", equalTo(0));
    }

    @Test
    void validatesConditionAndRejectsDisablingUblaWhileItRemainsConfigured() {
        String bucket = createBucket(true);
        setPolicy(bucket, conditionalPolicy("resource.name.startsWith('projects/_/buckets/" + bucket + "/objects/reports/')"))
                .statusCode(200).body("version", equalTo(3));

        given().contentType("application/json")
                .body(Map.of("iamConfiguration", Map.of("uniformBucketLevelAccess", Map.of("enabled", false))))
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));

        given().when().get("/storage/v1/b/" + bucket)
                .then().statusCode(200).body("iamConfiguration.uniformBucketLevelAccess.enabled", equalTo(true));
    }

    @Test
    void rejectsUnsupportedConditionWithoutReplacingExistingPolicy() {
        String bucket = createBucket(true);
        setPolicy(bucket, unconditionalPolicy()).statusCode(200);

        setPolicy(bucket, conditionalPolicy("resource.name.matches('.*')"))
                .statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));

        given().when().get("/storage/v1/b/" + bucket + "/iam")
                .then().statusCode(200).body("bindings[0].role", equalTo("roles/storage.objectViewer"));
    }

    @Test
    void rejectsMalformedPolicyWithoutReplacingExistingPolicy() {
        String bucket = createBucket(false);
        setPolicy(bucket, unconditionalPolicy()).statusCode(200);

        setPolicy(bucket, Map.of("version", "three"))
                .statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));

        given().when().get("/storage/v1/b/" + bucket + "/iam")
                .then().statusCode(200).body("bindings[0].role", equalTo("roles/storage.objectViewer"));
    }

    @Test
    void disabledModePreservesTestIamPermissionsEchoBehavior() {
        String bucket = createBucket(false);

        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("permissions", List.of("storage.objects.get", "storage.objects.get")))
                .when().post("/storage/v1/b/" + bucket + "/iam:testPermissions")
                .then().statusCode(200).body("permissions", equalTo(List.of("storage.objects.get", "storage.objects.get")));
    }

    private static String createBucket(boolean uniformBucketLevelAccess) {
        String bucket = "iam-policy-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = uniformBucketLevelAccess
                ? Map.of("name", bucket, "iamConfiguration", Map.of(
                        "uniformBucketLevelAccess", Map.of("enabled", true)))
                : Map.of("name", bucket);
        given().contentType("application/json").body(body)
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);
        return bucket;
    }

    private static io.restassured.response.ValidatableResponse setPolicy(String bucket, Map<String, Object> policy) {
        return given().contentType("application/json").body(policy)
                .when().put("/storage/v1/b/" + bucket + "/iam")
                .then();
    }

    private static Map<String, Object> unconditionalPolicy() {
        return Map.of("version", 1, "bindings", List.of(Map.of(
                "role", "roles/storage.objectViewer", "members", List.of("allUsers"))));
    }

    private static Map<String, Object> conditionalPolicy(String expression) {
        return Map.of("version", 3, "bindings", List.of(Map.of(
                "role", "roles/storage.objectViewer",
                "members", List.of("serviceAccount:reader@example.test"),
                "condition", Map.of("title", "reports", "expression", expression))));
    }
}

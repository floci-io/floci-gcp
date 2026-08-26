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
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IamBucketPolicyEnforceRestIntegrationTest.EnforceAuthorizationProfile.class)
class IamBucketPolicyEnforceRestIntegrationTest {

    @Inject
    IamService iamService;

    @Test
    void returnsOnlyPermissionsGrantedByBucketPolicy() {
        String bucket = "iam-enforce-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType("application/json").body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);
        iamService.setPolicy("buckets/" + bucket, objectViewerPolicy());

        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("permissions", List.of("storage.objects.get", "storage.buckets.get")))
                .when().post("/storage/v1/b/" + bucket + "/iam:testPermissions")
                .then().statusCode(200).body("permissions", equalTo(List.of("storage.objects.get")));
    }

    public static class EnforceAuthorizationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }

    private static StoredPolicy objectViewerPolicy() {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/storage.objectViewer", "members", List.of("allUsers"))));
        return policy;
    }
}

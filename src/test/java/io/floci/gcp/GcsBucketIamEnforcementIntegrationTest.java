package io.floci.gcp;

import io.floci.gcp.core.common.TokenRegistry;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(GcsBucketIamEnforcementIntegrationTest.GcsIamEnforcementProfile.class)
class GcsBucketIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String PLAYER_EMAIL = "player@test-project.iam.gserviceaccount.com";
    private static final String PLAYER_TOKEN = "player-token";
    private static final String ROOT_TOKEN = "root-token-test";

    @Inject
    TokenRegistry tokenRegistry;

    @Inject
    IamService iamService;

    @BeforeEach
    void setUp() {
        tokenRegistry.register(PLAYER_TOKEN, PLAYER_EMAIL);
        iamService.setPolicy("projects/" + PROJECT, new StoredPolicy());
    }

    @Test
    void playerWithoutBindingIsDeniedCreateBucket() {
        String bucket = "gcs-deny-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("{\"name\":\"" + bucket + "\",\"location\":\"US\"}")
                .when().post("/storage/v1/b?project=" + PROJECT)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'storage.buckets.create' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void storageAdminCanCreateBucket() {
        String bucket = "gcs-admin-" + UUID.randomUUID().toString().substring(0, 8);
        bindPlayer("roles/storage.admin");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("{\"name\":\"" + bucket + "\",\"location\":\"US\"}")
                .when().post("/storage/v1/b?project=" + PROJECT)
                .then()
                .statusCode(200)
                .body("name", equalTo(bucket));
    }

    @Test
    void objectAdminIsDeniedSetBucketIamPolicy() {
        String bucket = "gcs-objadm-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .body("{\"name\":\"" + bucket + "\",\"location\":\"US\"}")
                .when().post("/storage/v1/b?project=" + PROJECT)
                .then()
                .statusCode(200);

        bindPlayer("roles/storage.objectAdmin");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("{\"bindings\":[]}")
                .when().put("/storage/v1/b/" + bucket + "/iam")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'storage.buckets.setIamPolicy' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    private void bindPlayer(String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", role,
                "members", List.of("serviceAccount:" + PLAYER_EMAIL))));
        iamService.setPolicy("projects/" + PROJECT, policy);
    }

    public static class GcsIamEnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.auth.validate-tokens", "true",
                    "floci-gcp.auth.root-service-account",
                    "operator@test-project.iam.gserviceaccount.com",
                    "floci-gcp.auth.root-access-token", ROOT_TOKEN,
                    "floci-gcp.services.iam.enforcement-enabled", "true",
                    "floci-gcp.services.iam.strict-enforcement-enabled", "true");
        }
    }
}

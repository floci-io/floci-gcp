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
@TestProfile(GcsIamEnforcementIntegrationTest.GcsIamEnforcementProfile.class)
class GcsIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String PLAYER_EMAIL = "player@test-project.iam.gserviceaccount.com";
    private static final String PLAYER_TOKEN = "player-token";
    private static final String ROOT_TOKEN = "root-token-test";

    @Inject
    TokenRegistry tokenRegistry;

    @Inject
    IamService iamService;

    private String bucket;
    private String objectName;
    private String listPath;

    @BeforeEach
    void setUp() {
        tokenRegistry.register(PLAYER_TOKEN, PLAYER_EMAIL);
        iamService.setPolicy("projects/" + PROJECT, new StoredPolicy());

        bucket = "gcs-iam-" + UUID.randomUUID().toString().substring(0, 8);
        objectName = "hello.txt";
        listPath = "/storage/v1/b/" + bucket + "/o";

        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .body("{\"name\":\"" + bucket + "\",\"location\":\"US\"}")
                .when().post("/storage/v1/b?project=" + PROJECT)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("text/plain")
                .body("hello")
                .when().post("/upload/storage/v1/b/" + bucket + "/o?uploadType=media&name=" + objectName)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithoutBindingIsDeniedListObjects() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(listPath)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'storage.objects.list' denied on resource 'projects/" + PROJECT + "'."));
    }

    @Test
    void playerWithObjectViewerBindingCanListObjects() {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/storage.objectViewer",
                "members", List.of("serviceAccount:" + PLAYER_EMAIL))));
        iamService.setPolicy("projects/" + PROJECT, policy);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(listPath)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithObjectViewerBindingCanGetObject() {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/storage.objectViewer",
                "members", List.of("serviceAccount:" + PLAYER_EMAIL))));
        iamService.setPolicy("projects/" + PROJECT, policy);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get("/storage/v1/b/" + bucket + "/o/" + objectName)
                .then()
                .statusCode(200);
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

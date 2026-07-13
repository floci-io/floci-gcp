package io.floci.gcp.services.tasks;

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
@TestProfile(CloudTasksIamEnforcementIntegrationTest.CtfIamProfile.class)
class CloudTasksIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String LOCATION = "us-central1";
    private static final String QUEUES_PATH =
            "/v2/projects/" + PROJECT + "/locations/" + LOCATION + "/queues";
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
    void playerWithoutBindingIsDeniedListQueues() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(QUEUES_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'cloudtasks.queues.list' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithCloudTasksViewerCanListButNotCreate() {
        bindPlayer("roles/cloudtasks.viewer");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(QUEUES_PATH)
                .then()
                .statusCode(200);

        String queueId = "ctf-q-" + UUID.randomUUID().toString().substring(0, 8);
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .queryParam("queueId", queueId)
                .body(Map.of())
                .when().post(QUEUES_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithCloudTasksAdminCanCreateAndList() {
        bindPlayer("roles/cloudtasks.admin");
        String queueId = "ctf-q-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .queryParam("queueId", queueId)
                .body(Map.of())
                .when().post(QUEUES_PATH)
                .then()
                .statusCode(200)
                .body("name", equalTo(
                        "projects/" + PROJECT + "/locations/" + LOCATION + "/queues/" + queueId));

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(QUEUES_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    void rootTokenAlwaysCanListQueues() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(QUEUES_PATH)
                .then()
                .statusCode(200);
    }

    private void bindPlayer(String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", role,
                "members", List.of("serviceAccount:" + PLAYER_EMAIL))));
        iamService.setPolicy("projects/" + PROJECT, policy);
    }

    public static class CtfIamProfile implements QuarkusTestProfile {
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

package io.floci.gcp.services.scheduler;

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
@TestProfile(SchedulerIamEnforcementIntegrationTest.CtfIamProfile.class)
class SchedulerIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String LOCATION = "us-central1";
    private static final String JOBS_PATH =
            "/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/jobs";
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
    void playerWithoutBindingIsDeniedListJobs() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(JOBS_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'cloudscheduler.jobs.list' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithCloudSchedulerAdminCanListJobs() {
        bindPlayer("roles/cloudscheduler.admin");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(JOBS_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithViewerCanListButNotCreate() {
        bindPlayer("roles/cloudscheduler.viewer");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(JOBS_PATH)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(httpJobBody("viewer-denied-" + UUID.randomUUID().toString().substring(0, 8)))
                .when().post(JOBS_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithAdminCanCreateAndRunJob() {
        bindPlayer("roles/cloudscheduler.admin");
        String jobId = "ctf-job-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(httpJobBody(jobId))
                .when().post(JOBS_PATH)
                .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("{}")
                .when().post(JOBS_PATH + "/" + jobId + ":run")
                .then()
                .statusCode(200);
    }

    @Test
    void rootTokenAlwaysCanListJobs() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(JOBS_PATH)
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

    private static String httpJobBody(String jobId) {
        String name = "projects/" + PROJECT + "/locations/" + LOCATION + "/jobs/" + jobId;
        return """
                {
                  "name": "%s",
                  "schedule": "*/5 * * * *",
                  "httpTarget": {
                    "uri": "http://127.0.0.1:1/",
                    "httpMethod": "POST"
                  }
                }
                """.formatted(name);
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

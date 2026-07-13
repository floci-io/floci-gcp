package io.floci.gcp.services.eventarc;

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
@TestProfile(EventarcIamEnforcementIntegrationTest.CtfIamProfile.class)
class EventarcIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String LOCATION = "us-central1";
    private static final String TRIGGERS_PATH =
            "/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/triggers";
    private static final String PLAYER_EMAIL = "player@test-project.iam.gserviceaccount.com";
    private static final String PLAYER_TOKEN = "player-token";
    private static final String ROOT_TOKEN = "root-token-test";

    private static final String TRIGGER_BODY = """
            {
              "eventFilters": [
                { "attribute": "type", "value": "google.cloud.storage.object.v1.finalized" },
                { "attribute": "bucket", "value": "ctf-bucket" }
              ],
              "destination": {
                "httpEndpoint": { "uri": "http://example.com/endpoint" }
              }
            }
            """;

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
    void playerWithoutBindingIsDeniedListTriggers() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(TRIGGERS_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'eventarc.triggers.list' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithEventarcViewerCanListTriggers() {
        bindPlayer("roles/eventarc.viewer");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(TRIGGERS_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithEventarcViewerIsDeniedCreateTrigger() {
        bindPlayer("roles/eventarc.viewer");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .queryParam("triggerId", "ctf-t-" + UUID.randomUUID().toString().substring(0, 8))
                .body(TRIGGER_BODY)
                .when().post(TRIGGERS_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'eventarc.triggers.create' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithEventarcAdminCanCreateTrigger() {
        bindPlayer("roles/eventarc.admin");
        String triggerId = "ctf-t-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .queryParam("triggerId", triggerId)
                .body(TRIGGER_BODY)
                .when().post(TRIGGERS_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    void rootTokenAlwaysCanListTriggers() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(TRIGGERS_PATH)
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

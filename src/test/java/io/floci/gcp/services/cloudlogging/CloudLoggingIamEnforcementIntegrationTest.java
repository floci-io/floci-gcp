package io.floci.gcp.services.cloudlogging;

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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(CloudLoggingIamEnforcementIntegrationTest.CtfIamProfile.class)
class CloudLoggingIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String LIST_PATH = "/v2/entries:list";
    private static final String WRITE_PATH = "/v2/entries:write";
    private static final String LOGS_PATH = "/v2/projects/" + PROJECT + "/logs";
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
    void playerWithoutBindingIsDeniedListEntries() {
        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(Map.of("resourceNames", List.of("projects/" + PROJECT)))
                .when().post(LIST_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'logging.logEntries.list' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithLoggingViewerCanListEntriesButNotWrite() {
        bindPlayer("roles/logging.viewer");

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(Map.of("resourceNames", List.of("projects/" + PROJECT)))
                .when().post(LIST_PATH)
                .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(Map.of(
                        "entries", List.of(Map.of(
                                "logName", "projects/" + PROJECT + "/logs/ctf",
                                "textPayload", "denied-write"))))
                .when().post(WRITE_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithLogWriterCanWriteButNotList() {
        bindPlayer("roles/logging.logWriter");

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(Map.of(
                        "entries", List.of(Map.of(
                                "logName", "projects/" + PROJECT + "/logs/ctf",
                                "textPayload", "writer-ok",
                                "resource", Map.of("type", "global")))))
                .when().post(WRITE_PATH)
                .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(Map.of("resourceNames", List.of("projects/" + PROJECT)))
                .when().post(LIST_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithLoggingAdminCanListLogs() {
        bindPlayer("roles/logging.admin");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(LOGS_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    void rootTokenAlwaysCanListEntries() {
        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .body(Map.of("resourceNames", List.of("projects/" + PROJECT)))
                .when().post(LIST_PATH)
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

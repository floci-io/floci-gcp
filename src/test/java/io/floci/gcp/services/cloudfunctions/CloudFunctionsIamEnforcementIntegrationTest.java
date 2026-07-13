package io.floci.gcp.services.cloudfunctions;

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
@TestProfile(CloudFunctionsIamEnforcementIntegrationTest.CtfIamProfile.class)
class CloudFunctionsIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String LOCATION = "us-central1";
    private static final String FUNCTIONS_PATH =
            "/v2/projects/" + PROJECT + "/locations/" + LOCATION + "/functions";
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
    void playerWithoutBindingIsDeniedListFunctions() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(FUNCTIONS_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'cloudfunctions.functions.list' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithDeveloperBindingCanListFunctions() {
        bindPlayer("roles/cloudfunctions.developer");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(FUNCTIONS_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithAdminBindingCanCreateGetAndDeleteFunction() {
        bindPlayer("roles/cloudfunctions.admin");
        String functionId = "ctf-fn-" + UUID.randomUUID().toString().substring(0, 8);
        String functionPath = FUNCTIONS_PATH + "/" + functionId;

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .queryParam("functionId", functionId)
                .body("{\"buildConfig\":{\"runtime\":\"java21\"}}")
                .when().post(FUNCTIONS_PATH)
                .then()
                .statusCode(200)
                .body("done", equalTo(true));

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(functionPath)
                .then()
                .statusCode(200)
                .body("name", equalTo(
                        "projects/" + PROJECT + "/locations/" + LOCATION + "/functions/" + functionId));

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().delete(functionPath)
                .then()
                .statusCode(200)
                .body("done", equalTo(true));
    }

    @Test
    void playerWithDeveloperBindingCanGenerateUploadUrl() {
        bindPlayer("roles/cloudfunctions.developer");

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("{}")
                .when().post(FUNCTIONS_PATH + ":generateUploadUrl")
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithoutBindingIsDeniedGenerateUploadUrl() {
        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("{}")
                .when().post(FUNCTIONS_PATH + ":generateUploadUrl")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'cloudfunctions.functions.sourceCodeSet' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void rootTokenAlwaysCanListFunctions() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(FUNCTIONS_PATH)
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

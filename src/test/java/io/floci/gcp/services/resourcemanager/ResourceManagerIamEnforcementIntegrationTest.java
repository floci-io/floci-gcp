package io.floci.gcp.services.resourcemanager;

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
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(ResourceManagerIamEnforcementIntegrationTest.CtfIamProfile.class)
class ResourceManagerIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String PROJECT_PATH = "/v1/projects/" + PROJECT;
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
    void playerWithoutBindingIsDeniedGetProject() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(PROJECT_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'resourcemanager.projects.get' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithBrowserRoleCanGetProject() {
        bindPlayer("roles/browser");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(PROJECT_PATH)
                .then()
                .statusCode(200)
                .body("projectId", equalTo(PROJECT));
    }

    @Test
    void playerWithoutBindingIsDeniedGetIamPolicy() {
        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("{}")
                .when().post(PROJECT_PATH + ":getIamPolicy")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'resourcemanager.projects.getIamPolicy' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithProjectIamAdminPassesGetIamPolicyIamGate() {
        bindPlayer("roles/resourcemanager.projectIamAdmin");

        // CRM getIamPolicy is mapped for CTF but not implemented on ResourceManagerController.
        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("{}")
                .when().post(PROJECT_PATH + ":getIamPolicy")
                .then()
                .statusCode(not(equalTo(403)));
    }

    @Test
    void rootTokenAlwaysCanGetProject() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(PROJECT_PATH)
                .then()
                .statusCode(200)
                .body("projectId", equalTo(PROJECT));
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

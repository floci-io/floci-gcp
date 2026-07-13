package io.floci.gcp.services.cloudsql;

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
@TestProfile(CloudSqlIamEnforcementIntegrationTest.CtfIamProfile.class)
class CloudSqlIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String BASE = "/v1/projects/" + PROJECT;
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
    void playerWithoutBindingIsDeniedListInstances() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(BASE + "/instances")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'cloudsql.instances.list' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithCloudSqlAdminCanListInstances() {
        bindPlayer("roles/cloudsql.admin");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(BASE + "/instances")
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithViewerCanListButNotCreateInstance() {
        bindPlayer("roles/cloudsql.viewer");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(BASE + "/instances")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(instanceBody("viewer-denied-" + UUID.randomUUID().toString().substring(0, 8)))
                .when().post(BASE + "/instances")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithAdminCanCreateInstanceAndDatabase() {
        bindPlayer("roles/cloudsql.admin");
        String instance = "ctf-sql-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(instanceBody(instance))
                .when().post(BASE + "/instances")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("{\"name\":\"appdb\"}")
                .when().post(BASE + "/instances/" + instance + "/databases")
                .then()
                .statusCode(200);
    }

    @Test
    void rootTokenAlwaysCanListInstances() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(BASE + "/instances")
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

    private static String instanceBody(String name) {
        return """
                {
                  "name": "%s",
                  "databaseVersion": "POSTGRES_18",
                  "region": "us-central1",
                  "settings": {"tier": "db-custom-1-3840"}
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
                    "floci-gcp.services.iam.strict-enforcement-enabled", "true",
                    "floci-gcp.services.cloudsql.mock", "true");
        }
    }
}

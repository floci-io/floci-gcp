package io.floci.gcp.services.secretmanager;

import io.floci.gcp.core.common.TokenRegistry;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(SecretManagerIamEnforcementIntegrationTest.CtfIamProfile.class)
class SecretManagerIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String SECRETS_PATH = "/v1/projects/" + PROJECT + "/secrets";
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
    void playerWithoutBindingIsDeniedListSecrets() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(SECRETS_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithSecretAdminBindingCanListSecrets() {
        bindPlayer("roles/secretmanager.admin");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(SECRETS_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithSecretAccessorIsDeniedListSecrets() {
        bindPlayer("roles/secretmanager.secretAccessor");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(SECRETS_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithSecretAccessorCanAccessVersion() {
        String secretId = "ctf-secret-" + UUID.randomUUID().toString().substring(0, 8);
        seedSecretWithVersion(secretId);
        bindPlayer("roles/secretmanager.secretAccessor");

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(SECRETS_PATH + "/" + secretId + "/versions/1:access")
                .then()
                .statusCode(200)
                .body("name", equalTo("projects/" + PROJECT + "/secrets/" + secretId + "/versions/1"));
    }

    @Test
    void rootTokenAlwaysCanListSecrets() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(SECRETS_PATH)
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

    private void seedSecretWithVersion(String secretId) {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .queryParam("secretId", secretId)
                .body(Map.of("replication", Map.of("automatic", Map.of())))
                .when().post(SECRETS_PATH)
                .then()
                .statusCode(200);

        String payload = Base64.getEncoder().encodeToString("ctf-value".getBytes());
        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .body(Map.of("payload", Map.of("data", payload)))
                .when().post(SECRETS_PATH + "/" + secretId + ":addVersion")
                .then()
                .statusCode(200);
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

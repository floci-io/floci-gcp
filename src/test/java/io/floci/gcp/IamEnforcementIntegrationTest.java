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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IamEnforcementIntegrationTest.IamEnforcementProfile.class)
class IamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String LIST_PATH = "/v1/projects/" + PROJECT + "/serviceAccounts";
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
        // Clear project policy so tests start without player bindings.
        iamService.setPolicy("projects/" + PROJECT, new StoredPolicy());
    }

    @Test
    void playerWithoutBindingIsDeniedList() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(LIST_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithServiceAccountAdminBindingCanList() {
        bindPlayerServiceAccountAdmin();

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(LIST_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithServiceAccountAdminCanListKeysAndSignBlob() {
        bindPlayerServiceAccountAdmin();
        String accountId = "keys-ok-" + System.nanoTime();
        String saEmail = accountId + "@" + PROJECT + ".iam.gserviceaccount.com";
        createServiceAccountAsRoot(accountId);

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(LIST_PATH + "/" + saEmail + "/keys")
                .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("{\"bytesToSign\":\"dGVzdA==\"}")
                .when().post(LIST_PATH + "/" + saEmail + ":signBlob")
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithoutBindingIsDeniedSignBlob() {
        String accountId = "keys-deny-" + System.nanoTime();
        String saEmail = accountId + "@" + PROJECT + ".iam.gserviceaccount.com";
        createServiceAccountAsRoot(accountId);

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("{\"bytesToSign\":\"dGVzdA==\"}")
                .when().post(LIST_PATH + "/" + saEmail + ":signBlob")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'iam.serviceAccounts.signBlob' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void rootTokenAlwaysCanList() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(LIST_PATH)
                .then()
                .statusCode(200);
    }

    private void createServiceAccountAsRoot(String accountId) {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .body("{\"accountId\":\"" + accountId + "\"}")
                .when().post(LIST_PATH)
                .then()
                .statusCode(200);
    }

    private void bindPlayerServiceAccountAdmin() {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/iam.serviceAccountAdmin",
                "members", List.of("serviceAccount:" + PLAYER_EMAIL))));
        iamService.setPolicy("projects/" + PROJECT, policy);
    }

    public static class IamEnforcementProfile implements QuarkusTestProfile {
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


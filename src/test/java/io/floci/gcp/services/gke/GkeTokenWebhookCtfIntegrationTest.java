package io.floci.gcp.services.gke;

import io.floci.gcp.core.common.TokenRegistry;
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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(GkeTokenWebhookCtfIntegrationTest.ValidateTokensProfile.class)
class GkeTokenWebhookCtfIntegrationTest {

    private static final String WEBHOOK = "/_floci-gcp/gke/token-webhook";
    private static final String ROOT_TOKEN = "root-token-test";
    private static final String OPERATOR_EMAIL =
            "operator@test-project.iam.gserviceaccount.com";
    private static final String PLAYER_TOKEN = "player-token-gke-webhook";
    private static final String PLAYER_EMAIL =
            "player@test-project.iam.gserviceaccount.com";

    @Inject
    TokenRegistry tokenRegistry;

    @BeforeEach
    void registerPlayerToken() {
        tokenRegistry.register(PLAYER_TOKEN, PLAYER_EMAIL);
    }

    @Test
    void blankTokenIsNotAuthenticated() {
        given()
                .contentType("application/json")
                .body(tokenReview(""))
                .when().post(WEBHOOK)
                .then()
                .statusCode(200)
                .body("kind", equalTo("TokenReview"))
                .body("status.authenticated", equalTo(false));
    }

    @Test
    void unregisteredTokenIsNotAuthenticated() {
        given()
                .contentType("application/json")
                .body(tokenReview("bad-token"))
                .when().post(WEBHOOK)
                .then()
                .statusCode(200)
                .body("kind", equalTo("TokenReview"))
                .body("status.authenticated", equalTo(false));
    }

    @Test
    void rootTokenAuthenticatesAsOperatorWithMasters() {
        given()
                .contentType("application/json")
                .body(tokenReview(ROOT_TOKEN))
                .when().post(WEBHOOK)
                .then()
                .statusCode(200)
                .body("kind", equalTo("TokenReview"))
                .body("status.authenticated", equalTo(true))
                .body("status.user.username", equalTo(OPERATOR_EMAIL))
                .body("status.user.uid", equalTo(OPERATOR_EMAIL))
                .body("status.user.groups", equalTo(List.of("system:masters")));
    }

    @Test
    void registeredPlayerTokenAuthenticatesWithoutMasters() {
        given()
                .contentType("application/json")
                .body(tokenReview(PLAYER_TOKEN))
                .when().post(WEBHOOK)
                .then()
                .statusCode(200)
                .body("kind", equalTo("TokenReview"))
                .body("status.authenticated", equalTo(true))
                .body("status.user.username", equalTo(PLAYER_EMAIL))
                .body("status.user.uid", equalTo(PLAYER_EMAIL))
                .body("status.user.groups", equalTo(List.of("system:authenticated")))
                .body("status.user.groups", not(hasItem("system:masters")));
    }

    @Test
    void echoesRequestApiVersion() {
        given()
                .contentType("application/json")
                .body(Map.of(
                        "apiVersion", "authentication.k8s.io/v1beta1",
                        "kind", "TokenReview",
                        "spec", Map.of("token", ROOT_TOKEN)))
                .when().post(WEBHOOK)
                .then()
                .statusCode(200)
                .body("apiVersion", equalTo("authentication.k8s.io/v1beta1"))
                .body("status.authenticated", equalTo(true))
                .body("status.user.username", equalTo(OPERATOR_EMAIL))
                .body("status.user.groups", equalTo(List.of("system:masters")));
    }

    private static Map<String, Object> tokenReview(String token) {
        return Map.of(
                "apiVersion", "authentication.k8s.io/v1",
                "kind", "TokenReview",
                "spec", Map.of("token", token));
    }

    public static class ValidateTokensProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.auth.validate-tokens", "true",
                    "floci-gcp.auth.root-service-account", OPERATOR_EMAIL,
                    "floci-gcp.auth.root-access-token", ROOT_TOKEN,
                    "floci-gcp.services.iam.enforcement-enabled", "false");
        }
    }
}

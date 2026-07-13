package io.floci.gcp.services.pubsub;

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
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@TestProfile(PubSubIamEnforcementIntegrationTest.CtfIamProfile.class)
class PubSubIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String TOPICS_PATH = "/v1/projects/" + PROJECT + "/topics";
    private static final String SUBSCRIPTIONS_PATH = "/v1/projects/" + PROJECT + "/subscriptions";
    private static final String PLAYER_EMAIL = "player@test-project.iam.gserviceaccount.com";
    private static final String PLAYER_TOKEN = "player-token";
    private static final String ROOT_TOKEN = "root-token-test";

    @Inject
    TokenRegistry tokenRegistry;

    @Inject
    IamService iamService;

    private String topicId;
    private String subscriptionId;
    private String topicPath;
    private String subscriptionPath;

    @BeforeEach
    void setUp() {
        tokenRegistry.register(PLAYER_TOKEN, PLAYER_EMAIL);
        iamService.setPolicy("projects/" + PROJECT, new StoredPolicy());

        topicId = "ctf-topic-" + UUID.randomUUID().toString().substring(0, 8);
        subscriptionId = "ctf-sub-" + UUID.randomUUID().toString().substring(0, 8);
        topicPath = TOPICS_PATH + "/" + topicId;
        subscriptionPath = SUBSCRIPTIONS_PATH + "/" + subscriptionId;

        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .body("{}")
                .when().put(topicPath)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .body(Map.of("topic", "projects/" + PROJECT + "/topics/" + topicId))
                .when().put(subscriptionPath)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithoutBindingIsDeniedListTopics() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(TOPICS_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'pubsub.topics.list' denied on resource 'projects/" + PROJECT + "'."));
    }

    @Test
    void playerWithPubSubAdminCanListTopics() {
        bindPlayer("roles/pubsub.admin");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(TOPICS_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithPublisherCanPublishButNotListTopics() {
        bindPlayer("roles/pubsub.publisher");

        String payload = Base64.getEncoder().encodeToString("hello".getBytes());
        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(Map.of("messages", List.of(Map.of("data", payload))))
                .when().post(topicPath + ":publish")
                .then()
                .statusCode(200)
                .body("messageIds", hasSize(1));

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(TOPICS_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithSubscriberCanPullButNotPublish() {
        String payload = Base64.getEncoder().encodeToString("for-pull".getBytes());
        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .body(Map.of("messages", List.of(Map.of("data", payload))))
                .when().post(topicPath + ":publish")
                .then()
                .statusCode(200);

        bindPlayer("roles/pubsub.subscriber");

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(Map.of("maxMessages", 1))
                .when().post(subscriptionPath + ":pull")
                .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(Map.of("messages", List.of(Map.of("data", payload))))
                .when().post(topicPath + ":publish")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'pubsub.topics.publish' denied on resource 'projects/" + PROJECT + "'."));
    }

    @Test
    void rootTokenAlwaysCanListTopics() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(TOPICS_PATH)
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

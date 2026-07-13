package io.floci.gcp.services.kafka;

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
@TestProfile(KafkaIamEnforcementIntegrationTest.CtfIamProfile.class)
class KafkaIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String LOCATION = "us-central1";
    private static final String CLUSTERS_PATH =
            "/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/clusters";
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
    void playerWithoutBindingIsDeniedListClusters() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(CLUSTERS_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'managedkafka.clusters.list' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithManagedKafkaAdminCanListClusters() {
        bindPlayer("roles/managedkafka.admin");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(CLUSTERS_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithViewerCanListButNotCreateCluster() {
        bindPlayer("roles/managedkafka.viewer");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(CLUSTERS_PATH)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .queryParam("clusterId", "viewer-denied-" + UUID.randomUUID().toString().substring(0, 8))
                .body(clusterBody())
                .when().post(CLUSTERS_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithClusterEditorCanCreateClusterButNotTopic() {
        String clusterId = "ctf-mk-" + UUID.randomUUID().toString().substring(0, 8);
        bindPlayer("roles/managedkafka.clusterEditor");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .queryParam("clusterId", clusterId)
                .body(clusterBody())
                .when().post(CLUSTERS_PATH)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .queryParam("topicId", "denied-topic")
                .body(Map.of("partitionCount", 1, "replicationFactor", 1))
                .when().post(CLUSTERS_PATH + "/" + clusterId + "/topics")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'managedkafka.topics.create' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void rootTokenAlwaysCanListClusters() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(CLUSTERS_PATH)
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

    private static Map<String, Object> clusterBody() {
        return Map.of(
                "capacityConfig", Map.of("vcpuCount", 3, "memoryBytes", 3221225472L),
                "gcpConfig", Map.of("accessConfig", Map.of("networkConfigs",
                        List.of(Map.of("subnet",
                                "projects/test/regions/us-central1/subnetworks/default")))));
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
                    "floci-gcp.services.kafka.mock", "true");
        }
    }
}

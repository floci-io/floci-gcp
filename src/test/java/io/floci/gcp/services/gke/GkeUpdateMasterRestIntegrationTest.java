package io.floci.gcp.services.gke;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * {@code ClusterManager.UpdateMaster} over REST ({@code POST .../clusters/{id}:updateMaster}).
 * The custom-method colon suffix is what makes this worth a route-level test on top of
 * {@link GkeServiceTest}: the JAX-RS path regex is the piece a service-level test cannot see.
 */
@QuarkusTest
class GkeUpdateMasterRestIntegrationTest {

    private static final String PROJECT = "gke-update-master-it";
    private static final String LOCATION = "us-central1";
    private static final String BASE = "/container/v1/projects/" + PROJECT + "/locations/" + LOCATION;

    @Test
    void updateMasterMovesTheControlPlaneAndLeavesNodesAlone() {
        String cluster = "upgrade-me";
        String clusterPath = BASE + "/clusters/" + cluster;

        given()
                .contentType("application/json")
                .body("{\"cluster\":{\"name\":\"" + cluster + "\"}}")
                .when().post(BASE + "/clusters")
                .then()
                .statusCode(200)
                .body("status", equalTo("DONE"));

        String nodeVersionBefore = given()
                .when().get(clusterPath)
                .then()
                .statusCode(200)
                .extract().path("currentNodeVersion");

        String operationName = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"masterVersion\":\"1.31.5-gke.1\"}")
                .when().post(clusterPath + ":updateMaster")
                .then()
                .statusCode(200)
                .body("operationType", equalTo("UPGRADE_MASTER"))
                .body("status", equalTo("DONE"))
                .body("targetLink", equalTo("projects/" + PROJECT + "/locations/" + LOCATION + "/clusters/" + cluster))
                .body("name", startsWith("operation-"))
                .extract().path("name");

        given()
                .when().get(BASE + "/operations/" + operationName)
                .then()
                .statusCode(200)
                .body("operationType", equalTo("UPGRADE_MASTER"))
                .body("status", equalTo("DONE"));

        // Real GKE upgrades the control plane independently of node pools: the node version
        // and the pool's own version are unaffected by a master-only upgrade.
        given()
                .when().get(clusterPath)
                .then()
                .statusCode(200)
                .body("currentMasterVersion", equalTo("1.31.5-gke.1"))
                .body("currentNodeVersion", equalTo(nodeVersionBefore))
                .body("nodePools[0].version", equalTo(nodeVersionBefore));
    }

    @Test
    void updateMasterRequiresAMasterVersion() {
        String cluster = "no-version";
        String clusterPath = BASE + "/clusters/" + cluster;

        given()
                .contentType("application/json")
                .body("{\"cluster\":{\"name\":\"" + cluster + "\"}}")
                .when().post(BASE + "/clusters")
                .then()
                .statusCode(200);

        String masterVersionBefore = given()
                .when().get(clusterPath)
                .then()
                .statusCode(200)
                .extract().path("currentMasterVersion");

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post(clusterPath + ":updateMaster")
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));

        given()
                .when().get(clusterPath)
                .then()
                .statusCode(200)
                .body("currentMasterVersion", equalTo(masterVersionBefore));
    }

    @Test
    void updateMasterOnAMissingClusterIs404() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"masterVersion\":\"1.31.5-gke.1\"}")
                .when().post(BASE + "/clusters/does-not-exist:updateMaster")
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
    }
}

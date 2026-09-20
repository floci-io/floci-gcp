package io.floci.gcp.services.gke;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * {@code ClusterManager.UpdateCluster} over REST ({@code PUT .../clusters/{id}}), with the
 * request shapes gcloud actually sends for {@code container clusters upgrade}.
 */
@QuarkusTest
class GkeUpdateClusterRestIntegrationTest {

    private static final String PROJECT = "gke-update-cluster-it";
    private static final String LOCATION = "us-central1";
    private static final String BASE = "/container/v1/projects/" + PROJECT + "/locations/" + LOCATION;

    @Test
    void gcloudUpgradeWithoutAVersionSendsDashAndGetsARealVersionBack() {
        String cluster = "gcloud-upgrade";
        String clusterPath = BASE + "/clusters/" + cluster;

        given()
                .contentType("application/json")
                .body("{\"cluster\":{\"name\":\"" + cluster + "\",\"initialClusterVersion\":\"1.29.0-gke.1\"}}")
                .when().post(BASE + "/clusters")
                .then()
                .statusCode(200);

        String advertised = given()
                .when().get(BASE + "/serverConfig")
                .then()
                .statusCode(200)
                .extract().path("defaultClusterVersion");

        // `gcloud container clusters upgrade C --master` with no --cluster-version.
        given()
                .contentType("application/json")
                .body("{\"update\":{\"desiredMasterVersion\":\"-\"}}")
                .when().put(clusterPath)
                .then()
                .statusCode(200)
                .body("status", equalTo("DONE"));

        given()
                .when().get(clusterPath)
                .then()
                .statusCode(200)
                .body("currentMasterVersion", equalTo(advertised))
                .body("currentMasterVersion", not(equalTo("-")))
                .body("currentNodeVersion", equalTo("1.29.0-gke.1"));

        // `gcloud container clusters upgrade C --node-pool default-pool` with no --cluster-version:
        // "-" on the node side means the cluster's master version.
        given()
                .contentType("application/json")
                .body("{\"update\":{\"desiredNodeVersion\":\"-\",\"desiredNodePoolId\":\"default-pool\"}}")
                .when().put(clusterPath)
                .then()
                .statusCode(200)
                .body("status", equalTo("DONE"));

        given()
                .when().get(clusterPath)
                .then()
                .statusCode(200)
                .body("currentNodeVersion", equalTo(advertised))
                .body("nodePools[0].version", equalTo(advertised));
    }

    @Test
    void nonStringVersionsAreBadRequestsAndLeaveTheClusterUntouched() {
        String cluster = "version-shape";
        String clusterPath = BASE + "/clusters/" + cluster;

        given()
                .contentType("application/json")
                .body("{\"cluster\":{\"name\":\"" + cluster
                        + "\",\"initialClusterVersion\":\"1.29.0-gke.1\"}}")
                .when().post(BASE + "/clusters")
                .then()
                .statusCode(200);

        for (String field : new String[] {"desiredNodeVersion", "desiredMasterVersion"}) {
            given()
                    .contentType("application/json")
                    .body("{\"update\":{\"" + field + "\":123}}")
                    .when().put(clusterPath)
                    .then()
                    .statusCode(400)
                    .body("error.code", equalTo(400))
                    .body("error.status", equalTo("INVALID_ARGUMENT"))
                    .body("error.message", equalTo(field + " must be a string"));
        }

        given()
                .contentType("application/json")
                .body("{\"update\":{\"desiredNodeVersion\":\"1.30.0-gke.1\",\"desiredMasterVersion\":123}}")
                .when().put(clusterPath)
                .then()
                .statusCode(400)
                .body("error.message", equalTo("desiredMasterVersion must be a string"));

        for (String field : new String[] {"desiredNodeVersion", "desiredMasterVersion"}) {
            given()
                    .contentType("application/json")
                    .body("{\"update\":{\"" + field + "\":\"\"}}")
                    .when().put(clusterPath)
                    .then()
                    .statusCode(200);
        }

        given()
                .when().get(clusterPath)
                .then()
                .statusCode(200)
                .body("currentMasterVersion", equalTo("1.29.0-gke.1"))
                .body("currentNodeVersion", equalTo("1.29.0-gke.1"))
                .body("nodePools[0].version", equalTo("1.29.0-gke.1"));
    }
}

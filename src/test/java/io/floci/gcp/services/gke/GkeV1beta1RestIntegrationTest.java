package io.floci.gcp.services.gke;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * The container API under {@code v1beta1}, as the google-beta Terraform provider and Pulumi gcp
 * call it: same HTTP bindings as {@code v1}, served by the same handlers and the same store.
 */
@QuarkusTest
class GkeV1beta1RestIntegrationTest {

    private static final String PROJECT = "gke-v1beta1-it";
    private static final String LOCATION = "us-central1";
    private static final String CANONICAL = "/v1beta1/projects/" + PROJECT + "/locations/" + LOCATION;
    private static final String PREFIXED = "/container" + CANONICAL;
    private static final String V1 = "/container/v1/projects/" + PROJECT + "/locations/" + LOCATION;

    @Test
    void clusterLifecycleOnV1beta1WithBetaOnlyFields() {
        String cluster = "beta-cluster";
        String operation = given()
                .header("Host", "container.localhost")
                .contentType("application/json")
                .body("{\"cluster\":{\"name\":\"" + cluster + "\","
                        + "\"workloadAltsConfig\":{\"enableAlts\":true},"
                        + "\"protectConfig\":{},"
                        + "\"nodeConfig\":{\"hostMaintenancePolicy\":{\"maintenanceInterval\":\"PERIODIC\"}}}}")
                .when().post(CANONICAL + "/clusters")
                .then()
                .statusCode(200)
                .body("operationType", equalTo("CREATE_CLUSTER"))
                .extract().path("name");

        given()
                .when().get(PREFIXED + "/operations/" + operation)
                .then()
                .statusCode(200)
                .body("name", equalTo(operation));

        given()
                .when().get(PREFIXED + "/clusters/" + cluster)
                .then()
                .statusCode(200)
                .body("name", equalTo(cluster));

        given()
                .when().get(V1 + "/clusters")
                .then()
                .statusCode(200)
                .body("clusters.name", hasItem(cluster));

        given()
                .contentType("application/json")
                .body("{\"nodePool\":{\"name\":\"beta-pool\",\"initialNodeCount\":1,"
                        + "\"config\":{\"ephemeralStorageConfig\":{\"localSsdCount\":1}}}}")
                .when().post(PREFIXED + "/clusters/" + cluster + "/nodePools")
                .then()
                .statusCode(200)
                .body("operationType", equalTo("CREATE_NODE_POOL"));

        given()
                .header("Host", "container.localhost")
                .when().get(CANONICAL + "/clusters/" + cluster + "/nodePools")
                .then()
                .statusCode(200)
                .body("nodePools.name", hasItem("beta-pool"));

        given()
                .when().delete(PREFIXED + "/clusters/" + cluster)
                .then()
                .statusCode(200)
                .body("operationType", equalTo("DELETE_CLUSTER"));
    }

    @Test
    void projectScopedRouteOnV1beta1() {
        given()
                .when().get("/container/v1beta1/projects/" + PROJECT + "/aggregated/usableSubnetworks")
                .then()
                .statusCode(200);
    }

    @Test
    void listLocationsIsUnimplementedNotAStorageError() {
        given()
                .header("Host", "container.localhost")
                .when().get("/v1beta1/projects/" + PROJECT + "/locations")
                .then()
                .statusCode(501)
                .contentType(containsString("application/json"))
                .body("error.status", equalTo("UNIMPLEMENTED"))
                .body("error.message", containsString("ListLocations"));
    }
}

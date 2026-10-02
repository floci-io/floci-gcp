package io.floci.gcp.services.gke;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code ClusterManager.ListOperations} / {@code GetOperation} over REST are scoped to the
 * project in the request path, as real GKE names operations under their project.
 */
@QuarkusTest
class GkeOperationsRestIntegrationTest {

    private static final String LOCATION = "us-central1";
    private static final String PROJECT_A = "gke-ops-it-a";
    private static final String PROJECT_B = "gke-ops-it-b";

    private static String base(String project) {
        return "/container/v1/projects/" + project + "/locations/" + LOCATION;
    }

    private static String createCluster(String project, String cluster) {
        return given()
                .contentType("application/json")
                .body("{\"cluster\":{\"name\":\"" + cluster + "\"}}")
                .when().post(base(project) + "/clusters")
                .then()
                .statusCode(200)
                .extract().path("name");
    }

    @Test
    void operationsAreScopedToTheRequestProject() {
        String opA = createCluster(PROJECT_A, "ops-a");
        String opB = createCluster(PROJECT_B, "ops-b");

        given()
                .when().get(base(PROJECT_A) + "/operations")
                .then()
                .statusCode(200)
                .body("operations.name", contains(opA));

        given()
                .when().get(base(PROJECT_B) + "/operations")
                .then()
                .statusCode(200)
                .body("operations.name", contains(opB));

        given()
                .when().get(base(PROJECT_A) + "/operations/" + opB)
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));

        given()
                .when().get(base(PROJECT_B) + "/operations/" + opB)
                .then()
                .statusCode(200)
                .body("name", equalTo(opB))
                .body("selfLink", equalTo("projects/" + PROJECT_B + "/locations/" + LOCATION + "/operations/" + opB));
    }
}

package io.floci.gcp.lifecycle;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class EmulatorInfoControllerIntegrationTest {

    @Test
    void stateReset_returnsOk() {
        given()
                .when().post("/_floci-gcp/state/reset")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("status", equalTo("OK"));
    }

    @Test
    void stateNuke_returnsOk() {
        given()
                .when().post("/_floci-gcp/state/nuke")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("status", equalTo("OK"));
    }

    @Test
    void stateReset_clearsServiceState() {
        String project = "reset-it";
        String topicName = "projects/" + project + "/topics/to-be-reset";

        given()
                .contentType("application/json")
                .body("{}")
                .when().put("/v1/projects/" + project + "/topics/to-be-reset")
                .then()
                .statusCode(200)
                .body("name", equalTo(topicName));

        given()
                .when().get("/v1/projects/" + project + "/topics/to-be-reset")
                .then()
                .statusCode(200)
                .body("name", equalTo(topicName));

        given()
                .when().post("/_floci-gcp/state/reset")
                .then()
                .statusCode(200)
                .body("status", equalTo("OK"));

        given()
                .when().get("/v1/projects/" + project + "/topics/to-be-reset")
                .then()
                .statusCode(404);
    }
}

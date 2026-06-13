package io.floci.gcp.services.cloudrun;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestProfile(CloudRunExecutionRestIntegrationTest.ExecutionProfile.class)
class CloudRunExecutionRestIntegrationTest {

    @AfterEach
    void cleanUpService() {
        Response delete = given()
                .when().delete(servicePath("run-exec-it", "us-central1", "nginx"));
        if (delete.statusCode() == 200) {
            String operationName = delete.path("name");
            given()
                    .urlEncodingEnabled(false)
                    .contentType("application/json")
                    .body("{\"timeout\":\"60s\"}")
                    .when().post("/v2/" + operationName + ":wait");
        }
    }

    @Test
    void createsInvokesAndDeletesDockerBackedService() {
        String project = "run-exec-it";
        String location = "us-central1";
        String serviceId = "nginx";
        String servicePath = servicePath(project, location, serviceId);
        String invocationPath = "/run/v2/projects/" + project + "/locations/" + location + "/services/" + serviceId;

        String operationName = given()
                .contentType("application/json")
                .queryParam("serviceId", serviceId)
                .body("{\"template\":{\"containers\":[{\"image\":\"nginx:latest\",\"ports\":[{\"containerPort\":80}]}]}}")
                .when().post("/v2/projects/" + project + "/locations/" + location + "/services")
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .body("metadata.terminalCondition.state", equalTo("CONDITION_PENDING"))
                .body("metadata.reconciling", equalTo(true))
                .body("metadata.latestReadyRevision", nullValue())
                .extract().path("name");

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"timeout\":\"60s\"}")
                .when().post("/v2/" + operationName + ":wait")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.terminalCondition.state", equalTo("CONDITION_SUCCEEDED"))
                .body("response.latestReadyRevision", notNullValue())
                .body("response.uri", equalTo("http://localhost:4588" + invocationPath));

        given()
                .header("X-Cloud-Run-Test", "execution")
                .when().get(invocationPath + "/?probe=1")
                .then()
                .statusCode(200)
                .body(containsString("Welcome to nginx"));

        String deleteOperation = given()
                .when().delete(servicePath)
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .extract().path("name");

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"timeout\":\"60s\"}")
                .when().post("/v2/" + deleteOperation + ":wait")
                .then()
                .statusCode(200)
                .body("done", equalTo(true));

        given()
                .when().get(invocationPath + "/")
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
    }

    private static String servicePath(String project, String location, String serviceId) {
        return "/v2/projects/" + project + "/locations/" + location + "/services/" + serviceId;
    }

    public static class ExecutionProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.services.cloudrun.execution.enabled", "true",
                    "floci-gcp.services.cloudrun.execution.startup-timeout", "60s");
        }
    }
}

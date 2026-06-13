package io.floci.gcp.services.cloudrun;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CloudRunRestIntegrationTest {

    @Test
    void cloudRunRestPathsUseProtoJsonAndLroPolling() {
        String project = "run-it-1";
        String location = "us-central1";
        String serviceId = "svc";
        String servicePath = "/v2/projects/" + project + "/locations/" + location + "/services/" + serviceId;

        String operationName = given()
                .contentType("application/json")
                .queryParam("serviceId", serviceId)
                .body("{\"template\":{\"containers\":[{\"image\":\"gcr.io/run-it-1/svc:latest\"}]}}")
                .when().post("/v2/projects/" + project + "/locations/" + location + "/services")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Service"))
                .body("response.name", equalTo("projects/" + project + "/locations/" + location + "/services/" + serviceId))
                .body("response.latestReadyRevision", containsString("/revisions/"))
                .extract().path("name");

        given()
                .when().get("/v2/" + operationName)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Service"))
                .body("metadata.name", equalTo("projects/" + project + "/locations/" + location + "/services/" + serviceId))
                .body("response.name", equalTo("projects/" + project + "/locations/" + location + "/services/" + serviceId));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v2/" + operationName + ":wait")
                .then()
                .statusCode(200)
                .body("name", equalTo(operationName))
                .body("done", equalTo(true));

        String revisionName = given()
                .queryParam("$alt", "json;enum-encoding=int")
                .when().get(servicePath)
                .then()
                .statusCode(200)
                .body("latestReadyRevision", containsString("/revisions/"))
                .body("terminalCondition.type", equalTo("Ready"))
                .body("trafficStatuses[0].percent", equalTo(100))
                .extract().path("latestReadyRevision");

        given()
                .queryParam("$alt", "json;enum-encoding=int")
                .when().get("/v2/projects/" + project + "/locations/" + location + "/services")
                .then()
                .statusCode(200)
                .body("services.name", hasItem("projects/" + project + "/locations/" + location + "/services/" + serviceId));

        given()
                .when().get(servicePath + "/revisions")
                .then()
                .statusCode(200)
                .body("revisions[0].name", equalTo(revisionName));

        given()
                .when().get("/v2/" + revisionName)
                .then()
                .statusCode(200)
                .body("service", equalTo("projects/" + project + "/locations/" + location + "/services/" + serviceId));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"policy\":{\"bindings\":[{\"role\":\"roles/run.invoker\",\"members\":[\"allUsers\"]}]}}")
                .when().post(servicePath + ":setIamPolicy")
                .then()
                .statusCode(200)
                .body("bindings[0].role", equalTo("roles/run.invoker"))
                .body("bindings[0].members", hasItem("allUsers"));

        given()
                .urlEncodingEnabled(false)
                .when().get(servicePath + ":getIamPolicy")
                .then()
                .statusCode(200)
                .body("bindings[0].members", hasItem("allUsers"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"permissions\":[\"run.services.get\",\"run.services.delete\"]}")
                .when().post(servicePath + ":testIamPermissions")
                .then()
                .statusCode(200)
                .body("permissions", contains("run.services.get", "run.services.delete"));

        given()
                .when().get("/v2/projects/" + project + "/locations/" + location + "/operations")
                .then()
                .statusCode(200)
                .body("operations.name", hasItem(operationName));

        given()
                .when().delete("/v2/" + operationName)
                .then()
                .statusCode(200)
                .body("$", anEmptyMap());

        given()
                .when().get("/v2/" + operationName)
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
    }

    @Test
    void missingCloudRunResourceUsesGcpErrorWrapper() {
        given()
                .when().get("/v2/projects/run-it-2/locations/us-central1/services/missing")
                .then()
                .statusCode(404)
                .body("error.code", equalTo(404))
                .body("error.status", equalTo("NOT_FOUND"))
                .body("error.errors[0].reason", equalTo("notFound"));
    }

    @Test
    void validateOnlyCreateDoesNotPersistResource() {
        String project = "run-it-validate";
        String location = "us-central1";

        String operationName = given()
                .contentType("application/json")
                .queryParam("serviceId", "svc")
                .queryParam("validateOnly", true)
                .body("{}")
                .when().post("/v2/projects/" + project + "/locations/" + location + "/services")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .extract().path("name");

        given()
                .when().get("/v2/projects/" + project + "/locations/" + location + "/services/svc")
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));

        given()
                .when().get("/v2/" + operationName)
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));

        given()
                .when().get("/v2/projects/" + project + "/locations/" + location + "/operations")
                .then()
                .statusCode(200)
                .body("$", anEmptyMap());
    }

    @Test
    void patchServiceUpdatesTemplateAndReturnsOperation() {
        String project = "run-it-patch";
        String location = "us-central1";
        String serviceId = "svc";
        String servicePath = "/v2/projects/" + project + "/locations/" + location + "/services/" + serviceId;

        given()
                .contentType("application/json")
                .queryParam("serviceId", serviceId)
                .body("{\"template\":{\"containers\":[{\"image\":\"gcr.io/run-it-patch/svc:v1\"}]}}")
                .when().post("/v2/projects/" + project + "/locations/" + location + "/services")
                .then()
                .statusCode(200)
                .body("done", equalTo(true));

        String updatedRevision = given()
                .contentType("application/json")
                .queryParam("updateMask", "labels,template")
                .body("""
                        {
                          "labels": {"env": "patch"},
                          "template": {
                            "containers": [{
                              "image": "gcr.io/run-it-patch/svc:v2",
                              "ports": [{"containerPort": 9090}]
                            }]
                          }
                        }
                        """)
                .when().patch(servicePath)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.labels.env", equalTo("patch"))
                .body("response.latestCreatedRevision", endsWith("/revisions/svc-00002"))
                .body("response.latestReadyRevision", endsWith("/revisions/svc-00002"))
                .extract().path("response.latestReadyRevision");

        given()
                .when().get("/v2/" + updatedRevision)
                .then()
                .statusCode(200)
                .body("containers[0].image", equalTo("gcr.io/run-it-patch/svc:v2"))
                .body("containers[0].ports[0].containerPort", equalTo(9090));
    }

}

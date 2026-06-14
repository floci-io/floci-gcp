package io.floci.gcp.services.cloudrun;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
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
        deleteServiceIfPresent("run-exec-it", "us-central1", "nginx");
        deleteServiceIfPresent("run-exec-gcs", "us-central1", "nginx-gcs");
        deleteServiceIfPresent("run-exec-gcs-write", "us-central1", "nginx-gcs-write");
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

        String serviceUri = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"timeout\":\"60s\"}")
                .when().post("/v2/" + operationName + ":wait")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.terminalCondition.state", equalTo("CONDITION_SUCCEEDED"))
                .body("response.latestReadyRevision", notNullValue())
                .body("response.uri", equalTo("http://nginx-d7b4d7c7e6dc.us-central1.run.localhost.floci.io:4588"))
                .extract().path("response.uri");

        URI uri = URI.create(serviceUri);
        given()
                .header("Host", uri.getAuthority())
                .when().get("/?probe=1")
                .then()
                .statusCode(200)
                .body(containsString("Welcome to nginx"));

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

    @Test
    void mountsGcsVolumeIntoDockerBackedService() {
        String project = "run-exec-gcs";
        String location = "us-central1";
        String serviceId = "nginx-gcs";
        String bucket = "run-exec-gcs-volume";

        given()
                .contentType("application/json")
                .body("{\"name\":\"" + bucket + "\",\"location\":\"US\"}")
                .when().post("/storage/v1/b?project=" + project)
                .then()
                .statusCode(200);
        given()
                .contentType("text/html")
                .body("hello from gcs volume")
                .when().post("/upload/storage/v1/b/" + bucket + "/o?uploadType=media&name=index.html")
                .then()
                .statusCode(200);

        String operationName = given()
                .contentType("application/json")
                .queryParam("serviceId", serviceId)
                .body("""
                        {
                          "template": {
                            "volumes": [{
                              "name": "site",
                              "gcs": {
                                "bucket": "run-exec-gcs-volume",
                                "readOnly": true
                              }
                            }],
                            "containers": [{
                              "image": "nginx:latest",
                              "ports": [{"containerPort": 80}],
                              "volumeMounts": [{
                                "name": "site",
                                "mountPath": "/usr/share/nginx/html"
                              }]
                            }]
                          }
                        }
                        """)
                .when().post("/v2/projects/" + project + "/locations/" + location + "/services")
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .extract().path("name");

        String serviceUri = waitOperation(operationName)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.terminalCondition.state", equalTo("CONDITION_SUCCEEDED"))
                .extract().path("response.uri");

        URI uri = URI.create(serviceUri);
        given()
                .header("Host", uri.getAuthority())
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("hello from gcs volume"));
    }

    @Test
    void syncsWritableGcsVolumeWhenRuntimeStops() {
        String project = "run-exec-gcs-write";
        String location = "us-central1";
        String serviceId = "nginx-gcs-write";
        String bucket = "run-exec-gcs-write-volume";

        given()
                .contentType("application/json")
                .body("{\"name\":\"" + bucket + "\",\"location\":\"US\"}")
                .when().post("/storage/v1/b?project=" + project)
                .then()
                .statusCode(200);

        String operationName = given()
                .contentType("application/json")
                .queryParam("serviceId", serviceId)
                .body("""
                        {
                          "template": {
                            "volumes": [{
                              "name": "site",
                              "gcs": {
                                "bucket": "run-exec-gcs-write-volume"
                              }
                            }],
                            "containers": [{
                              "image": "nginx:latest",
                              "command": ["/bin/sh", "-c"],
                              "args": ["echo synced from writable volume > /usr/share/nginx/html/index.html && nginx -g 'daemon off;'"],
                              "ports": [{"containerPort": 80}],
                              "volumeMounts": [{
                                "name": "site",
                                "mountPath": "/usr/share/nginx/html"
                              }]
                            }]
                          }
                        }
                        """)
                .when().post("/v2/projects/" + project + "/locations/" + location + "/services")
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .extract().path("name");

        String serviceUri = waitOperation(operationName)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.terminalCondition.state", equalTo("CONDITION_SUCCEEDED"))
                .extract().path("response.uri");

        URI uri = URI.create(serviceUri);
        given()
                .header("Host", uri.getAuthority())
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("synced from writable volume"));

        String deleteOperation = given()
                .when().delete(servicePath(project, location, serviceId))
                .then()
                .statusCode(200)
                .extract().path("name");
        waitOperation(deleteOperation).then().statusCode(200).body("done", equalTo(true));

        assertGcsObjectEventuallyContains(bucket, "index.html", "synced from writable volume");
    }

    private static String servicePath(String project, String location, String serviceId) {
        return "/v2/projects/" + project + "/locations/" + location + "/services/" + serviceId;
    }

    private static void deleteServiceIfPresent(String project, String location, String serviceId) {
        Response delete = given()
                .when().delete(servicePath(project, location, serviceId));
        if (delete.statusCode() == 200) {
            waitOperation(delete.path("name"));
        }
    }

    private static Response waitOperation(String operationName) {
        return given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"timeout\":\"60s\"}")
                .when().post("/v2/" + operationName + ":wait");
    }

    private static void assertGcsObjectEventuallyContains(String bucket, String object, String expected) {
        Response last = null;
        for (int i = 0; i < 30; i++) {
            last = given()
                    .queryParam("prefix", object)
                    .when().get("/storage/v1/b/" + bucket + "/o");
            if (last.statusCode() == 200 && last.asString().contains("\"name\":\"" + object + "\"")) {
                given()
                        .when().get("/storage/v1/b/" + bucket + "/o/" + object + "?alt=media")
                        .then()
                        .statusCode(200)
                        .body(containsString(expected));
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for GCS object sync", interrupted);
            }
        }
        throw new AssertionError("GCS object was not synced; last status="
                + (last == null ? "none" : last.statusCode()) + " body="
                + (last == null ? "" : last.asString()));
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

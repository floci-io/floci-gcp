package io.floci.gcp.services.cloudrun;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestProfile(CloudRunExecutionRestIntegrationTest.ExecutionProfile.class)
class CloudRunExecutionRestIntegrationTest {

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable(),
                "Docker daemon is required for Cloud Run execution integration tests");
    }

    @AfterEach
    void cleanUpService() {
        deleteServiceIfPresent("run-exec-it", "us-central1", "nginx");
        deleteServiceIfPresent("run-exec-gcs", "us-central1", "nginx-gcs");
        deleteServiceIfPresent("run-exec-gcs-write", "us-central1", "nginx-gcs-write");
        deleteServiceIfPresent("run-exec-gcs-replace", "us-central1", "nginx-gcs-replace");
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
        assertHttpEventuallyContains("/?probe=1", Map.of("Host", uri.getAuthority()), "Welcome to nginx");
        assertHttpEventuallyContains(invocationPath + "/?probe=1", Map.of("X-Cloud-Run-Test", "execution"),
                "Welcome to nginx");

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
        assertHttpEventuallyContains("/", Map.of("Host", uri.getAuthority()), "hello from gcs volume");
    }

    @Test
    void replacesSameNameServiceWithGcsVolume() {
        String project = "run-exec-gcs-replace";
        String location = "us-central1";
        String serviceId = "nginx-gcs-replace";
        String bucket = "run-exec-gcs-replace-volume";

        given()
                .contentType("application/json")
                .body("{\"name\":\"" + bucket + "\",\"location\":\"US\"}")
                .when().post("/storage/v1/b?project=" + project)
                .then()
                .statusCode(200);
        uploadObject(bucket, "index.html", "replacement generation one");

        String firstUri = createGcsVolumeService(project, location, serviceId, bucket);
        URI first = URI.create(firstUri);
        assertHttpEventuallyContains("/", Map.of("Host", first.getAuthority()), "replacement generation one");

        String deleteOperation = given()
                .when().delete(servicePath(project, location, serviceId))
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .extract().path("name");
        waitOperation(deleteOperation).then().statusCode(200).body("done", equalTo(true));

        uploadObject(bucket, "index.html", "replacement generation two");

        String secondUri = createGcsVolumeService(project, location, serviceId, bucket);
        URI second = URI.create(secondUri);
        assertHttpEventuallyContains("/", Map.of("Host", second.getAuthority()), "replacement generation two");
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
        assertHttpEventuallyContains("/", Map.of("Host", uri.getAuthority()), "synced from writable volume");

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

    private static String createGcsVolumeService(String project, String location, String serviceId, String bucket) {
        String operationName = given()
                .contentType("application/json")
                .queryParam("serviceId", serviceId)
                .body(gcsVolumeServiceBody(bucket))
                .when().post("/v2/projects/" + project + "/locations/" + location + "/services")
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .extract().path("name");

        return waitOperation(operationName)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.terminalCondition.state", equalTo("CONDITION_SUCCEEDED"))
                .extract().path("response.uri");
    }

    private static String gcsVolumeServiceBody(String bucket) {
        return """
                {
                  "template": {
                    "volumes": [{
                      "name": "site",
                      "gcs": {
                        "bucket": "%s",
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
                """.formatted(bucket);
    }

    private static void uploadObject(String bucket, String object, String body) {
        given()
                .contentType("text/html")
                .body(body)
                .when().post("/upload/storage/v1/b/" + bucket + "/o?uploadType=media&name=" + object)
                .then()
                .statusCode(200);
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

    private static void assertHttpEventuallyContains(String path, Map<String, String> headers, String expected) {
        Response last = null;
        for (int i = 0; i < 30; i++) {
            last = given()
                    .headers(headers)
                    .when().get(path);
            if (last.statusCode() == 200 && last.asString().contains(expected)) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for Cloud Run invocation", interrupted);
            }
        }
        throw new AssertionError("Cloud Run invocation did not return expected content; last status="
                + (last == null ? "none" : last.statusCode()) + " body="
                + (last == null ? "" : last.asString()));
    }

    private static boolean dockerAvailable() {
        Process process = null;
        try {
            process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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

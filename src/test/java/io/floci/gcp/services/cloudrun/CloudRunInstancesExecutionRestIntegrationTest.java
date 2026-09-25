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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestProfile(CloudRunInstancesExecutionRestIntegrationTest.ExecutionProfile.class)
class CloudRunInstancesExecutionRestIntegrationTest {

    private static final String PROJECT = "inst-exec-it";
    private static final String LOCATION = "us-central1";
    private static final String INSTANCE_ID = "httpd";
    private static final String INSTANCE_NAME =
            "projects/" + PROJECT + "/locations/" + LOCATION + "/instances/" + INSTANCE_ID;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable(),
                "Docker daemon is required for Cloud Run instance execution integration tests");
    }

    @AfterEach
    void cleanUpInstance() {
        Response response = given().when().delete("/v2/" + INSTANCE_NAME);
        if (response.statusCode() == 200 && !Boolean.TRUE.equals(response.path("done"))) {
            waitOperation(response.path("name"));
        }
    }

    @Test
    void createStopStartPatchAndDeleteDockerBackedInstance() {
        String createOperation = given()
                .contentType("application/json")
                .queryParam("instanceId", INSTANCE_ID)
                .body(httpdBody("hello-instance"))
                .when().post("/v2/projects/" + PROJECT + "/locations/" + LOCATION + "/instances")
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .body("metadata.generation", equalTo("1"))
                .body("metadata.reconciling", equalTo(true))
                .body("metadata.terminalCondition.type", equalTo("Running"))
                .body("metadata.terminalCondition.state", equalTo("CONDITION_RECONCILING"))
                .body("metadata.terminalCondition.message", equalTo("Waiting for instance to start."))
                .extract().path("name");

        String url = waitOperation(createOperation)
                .then()
                .body("done", equalTo(true))
                .body("error", nullValue())
                .body("response.observedGeneration", equalTo("1"))
                .body("response.terminalCondition.state", equalTo("CONDITION_SUCCEEDED"))
                .body("response.terminalCondition.message", startsWith("Started instance in "))
                .body("response.conditions[0].type", equalTo("ContainerReady"))
                .body("response.conditions[0].message", startsWith("Imported container image in "))
                .body("response.containerStatuses[0].imageDigest", containsString("@sha256:"))
                .body("response.urls[0]", equalTo(
                        "http://httpd-" + projectToken() + ".us-central1.run.localhost.floci.io:4588"))
                .extract().path("response.urls[0]");
        Map<String, String> host = Map.of("Host", URI.create(url).getAuthority());

        assertHttpEventuallyContains("/", host, "hello-instance");
        assertHttpEventuallyContains("/env.txt", host, "PORT=8080");
        given()
                .headers(host)
                .when().get("/env.txt")
                .then()
                .statusCode(200)
                .body(not(containsString("K_SERVICE")))
                .body(not(containsString("CLOUD_RUN")));

        String stopOperation = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v2/" + INSTANCE_NAME + ":stop")
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .body("metadata.terminalCondition.message", equalTo("Waiting for instance to be stopped."))
                .extract().path("name");
        waitOperation(stopOperation)
                .then()
                .body("done", equalTo(true))
                .body("response.generation", equalTo("2"))
                .body("response.terminalCondition.state", equalTo("CONDITION_FAILED"))
                .body("response.terminalCondition.message", equalTo("Instance stopped."))
                .body("response.urls[0]", equalTo(url))
                .body("response.containerStatuses[0].imageDigest", containsString("@sha256:"));

        given()
                .headers(host)
                .when().get("/")
                .then()
                .statusCode(503);

        String startOperation = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v2/" + INSTANCE_NAME + ":start")
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .extract().path("name");
        waitOperation(startOperation)
                .then()
                .body("done", equalTo(true))
                .body("response.generation", equalTo("3"))
                .body("response.terminalCondition.state", equalTo("CONDITION_SUCCEEDED"));
        assertHttpEventuallyContains("/", host, "hello-instance");

        given()
                .contentType("application/json")
                .queryParam("updateMask", "labels")
                .body("{\"labels\":{\"env\":\"test\"}}")
                .when().patch("/v2/" + INSTANCE_NAME)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.generation", equalTo("4"))
                .body("response.terminalCondition.state", equalTo("CONDITION_SUCCEEDED"));
        assertHttpEventuallyContains("/", host, "hello-instance");

        String patchOperation = given()
                .contentType("application/json")
                .queryParam("updateMask", "containers")
                .body(httpdBody("patched-instance"))
                .when().patch("/v2/" + INSTANCE_NAME)
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .body("metadata.terminalCondition.message", equalTo("Waiting for instance to start."))
                .extract().path("name");
        waitOperation(patchOperation)
                .then()
                .body("done", equalTo(true))
                .body("response.generation", equalTo("5"))
                .body("response.labels.env", equalTo("test"))
                .body("response.terminalCondition.state", equalTo("CONDITION_SUCCEEDED"));
        assertHttpEventuallyContains("/", host, "patched-instance");

        String deleteOperation = given()
                .when().delete("/v2/" + INSTANCE_NAME)
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .extract().path("name");
        waitOperation(deleteOperation)
                .then()
                .body("done", equalTo(true))
                .body("response.terminalCondition.message", equalTo("Instance completed for deletion."));

        given().when().get("/v2/" + INSTANCE_NAME).then().statusCode(404);
        given()
                .headers(host)
                .when().get("/")
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
    }

    private static String httpdBody(String content) {
        return """
                {"containers":[{"image":"busybox:latest","command":["sh","-c"],
                 "args":["mkdir -p /www && echo %s > /www/index.html && env > /www/env.txt && httpd -f -p 8080 -h /www"]}]}
                """.formatted(content);
    }

    private static String projectToken() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(PROJECT.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Response waitOperation(String operationName) {
        return given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"timeout\":\"120s\"}")
                .when().post("/v2/" + operationName + ":wait");
    }

    private static void assertHttpEventuallyContains(String path, Map<String, String> headers, String expected) {
        Response last = null;
        for (int i = 0; i < 40; i++) {
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
                throw new AssertionError("Interrupted while waiting for Cloud Run instance invocation", interrupted);
            }
        }
        throw new AssertionError("Cloud Run instance invocation did not return expected content; last status="
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
                    "floci-gcp.services.cloudrun.mock", "false",
                    "floci-gcp.services.cloudrun.execution.startup-timeout", "60s",
                    "floci-gcp.services.cloudrun.execution.cleanup-timeout", "2s");
        }
    }
}

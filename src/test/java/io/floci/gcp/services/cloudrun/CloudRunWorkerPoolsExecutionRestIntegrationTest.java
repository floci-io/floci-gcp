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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(CloudRunWorkerPoolsExecutionRestIntegrationTest.WorkerPoolExecutionProfile.class)
class CloudRunWorkerPoolsExecutionRestIntegrationTest {

    private static final String LOCATION = "us-central1";
    private static final String WORKER_LOOP = "trap 'exit 0' TERM; while true; do sleep 1; done";

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable(),
                "Docker daemon is required for Cloud Run worker pool execution integration tests");
    }

    @AfterEach
    void cleanUp() {
        deletePoolIfPresent("wp-exec-scale", "scale");
        deletePoolIfPresent("wp-exec-env", "env");
    }

    @Test
    void replicaCountFollowsManualInstanceCountWithinCap() {
        String project = "wp-exec-scale";
        String pool = poolPath(project, "scale");

        String operationName = given()
                .contentType("application/json")
                .queryParam("workerPoolId", "scale")
                .body(poolBody("busybox:latest", WORKER_LOOP, 3))
                .when().post(parentPath(project) + "/workerPools")
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .body("metadata.reconciling", equalTo(true))
                .body("metadata.terminalCondition.state", equalTo("CONDITION_RECONCILING"))
                .extract().path("name");

        String firstRevision = waitOperation(operationName)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("error", nullValue())
                .body("response.terminalCondition.state", equalTo("CONDITION_SUCCEEDED"))
                .body("response.scaling.manualInstanceCount", equalTo(3))
                .extract().path("response.latestReadyRevision");
        assertEquals(2, runningContainers(firstRevision));
        given()
                .when().get("/v2/" + firstRevision)
                .then()
                .body("scalingStatus.desiredMinInstanceCount", equalTo(3))
                .body("conditions.find { it.type == 'Ready' }.state", equalTo("CONDITION_SUCCEEDED"));

        patchScaling(pool, 0);
        assertEquals(0, runningContainers(firstRevision));
        given()
                .when().get(pool)
                .then()
                .body("scaling.manualInstanceCount", equalTo(0))
                .body("terminalCondition.state", equalTo("CONDITION_SUCCEEDED"));

        patchScaling(pool, 1);
        assertEquals(1, runningContainers(firstRevision));

        String templateOperation = given()
                .contentType("application/json")
                .queryParam("updateMask", "template")
                .body("{\"template\":{\"containers\":[{\"image\":\"busybox:latest\",\"command\":[\"sh\",\"-c\"],"
                        + "\"args\":[\"echo second; " + WORKER_LOOP + "\"]}]}}")
                .when().patch(pool)
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .extract().path("name");
        String secondRevision = waitOperation(templateOperation)
                .then()
                .body("done", equalTo(true))
                .body("error", nullValue())
                .body("response.latestReadyRevision", not(equalTo(firstRevision)))
                .extract().path("response.latestReadyRevision");
        assertEquals(1, runningContainers(secondRevision));
        assertEquals(0, runningContainers(firstRevision));
        given()
                .when().get("/v2/" + firstRevision)
                .then()
                .body("conditions.find { it.type == 'Active' }.message", equalTo("Revision retired."));

        String deleteOperation = given()
                .when().delete(pool)
                .then()
                .statusCode(200)
                .body("done", nullValue())
                .extract().path("name");
        waitOperation(deleteOperation)
                .then()
                .body("done", equalTo(true))
                .body("error", nullValue())
                .body("response.deleteTime", notNullValue());
        assertEquals(0, runningContainers(secondRevision));
        given().when().get(pool).then().statusCode(404);
    }

    @Test
    void workerContainersSeeWorkerPoolEnvironment() {
        String project = "wp-exec-env";
        String bucket = "wp-exec-env-volume";
        given()
                .contentType("application/json")
                .body("{\"name\":\"" + bucket + "\",\"location\":\"US\"}")
                .when().post("/storage/v1/b?project=" + project)
                .then()
                .statusCode(200);

        String operationName = given()
                .contentType("application/json")
                .queryParam("workerPoolId", "env")
                .body("""
                        {
                          "template": {
                            "volumes": [{"name": "out", "gcs": {"bucket": "%s"}}],
                            "containers": [{
                              "image": "busybox:latest",
                              "command": ["sh", "-c"],
                              "args": ["env > /out/env.txt; %s"],
                              "env": [{"name": "APP_MODE", "value": "worker"}],
                              "volumeMounts": [{"name": "out", "mountPath": "/out"}]
                            }]
                          }
                        }
                        """.formatted(bucket, WORKER_LOOP))
                .when().post(parentPath(project) + "/workerPools")
                .then()
                .statusCode(200)
                .extract().path("name");
        String revision = waitOperation(operationName)
                .then()
                .body("done", equalTo(true))
                .body("error", nullValue())
                .extract().path("response.latestReadyRevision");
        String revisionId = revision.substring(revision.lastIndexOf('/') + 1);
        assertEquals(1, runningContainers(revision));

        String deleteOperation = given()
                .when().delete(poolPath(project, "env"))
                .then()
                .statusCode(200)
                .extract().path("name");
        waitOperation(deleteOperation).then().body("done", equalTo(true));
        assertEquals(0, runningContainers(revision));

        given()
                .when().get("/storage/v1/b/" + bucket + "/o/env.txt?alt=media")
                .then()
                .statusCode(200)
                .body(containsString("CLOUD_RUN_WORKER_POOL=env\n"))
                .body(containsString("CLOUD_RUN_REVISION=" + revisionId + "\n"))
                .body(containsString("APP_MODE=worker\n"))
                .body(not(matchesPattern("(?s).*(^|\\n)(PORT|K_SERVICE|K_REVISION)=.*")));
    }

    private static void patchScaling(String pool, int count) {
        String operationName = given()
                .contentType("application/json")
                .queryParam("updateMask", "scaling.manualInstanceCount")
                .body("{\"scaling\":{\"manualInstanceCount\":" + count + "}}")
                .when().patch(pool)
                .then()
                .statusCode(200)
                .extract().path("name");
        waitOperation(operationName)
                .then()
                .body("done", equalTo(true))
                .body("error", nullValue());
    }

    private static String poolBody(String image, String script, int instances) {
        return """
                {
                  "scaling": {"manualInstanceCount": %d},
                  "template": {
                    "containers": [{
                      "image": "%s",
                      "command": ["sh", "-c"],
                      "args": ["%s"]
                    }]
                  }
                }
                """.formatted(instances, image, script);
    }

    private static int runningContainers(String revisionName) {
        String output = docker("ps", "-q", "--filter", "label=floci_resource=" + revisionName);
        return (int) output.lines().filter(line -> !line.isBlank()).count();
    }

    private static String docker(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "docker";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("docker command timed out: " + String.join(" ", command));
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new AssertionError("docker command failed: " + output);
            }
            return output;
        } catch (IOException e) {
            throw new AssertionError("docker command failed to start", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while running docker", e);
        }
    }

    private static void deletePoolIfPresent(String project, String poolId) {
        Response delete = given().when().delete(poolPath(project, poolId));
        if (delete.statusCode() == 200) {
            waitOperation(delete.path("name"));
        }
    }

    private static Response waitOperation(String operationName) {
        return given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"timeout\":\"120s\"}")
                .when().post("/v2/" + operationName + ":wait");
    }

    private static String parentPath(String project) {
        return "/v2/projects/" + project + "/locations/" + LOCATION;
    }

    private static String poolPath(String project, String poolId) {
        return parentPath(project) + "/workerPools/" + poolId;
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

    public static class WorkerPoolExecutionProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.services.cloudrun.mock", "false",
                    "floci-gcp.services.cloudrun.execution.max-worker-instances", "2",
                    "floci-gcp.services.cloudrun.execution.cleanup-timeout", "3s");
        }
    }
}

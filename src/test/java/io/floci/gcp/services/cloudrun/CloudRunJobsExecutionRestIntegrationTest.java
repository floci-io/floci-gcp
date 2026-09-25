package io.floci.gcp.services.cloudrun;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CloudRunJobsExecutionRestIntegrationTest.JobsExecutionProfile.class)
class CloudRunJobsExecutionRestIntegrationTest {

    private static final String LOCATION = "us-central1";

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker daemon is required for Cloud Run jobs execution tests");
    }

    @Test
    void runsTasksToCompletionRespectingParallelism() {
        String project = "jobs-exec-parallel";
        createJob(project, "par", """
                {"template":{"taskCount":3,"parallelism":2,"template":{"containers":[{"image":"busybox",
                  "command":["sh","-c"],"args":["sleep 2"]}]}}}
                """);

        Response run = api()
                .contentType("application/json")
                .body("{}")
                .when().post(jobPath(project, "par") + ":run");
        run.then()
                .statusCode(200)
                .body("done", nullValue())
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Execution"))
                .body("metadata.reconciling", equalTo(true))
                .body("metadata.conditions[0].type", equalTo("Completed"))
                .body("metadata.conditions[0].state", equalTo("CONDITION_PENDING"));
        String executionName = run.path("metadata.name");

        waitOperation(run.path("name"))
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("error", nullValue())
                .body("response.name", equalTo(executionName))
                .body("response.parallelism", equalTo(2))
                .body("response.succeededCount", equalTo(3))
                .body("response.failedCount", nullValue())
                .body("response.conditions.type",
                        equalTo(List.of("Started", "Completed", "ContainerReady", "ResourcesAvailable")))
                .body("response.conditions[0].message", containsString("Started deployed execution in "))
                .body("response.conditions[1].message", containsString("Execution completed successfully in "))
                .body("response.conditions[2].message", containsString("Imported container image in "));

        Response tasks = given().when().get("/v2/" + executionName + "/tasks");
        tasks.then().statusCode(200);
        List<String> starts = tasks.path("tasks.startTime");
        List<String> completions = tasks.path("tasks.completionTime");
        Instant firstCompletion = Instant.parse(completions.get(0)).isBefore(Instant.parse(completions.get(1)))
                ? Instant.parse(completions.get(0))
                : Instant.parse(completions.get(1));
        assertFalse(Instant.parse(starts.get(2)).isBefore(firstCompletion),
                "task 2 must wait for a free parallelism slot: " + tasks.asString());

        given()
                .when().get(jobPath(project, "par"))
                .then()
                .body("latestCreatedExecution.completionStatus", equalTo("EXECUTION_SUCCEEDED"));
        assertEventuallyNoContainers(executionName);
    }

    @Test
    void injectsTaskEnvironmentAndRetriesNonZeroExit() {
        String project = "jobs-exec-env";
        String bucket = "jobs-exec-env-out";
        given()
                .contentType("application/json")
                .body("{\"name\":\"" + bucket + "\",\"location\":\"US\"}")
                .when().post("/storage/v1/b?project=" + project)
                .then()
                .statusCode(200);
        createJob(project, "envjob", """
                {"template":{"taskCount":2,"parallelism":1,"template":{
                  "maxRetries":1,
                  "volumes":[{"name":"out","gcs":{"bucket":"%s"}}],
                  "containers":[{"image":"busybox","command":["sh","-c"],
                    "env":[{"name":"FROM_TEMPLATE","value":"yes"}],
                    "args":["env > /out/task-$CLOUD_RUN_TASK_INDEX-attempt-$CLOUD_RUN_TASK_ATTEMPT.env; [ \\"$CLOUD_RUN_TASK_ATTEMPT\\" -ge 1 ]"],
                    "volumeMounts":[{"name":"out","mountPath":"/out"}]}]}}}
                """.formatted(bucket));

        Response operation = waitOperation(runJobOperation(project, "envjob"));
        operation.then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("error", nullValue())
                .body("response.succeededCount", equalTo(2))
                .body("response.retriedCount", equalTo(2));
        String executionName = operation.path("response.name");
        String executionId = CloudRunRuntimeService.lastSegment(executionName);

        given()
                .when().get("/v2/" + executionName + "/tasks")
                .then()
                .body("tasks.retried", equalTo(List.of(1, 1)))
                .body("tasks[0].lastAttemptResult.status", equalTo(Map.of()));

        String env = objectText(bucket, "task-1-attempt-0.env");
        assertTrue(env.contains("CLOUD_RUN_JOB=envjob"), env);
        assertTrue(env.contains("CLOUD_RUN_EXECUTION=" + executionId), env);
        assertTrue(env.contains("CLOUD_RUN_TASK_INDEX=1"), env);
        assertTrue(env.contains("CLOUD_RUN_TASK_ATTEMPT=0"), env);
        assertTrue(env.contains("CLOUD_RUN_TASK_COUNT=2"), env);
        assertTrue(env.contains("FROM_TEMPLATE=yes"), env);
        assertFalse(env.contains("PORT="), env);
        assertFalse(env.contains("K_SERVICE="), env);
        assertTrue(objectText(bucket, "task-1-attempt-1.env").contains("CLOUD_RUN_TASK_ATTEMPT=1"));
        assertTrue(objectText(bucket, "task-0-attempt-1.env").contains("CLOUD_RUN_TASK_INDEX=0"));
    }

    @Test
    void failsRunOperationWithCode10AfterRetriesAndFinishesOtherTasks() {
        String project = "jobs-exec-fail";
        createJob(project, "failing", """
                {"template":{"taskCount":2,"template":{"maxRetries":1,"containers":[{"image":"busybox",
                  "command":["sh","-c"],"args":["if [ \\"$CLOUD_RUN_TASK_INDEX\\" = 1 ]; then exit 3; fi; sleep 1"]}]}}}
                """);

        Response operation = waitOperation(runJobOperation(project, "failing"));
        String executionName = operation.path("metadata.name");
        String executionId = CloudRunRuntimeService.lastSegment(executionName);
        operation.then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response", nullValue())
                .body("error.code", equalTo(10))
                .body("error.message", equalTo("Task " + executionId
                        + "-task1 failed with exit code: 3 and message: The container exited with an error."))
                .body("metadata.succeededCount", equalTo(1))
                .body("metadata.failedCount", equalTo(1))
                .body("metadata.retriedCount", equalTo(1))
                .body("metadata.conditions[1].type", equalTo("Completed"))
                .body("metadata.conditions[1].state", equalTo("CONDITION_FAILED"))
                .body("metadata.conditions[1].executionReason", equalTo("NON_ZERO_EXIT_CODE"));

        given()
                .when().get("/v2/" + executionName + "/tasks/" + executionId + "-task1")
                .then()
                .statusCode(200)
                .body("retried", equalTo(1))
                .body("lastAttemptResult.status.code", equalTo(10))
                .body("lastAttemptResult.status.message", equalTo("The container exited with an error."))
                .body("lastAttemptResult.exitCode", equalTo(3))
                .body("conditions[1].state", equalTo("CONDITION_FAILED"));
        given()
                .when().get(jobPath(project, "failing"))
                .then()
                .body("latestCreatedExecution.completionStatus", equalTo("EXECUTION_FAILED"));
    }

    @Test
    void timesOutEachAttemptAndFailsWithCode4() {
        String project = "jobs-exec-timeout";
        createJob(project, "slow", """
                {"template":{"template":{"maxRetries":1,"timeout":"2s","containers":[{"image":"busybox",
                  "command":["sh","-c"],"args":["sleep 60"]}]}}}
                """);

        Response operation = waitOperation(runJobOperation(project, "slow"));
        String executionName = operation.path("metadata.name");
        String executionId = CloudRunRuntimeService.lastSegment(executionName);
        operation.then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("error.code", equalTo(4))
                .body("error.message", equalTo("Task " + executionId
                        + "-task0 failed with exit code: 0 and message: The configured timeout was reached."))
                .body("metadata.failedCount", equalTo(1))
                .body("metadata.retriedCount", equalTo(1));

        given()
                .when().get("/v2/" + executionName + "/tasks")
                .then()
                .body("tasks[0].retried", equalTo(1))
                .body("tasks[0].lastAttemptResult.status.code", equalTo(4))
                .body("tasks[0].lastAttemptResult.status.message", equalTo("The configured timeout was reached."))
                .body("tasks[0].lastAttemptResult.exitCode", nullValue());
        assertEventuallyNoContainers(executionName);
    }

    @Test
    void cancelStopsRunningContainersAndCompletesRunOperationWithExecution() {
        String project = "jobs-exec-cancel";
        createJob(project, "long", """
                {"template":{"taskCount":2,"parallelism":1,"template":{"containers":[{"image":"busybox",
                  "command":["sh","-c"],"args":["sleep 120"]}]}}}
                """);
        String runOperation = runJobOperation(project, "long");
        String executionName = operation(runOperation).path("metadata.name");
        awaitExecution(executionName, execution -> Integer.valueOf(1).equals(execution.path("runningCount")));

        Response cancel = api()
                .contentType("application/json")
                .body("{\"etag\":\"\\\"bogus\\\"\"}")
                .when().post("/v2/" + executionName + ":cancel");
        cancel.then()
                .statusCode(200)
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Execution"))
                .body("metadata.generation", equalTo("2"));

        waitOperation(cancel.path("name"))
                .then()
                .body("done", equalTo(true))
                .body("response.cancelledCount", equalTo(2))
                .body("response.conditions[1].state", equalTo("CONDITION_FAILED"))
                .body("response.conditions[1].message", equalTo("Cancelled by user."))
                .body("response.conditions[1].executionReason", equalTo("CANCELLED"));
        waitOperation(runOperation)
                .then()
                .body("done", equalTo(true))
                .body("error", nullValue())
                .body("response.name", equalTo(executionName))
                .body("response.conditions[1].message", equalTo("Cancelled by user."));
        given()
                .when().get("/v2/" + executionName + "/tasks")
                .then()
                .body("tasks.lastAttemptResult.status.code", equalTo(List.of(1, 1)))
                .body("tasks.lastAttemptResult.status.message",
                        equalTo(List.of("Cancelled by user.", "Cancelled by user.")));
        assertEventuallyNoContainers(executionName);

        api()
                .contentType("application/json")
                .body("{}")
                .when().post("/v2/" + executionName + ":cancel")
                .then()
                .statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"));
        given()
                .when().get(jobPath(project, "long"))
                .then()
                .body("latestCreatedExecution.completionStatus", equalTo("EXECUTION_CANCELLED"));
    }

    @Test
    void deletingRunningExecutionCancelsItFirst() {
        String project = "jobs-exec-delexec";
        createJob(project, "long", """
                {"template":{"template":{"containers":[{"image":"busybox","command":["sh","-c"],
                  "args":["sleep 120"]}]}}}
                """);
        String runOperation = runJobOperation(project, "long");
        String executionName = operation(runOperation).path("metadata.name");
        awaitExecution(executionName, execution -> Integer.valueOf(1).equals(execution.path("runningCount")));

        Response delete = given().when().delete("/v2/" + executionName);
        delete.then()
                .statusCode(200)
                .body("metadata.deleteTime", notNullValue())
                .body("metadata.generation", equalTo("2"));
        given().when().get("/v2/" + executionName).then().statusCode(404);

        waitOperation(delete.path("name"))
                .then()
                .body("done", equalTo(true))
                .body("response.deleteTime", notNullValue())
                .body("response.cancelledCount", equalTo(1))
                .body("response.conditions[1].message", equalTo("Cancelled by user."));
        waitOperation(runOperation)
                .then()
                .body("done", equalTo(true))
                .body("error", nullValue())
                .body("response.deleteTime", notNullValue());
        assertEventuallyNoContainers(executionName);
    }

    @Test
    void deletingJobCancelsItsRunningExecutionsConcurrently() {
        String project = "jobs-exec-deljob";
        createJob(project, "overlap", """
                {"template":{"template":{"containers":[{"image":"busybox","command":["sh","-c"],
                  "args":["sleep 120"]}]}}}
                """);
        String firstRun = runJobOperation(project, "overlap");
        String secondRun = runJobOperation(project, "overlap");
        String first = operation(firstRun).path("metadata.name");
        String second = operation(secondRun).path("metadata.name");
        awaitExecution(first, execution -> Integer.valueOf(1).equals(execution.path("runningCount")));
        awaitExecution(second, execution -> Integer.valueOf(1).equals(execution.path("runningCount")));

        given()
                .when().delete(jobPath(project, "overlap"))
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.deleteTime", notNullValue());
        given().when().get(jobPath(project, "overlap")).then().statusCode(404);
        given().when().get("/v2/" + first).then().statusCode(404);

        for (String run : List.of(firstRun, secondRun)) {
            waitOperation(run)
                    .then()
                    .body("done", equalTo(true))
                    .body("error", nullValue())
                    .body("response.cancelledCount", equalTo(1))
                    .body("response.deleteTime", notNullValue())
                    .body("response.conditions[1].executionReason", equalTo("CANCELLED"));
        }
        assertEventuallyNoContainers(first);
        assertEventuallyNoContainers(second);
    }

    @Test
    void rejectsUnsupportedTemplatesWithServicesMessages() {
        String project = "jobs-exec-invalid";
        given()
                .contentType("application/json")
                .queryParam("jobId", "sidecar")
                .body("""
                        {"template":{"template":{"containers":[{"image":"busybox"},{"image":"busybox"}]}}}
                        """)
                .when().post(jobsPath(project))
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("error.message", equalTo("Cloud Run execution supports exactly one container"));
        given().when().get(jobPath(project, "sidecar")).then().statusCode(404).body("error", not(nullValue()));
    }

    private static void createJob(String project, String jobId, String body) {
        given()
                .contentType("application/json")
                .queryParam("jobId", jobId)
                .body(body)
                .when().post(jobsPath(project))
                .then()
                .statusCode(200)
                .body("done", equalTo(true));
    }

    private static String runJobOperation(String project, String jobId) {
        return api()
                .contentType("application/json")
                .body("{}")
                .when().post(jobPath(project, jobId) + ":run")
                .then()
                .statusCode(200)
                .extract().path("name");
    }

    private static Response operation(String operationName) {
        return given().when().get("/v2/" + operationName);
    }

    private static Response waitOperation(String operationName) {
        return api()
                .contentType("application/json")
                .body("{\"timeout\":\"120s\"}")
                .when().post("/v2/" + operationName + ":wait");
    }

    private static void awaitExecution(String executionName, Predicate<Response> condition) {
        Response last = null;
        for (int i = 0; i < 300; i++) {
            last = given().when().get("/v2/" + executionName);
            if (last.statusCode() == 200 && condition.test(last)) {
                return;
            }
            sleep(200);
        }
        throw new AssertionError("Execution did not reach the expected state: "
                + (last == null ? "none" : last.asString()));
    }

    private static String objectText(String bucket, String object) {
        Response response = given().when().get("/storage/v1/b/" + bucket + "/o/" + object + "?alt=media");
        assertEquals(200, response.statusCode(), "missing GCS object " + object + ": " + response.asString());
        return response.asString();
    }

    private static void assertEventuallyNoContainers(String executionName) {
        String remaining = "";
        for (int i = 0; i < 50; i++) {
            remaining = dockerContainersFor(executionName);
            if (remaining.isBlank()) {
                return;
            }
            sleep(200);
        }
        throw new AssertionError("Task containers were not removed: " + remaining);
    }

    private static String dockerContainersFor(String executionName) {
        StringBuilder ids = new StringBuilder();
        for (int index = 0; index < 3; index++) {
            String taskName = executionName + "/tasks/" + CloudRunRuntimeService.lastSegment(executionName)
                    + "-task" + index;
            ids.append(docker("ps", "-aq", "--filter", "label=floci_resource=" + taskName));
        }
        return ids.toString().trim();
    }

    private static String docker(String... args) {
        try {
            List<String> command = new ArrayList<>(List.of("docker"));
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("docker CLI unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while calling docker", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", e);
        }
    }

    private static RequestSpecification api() {
        return given().urlEncodingEnabled(false);
    }

    private static String jobsPath(String project) {
        return "/v2/projects/" + project + "/locations/" + LOCATION + "/jobs";
    }

    private static String jobPath(String project, String jobId) {
        return jobsPath(project) + "/" + jobId;
    }

    static boolean dockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
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

    public static class JobsExecutionProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.services.cloudrun.mock", "false",
                    "floci-gcp.services.cloudrun.execution.startup-timeout", "60s",
                    "floci-gcp.services.cloudrun.execution.cleanup-timeout", "2s");
        }
    }
}

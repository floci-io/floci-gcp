package io.floci.gcp.services.cloudrun;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudRunJobsRestIntegrationTest {

    private static final String LOCATION = "us-central1";
    private static final String BUSYBOX_JOB = """
            {"template":{"template":{"containers":[{"image":"busybox","command":["sh","-c"],"args":["true"]}]}}}
            """;

    @Test
    void createCompletesGcpDefaultsAndPreservesClientFields() {
        String project = "jobs-it-defaults";
        String body = """
                {"client":"floci","clientVersion":"1.0","labels":{"team":"a"},
                 "template":{"template":{"containers":[{"image":"busybox"}]}}}
                """;
        api()
                .contentType("application/json")
                .queryParam("jobId", "defaults")
                .body(body)
                .when().post(jobsPath(project))
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Job"))
                .body("response.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Job"))
                .body("response.name", equalTo(jobName(project, "defaults")));

        api()
                .when().get(jobPath(project, "defaults"))
                .then()
                .statusCode(200)
                .body("uid", notNullValue())
                .body("generation", equalTo("1"))
                .body("observedGeneration", equalTo("1"))
                .body("launchStage", equalTo("GA"))
                .body("client", equalTo("floci"))
                .body("clientVersion", equalTo("1.0"))
                .body("labels.team", equalTo("a"))
                .body("etag", notNullValue())
                .body("terminalCondition.type", equalTo("Ready"))
                .body("terminalCondition.state", equalTo("CONDITION_SUCCEEDED"))
                .body("conditions", nullValue())
                .body("latestCreatedExecution", equalTo(Map.of()))
                .body("template.taskCount", equalTo(1))
                .body("template.parallelism", nullValue())
                .body("template.template.maxRetries", equalTo(3))
                .body("template.template.timeout", equalTo("600s"))
                .body("template.template.executionEnvironment", equalTo("EXECUTION_ENVIRONMENT_GEN2"))
                .body("template.template.containers[0].image", equalTo("busybox"))
                .body("template.template.containers[0].name", nullValue())
                .body("template.template.containers[0].resources.limits.cpu", equalTo("1000m"))
                .body("template.template.containers[0].resources.limits.memory", equalTo("512Mi"));
    }

    @Test
    void duplicateJobIdIsAlreadyExists() {
        String project = "jobs-it-dup";
        createJob(project, "dup", BUSYBOX_JOB);
        api()
                .contentType("application/json")
                .queryParam("jobId", "dup")
                .body(BUSYBOX_JOB)
                .when().post(jobsPath(project))
                .then()
                .statusCode(409)
                .body("error.code", equalTo(409))
                .body("error.status", equalTo("ALREADY_EXISTS"))
                .body("error.message", equalTo("Resource 'dup' already exists."));
    }

    @Test
    void validateOnlyCreatePatchRunAndDeletePersistNothing() {
        String project = "jobs-it-validate";
        api()
                .contentType("application/json")
                .queryParam("jobId", "dry")
                .queryParam("validateOnly", true)
                .body(BUSYBOX_JOB)
                .when().post(jobsPath(project))
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.name", equalTo(jobName(project, "dry")));
        api().when().get(jobPath(project, "dry")).then().statusCode(404).body("error.status", equalTo("NOT_FOUND"));

        api()
                .contentType("application/json")
                .queryParam("validateOnly", true)
                .queryParam("allowMissing", true)
                .body(BUSYBOX_JOB)
                .when().patch(jobPath(project, "dry"))
                .then()
                .statusCode(200)
                .body("done", equalTo(true));
        api().when().get(jobPath(project, "dry")).then().statusCode(404);

        createJob(project, "real", BUSYBOX_JOB);
        String operationName = api()
                .contentType("application/json")
                .body("{\"validateOnly\":true}")
                .when().post(jobPath(project, "real") + ":run")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Execution"))
                .body("metadata.name", matchesPattern(jobName(project, "real") + "/executions/real-[a-z0-9]{5}"))
                .extract().path("name");
        api().when().get("/v2/" + operationName).then().statusCode(404);
        api()
                .when().get(jobPath(project, "real") + "/executions")
                .then()
                .statusCode(200)
                .body("executions", nullValue());
        api()
                .when().get(jobPath(project, "real"))
                .then()
                .statusCode(200)
                .body("executionCount", nullValue());

        api()
                .queryParam("validateOnly", true)
                .when().delete(jobPath(project, "real"))
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.deleteTime", notNullValue());
        api().when().get(jobPath(project, "real")).then().statusCode(200);
    }

    @Test
    void listJobsPagesWithTheServicesTokenScheme() {
        String project = "jobs-it-list";
        createJob(project, "a", BUSYBOX_JOB);
        createJob(project, "b", BUSYBOX_JOB);
        createJob(project, "c", BUSYBOX_JOB);

        Response first = api()
                .queryParam("pageSize", 2)
                .queryParam("showDeleted", true)
                .when().get(jobsPath(project));
        first.then()
                .statusCode(200)
                .body("jobs.name", equalTo(List.of(jobName(project, "a"), jobName(project, "b"))))
                .body("nextPageToken", notNullValue());

        api()
                .queryParam("pageSize", 2)
                .queryParam("pageToken", first.path("nextPageToken").toString())
                .when().get(jobsPath(project))
                .then()
                .statusCode(200)
                .body("jobs.name", equalTo(List.of(jobName(project, "c"))))
                .body("nextPageToken", nullValue());
    }

    @Test
    void patchReplacesTemplateBumpsGenerationAndSupportsAllowMissing() {
        String project = "jobs-it-patch";
        api()
                .contentType("application/json")
                .body(BUSYBOX_JOB)
                .when().patch(jobPath(project, "missing"))
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));

        api()
                .contentType("application/json")
                .queryParam("allowMissing", true)
                .body(BUSYBOX_JOB)
                .when().patch(jobPath(project, "upserted"))
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.generation", equalTo("1"));

        api()
                .contentType("application/json")
                .body(BUSYBOX_JOB)
                .when().patch(jobPath(project, "upserted"))
                .then()
                .statusCode(200)
                .body("response.generation", equalTo("1"));

        api()
                .contentType("application/json")
                .body("""
                        {"template":{"taskCount":4,"template":{"maxRetries":0,"timeout":"30s",
                          "containers":[{"image":"busybox","args":["echo","patched"]}]}}}
                        """)
                .when().patch(jobPath(project, "upserted"))
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.generation", equalTo("2"))
                .body("response.observedGeneration", equalTo("2"))
                .body("response.template.taskCount", equalTo(4))
                .body("response.template.template.maxRetries", equalTo(0))
                .body("response.template.template.timeout", equalTo("30s"))
                .body("response.template.template.containers[0].args", equalTo(List.of("echo", "patched")));
    }

    @Test
    void runInMockModeSucceedsImmediatelyWithGcpNamesCountsAndConditions() {
        String project = "jobs-it-run";
        createJob(project, "batch", """
                {"template":{"taskCount":3,"template":{"containers":[{"image":"busybox"}]}}}
                """);

        Response run = api()
                .contentType("application/json")
                .body("{\"etag\":\"\\\"bogus\\\"\"}")
                .when().post(jobPath(project, "batch") + ":run");
        run.then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("error", nullValue())
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Execution"))
                .body("response.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Execution"))
                .body("response.name", matchesPattern(jobName(project, "batch") + "/executions/batch-[a-z0-9]{5}"))
                .body("response.job", equalTo("batch"))
                .body("response.taskCount", equalTo(3))
                .body("response.parallelism", equalTo(3))
                .body("response.succeededCount", equalTo(3))
                .body("response.runningCount", nullValue())
                .body("response.failedCount", nullValue())
                .body("response.startTime", notNullValue())
                .body("response.completionTime", notNullValue())
                .body("response.observedGeneration", equalTo("1"))
                .body("response.conditions.type",
                        equalTo(List.of("Started", "Completed", "ContainerReady", "ResourcesAvailable")))
                .body("response.conditions.state", equalTo(List.of("CONDITION_SUCCEEDED", "CONDITION_SUCCEEDED",
                        "CONDITION_SUCCEEDED", "CONDITION_SUCCEEDED")))
                .body("response.conditions[1].message", matchesPattern("Execution completed successfully in .*s\\."))
                .body("response.conditions[3].message", equalTo("Provisioned imported containers."))
                .body("response.template.maxRetries", equalTo(3));
        String executionName = run.path("response.name");
        String executionId = CloudRunRuntimeService.lastSegment(executionName);

        api()
                .when().get("/v2/" + run.path("name"))
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.name", equalTo(executionName));

        api()
                .when().get("/v2/" + executionName + "/tasks")
                .then()
                .statusCode(200)
                .body("tasks", hasSize(3))
                .body("tasks.name", equalTo(List.of(
                        executionName + "/tasks/" + executionId + "-task0",
                        executionName + "/tasks/" + executionId + "-task1",
                        executionName + "/tasks/" + executionId + "-task2")))
                .body("tasks[1].index", equalTo(1))
                .body("tasks[0].job", equalTo("batch"))
                .body("tasks[0].execution", equalTo(executionId))
                .body("tasks[0].lastAttemptResult.status", equalTo(Map.of()))
                .body("tasks[0].lastAttemptResult.exitCode", nullValue())
                .body("tasks[0].conditions.type", equalTo(List.of("Started", "Completed")))
                .body("tasks[0].conditions.state", equalTo(List.of("CONDITION_SUCCEEDED", "CONDITION_SUCCEEDED")))
                .body("tasks[0].scheduledTime", notNullValue())
                .body("tasks[0].startTime", notNullValue())
                .body("tasks[0].completionTime", notNullValue())
                .body("tasks[0].maxRetries", equalTo(3))
                .body("tasks[0].timeout", equalTo("600s"));

        api()
                .when().get("/v2/" + executionName + "/tasks/" + executionId + "-task2")
                .then()
                .statusCode(200)
                .body("index", equalTo(2));

        api()
                .when().get(jobPath(project, "batch"))
                .then()
                .statusCode(200)
                .body("executionCount", equalTo(1))
                .body("latestCreatedExecution.name", equalTo(executionId))
                .body("latestCreatedExecution.completionStatus", equalTo("EXECUTION_SUCCEEDED"))
                .body("latestCreatedExecution.createTime", notNullValue())
                .body("latestCreatedExecution.completionTime", notNullValue());

        api()
                .contentType("application/json")
                .body("{}")
                .when().post("/v2/" + executionName + ":cancel")
                .then()
                .statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message",
                        equalTo("Execution '" + executionId + "' cannot be cancelled because it is not running."));
    }

    @Test
    void overridesReplaceTaskCountTimeoutArgsAndMergeEnv() {
        String project = "jobs-it-overrides";
        createJob(project, "params", """
                {"template":{"taskCount":1,"template":{"containers":[{"image":"busybox","command":["sh","-c"],
                  "args":["echo base"],"env":[{"name":"A","value":"1"},{"name":"B","value":"2"}]}]}}}
                """);

        api()
                .contentType("application/json")
                .body("""
                        {"overrides":{"taskCount":2,"timeout":"42s","containerOverrides":[
                          {"name":"not-a-container","args":["echo override"],"env":[{"name":"B","value":"3"},
                           {"name":"C","value":"4"}]}]}}
                        """)
                .when().post(jobPath(project, "params") + ":run")
                .then()
                .statusCode(200)
                .body("response.taskCount", equalTo(2))
                .body("response.parallelism", equalTo(2))
                .body("response.template.timeout", equalTo("42s"))
                .body("response.template.containers[0].args", equalTo(List.of("echo override")))
                .body("response.template.containers[0].env.name", equalTo(List.of("A", "B", "C")))
                .body("response.template.containers[0].env.value", equalTo(List.of("1", "3", "4")));

        api()
                .contentType("application/json")
                .body("{\"overrides\":{\"containerOverrides\":[{\"clearArgs\":true}]}}")
                .when().post(jobPath(project, "params") + ":run")
                .then()
                .statusCode(200)
                .body("response.taskCount", equalTo(1))
                .body("response.template.containers[0].args", nullValue())
                .body("response.template.containers[0].command", equalTo(List.of("sh", "-c")));

        api()
                .when().get(jobPath(project, "params"))
                .then()
                .statusCode(200)
                .body("executionCount", equalTo(2))
                .body("template.template.containers[0].args", equalTo(List.of("echo base")));

        createJob(project, "sidecars", """
                {"template":{"template":{"containers":[{"name":"main","image":"busybox"},
                  {"name":"side","image":"busybox"}]}}}
                """);
        api()
                .contentType("application/json")
                .body("{\"overrides\":{\"containerOverrides\":[{\"name\":\"other\",\"clearArgs\":true}]}}")
                .when().post(jobPath(project, "sidecars") + ":run")
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void executionAndTaskListsSupportWildcardParentsAndPaging() {
        String project = "jobs-it-wildcards";
        createJob(project, "one", BUSYBOX_JOB);
        createJob(project, "two", """
                {"template":{"taskCount":2,"template":{"containers":[{"image":"busybox"}]}}}
                """);
        String first = runJob(project, "one");
        String second = runJob(project, "two");
        String third = runJob(project, "one");

        api()
                .when().get(jobPath(project, "-") + "/executions")
                .then()
                .statusCode(200)
                .body("executions.name", equalTo(List.of(third, second, first)));

        Response page = api()
                .queryParam("pageSize", 1)
                .when().get(jobPath(project, "one") + "/executions");
        page.then()
                .statusCode(200)
                .body("executions.name", equalTo(List.of(third)))
                .body("nextPageToken", notNullValue());
        api()
                .queryParam("pageSize", 1)
                .queryParam("pageToken", page.path("nextPageToken").toString())
                .when().get(jobPath(project, "one") + "/executions")
                .then()
                .statusCode(200)
                .body("executions.name", equalTo(List.of(first)))
                .body("nextPageToken", nullValue());

        api()
                .when().get(jobPath(project, "two") + "/executions/-/tasks")
                .then()
                .statusCode(200)
                .body("tasks", hasSize(2));
        api()
                .when().get(jobPath(project, "-") + "/executions/-/tasks")
                .then()
                .statusCode(200)
                .body("tasks", hasSize(4));
        api()
                .when().get(jobPath(project, "missing") + "/executions")
                .then()
                .statusCode(404);
    }

    @Test
    void startAndRunExecutionTokensStartOneNamedExecutionPerToken() {
        String project = "jobs-it-tokens";
        api()
                .contentType("application/json")
                .queryParam("jobId", "start")
                .body("""
                        {"startExecutionToken":"first",
                         "template":{"template":{"containers":[{"image":"busybox"}]}}}
                        """)
                .when().post(jobsPath(project))
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.executionCount", equalTo(1))
                .body("response.latestCreatedExecution.name", equalTo("start-first"))
                .body("response.startExecutionToken", equalTo("first"));
        api()
                .when().get(jobPath(project, "start") + "/executions/start-first")
                .then()
                .statusCode(200)
                .body("completionTime", notNullValue());

        api()
                .contentType("application/json")
                .body("""
                        {"startExecutionToken":"first",
                         "template":{"template":{"containers":[{"image":"busybox"}]}}}
                        """)
                .when().patch(jobPath(project, "start"))
                .then()
                .statusCode(200)
                .body("response.generation", equalTo("1"))
                .body("response.executionCount", equalTo(1));

        api()
                .contentType("application/json")
                .body("""
                        {"startExecutionToken":"second",
                         "template":{"template":{"containers":[{"image":"busybox"}]}}}
                        """)
                .when().patch(jobPath(project, "start"))
                .then()
                .statusCode(200)
                .body("response.generation", equalTo("2"))
                .body("response.executionCount", equalTo(2))
                .body("response.latestCreatedExecution.name", equalTo("start-second"));

        api()
                .contentType("application/json")
                .queryParam("jobId", "runner")
                .body("""
                        {"runExecutionToken":"once",
                         "template":{"template":{"containers":[{"image":"busybox"}]}}}
                        """)
                .when().post(jobsPath(project))
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Job"))
                .body("response.latestCreatedExecution.name", equalTo("runner-once"))
                .body("response.latestCreatedExecution.completionStatus", equalTo("EXECUTION_SUCCEEDED"))
                .body("response.runExecutionToken", equalTo("once"));
    }

    @Test
    void deletingExecutionsAndJobsIsImmediate() {
        String project = "jobs-it-delete";
        createJob(project, "gone", """
                {"template":{"taskCount":2,"template":{"containers":[{"image":"busybox"}]}}}
                """);
        String kept = runJob(project, "gone");
        String removed = runJob(project, "gone");

        given()
                .queryParam("etag", "\"bogus\"")
                .when().delete("/v2/" + removed)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Execution"))
                .body("response.name", equalTo(removed))
                .body("response.generation", equalTo("2"))
                .body("response.deleteTime", notNullValue())
                .body("response.expireTime", notNullValue());
        api().when().get("/v2/" + removed).then().statusCode(404);
        api().when().get("/v2/" + removed + "/tasks").then().statusCode(404);
        api()
                .when().get(jobPath(project, "gone"))
                .then()
                .statusCode(200)
                .body("latestCreatedExecution.name", equalTo(CloudRunRuntimeService.lastSegment(removed)))
                .body("latestCreatedExecution.deleteTime", notNullValue());

        given()
                .queryParam("etag", "\"bogus\"")
                .when().delete(jobPath(project, "gone"))
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.name", equalTo(jobName(project, "gone")))
                .body("response.generation", equalTo("2"))
                .body("response.deleteTime", notNullValue())
                .body("response.expireTime", notNullValue());
        api().when().get(jobPath(project, "gone")).then().statusCode(404);
        api().when().get("/v2/" + kept).then().statusCode(404);
        api()
                .when().get(jobPath(project, "-") + "/executions/-/tasks")
                .then()
                .statusCode(200)
                .body("tasks", nullValue());
        api().when().delete(jobPath(project, "gone")).then().statusCode(404);
    }

    @Test
    void iamPolicyRoundTripsOnJobs() {
        String project = "jobs-it-iam";
        createJob(project, "secured", BUSYBOX_JOB);
        String resource = jobPath(project, "secured");

        // Same codec as Cloud Run services: the stored "ACAB" etag string is sent as its UTF-8 bytes.
        String initialEtag = api()
                .when().get(resource + ":getIamPolicy")
                .then()
                .statusCode(200)
                .body("etag", notNullValue())
                .body("bindings", nullValue())
                .extract().path("etag");

        String etag = api()
                .contentType("application/json")
                .body("{\"policy\":{\"bindings\":[{\"role\":\"roles/run.invoker\",\"members\":[\"allUsers\"]}]}}")
                .when().post(resource + ":setIamPolicy")
                .then()
                .statusCode(200)
                .body("bindings[0].role", equalTo("roles/run.invoker"))
                .extract().path("etag");
        assertNotEquals(initialEtag, etag);

        api()
                .when().get(resource + ":getIamPolicy")
                .then()
                .statusCode(200)
                .body("bindings[0].members", hasItem("allUsers"));

        api()
                .contentType("application/json")
                .body("{\"permissions\":[\"run.jobs.get\",\"run.jobs.run\"]}")
                .when().post(resource + ":testIamPermissions")
                .then()
                .statusCode(200)
                .body("permissions", equalTo(List.of("run.jobs.get", "run.jobs.run")));

        api().when().get(jobPath(project, "absent") + ":getIamPolicy").then().statusCode(404);
    }

    @Test
    void unknownExecutionAndTaskAreNotFound() {
        String project = "jobs-it-missing";
        createJob(project, "exists", BUSYBOX_JOB);
        api().when().get(jobPath(project, "exists") + "/executions/nope").then().statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
        api().contentType("application/json").body("{}")
                .when().post(jobPath(project, "exists") + "/executions/nope:cancel").then().statusCode(404);
        api().when().delete(jobPath(project, "exists") + "/executions/nope").then().statusCode(404);
        api().when().get(jobPath(project, "exists") + "/executions/nope/tasks/nope-task0").then().statusCode(404);
        api().contentType("application/json").body("{}")
                .when().post(jobPath(project, "absent") + ":run").then().statusCode(404);
    }

    @Test
    void executionIdsAreUniquePerRun() {
        String project = "jobs-it-names";
        createJob(project, "names", BUSYBOX_JOB);
        String first = runJob(project, "names");
        String second = runJob(project, "names");
        assertNotEquals(first, second);
        assertTrue(CloudRunRuntimeService.lastSegment(first).matches("names-[a-z0-9]{5}"));
        assertEquals(2, (int) api().when().get(jobPath(project, "names")).path("executionCount"));
        api()
                .when().get(jobPath(project, "names") + "/executions")
                .then()
                .body("executions.name", hasItem(containsString("/executions/names-")));
    }

    /** Keeps the {@code :verb} suffix of custom-method paths unencoded, as GCP clients send it. */
    private static RequestSpecification api() {
        return given().urlEncodingEnabled(false);
    }

    private static void createJob(String project, String jobId, String body) {
        api()
                .contentType("application/json")
                .queryParam("jobId", jobId)
                .body(body)
                .when().post(jobsPath(project))
                .then()
                .statusCode(200)
                .body("done", equalTo(true));
    }

    private static String runJob(String project, String jobId) {
        return api()
                .contentType("application/json")
                .body("{}")
                .when().post(jobPath(project, jobId) + ":run")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .extract().path("response.name");
    }

    private static String jobsPath(String project) {
        return "/v2/projects/" + project + "/locations/" + LOCATION + "/jobs";
    }

    private static String jobPath(String project, String jobId) {
        return jobsPath(project) + "/" + jobId;
    }

    private static String jobName(String project, String jobId) {
        return "projects/" + project + "/locations/" + LOCATION + "/jobs/" + jobId;
    }
}

package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Container;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Leaves state for {@link CloudRunJobsRestartPhase2RestIntegrationTest}, which starts the emulator on the same
 * persistent storage: a job execution still running when the emulator stops, an execution deleted while its task
 * container is still stopping, and a job task container the emulator does not track (as after a crash).
 */
@QuarkusTest
@TestProfile(CloudRunJobsRestartProfiles.Phase1BeforeRestart.class)
class CloudRunJobsRestartPhase1RestIntegrationTest {

    private static final String PARENT = "/v2/projects/jobs-restart/locations/us-central1/jobs";

    @Inject
    CloudRunRuntimeService runtimeService;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(CloudRunJobsExecutionRestIntegrationTest.dockerAvailable(),
                "Docker daemon is required for Cloud Run jobs restart tests");
    }

    @Test
    void leavesRunningDeletedAndOrphanedWorkAcrossTheRestart() throws IOException {
        Response interrupted = runSleepingJob("interrupted", 2);
        String executionName = interrupted.path("metadata.name");
        String operationName = interrupted.path("name");
        awaitRunning(executionName);

        Response deletedRun = runSleepingJob("deleted-running", 1);
        String deletedExecution = deletedRun.path("metadata.name");
        awaitRunning(deletedExecution);
        Response delete = given().when().delete("/v2/" + deletedExecution);
        delete.then().statusCode(200).body("done", not(equalTo(true)));
        given().when().get("/v2/" + deletedExecution).then().statusCode(404);

        String orphan = startOrphanContainer();

        Files.writeString(CloudRunJobsRestartProfiles.MARKER, String.join("\n", List.of(executionName,
                operationName, deletedRun.path("name"), delete.path("name"), orphan)), StandardCharsets.UTF_8);
    }

    private static Response runSleepingJob(String jobId, int taskCount) {
        given()
                .contentType("application/json")
                .queryParam("jobId", jobId)
                .body("""
                        {"template":{"taskCount":%d,"parallelism":1,"template":{"containers":[{"image":"busybox",
                          "command":["sh","-c"],"args":["sleep 300"]}]}}}
                        """.formatted(taskCount))
                .when().post(PARENT)
                .then()
                .statusCode(200)
                .body("done", equalTo(true));
        Response run = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post(PARENT + "/" + jobId + ":run");
        run.then().statusCode(200);
        return run;
    }

    private static void awaitRunning(String executionName) {
        Response last = null;
        for (int i = 0; i < 300; i++) {
            last = given().when().get("/v2/" + executionName);
            if (Integer.valueOf(1).equals(last.path("runningCount"))) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the task to start", e);
            }
        }
        throw new AssertionError("Task never started: " + (last == null ? "none" : last.asString()));
    }

    /** A job task container created outside the emulator's bookkeeping, as one left behind by a killed process. */
    private String startOrphanContainer() {
        String task = "projects/jobs-restart/locations/us-central1/jobs/orphan/executions/orphan-abcde/tasks/"
                + "orphan-abcde-task0";
        Container container = Container.newBuilder()
                .setImage("busybox")
                .addCommand("sh")
                .addCommand("-c")
                .addArgs("sleep 300")
                .build();
        ContainerSpec spec = runtimeService.buildWorkloadSpec("jobs-restart", "us-central1", task,
                runtimeService.workloadContainerName("orphan-abcde-task0", "attempt0", "leak"), container, Map.of(),
                null, List.of());
        String containerId = lifecycleManager.createAndStart(spec).containerId();
        assertEquals(true, lifecycleManager.isContainerRunning(containerId));
        return containerId;
    }
}

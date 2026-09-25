package io.floci.gcp.services.cloudrun;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Starts the emulator on the persistent storage left by {@link CloudRunJobsRestartPhase1RestIntegrationTest} and
 * checks that the interrupted execution, its tasks and its run operation are failed with the restart message.
 */
@QuarkusTest
@TestProfile(CloudRunJobsRestartProfiles.Phase2AfterRestart.class)
class CloudRunJobsRestartPhase2RestIntegrationTest {

    @BeforeAll
    static void requirePhaseOne() {
        Assumptions.assumeTrue(Files.exists(CloudRunJobsRestartProfiles.MARKER),
                "Runs after CloudRunJobsRestartPhase1RestIntegrationTest in the same test JVM");
    }

    @Test
    void startupReconciliationFailsInterruptedExecutions() throws IOException {
        List<String> marker = Files.readAllLines(CloudRunJobsRestartProfiles.MARKER, StandardCharsets.UTF_8);
        String executionName = marker.get(0);
        String operationName = marker.get(1);

        Response execution = null;
        for (int i = 0; i < 100; i++) {
            execution = given().when().get("/v2/" + executionName);
            if (execution.statusCode() == 200 && execution.path("completionTime") != null) {
                break;
            }
            sleep();
        }
        execution.then()
                .statusCode(200)
                .body("failedCount", equalTo(2))
                .body("runningCount", equalTo(null))
                .body("reconciling", equalTo(null))
                .body("conditions[1].type", equalTo("Completed"))
                .body("conditions[1].state", equalTo("CONDITION_FAILED"))
                .body("conditions[1].message", equalTo("The emulator restarted before the execution completed."));

        given()
                .when().get("/v2/" + executionName + "/tasks")
                .then()
                .statusCode(200)
                .body("tasks.lastAttemptResult.status.code", equalTo(List.of(10, 10)))
                .body("tasks.lastAttemptResult.status.message", equalTo(List.of(
                        "The emulator restarted before the task completed.",
                        "The emulator restarted before the task completed.")));

        given()
                .when().get("/v2/" + operationName)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("error.code", equalTo(10))
                .body("error.message", equalTo("The emulator restarted before the execution completed."));

        given()
                .when().get("/v2/projects/jobs-restart/locations/us-central1/jobs/interrupted")
                .then()
                .statusCode(200)
                .body("latestCreatedExecution.completionStatus", equalTo("EXECUTION_FAILED"));
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for reconciliation", e);
        }
    }
}

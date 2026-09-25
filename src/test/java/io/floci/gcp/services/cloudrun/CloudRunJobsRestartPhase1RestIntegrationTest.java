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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Leaves a job execution running when the emulator stops. {@link CloudRunJobsRestartPhase2RestIntegrationTest}
 * then starts the emulator on the same persistent storage and checks startup reconciliation.
 */
@QuarkusTest
@TestProfile(CloudRunJobsRestartProfiles.Phase1BeforeRestart.class)
class CloudRunJobsRestartPhase1RestIntegrationTest {

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(CloudRunJobsExecutionRestIntegrationTest.dockerAvailable(),
                "Docker daemon is required for Cloud Run jobs restart tests");
    }

    @Test
    void leavesAnExecutionRunningAcrossTheRestart() throws IOException {
        String jobPath = "/v2/projects/jobs-restart/locations/us-central1/jobs/interrupted";
        given()
                .contentType("application/json")
                .queryParam("jobId", "interrupted")
                .body("""
                        {"template":{"taskCount":2,"parallelism":1,"template":{"containers":[{"image":"busybox",
                          "command":["sh","-c"],"args":["sleep 300"]}]}}}
                        """)
                .when().post("/v2/projects/jobs-restart/locations/us-central1/jobs")
                .then()
                .statusCode(200)
                .body("done", equalTo(true));

        Response run = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post(jobPath + ":run");
        run.then().statusCode(200);
        String executionName = run.path("metadata.name");
        String operationName = run.path("name");

        Response last = null;
        for (int i = 0; i < 300; i++) {
            last = given().when().get("/v2/" + executionName);
            if (Integer.valueOf(1).equals(last.path("runningCount"))) {
                Files.writeString(CloudRunJobsRestartProfiles.MARKER, executionName + "\n" + operationName,
                        StandardCharsets.UTF_8);
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
}

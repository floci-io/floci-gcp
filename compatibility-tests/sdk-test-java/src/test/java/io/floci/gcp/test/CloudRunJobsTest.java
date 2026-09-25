package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.run.v2.CancelExecutionRequest;
import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.CreateJobRequest;
import com.google.cloud.run.v2.DeleteJobRequest;
import com.google.cloud.run.v2.Execution;
import com.google.cloud.run.v2.ExecutionReference;
import com.google.cloud.run.v2.ExecutionTemplate;
import com.google.cloud.run.v2.ExecutionsClient;
import com.google.cloud.run.v2.ExecutionsSettings;
import com.google.cloud.run.v2.GetJobRequest;
import com.google.cloud.run.v2.Job;
import com.google.cloud.run.v2.JobsClient;
import com.google.cloud.run.v2.JobsSettings;
import com.google.cloud.run.v2.ListExecutionsRequest;
import com.google.cloud.run.v2.ListJobsRequest;
import com.google.cloud.run.v2.ListTasksRequest;
import com.google.cloud.run.v2.RunJobRequest;
import com.google.cloud.run.v2.Task;
import com.google.cloud.run.v2.TaskTemplate;
import com.google.cloud.run.v2.TasksClient;
import com.google.cloud.run.v2.TasksSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudRunJobsTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String LOCATION = "us-central1";
    private static final String PARENT = "projects/" + PROJECT_ID + "/locations/" + LOCATION;
    private static final String JOB_ID = TestFixtures.uniqueName("run-job");
    private static final String JOB_NAME = PARENT + "/jobs/" + JOB_ID;
    private static final boolean EXECUTION_ENABLED = Boolean.parseBoolean(
            System.getenv().getOrDefault("FLOCI_GCP_CLOUDRUN_EXECUTION_ENABLED", "false"));

    private static JobsClient jobsClient;
    private static ExecutionsClient executionsClient;
    private static TasksClient tasksClient;
    private static String executionName;

    @BeforeAll
    static void setUp() throws IOException {
        jobsClient = JobsClient.create(JobsSettings.newHttpJsonBuilder()
                .setEndpoint(TestFixtures.endpoint())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build());
        executionsClient = ExecutionsClient.create(ExecutionsSettings.newHttpJsonBuilder()
                .setEndpoint(TestFixtures.endpoint())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build());
        tasksClient = TasksClient.create(TasksSettings.newHttpJsonBuilder()
                .setEndpoint(TestFixtures.endpoint())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build());
    }

    @AfterAll
    static void tearDown() {
        if (jobsClient != null) {
            jobsClient.close();
        }
        if (executionsClient != null) {
            executionsClient.close();
        }
        if (tasksClient != null) {
            tasksClient.close();
        }
    }

    @Test
    @Order(1)
    void createJobCompletesDefaults() throws Exception {
        Job job = Job.newBuilder()
                .setTemplate(ExecutionTemplate.newBuilder()
                        .setTaskCount(2)
                        .setTemplate(TaskTemplate.newBuilder()
                                .setMaxRetries(0)
                                .addContainers(Container.newBuilder()
                                        .setImage("busybox")
                                        .addCommand("sh")
                                        .addCommand("-c")
                                        .addArgs("echo task $CLOUD_RUN_TASK_INDEX of $CLOUD_RUN_TASK_COUNT"))))
                .build();

        Job created = jobsClient.createJobAsync(CreateJobRequest.newBuilder()
                        .setParent(PARENT)
                        .setJobId(JOB_ID)
                        .setJob(job)
                        .build())
                .get(60, TimeUnit.SECONDS);

        assertThat(created.getName()).isEqualTo(JOB_NAME);
        assertThat(created.getGeneration()).isEqualTo(1);
        assertThat(created.getTerminalCondition().getType()).isEqualTo("Ready");
        assertThat(created.getTemplate().getTaskCount()).isEqualTo(2);
        assertThat(created.getTemplate().getTemplate().getTimeout().getSeconds()).isEqualTo(600);
        assertThat(created.getTemplate().getTemplate().getContainers(0).getResources().getLimitsMap())
                .containsEntry("cpu", "1000m")
                .containsEntry("memory", "512Mi");

        List<Job> jobs = new ArrayList<>();
        jobsClient.listJobs(ListJobsRequest.newBuilder().setParent(PARENT).build()).iterateAll().forEach(jobs::add);
        assertThat(jobs).anyMatch(listed -> listed.getName().equals(JOB_NAME));
    }

    @Test
    @Order(2)
    void runJobWaitsForTheExecutionToFinish() throws Exception {
        OperationFuture<Execution, Execution> run = jobsClient.runJobAsync(RunJobRequest.newBuilder()
                .setName(JOB_NAME)
                .build());
        Execution execution = run.get(180, TimeUnit.SECONDS);

        executionName = execution.getName();
        assertThat(executionName).matches(JOB_NAME + "/executions/" + JOB_ID + "-[a-z0-9]{5}");
        assertThat(execution.getSucceededCount()).isEqualTo(2);
        assertThat(execution.hasCompletionTime()).isTrue();
        assertThat(completed(execution).getState()).isEqualTo(Condition.State.CONDITION_SUCCEEDED);

        Job job = jobsClient.getJob(GetJobRequest.newBuilder().setName(JOB_NAME).build());
        assertThat(job.getExecutionCount()).isEqualTo(1);
        assertThat(job.getLatestCreatedExecution().getCompletionStatus())
                .isEqualTo(ExecutionReference.CompletionStatus.EXECUTION_SUCCEEDED);
    }

    @Test
    @Order(3)
    void listExecutionsAndTasks() {
        List<Execution> executions = new ArrayList<>();
        executionsClient.listExecutions(ListExecutionsRequest.newBuilder().setParent(JOB_NAME).build())
                .iterateAll()
                .forEach(executions::add);
        assertThat(executions).extracting(Execution::getName).contains(executionName);

        List<Task> tasks = new ArrayList<>();
        tasksClient.listTasks(ListTasksRequest.newBuilder().setParent(executionName).build())
                .iterateAll()
                .forEach(tasks::add);
        assertThat(tasks).extracting(Task::getIndex).containsExactly(0, 1);
        assertThat(tasks.get(1).getName()).endsWith("/tasks/" + executionName.substring(
                executionName.lastIndexOf('/') + 1) + "-task1");
        assertThat(tasks).allSatisfy(task -> assertThat(task.getLastAttemptResult().hasStatus()).isTrue());
    }

    @Test
    @Order(4)
    void failedRunSurfacesTheTaskError() throws Exception {
        if (!EXECUTION_ENABLED) {
            return;
        }
        OperationFuture<Execution, Execution> run = jobsClient.runJobAsync(RunJobRequest.newBuilder()
                .setName(JOB_NAME)
                .setOverrides(RunJobRequest.Overrides.newBuilder()
                        .setTaskCount(1)
                        .addContainerOverrides(RunJobRequest.Overrides.ContainerOverride.newBuilder()
                                .addArgs("exit 3")))
                .build());

        assertThatThrownBy(() -> run.get(180, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("failed with exit code: 3 and message: The container exited with an error.");
    }

    @Test
    @Order(5)
    void cancelExecution() throws Exception {
        if (!EXECUTION_ENABLED) {
            // HTTP/JSON clients derive the status from the HTTP code, so FAILED_PRECONDITION (400) surfaces as
            // INVALID_ARGUMENT, as it does against GCP.
            assertThatThrownBy(() -> executionsClient.cancelExecutionAsync(CancelExecutionRequest.newBuilder()
                            .setName(executionName)
                            .build())
                    .get(60, TimeUnit.SECONDS))
                    .hasMessageContaining("cannot be cancelled because it is not running.")
                    .satisfies(error -> assertThat(statusCode(error))
                            .isEqualTo(StatusCode.Code.INVALID_ARGUMENT));
            return;
        }

        OperationFuture<Execution, Execution> run = jobsClient.runJobAsync(RunJobRequest.newBuilder()
                .setName(JOB_NAME)
                .setOverrides(RunJobRequest.Overrides.newBuilder()
                        .setTaskCount(1)
                        .addContainerOverrides(RunJobRequest.Overrides.ContainerOverride.newBuilder()
                                .addArgs("sleep 120")))
                .build());
        String running = run.getMetadata().get(60, TimeUnit.SECONDS).getName();

        Execution cancelled = executionsClient.cancelExecutionAsync(CancelExecutionRequest.newBuilder()
                        .setName(running)
                        .build())
                .get(120, TimeUnit.SECONDS);
        assertThat(cancelled.getCancelledCount()).isEqualTo(1);
        assertThat(completed(cancelled).getMessage()).isEqualTo("Cancelled by user.");

        Execution runResult = run.get(120, TimeUnit.SECONDS);
        assertThat(runResult.getName()).isEqualTo(running);
        assertThat(completed(runResult).getExecutionReason()).isEqualTo(Condition.ExecutionReason.CANCELLED);
    }

    @Test
    @Order(6)
    void deleteJob() throws Exception {
        Job deleted = jobsClient.deleteJobAsync(DeleteJobRequest.newBuilder().setName(JOB_NAME).build())
                .get(60, TimeUnit.SECONDS);
        assertThat(deleted.hasDeleteTime()).isTrue();

        assertThatThrownBy(() -> jobsClient.getJob(GetJobRequest.newBuilder().setName(JOB_NAME).build()))
                .satisfies(error -> assertThat(statusCode(error)).isEqualTo(StatusCode.Code.NOT_FOUND));
    }

    private static Condition completed(Execution execution) {
        return execution.getConditionsList().stream()
                .filter(condition -> condition.getType().equals("Completed"))
                .findFirst()
                .orElseThrow();
    }

    private static StatusCode.Code statusCode(Throwable error) {
        Throwable current = error;
        while (current != null && !(current instanceof ApiException)) {
            current = current.getCause();
        }
        assertThat(current).isInstanceOf(ApiException.class);
        return ((ApiException) current).getStatusCode().getCode();
    }
}

package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.EnvVar;
import com.google.cloud.run.v2.Execution;
import com.google.cloud.run.v2.ExecutionReference;
import com.google.cloud.run.v2.ExecutionTemplate;
import com.google.cloud.run.v2.RunJobRequest;
import com.google.cloud.run.v2.Task;
import com.google.cloud.run.v2.TaskTemplate;
import com.google.longrunning.Operation;
import com.google.protobuf.Duration;
import com.google.rpc.Status;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.cloudrun.CloudRunExecutionCoordinator.Events;
import io.floci.gcp.services.cloudrun.CloudRunExecutionCoordinator.TaskHandle;
import io.floci.gcp.services.cloudrun.CloudRunExecutionCoordinator.TaskOutcome;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// java.time.Duration is written fully qualified: it collides with the imported com.google.protobuf.Duration.
class CloudRunJobsServiceTest {

    private static final String JOB = "projects/p/locations/us-central1/jobs/job";

    // ── Naming ──────────────────────────────────────────────────────────────

    @Test
    void executionIdsAreJobIdPlusFiveLowercaseAlphanumerics() {
        Random random = new Random(42);
        for (int i = 0; i < 200; i++) {
            assertTrue(CloudRunJobTemplates.randomExecutionId("my-job", random).matches("my-job-[a-z0-9]{5}"));
        }
        assertEquals("my-job-first", CloudRunJobTemplates.tokenExecutionId("my-job", "first"));
        assertEquals("my-job-abcde-task3", CloudRunJobTemplates.taskId("my-job-abcde", 3));
    }

    @Test
    void conditionDurationsAreTruncatedToHundredths() {
        assertEquals("9.84s", CloudRunJobTemplates.formatSeconds(java.time.Duration.ofMillis(9847)));
        assertEquals("15.5s", CloudRunJobTemplates.formatSeconds(java.time.Duration.ofMillis(15502)));
        assertEquals("10s", CloudRunJobTemplates.formatSeconds(java.time.Duration.ofMillis(10004)));
        assertEquals("0s", CloudRunJobTemplates.formatSeconds(java.time.Duration.ofMillis(3)));
    }

    @Test
    void defaultsMatchGcpJobCompletion() {
        ExecutionTemplate completed = CloudRunJobTemplates.withDefaults(ExecutionTemplate.newBuilder()
                .setTemplate(TaskTemplate.newBuilder()
                        .addContainers(Container.newBuilder().setImage("busybox")))
                .build());
        assertEquals(1, completed.getTaskCount());
        assertEquals(0, completed.getParallelism());
        assertEquals(1, CloudRunJobTemplates.effectiveParallelism(completed));
        assertEquals(3, completed.getTemplate().getMaxRetries());
        assertEquals(600, completed.getTemplate().getTimeout().getSeconds());
        assertEquals("1000m", completed.getTemplate().getContainers(0).getResources().getLimitsOrThrow("cpu"));
        assertEquals("512Mi", completed.getTemplate().getContainers(0).getResources().getLimitsOrThrow("memory"));
        assertEquals("", completed.getTemplate().getContainers(0).getName());

        ExecutionTemplate explicit = CloudRunJobTemplates.withDefaults(ExecutionTemplate.newBuilder()
                .setTaskCount(3)
                .setParallelism(5)
                .setTemplate(TaskTemplate.newBuilder().setMaxRetries(0))
                .build());
        assertEquals(0, explicit.getTemplate().getMaxRetries());
        assertTrue(explicit.getTemplate().hasMaxRetries());
        assertEquals(5, CloudRunJobTemplates.effectiveParallelism(explicit));
    }

    // ── Overrides ───────────────────────────────────────────────────────────

    @Test
    void containerOverrideMatchesByNameReplacesArgsAndMergesEnv() {
        ExecutionTemplate template = template(
                container("main", List.of("a"), Map.of("A", "1", "B", "2")),
                container("side", List.of("s"), Map.of()));
        ExecutionTemplate overridden = CloudRunJobTemplates.applyOverrides(template,
                RunJobRequest.Overrides.newBuilder()
                        .setTaskCount(7)
                        .setTimeout(Duration.newBuilder().setSeconds(9))
                        .addContainerOverrides(RunJobRequest.Overrides.ContainerOverride.newBuilder()
                                .setName("side")
                                .addArgs("x")
                                .addArgs("y")
                                .addEnv(EnvVar.newBuilder().setName("C").setValue("3")))
                        .build());
        assertEquals(7, overridden.getTaskCount());
        assertEquals(9, overridden.getTemplate().getTimeout().getSeconds());
        assertEquals(List.of("a"), overridden.getTemplate().getContainers(0).getArgsList());
        assertEquals(List.of("x", "y"), overridden.getTemplate().getContainers(1).getArgsList());
        assertEquals("C", overridden.getTemplate().getContainers(1).getEnv(0).getName());

        ExecutionTemplate merged = CloudRunJobTemplates.applyOverrides(template, RunJobRequest.Overrides.newBuilder()
                .addContainerOverrides(RunJobRequest.Overrides.ContainerOverride.newBuilder()
                        .setName("main")
                        .addEnv(EnvVar.newBuilder().setName("B").setValue("20"))
                        .addEnv(EnvVar.newBuilder().setName("Z").setValue("26")))
                .build());
        List<EnvVar> env = merged.getTemplate().getContainers(0).getEnvList();
        assertEquals(List.of("A", "B", "Z"), env.stream().map(EnvVar::getName).toList());
        assertEquals(List.of("1", "20", "26"), env.stream().map(EnvVar::getValue).toList());
        assertEquals(template.getTaskCount(), merged.getTaskCount());
    }

    @Test
    void unmatchedOverrideAppliesToTheOnlyContainerAndClearArgsRemovesArgs() {
        ExecutionTemplate single = template(container("", List.of("a", "b"), Map.of()));
        ExecutionTemplate cleared = CloudRunJobTemplates.applyOverrides(single, RunJobRequest.Overrides.newBuilder()
                .addContainerOverrides(RunJobRequest.Overrides.ContainerOverride.newBuilder()
                        .setName("not-a-container")
                        .setClearArgs(true))
                .build());
        assertEquals(0, cleared.getTemplate().getContainers(0).getArgsCount());

        ExecutionTemplate unnamed = CloudRunJobTemplates.applyOverrides(single, RunJobRequest.Overrides.newBuilder()
                .addContainerOverrides(RunJobRequest.Overrides.ContainerOverride.newBuilder().addArgs("z"))
                .build());
        assertEquals(List.of("z"), unnamed.getTemplate().getContainers(0).getArgsList());
    }

    @Test
    void unmatchedOverrideWithSeveralContainersIsInvalid() {
        ExecutionTemplate template = template(container("main", List.of(), Map.of()),
                container("side", List.of(), Map.of()));
        GcpException error = assertThrows(GcpException.class, () -> CloudRunJobTemplates.applyOverrides(template,
                RunJobRequest.Overrides.newBuilder()
                        .addContainerOverrides(RunJobRequest.Overrides.ContainerOverride.newBuilder()
                                .setName("other")
                                .setClearArgs(true))
                        .build()));
        assertEquals(400, error.getHttpStatus());
    }

    // ── Coordinator ─────────────────────────────────────────────────────────

    @Test
    void coordinatorRespectsParallelismAndSucceedsAfterEveryTask() throws Exception {
        Harness harness = new Harness(execution(3, 2, 0), null);
        harness.coordinator.begin();
        harness.coordinator.start();

        harness.await(() -> harness.runner.launches.size() == 2);
        assertEquals(List.of(0, 1), harness.runner.launchedIndexes());
        harness.await(() -> harness.sink.lastExecution().getRunningCount() == 2);

        harness.runner.finish(1, 0, new TaskOutcome.Exited(0));
        harness.await(() -> harness.runner.launches.size() == 3);
        assertEquals(2, harness.runner.launches.get(2).index);
        harness.runner.finish(0, 0, new TaskOutcome.Exited(0));
        harness.runner.finish(2, 0, new TaskOutcome.Exited(0));

        Execution result = harness.coordinator.finished().get(5, TimeUnit.SECONDS);
        assertEquals(3, result.getSucceededCount());
        assertEquals(0, result.getRunningCount());
        assertTrue(result.hasCompletionTime());
        assertFalse(result.getReconciling());
        assertEquals(Condition.State.CONDITION_SUCCEEDED, completed(result).getState());
        assertTrue(completed(result).getMessage().startsWith("Execution completed successfully in "));
        assertEquals(List.of("Started", "Completed", "ContainerReady", "ResourcesAvailable"),
                result.getConditionsList().stream().map(Condition::getType).toList());
        assertNull(harness.sink.finishedError.get(5, TimeUnit.SECONDS).orElse(null));
        harness.coordinator.closed().get(5, TimeUnit.SECONDS);
        assertTrue(harness.coordinator.cancel().isEmpty());
    }

    @Test
    void coordinatorRetriesNonZeroExitAndFailsOnlyAfterEveryTaskIsTerminal() throws Exception {
        Harness harness = new Harness(execution(2, 2, 1), null);
        harness.coordinator.begin();
        harness.coordinator.start();
        harness.await(() -> harness.runner.launches.size() == 2);

        harness.runner.finish(1, 0, new TaskOutcome.Exited(3));
        harness.await(() -> harness.runner.launches.size() == 3);
        assertEquals(1, harness.runner.launches.get(2).index);
        assertEquals(1, harness.runner.launches.get(2).attempt);
        harness.runner.finish(1, 1, new TaskOutcome.Exited(3));
        harness.await(() -> harness.sink.tasks.get(1).getConditions(1).getState()
                == Condition.State.CONDITION_FAILED);
        assertFalse(harness.coordinator.finished().isDone(), "task 0 is still running");

        harness.runner.finish(0, 0, new TaskOutcome.Exited(0));
        Execution result = harness.coordinator.finished().get(5, TimeUnit.SECONDS);
        assertEquals(1, result.getSucceededCount());
        assertEquals(1, result.getFailedCount());
        assertEquals(1, result.getRetriedCount());
        assertEquals(Condition.ExecutionReason.NON_ZERO_EXIT_CODE, completed(result).getExecutionReason());
        Status error = harness.sink.finishedError.get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(10, error.getCode());
        assertEquals("Task job-abcde-task1 failed with exit code: 3 and message: The container exited with an error.",
                error.getMessage());
        Task failed = harness.sink.tasks.get(1);
        assertEquals(1, failed.getRetried());
        assertEquals(3, failed.getLastAttemptResult().getExitCode());
        assertEquals(10, failed.getLastAttemptResult().getStatus().getCode());
    }

    @Test
    void coordinatorReportsTimeoutAsCode4WithoutExitCode() throws Exception {
        Harness harness = new Harness(execution(1, 1, 0), null);
        harness.coordinator.begin();
        harness.coordinator.start();
        harness.await(() -> harness.runner.launches.size() == 1);
        harness.runner.finish(0, 0, new TaskOutcome.TimedOut());

        harness.coordinator.finished().get(5, TimeUnit.SECONDS);
        Status error = harness.sink.finishedError.get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(4, error.getCode());
        assertEquals("Task job-abcde-task0 failed with exit code: 0 and message: The configured timeout was reached.",
                error.getMessage());
        Task task = harness.sink.tasks.get(0);
        assertEquals(0, task.getLastAttemptResult().getExitCode());
        assertEquals("The configured timeout was reached.", task.getLastAttemptResult().getStatus().getMessage());
    }

    @Test
    void cancelStopsRunningTasksSkipsPendingOnesAndCompletesWithExecution() throws Exception {
        Harness harness = new Harness(execution(3, 1, 3), null);
        harness.coordinator.begin();
        harness.coordinator.start();
        harness.await(() -> harness.runner.launches.size() == 1);

        String operation = harness.coordinator.cancel().orElseThrow().get(5, TimeUnit.SECONDS);
        assertTrue(harness.runner.launches.get(0).stopped);
        assertFalse(harness.coordinator.finished().isDone(), "waits for the stopped container to exit");
        harness.runner.finish(0, 0, new TaskOutcome.Stopped());

        Execution result = harness.coordinator.finished().get(5, TimeUnit.SECONDS);
        assertEquals(1, harness.runner.launches.size(), "pending tasks are never started");
        assertEquals(3, result.getCancelledCount());
        assertEquals(2, result.getGeneration());
        assertEquals("Cancelled by user.", completed(result).getMessage());
        assertEquals(Condition.ExecutionReason.CANCELLED, completed(result).getExecutionReason());
        assertTrue(harness.sink.finishedError.get(5, TimeUnit.SECONDS).isEmpty());
        assertEquals(result, harness.sink.completedOperations.get(operation));
        assertEquals(1, harness.sink.tasks.get(2).getLastAttemptResult().getStatus().getCode());

        harness.coordinator.closed().get(5, TimeUnit.SECONDS);
        CloudRunExecutionCoordinator terminal = harness.restore();
        CompletableFuture<String> rejected = terminal.cancel().orElseThrow();
        terminal.start();
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> rejected.get(5, TimeUnit.SECONDS));
        GcpException cause = assertInstanceOf(GcpException.class, error.getCause());
        assertEquals(400, cause.getHttpStatus());
        assertEquals("Execution 'job-abcde' cannot be cancelled because it is not running.", cause.getMessage());
    }

    @Test
    void deleteRemovesRecordsImmediatelyAndCancelsRunningTasks() throws Exception {
        Harness harness = new Harness(execution(1, 1, 0), null);
        harness.coordinator.begin();
        harness.coordinator.start();
        harness.await(() -> harness.runner.launches.size() == 1);
        harness.await(() -> harness.sink.lastExecution().getRunningCount() == 1);
        int savesBeforeDelete = harness.sink.saves.get();

        String operation = harness.coordinator.delete(true).orElseThrow().get(5, TimeUnit.SECONDS);
        assertTrue(harness.sink.deleted);
        assertTrue(harness.runner.launches.get(0).stopped);
        harness.runner.finish(0, 0, new TaskOutcome.Stopped());

        Execution result = harness.coordinator.finished().get(5, TimeUnit.SECONDS);
        assertTrue(result.hasDeleteTime());
        assertEquals(1, result.getCancelledCount());
        assertEquals(savesBeforeDelete, harness.sink.saves.get(), "no writes after deletion");
        assertEquals(result, harness.sink.completedOperations.get(operation));
    }

    @Test
    void reconcileFailsEveryNonTerminalTaskWithTheRestartMessage() throws Exception {
        Harness harness = new Harness(execution(2, 2, 3), null);
        harness.coordinator.reconcile();
        harness.coordinator.start();

        Execution result = harness.coordinator.finished().get(5, TimeUnit.SECONDS);
        assertEquals(2, result.getFailedCount());
        assertEquals("The emulator restarted before the execution completed.", completed(result).getMessage());
        Status error = harness.sink.finishedError.get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(10, error.getCode());
        assertEquals("The emulator restarted before the task completed.",
                harness.sink.tasks.get(0).getLastAttemptResult().getStatus().getMessage());
        assertTrue(harness.runner.launches.isEmpty());
    }

    @Test
    void runBoundStopsTasksAndFailsWithDeadlineExceeded() throws Exception {
        Harness harness = new Harness(execution(1, 1, 0), java.time.Duration.ofMillis(200));
        harness.coordinator.begin();
        harness.coordinator.start();
        harness.await(() -> harness.runner.launches.size() == 1);
        harness.await(() -> harness.runner.launches.get(0).stopped);
        harness.runner.finish(0, 0, new TaskOutcome.Stopped());

        harness.coordinator.finished().get(5, TimeUnit.SECONDS);
        Status error = harness.sink.finishedError.get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(4, error.getCode());
    }

    @Test
    void imagePreparationFailureFailsEveryTask() throws Exception {
        Harness harness = new Harness(execution(2, 2, 3), null);
        harness.runner.prepareFailure = "Image 'nope' not found.";
        harness.coordinator.begin();
        harness.coordinator.start();

        Execution result = harness.coordinator.finished().get(5, TimeUnit.SECONDS);
        assertEquals(2, result.getFailedCount());
        assertEquals("Image 'nope' not found.", completed(result).getMessage());
        assertEquals(9, harness.sink.finishedError.get(5, TimeUnit.SECONDS).orElseThrow().getCode());
        assertTrue(harness.runner.launches.isEmpty());
    }

    @Test
    void shutdownStopsWithoutWritingTerminalState() throws Exception {
        Harness harness = new Harness(execution(1, 1, 0), null);
        harness.coordinator.begin();
        harness.coordinator.start();
        harness.await(() -> harness.runner.launches.size() == 1);
        harness.await(() -> harness.sink.lastExecution().getRunningCount() == 1);

        harness.coordinator.shutdown().orElseThrow().get(5, TimeUnit.SECONDS);
        harness.runner.finish(0, 0, new TaskOutcome.Stopped());
        assertTrue(harness.coordinator.closed().isDone());
        assertFalse(harness.sink.lastExecution().hasCompletionTime());
        assertFalse(harness.sink.finishedError.isDone());
    }

    @Test
    void containerThatStopsWithoutAStopRequestIsAFailedAttemptAndIsRetried() throws Exception {
        Harness harness = new Harness(execution(1, 1, 1), null);
        harness.coordinator.begin();
        harness.coordinator.start();
        harness.await(() -> harness.runner.launches.size() == 1);

        harness.runner.finish(0, 0, new TaskOutcome.Stopped());
        harness.await(() -> harness.runner.launches.size() == 2);
        assertEquals(1, harness.runner.launches.get(1).attempt);
        harness.runner.finish(0, 1, new TaskOutcome.Stopped());

        Execution result = harness.coordinator.finished().get(5, TimeUnit.SECONDS);
        assertEquals(1, result.getFailedCount());
        assertEquals(0, result.getCancelledCount());
        assertEquals(1, result.getRetriedCount());
        assertEquals(Condition.State.CONDITION_FAILED, completed(result).getState());
        Status error = harness.sink.finishedError.get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(13, error.getCode());
        assertEquals("Task job-abcde-task0 failed with message: The task container stopped unexpectedly.",
                error.getMessage());
        Task task = harness.sink.tasks.get(0);
        assertEquals(13, task.getLastAttemptResult().getStatus().getCode());
        assertEquals("The task container stopped unexpectedly.", task.getLastAttemptResult().getStatus().getMessage());
    }

    @Test
    void successfulExitWhoseGcsWriteBackFailsIsRetriedAndFailsTheExecution() throws Exception {
        Harness harness = new Harness(execution(1, 1, 1), null);
        harness.coordinator.begin();
        harness.coordinator.start();
        harness.await(() -> harness.runner.launches.size() == 1);

        harness.runner.finish(0, 0, CloudRunJobsRuntime.afterWriteBack(new TaskOutcome.Exited(0),
                Optional.of("bucket out-bucket: upload failed"), "task0", 0));
        harness.await(() -> harness.runner.launches.size() == 2);
        assertEquals(1, harness.runner.launches.get(1).attempt);
        harness.runner.finish(0, 1, CloudRunJobsRuntime.afterWriteBack(new TaskOutcome.Exited(0),
                Optional.of("bucket out-bucket: upload failed"), "task0", 1));

        Execution result = harness.coordinator.finished().get(5, TimeUnit.SECONDS);
        assertEquals(1, result.getFailedCount());
        assertEquals(0, result.getSucceededCount());
        assertEquals(1, result.getRetriedCount());
        assertEquals(Condition.State.CONDITION_FAILED, completed(result).getState());
        Status error = harness.sink.finishedError.get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(13, error.getCode());
        assertEquals("Task job-abcde-task0 failed with message: The task's GCS volume could not be written back: "
                + "bucket out-bucket: upload failed.", error.getMessage());
        Task task = harness.sink.tasks.get(0);
        assertEquals(13, task.getLastAttemptResult().getStatus().getCode());
        assertEquals("The task's GCS volume could not be written back: bucket out-bucket: upload failed.",
                task.getLastAttemptResult().getStatus().getMessage());
    }

    @Test
    void failedGcsWriteBackKeepsAnAlreadyFailedOrStoppedOutcome() {
        Optional<String> failure = Optional.of("bucket out-bucket: upload failed");

        assertEquals(new TaskOutcome.Exited(0),
                CloudRunJobsRuntime.afterWriteBack(new TaskOutcome.Exited(0), Optional.empty(), "task0", 0));
        assertEquals(new TaskOutcome.Exited(3),
                CloudRunJobsRuntime.afterWriteBack(new TaskOutcome.Exited(3), failure, "task0", 0));
        assertEquals(new TaskOutcome.TimedOut(),
                CloudRunJobsRuntime.afterWriteBack(new TaskOutcome.TimedOut(), failure, "task0", 0));
        assertEquals(new TaskOutcome.Stopped(),
                CloudRunJobsRuntime.afterWriteBack(new TaskOutcome.Stopped(), failure, "task0", 0));
        assertEquals(new TaskOutcome.StartFailed("boom"),
                CloudRunJobsRuntime.afterWriteBack(new TaskOutcome.StartFailed("boom"), failure, "task0", 0));
    }

    @Test
    void cancelDuringADeadlineStopKeepsTheDeadlineFailure() throws Exception {
        Harness harness = new Harness(execution(1, 1, 0), java.time.Duration.ofMillis(200));
        harness.coordinator.begin();
        harness.coordinator.start();
        harness.await(() -> harness.runner.launches.size() == 1);
        harness.await(() -> harness.runner.launches.get(0).stopped);

        String operation = harness.coordinator.cancel().orElseThrow().get(5, TimeUnit.SECONDS);
        harness.runner.finish(0, 0, new TaskOutcome.Stopped());

        Execution result = harness.coordinator.finished().get(5, TimeUnit.SECONDS);
        Status error = harness.sink.finishedError.get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(4, error.getCode());
        assertEquals("Execution job-abcde did not complete within 0s.", completed(result).getMessage());
        assertEquals(Condition.ExecutionReason.EXECUTION_REASON_UNDEFINED, completed(result).getExecutionReason());
        assertEquals(result, harness.sink.completedOperations.get(operation));
    }

    @Test
    void deleteRacingALaunchReachesTheLaunchingCoordinator() throws Exception {
        Stores stores = new Stores();
        CloudRunJobsService service = stores.service(null, null);
        service.createJob("p", "us-central1", "job", JOB_BODY, false);
        AtomicReference<Object> deleteResult = new AtomicReference<>();
        AtomicReference<Thread> deleter = new AtomicReference<>();
        stores.executions.onFirstPut = executionName -> {
            Thread thread = Thread.ofPlatform().start(() -> {
                try {
                    deleteResult.set(service.deleteExecution(executionName, false));
                } catch (RuntimeException e) {
                    deleteResult.set(e);
                }
            });
            deleter.set(thread);
            awaitParked(thread);
        };

        Operation run = service.runJob(JOB, "{}");
        deleter.get().join(TimeUnit.SECONDS.toMillis(10));

        Execution metadata = run.getMetadata().unpack(Execution.class);
        Operation delete = assertInstanceOf(Operation.class, deleteResult.get(),
                () -> "delete failed: " + deleteResult.get());
        assertTrue(stores.operations.get(delete.getName()).getDone());
        assertTrue(run.getDone());
        Execution cancelled = run.getResponse().unpack(Execution.class);
        assertEquals(Condition.ExecutionReason.CANCELLED, completed(cancelled).getExecutionReason());
        assertTrue(stores.executions.get(metadata.getName()).isEmpty(), "the deleted execution stays deleted");
        assertTrue(stores.tasks.keys().isEmpty(), "no task record survives the delete");
        assertTrue(stores.tombstones.keys().isEmpty());
        GcpException missing = assertThrows(GcpException.class, () -> service.getExecution(metadata.getName()));
        assertEquals("Resource '" + CloudRunRuntimeService.lastSegment(metadata.getName())
                + "' of kind 'EXECUTION' in region 'us-central1' in project 'p' does not exist.",
                missing.getMessage());
    }

    @Test
    void startupCompletesTheOperationsOfAnExecutionDeletedWhileItsContainersWereStopping() throws Exception {
        Stores stores = new Stores();
        StubRunner runner = new StubRunner();
        CloudRunJobsService before = stores.service(dockerConfig(), runner);
        before.createJob("p", "us-central1", "job", JOB_BODY, false);
        Operation run = before.runJob(JOB, "{}");
        String executionName = run.getMetadata().unpack(Execution.class).getName();
        for (int i = 0; i < 500 && runner.launches.isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertEquals(1, runner.launches.size());
        Operation delete = before.deleteExecution(executionName, false);
        assertFalse(delete.getDone(), "the delete waits for the container to stop");
        before.stopManagedContainers();
        assertFalse(stores.operations.get(run.getName()).getDone());
        assertEquals(List.of(executionName), List.copyOf(stores.tombstones.keys()));

        CloudRunJobsService after = stores.service(dockerConfig(), new StubRunner());
        after.reconcileInterruptedExecutions();

        for (Operation operation : List.of(stores.operations.get(run.getName()),
                stores.operations.get(delete.getName()))) {
            assertTrue(operation.getDone(), operation.getName());
            assertFalse(operation.hasError(), operation.getName());
            Execution response = operation.getResponse().unpack(Execution.class);
            assertEquals(Condition.ExecutionReason.CANCELLED, completed(response).getExecutionReason());
            assertEquals("Cancelled by user.", completed(response).getMessage());
            assertEquals(1, response.getCancelledCount());
            assertTrue(response.hasDeleteTime());
            assertTrue(response.hasCompletionTime());
        }
        assertTrue(stores.tombstones.keys().isEmpty());
        assertEquals(ExecutionReference.CompletionStatus.EXECUTION_CANCELLED,
                after.getJob(JOB).getLatestCreatedExecution().getCompletionStatus());
    }

    @Test
    void recreatingAJobWhileItsExecutionsAreBeingCleanedUpIsAborted() throws Exception {
        Stores stores = new Stores();
        CloudRunJobsService service = stores.service(null, null);
        String tokenBody = """
                {"startExecutionToken":"first","template":{"template":{"containers":[{"image":"busybox"}]}}}
                """;
        service.createJob("p", "us-central1", "job", tokenBody, false);
        String tokenExecution = JOB + "/executions/job-first";
        assertTrue(stores.executions.get(tokenExecution).isPresent());
        AtomicReference<Object> createDuringCleanup = new AtomicReference<>();
        AtomicReference<Object> upsertDuringCleanup = new AtomicReference<>();
        stores.executions.onFirstDelete = executionName -> {
            createDuringCleanup.set(attempt(() -> service.createJob("p", "us-central1", "job", tokenBody, false)));
            upsertDuringCleanup.set(attempt(() -> service.updateJob(JOB, tokenBody, false, true)));
        };

        service.deleteJob(JOB, false);

        for (Object result : List.of(createDuringCleanup.get(), upsertDuringCleanup.get())) {
            GcpException aborted = assertInstanceOf(GcpException.class, result, () -> "expected ABORTED: " + result);
            assertEquals(409, aborted.getHttpStatus());
            assertEquals("ABORTED", aborted.getGcpStatus());
            assertEquals("Job 'job' is being deleted.", aborted.getMessage());
        }
        assertTrue(stores.jobs.get(JOB).isEmpty());
        assertTrue(stores.executions.get(tokenExecution).isEmpty());

        Operation recreated = service.createJob("p", "us-central1", "job", tokenBody, false);

        assertTrue(recreated.getDone());
        assertEquals("job-first", service.getJob(JOB).getLatestCreatedExecution().getName());
        assertTrue(stores.executions.get(tokenExecution).isPresent(),
                "the recreated job gets the execution its token requested");
    }

    @Test
    void notFoundMessagesMatchGcp() {
        assertEquals("Resource 'job' of kind 'JOB' in region 'us-central1' in project 'p' does not exist.",
                CloudRunJobTemplates.notFoundMessage(CloudRunJobTemplates.KIND_JOB, JOB));
        assertEquals("Resource 'job-abcde-task0' of kind 'TASK' in region 'us-central1' in project 'p' does not exist.",
                CloudRunJobTemplates.notFoundMessage(CloudRunJobTemplates.KIND_TASK,
                        JOB + "/executions/job-abcde/tasks/job-abcde-task0"));
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static final String JOB_BODY = """
            {"template":{"template":{"maxRetries":0,"containers":[{"image":"busybox"}]}}}
            """;

    private static Object attempt(Supplier<Object> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static void awaitParked(Thread thread) {
        for (int i = 0; i < 500; i++) {
            Thread.State state = thread.getState();
            if (state == Thread.State.TIMED_WAITING || state == Thread.State.WAITING
                    || state == Thread.State.BLOCKED || state == Thread.State.TERMINATED) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the racing request", e);
            }
        }
        throw new AssertionError("The racing request never parked");
    }

    private static EmulatorConfig dockerConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudrun().mock()).thenReturn(false);
        when(config.services().cloudrun().execution().cleanupTimeout()).thenReturn(java.time.Duration.ofSeconds(1));
        when(config.services().cloudrun().execution().startupTimeout()).thenReturn(java.time.Duration.ofSeconds(60));
        return config;
    }

    /** Storage shared by the services of one test, so a second service instance acts as a restarted emulator. */
    private static final class Stores {
        final InMemoryStorage<String, String> jobs = new InMemoryStorage<>();
        final HookedStorage executions = new HookedStorage();
        final InMemoryStorage<String, String> tasks = new InMemoryStorage<>();
        final InMemoryStorage<String, String> tombstones = new InMemoryStorage<>();
        final LongRunningOperationsService operations;

        Stores() {
            StorageFactory factory = mock(StorageFactory.class);
            StorageBackend<String, String> operationStore = new InMemoryStorage<>();
            when(factory.<String>createGlobal(anyString(), anyString(), any())).thenReturn(operationStore);
            operations = new LongRunningOperationsService(factory);
        }

        CloudRunJobsService service(EmulatorConfig config, CloudRunExecutionCoordinator.TaskRunner runner) {
            IamService iamService = mock(IamService.class);
            doAnswer(invocation -> {
                invocation.<Runnable>getArgument(1).run();
                return null;
            }).when(iamService).deleteResourceAndPolicy(anyString(), any());
            return new CloudRunJobsService(jobs, executions, tasks, tombstones, operations, iamService,
                    config, runner, null, Clock.systemUTC());
        }
    }

    /**
     * Runs {@link #onFirstPut} once, right after the first execution record is written, and {@link #onFirstDelete}
     * once, right after the first execution record is deleted.
     */
    private static final class HookedStorage extends InMemoryStorage<String, String> {
        volatile Consumer<String> onFirstPut;
        volatile Consumer<String> onFirstDelete;

        @Override
        public void delete(String key) {
            super.delete(key);
            Consumer<String> hook = onFirstDelete;
            if (hook != null) {
                onFirstDelete = null;
                hook.accept(key);
            }
        }

        @Override
        public void put(String key, String value) {
            super.put(key, value);
            Consumer<String> hook = onFirstPut;
            if (hook != null) {
                onFirstPut = null;
                hook.accept(key);
            }
        }
    }

    private static Condition completed(Execution execution) {
        return execution.getConditionsList().stream()
                .filter(condition -> condition.getType().equals("Completed"))
                .findFirst()
                .orElseThrow();
    }

    private static ExecutionTemplate template(Container... containers) {
        return ExecutionTemplate.newBuilder()
                .setTaskCount(1)
                .setTemplate(TaskTemplate.newBuilder().addAllContainers(List.of(containers)))
                .build();
    }

    private static Container container(String name, List<String> args, Map<String, String> env) {
        Container.Builder builder = Container.newBuilder().setName(name).setImage("busybox").addAllArgs(args);
        env.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.addEnv(EnvVar.newBuilder().setName(entry.getKey())
                        .setValue(entry.getValue())));
        return builder.build();
    }

    private static Execution execution(int taskCount, int parallelism, int maxRetries) {
        return Execution.newBuilder()
                .setName(JOB + "/executions/job-abcde")
                .setGeneration(1)
                .setCreateTime(CloudRunJobTemplates.timestamp(Clock.systemUTC().instant()))
                .setJob("job")
                .setTaskCount(taskCount)
                .setParallelism(parallelism)
                .setTemplate(TaskTemplate.newBuilder()
                        .setMaxRetries(maxRetries)
                        .setTimeout(Duration.newBuilder().setSeconds(600))
                        .addContainers(Container.newBuilder().setImage("busybox")))
                .setReconciling(true)
                .addConditions(CloudRunJobTemplates.condition("Completed", Condition.State.CONDITION_PENDING,
                        null, null))
                .build();
    }

    private static List<Task> tasks(Execution execution) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < execution.getTaskCount(); i++) {
            tasks.add(Task.newBuilder()
                    .setName(execution.getName() + "/tasks/job-abcde-task" + i)
                    .setGeneration(1)
                    .setIndex(i)
                    .setMaxRetries(execution.getTemplate().getMaxRetries())
                    .addConditions(CloudRunJobTemplates.condition("Started", Condition.State.CONDITION_PENDING,
                            null, null))
                    .addConditions(CloudRunJobTemplates.condition("Completed", Condition.State.CONDITION_PENDING,
                            null, null))
                    .build());
        }
        return tasks;
    }

    private static final class Harness {
        final StubRunner runner = new StubRunner();
        final RecordingSink sink = new RecordingSink();
        final CloudRunExecutionCoordinator coordinator;
        private final java.time.Duration bound;

        Harness(Execution execution, java.time.Duration bound) {
            this.bound = bound;
            this.coordinator = new CloudRunExecutionCoordinator(execution, tasks(execution), runner, sink,
                    Clock.systemUTC(), bound, closed -> { });
        }

        /** A second coordinator built from the records the first one saved, as the service does after close. */
        CloudRunExecutionCoordinator restore() {
            return new CloudRunExecutionCoordinator(sink.lastExecution(), List.copyOf(sink.tasks.values()), runner,
                    sink, Clock.systemUTC(), bound, closed -> { });
        }

        void await(BooleanSupplier condition) throws InterruptedException {
            for (int i = 0; i < 500; i++) {
                if (condition.getAsBoolean()) {
                    return;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("condition not reached; launches=" + runner.launchedIndexes());
        }
    }

    private static final class StubLaunch implements TaskHandle {
        final int index;
        final int attempt;
        volatile boolean stopped;

        StubLaunch(int index, int attempt) {
            this.index = index;
            this.attempt = attempt;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    private static final class StubRunner implements CloudRunExecutionCoordinator.TaskRunner {
        final List<StubLaunch> launches = new CopyOnWriteArrayList<>();
        volatile Events events;
        volatile String prepareFailure;

        @Override
        public void prepare(Execution execution, Events events) {
            this.events = events;
            if (prepareFailure != null) {
                events.prepareFailed(prepareFailure);
            } else {
                events.prepared();
            }
        }

        @Override
        public TaskHandle launch(Execution execution, Task task, int attempt, Events events) {
            StubLaunch launch = new StubLaunch(task.getIndex(), attempt);
            launches.add(launch);
            events.taskStarted(task.getIndex(), attempt);
            return launch;
        }

        void finish(int index, int attempt, TaskOutcome outcome) {
            events.taskFinished(index, attempt, outcome);
        }

        List<Integer> launchedIndexes() {
            return launches.stream().map(launch -> launch.index).toList();
        }
    }

    private static final class RecordingSink implements CloudRunExecutionCoordinator.Sink {
        final Map<Integer, Task> tasks = new ConcurrentHashMap<>();
        final Map<String, Execution> completedOperations = new ConcurrentHashMap<>();
        final CompletableFuture<Optional<Status>> finishedError = new CompletableFuture<>();
        final AtomicInteger saves = new AtomicInteger();
        final AtomicInteger operations = new AtomicInteger();
        volatile Execution execution;
        volatile boolean deleted;

        Execution lastExecution() {
            return execution == null ? Execution.getDefaultInstance() : execution;
        }

        @Override
        public void save(Execution execution, List<Task> changedTasks) {
            saves.incrementAndGet();
            for (Task task : changedTasks) {
                tasks.put(task.getIndex(), task);
            }
            this.execution = execution;
        }

        @Override
        public void delete(Execution execution, List<Task> tasks) {
            deleted = true;
        }

        @Override
        public String startOperation(Execution metadata) {
            return "operations/" + operations.incrementAndGet();
        }

        @Override
        public void completeOperation(String operationName, Execution execution) {
            completedOperations.put(operationName, execution);
        }

        @Override
        public void finished(Execution execution, Status error) {
            finishedError.complete(Optional.ofNullable(error));
        }
    }
}

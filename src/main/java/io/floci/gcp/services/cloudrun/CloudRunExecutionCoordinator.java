package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.Execution;
import com.google.cloud.run.v2.Task;
import com.google.cloud.run.v2.TaskAttemptResult;
import com.google.protobuf.Timestamp;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.floci.gcp.core.common.GcpException;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns one Cloud Run job execution and its tasks.
 *
 * <p>Invariant: every mutation of an execution record or of its task records (create excepted) is performed by
 * that execution's coordinator, on the coordinator's own thread, and at most one coordinator exists per execution
 * at any time. Every competing path is delivered as a command on the coordinator queue and applied in arrival
 * order: task container start and exit, per-attempt timeout, image preparation, cancel, execution delete, job
 * delete, the run deadline, startup reconciliation and emulator shutdown. A coordinator closes once its execution
 * is terminal and its queue is empty; closing and unregistering happen under the same monitor that guards
 * {@link #submit}, so a caller that loses the race sees {@code submit == false} and can create a fresh coordinator
 * from the stored, now immutable, records.
 */
final class CloudRunExecutionCoordinator {

    private static final Logger LOG = Logger.getLogger(CloudRunExecutionCoordinator.class);

    /** Starts containers for an execution. Implementations must not block the caller. */
    interface TaskRunner {
        void prepare(Execution execution, Events events);

        TaskHandle launch(Execution execution, Task task, int attempt, Events events);
    }

    interface TaskHandle {
        /** Requests the attempt to stop; the runner still reports {@link Events#taskFinished}. */
        void stop();
    }

    /** Callbacks from a {@link TaskRunner}; each one is queued as a coordinator command. */
    interface Events {
        void prepared();

        void prepareFailed(String message);

        void taskStarted(int index, int attempt);

        void taskFinished(int index, int attempt, TaskOutcome outcome);
    }

    sealed interface TaskOutcome {
        record Exited(int exitCode) implements TaskOutcome {}

        record TimedOut() implements TaskOutcome {}

        record Stopped() implements TaskOutcome {}

        record StartFailed(String message) implements TaskOutcome {}
    }

    /** Persistence and operation side effects, invoked only from the coordinator thread. */
    interface Sink {
        void save(Execution execution, List<Task> changedTasks);

        void delete(Execution execution, List<Task> tasks);

        String startOperation(Execution metadata);

        void completeOperation(String operationName, Execution execution);

        void finished(Execution execution, Status error);
    }

    private enum TaskState {
        PENDING, SCHEDULED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

        boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    private sealed interface Command {}

    private record Begin() implements Command {}

    private record Reconcile() implements Command {}

    private record Prepared() implements Command {}

    private record PrepareFailed(String message) implements Command {}

    private record TaskStarted(int index, int attempt) implements Command {}

    private record TaskFinished(int index, int attempt, TaskOutcome outcome) implements Command {}

    private record Cancel(CompletableFuture<String> ack) implements Command {}

    private record Delete(CompletableFuture<String> ack, boolean withOperation) implements Command {}

    private record Shutdown(CompletableFuture<Void> ack) implements Command {}

    private static final TaskHandle NO_OP_HANDLE = () -> { };

    private final String name;
    private final TaskRunner runner;
    private final Sink sink;
    private final Clock clock;
    private final Duration deadlineBound;
    private final Consumer<CloudRunExecutionCoordinator> onClosed;
    private final LinkedBlockingQueue<Command> queue = new LinkedBlockingQueue<>();
    private final CompletableFuture<Execution> finished = new CompletableFuture<>();
    private final CompletableFuture<Void> closedFuture = new CompletableFuture<>();
    private final Events events = new QueuedEvents();
    private boolean closed;

    // Confined to the coordinator thread.
    private final Execution.Builder execution;
    private final Task.Builder[] tasks;
    private final TaskState[] states;
    private final Map<Integer, TaskHandle> running = new HashMap<>();
    private final TreeSet<Integer> pending = new TreeSet<>();
    private final TreeSet<Integer> dirty = new TreeSet<>();
    private final List<String> completionOperations = new ArrayList<>();
    private Condition started;
    private Condition completed;
    private Condition containerReady;
    private Condition resourcesAvailable;
    private boolean begun;
    private boolean preparing;
    private boolean stopping;
    private boolean userCancelled;
    private boolean deleted;
    private boolean terminal;
    private boolean firstTaskStarted;
    private Instant resourcesReadyAt;
    private Instant deadline;
    private Status failure;
    private Condition.ExecutionReason failureReason;

    /**
     * @param deadlineBound upper bound for the whole run once it begins, or {@code null} for none
     * @param onClosed      invoked under the coordinator monitor when it closes, to unregister it
     */
    CloudRunExecutionCoordinator(Execution execution, List<Task> taskRecords, TaskRunner runner, Sink sink,
                                 Clock clock, Duration deadlineBound,
                                 Consumer<CloudRunExecutionCoordinator> onClosed) {
        this.name = execution.getName();
        this.runner = runner;
        this.sink = sink;
        this.clock = clock;
        this.deadlineBound = deadlineBound;
        this.onClosed = onClosed;
        this.execution = execution.toBuilder();
        this.terminal = execution.hasCompletionTime();
        this.tasks = new Task.Builder[execution.getTaskCount()];
        this.states = new TaskState[execution.getTaskCount()];
        for (Task task : taskRecords) {
            if (task.getIndex() >= 0 && task.getIndex() < tasks.length) {
                tasks[task.getIndex()] = task.toBuilder();
                states[task.getIndex()] = storedState(task);
            }
        }
        for (int i = 0; i < tasks.length; i++) {
            if (tasks[i] == null) {
                throw new IllegalArgumentException("Missing task " + i + " for execution " + name);
            }
            if (states[i] == TaskState.PENDING) {
                pending.add(i);
            }
        }
        for (Condition condition : execution.getConditionsList()) {
            switch (condition.getType()) {
                case "Started" -> started = condition;
                case "Completed" -> completed = condition;
                case "ContainerReady" -> containerReady = condition;
                case "ResourcesAvailable" -> resourcesAvailable = condition;
                default -> LOG.debugf("Ignoring stored execution condition type=%s execution=%s",
                        condition.getType(), name);
            }
        }
        if (completed == null) {
            completed = CloudRunJobTemplates.condition("Completed", Condition.State.CONDITION_PENDING, null, null);
        }
    }

    String name() {
        return name;
    }

    void start() {
        Thread.ofVirtual()
                .name("cloudrun-execution-" + CloudRunRuntimeService.lastSegment(name))
                .start(this::loop);
    }

    /** Completes with the terminal execution; fails if the coordinator stops for emulator shutdown. */
    CompletableFuture<Execution> finished() {
        return finished;
    }

    CompletableFuture<Void> closed() {
        return closedFuture;
    }

    boolean begin() {
        return submit(new Begin());
    }

    boolean reconcile() {
        return submit(new Reconcile());
    }

    /** Returns the cancel operation name future, or empty when the coordinator already closed. */
    Optional<CompletableFuture<String>> cancel() {
        CompletableFuture<String> ack = new CompletableFuture<>();
        return submit(new Cancel(ack)) ? Optional.of(ack) : Optional.empty();
    }

    /**
     * Returns a future that completes once the records are deleted, with the delete operation name when
     * {@code withOperation} is set, or empty when the coordinator already closed.
     */
    Optional<CompletableFuture<String>> delete(boolean withOperation) {
        CompletableFuture<String> ack = new CompletableFuture<>();
        return submit(new Delete(ack, withOperation)) ? Optional.of(ack) : Optional.empty();
    }

    Optional<CompletableFuture<Void>> shutdown() {
        CompletableFuture<Void> ack = new CompletableFuture<>();
        return submit(new Shutdown(ack)) ? Optional.of(ack) : Optional.empty();
    }

    private synchronized boolean submit(Command command) {
        if (closed) {
            return false;
        }
        queue.add(command);
        return true;
    }

    private void loop() {
        while (true) {
            if (terminal && closeIfIdle()) {
                return;
            }
            Command command;
            try {
                command = nextCommand();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warnf("Cloud Run execution coordinator interrupted execution=%s", name);
                closeForShutdown(null);
                return;
            }
            if (command == null) {
                handleSafely(this::onDeadline);
                continue;
            }
            if (command instanceof Shutdown shutdown) {
                closeForShutdown(shutdown.ack());
                return;
            }
            handleSafely(() -> handle(command));
        }
    }

    private Command nextCommand() throws InterruptedException {
        if (terminal) {
            return queue.poll();
        }
        if (deadline == null) {
            return queue.take();
        }
        long remaining = Duration.between(clock.instant(), deadline).toMillis();
        if (remaining <= 0) {
            return null;
        }
        return queue.poll(remaining, TimeUnit.MILLISECONDS);
    }

    private void handleSafely(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            LOG.errorf(e, "Cloud Run execution coordinator failed to apply a command execution=%s", name);
        }
    }

    private void handle(Command command) {
        switch (command) {
            case Begin ignored -> onBegin();
            case Reconcile ignored -> onReconcile();
            case Prepared ignored -> onPrepared();
            case PrepareFailed failed -> onPrepareFailed(failed.message());
            case TaskStarted taskStarted -> onTaskStarted(taskStarted.index(), taskStarted.attempt());
            case TaskFinished taskFinished ->
                    onTaskFinished(taskFinished.index(), taskFinished.attempt(), taskFinished.outcome());
            case Cancel cancel -> onCancel(cancel.ack());
            case Delete delete -> onDelete(delete);
            case Shutdown shutdown -> closeForShutdown(shutdown.ack());
        }
    }

    private boolean closeIfIdle() {
        synchronized (this) {
            if (!queue.isEmpty()) {
                return false;
            }
            closed = true;
            onClosed.accept(this);
        }
        closedFuture.complete(null);
        return true;
    }

    private void closeForShutdown(CompletableFuture<Void> ack) {
        List<Command> abandoned = new ArrayList<>();
        synchronized (this) {
            closed = true;
            queue.drainTo(abandoned);
            onClosed.accept(this);
        }
        GcpException shuttingDown = GcpException.unavailable("The emulator is shutting down.");
        for (Command command : abandoned) {
            switch (command) {
                case Cancel cancel -> cancel.ack().completeExceptionally(shuttingDown);
                case Delete delete -> delete.ack().completeExceptionally(shuttingDown);
                case Shutdown shutdown -> shutdown.ack().complete(null);
                default -> LOG.debugf("Dropping command on shutdown execution=%s command=%s", name, command);
            }
        }
        for (TaskHandle handle : running.values()) {
            handle.stop();
        }
        finished.completeExceptionally(shuttingDown);
        closedFuture.complete(null);
        if (ack != null) {
            ack.complete(null);
        }
    }

    private void onBegin() {
        if (terminal || begun) {
            return;
        }
        begun = true;
        preparing = true;
        if (deadlineBound != null) {
            deadline = clock.instant().plus(deadlineBound);
        }
        try {
            runner.prepare(snapshot(), events);
        } catch (RuntimeException e) {
            onPrepareFailed(message(e));
        }
    }

    private void onPrepared() {
        if (terminal || !preparing) {
            return;
        }
        preparing = false;
        if (stopping) {
            maybeFinish();
            return;
        }
        Instant now = clock.instant();
        String imported = "Imported container image in "
                + CloudRunJobTemplates.formatSeconds(Duration.between(createTime(), now)) + ".";
        containerReady = CloudRunJobTemplates.condition("ContainerReady", Condition.State.CONDITION_SUCCEEDED,
                imported, ts(now));
        resourcesAvailable = CloudRunJobTemplates.condition("ResourcesAvailable",
                Condition.State.CONDITION_SUCCEEDED, CloudRunJobTemplates.PROVISIONED_MESSAGE, ts(now));
        started = CloudRunJobTemplates.condition("Started", Condition.State.CONDITION_RECONCILING,
                CloudRunJobTemplates.WAITING_TO_START_MESSAGE, ts(now));
        resourcesReadyAt = now;
        execution.setStartTime(ts(now));
        schedule();
        save();
        maybeFinish();
    }

    private void onPrepareFailed(String message) {
        if (terminal) {
            return;
        }
        preparing = false;
        Instant now = clock.instant();
        containerReady = CloudRunJobTemplates.condition("ContainerReady", Condition.State.CONDITION_FAILED,
                message, ts(now));
        Status status = Status.newBuilder().setCode(Code.FAILED_PRECONDITION_VALUE).setMessage(message).build();
        for (int index : List.copyOf(pending)) {
            finishTask(index, TaskState.FAILED, status, null, message, now);
        }
        pending.clear();
        recordFailure(status, null);
        save();
        maybeFinish();
    }

    private void schedule() {
        int limit = parallelism();
        while (!stopping && running.size() < limit && !pending.isEmpty()) {
            launch(pending.pollFirst());
        }
    }

    private void launch(int index) {
        Task.Builder task = tasks[index];
        Instant now = clock.instant();
        states[index] = TaskState.SCHEDULED;
        if (!task.hasScheduledTime()) {
            task.setScheduledTime(ts(now));
        }
        dirty.add(index);
        int attempt = task.getRetried();
        try {
            running.put(index, runner.launch(snapshot(), task.build(), attempt, events));
        } catch (RuntimeException e) {
            running.put(index, NO_OP_HANDLE);
            events.taskFinished(index, attempt, new TaskOutcome.StartFailed(message(e)));
        }
    }

    private void onTaskStarted(int index, int attempt) {
        if (terminal || !validIndex(index) || states[index] != TaskState.SCHEDULED
                || attempt != tasks[index].getRetried()) {
            return;
        }
        Instant now = clock.instant();
        states[index] = TaskState.RUNNING;
        Task.Builder task = tasks[index];
        task.setStartTime(ts(now));
        setTaskCondition(task, CloudRunJobTemplates.condition("Started", Condition.State.CONDITION_SUCCEEDED,
                null, null));
        dirty.add(index);
        if (!firstTaskStarted) {
            firstTaskStarted = true;
            Instant since = resourcesReadyAt != null ? resourcesReadyAt : createTime();
            started = CloudRunJobTemplates.condition("Started", Condition.State.CONDITION_SUCCEEDED,
                    "Started deployed execution in "
                            + CloudRunJobTemplates.formatSeconds(Duration.between(since, now)) + ".", ts(now));
        }
        save();
    }

    private void onTaskFinished(int index, int attempt, TaskOutcome outcome) {
        if (terminal || !validIndex(index) || !running.containsKey(index)
                || attempt != tasks[index].getRetried()) {
            return;
        }
        running.remove(index);
        Instant now = clock.instant();
        String taskId = CloudRunRuntimeService.lastSegment(tasks[index].getName());
        if (stopping) {
            if (outcome instanceof TaskOutcome.Exited exited && exited.exitCode() == 0) {
                finishTask(index, TaskState.SUCCEEDED, Status.getDefaultInstance(), null, null, now);
            } else {
                cancelTask(index, now);
            }
        } else {
            switch (outcome) {
                case TaskOutcome.Exited exited when exited.exitCode() == 0 ->
                        finishTask(index, TaskState.SUCCEEDED, Status.getDefaultInstance(), null, null, now);
                case TaskOutcome.Exited exited -> attemptFailed(index, now,
                        status(Code.ABORTED_VALUE, CloudRunJobTemplates.EXIT_ERROR_MESSAGE), exited.exitCode(),
                        status(Code.ABORTED_VALUE, "Task " + taskId + " failed with exit code: "
                                + exited.exitCode() + " and message: " + CloudRunJobTemplates.EXIT_ERROR_MESSAGE),
                        Condition.ExecutionReason.NON_ZERO_EXIT_CODE);
                case TaskOutcome.TimedOut ignored -> attemptFailed(index, now,
                        status(Code.DEADLINE_EXCEEDED_VALUE, CloudRunJobTemplates.TIMEOUT_MESSAGE), null,
                        status(Code.DEADLINE_EXCEEDED_VALUE, "Task " + taskId
                                + " failed with exit code: 0 and message: " + CloudRunJobTemplates.TIMEOUT_MESSAGE),
                        null);
                case TaskOutcome.Stopped ignored -> attemptFailed(index, now,
                        status(Code.INTERNAL_VALUE, CloudRunJobTemplates.CONTAINER_VANISHED_MESSAGE), null,
                        status(Code.INTERNAL_VALUE, "Task " + taskId + " failed with message: "
                                + CloudRunJobTemplates.CONTAINER_VANISHED_MESSAGE),
                        null);
                case TaskOutcome.StartFailed startFailed -> {
                    Status status = status(Code.INTERNAL_VALUE, startFailed.message());
                    finishTask(index, TaskState.FAILED, status, null, startFailed.message(), now);
                    recordFailure(status(Code.INTERNAL_VALUE,
                            "Task " + taskId + " failed to start: " + startFailed.message()), null);
                }
            }
        }
        schedule();
        save();
        maybeFinish();
    }

    private void attemptFailed(int index, Instant now, Status attemptStatus, Integer exitCode,
                               Status executionError, Condition.ExecutionReason reason) {
        Task.Builder task = tasks[index];
        if (task.getRetried() < task.getMaxRetries()) {
            task.setLastAttemptResult(attemptResult(attemptStatus, exitCode));
            task.setRetried(task.getRetried() + 1);
            states[index] = TaskState.PENDING;
            pending.add(index);
            dirty.add(index);
            return;
        }
        finishTask(index, TaskState.FAILED, attemptStatus, exitCode, attemptStatus.getMessage(), now);
        recordFailure(executionError, reason);
    }

    private void onCancel(CompletableFuture<String> ack) {
        if (deleted) {
            ack.completeExceptionally(CloudRunJobTemplates.notFound(CloudRunJobTemplates.KIND_EXECUTION, name));
            return;
        }
        if (terminal) {
            ack.completeExceptionally(GcpException.failedPrecondition("Execution '"
                    + CloudRunRuntimeService.lastSegment(name)
                    + "' cannot be cancelled because it is not running."));
            return;
        }
        Instant now = clock.instant();
        execution.setGeneration(execution.getGeneration() + 1).setUpdateTime(ts(now));
        markUserCancelled();
        stopAll(now);
        save();
        String operation = sink.startOperation(snapshot());
        completionOperations.add(operation);
        ack.complete(operation);
        maybeFinish();
    }

    private void onDelete(Delete command) {
        if (deleted) {
            command.ack().completeExceptionally(CloudRunJobTemplates.notFound(CloudRunJobTemplates.KIND_EXECUTION,
                    name));
            return;
        }
        Instant now = clock.instant();
        execution.setGeneration(execution.getGeneration() + 1)
                .setUpdateTime(ts(now))
                .setDeleteTime(ts(now))
                .setExpireTime(ts(now.plusSeconds(CloudRunJobTemplates.EXPIRE_AFTER_DELETE_SECONDS)));
        if (!terminal) {
            markUserCancelled();
            stopAll(now);
        }
        Execution snapshot = snapshot();
        String operation = command.withOperation() ? sink.startOperation(snapshot) : null;
        List<Task> taskRecords = new ArrayList<>();
        for (Task.Builder task : tasks) {
            taskRecords.add(task.build());
        }
        sink.delete(snapshot, taskRecords);
        deleted = true;
        dirty.clear();
        if (terminal) {
            if (operation != null) {
                sink.completeOperation(operation, snapshot);
            }
        } else if (operation != null) {
            completionOperations.add(operation);
        }
        command.ack().complete(operation);
        maybeFinish();
    }

    private void onReconcile() {
        if (terminal) {
            return;
        }
        Instant now = clock.instant();
        Status status = status(Code.ABORTED_VALUE, CloudRunJobTemplates.RESTARTED_TASK_MESSAGE);
        for (int i = 0; i < tasks.length; i++) {
            if (!states[i].terminal()) {
                finishTask(i, TaskState.FAILED, status, null, CloudRunJobTemplates.RESTARTED_TASK_MESSAGE, now);
            }
        }
        pending.clear();
        running.clear();
        preparing = false;
        if (failure == null) {
            recordFailure(status(Code.ABORTED_VALUE, CloudRunJobTemplates.RESTARTED_EXECUTION_MESSAGE), null);
        }
        maybeFinish();
    }

    private void onDeadline() {
        if (terminal || deadline == null) {
            return;
        }
        deadline = null;
        String id = CloudRunRuntimeService.lastSegment(name);
        LOG.warnf("Cloud Run execution exceeded its run bound execution=%s bound=%s", name, deadlineBound);
        recordFailure(status(Code.DEADLINE_EXCEEDED_VALUE,
                "Execution " + id + " did not complete within " + deadlineBound.toSeconds() + "s."), null);
        stopAll(clock.instant());
        save();
        maybeFinish();
    }

    /**
     * A cancel or delete terminates the execution as cancelled unless a stop is already in progress for another
     * reason (the run deadline): the first termination reason is kept.
     */
    private void markUserCancelled() {
        if (!stopping) {
            userCancelled = true;
        }
    }

    private void stopAll(Instant now) {
        stopping = true;
        for (int index : List.copyOf(pending)) {
            cancelTask(index, now);
        }
        pending.clear();
        for (TaskHandle handle : running.values()) {
            handle.stop();
        }
        completed = CloudRunJobTemplates.condition("Completed", Condition.State.CONDITION_RECONCILING,
                CloudRunJobTemplates.WAITING_FOR_CANCEL_MESSAGE, ts(now));
    }

    private void cancelTask(int index, Instant now) {
        finishTask(index, TaskState.CANCELLED, status(Code.CANCELLED_VALUE, CloudRunJobTemplates.CANCELLED_MESSAGE),
                null, CloudRunJobTemplates.CANCELLED_MESSAGE, now);
    }

    private void finishTask(int index, TaskState state, Status attemptStatus, Integer exitCode, String message,
                            Instant now) {
        Task.Builder task = tasks[index];
        states[index] = state;
        task.setLastAttemptResult(attemptResult(attemptStatus, exitCode))
                .setCompletionTime(ts(now))
                .setUpdateTime(ts(now))
                .setReconciling(false)
                .setObservedGeneration(task.getGeneration());
        Condition.State conditionState = state == TaskState.SUCCEEDED
                ? Condition.State.CONDITION_SUCCEEDED
                : Condition.State.CONDITION_FAILED;
        setTaskCondition(task, CloudRunJobTemplates.condition("Completed", conditionState, message, null));
        dirty.add(index);
    }

    private void recordFailure(Status error, Condition.ExecutionReason reason) {
        if (failure == null) {
            failure = error;
            failureReason = reason;
        }
    }

    private void maybeFinish() {
        if (terminal || !running.isEmpty()) {
            return;
        }
        for (TaskState state : states) {
            if (!state.terminal()) {
                return;
            }
        }
        finish();
    }

    private void finish() {
        Instant now = clock.instant();
        terminal = true;
        Status error = null;
        if (userCancelled) {
            completed = CloudRunJobTemplates.condition("Completed", Condition.State.CONDITION_FAILED,
                    CloudRunJobTemplates.CANCELLED_MESSAGE, ts(now)).toBuilder()
                    .setExecutionReason(Condition.ExecutionReason.CANCELLED)
                    .build();
        } else if (failure != null) {
            Condition.Builder condition = CloudRunJobTemplates.condition("Completed",
                    Condition.State.CONDITION_FAILED, failure.getMessage(), ts(now)).toBuilder();
            if (failureReason != null) {
                condition.setExecutionReason(failureReason);
            }
            completed = condition.build();
            error = failure;
        } else {
            Instant since = execution.hasStartTime() ? CloudRunJobTemplates.instant(execution.getStartTime())
                    : createTime();
            completed = CloudRunJobTemplates.condition("Completed", Condition.State.CONDITION_SUCCEEDED,
                    "Execution completed successfully in "
                            + CloudRunJobTemplates.formatSeconds(Duration.between(since, now)) + ".", ts(now));
        }
        execution.setCompletionTime(ts(now))
                .setReconciling(false)
                .setObservedGeneration(execution.getGeneration());
        save();
        Execution result = snapshot();
        sink.finished(result, error);
        for (String operation : completionOperations) {
            sink.completeOperation(operation, result);
        }
        completionOperations.clear();
        finished.complete(result);
    }

    private void save() {
        Execution snapshot = snapshot();
        if (deleted) {
            dirty.clear();
            return;
        }
        List<Task> changed = new ArrayList<>();
        for (int index : dirty) {
            changed.add(tasks[index].build());
        }
        dirty.clear();
        sink.save(snapshot, changed);
    }

    private Execution snapshot() {
        int runningCount = 0;
        int succeeded = 0;
        int failed = 0;
        int cancelled = 0;
        int retried = 0;
        for (int i = 0; i < tasks.length; i++) {
            switch (states[i]) {
                case RUNNING -> runningCount++;
                case SUCCEEDED -> succeeded++;
                case FAILED -> failed++;
                case CANCELLED -> cancelled++;
            }
            retried += tasks[i].getRetried();
        }
        execution.setRunningCount(runningCount)
                .setSucceededCount(succeeded)
                .setFailedCount(failed)
                .setCancelledCount(cancelled)
                .setRetriedCount(retried)
                .clearConditions();
        if (started != null) {
            execution.addConditions(started);
        }
        execution.addConditions(completed);
        if (containerReady != null) {
            execution.addConditions(containerReady);
        }
        if (resourcesAvailable != null) {
            execution.addConditions(resourcesAvailable);
        }
        return execution.build();
    }

    private int parallelism() {
        int parallelism = execution.getParallelism();
        return parallelism > 0 ? parallelism : Math.max(1, tasks.length);
    }

    private Instant createTime() {
        return execution.hasCreateTime() ? CloudRunJobTemplates.instant(execution.getCreateTime()) : clock.instant();
    }

    private boolean validIndex(int index) {
        return index >= 0 && index < tasks.length;
    }

    private static TaskState storedState(Task task) {
        for (Condition condition : task.getConditionsList()) {
            if ("Completed".equals(condition.getType())) {
                if (condition.getState() == Condition.State.CONDITION_SUCCEEDED) {
                    return TaskState.SUCCEEDED;
                }
                if (condition.getState() == Condition.State.CONDITION_FAILED) {
                    return task.getLastAttemptResult().getStatus().getCode() == Code.CANCELLED_VALUE
                            ? TaskState.CANCELLED
                            : TaskState.FAILED;
                }
            }
        }
        return TaskState.PENDING;
    }

    private static void setTaskCondition(Task.Builder task, Condition condition) {
        List<Condition> conditions = new ArrayList<>(task.getConditionsList());
        boolean replaced = false;
        for (int i = 0; i < conditions.size(); i++) {
            if (conditions.get(i).getType().equals(condition.getType())) {
                conditions.set(i, condition);
                replaced = true;
            }
        }
        if (!replaced) {
            conditions.add(condition);
        }
        task.clearConditions().addAllConditions(conditions);
    }

    private static TaskAttemptResult attemptResult(Status status, Integer exitCode) {
        TaskAttemptResult.Builder result = TaskAttemptResult.newBuilder().setStatus(status);
        if (exitCode != null) {
            result.setExitCode(exitCode);
        }
        return result.build();
    }

    private static Status status(int code, String message) {
        return Status.newBuilder().setCode(code).setMessage(message).build();
    }

    private static Timestamp ts(Instant instant) {
        return CloudRunJobTemplates.timestamp(instant);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private final class QueuedEvents implements Events {
        @Override
        public void prepared() {
            submit(new Prepared());
        }

        @Override
        public void prepareFailed(String message) {
            submit(new PrepareFailed(message));
        }

        @Override
        public void taskStarted(int index, int attempt) {
            submit(new TaskStarted(index, attempt));
        }

        @Override
        public void taskFinished(int index, int attempt, TaskOutcome outcome) {
            if (!submit(new TaskFinished(index, attempt, outcome))) {
                LOG.debugf("Dropped task outcome after the coordinator closed execution=%s task=%d outcome=%s",
                        name, index, outcome);
            }
        }
    }
}

package io.floci.gcp.services.cloudrun;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.api.LaunchStage;
import com.google.cloud.run.v2.CancelExecutionRequest;
import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.Execution;
import com.google.cloud.run.v2.ExecutionReference;
import com.google.cloud.run.v2.ExecutionTemplate;
import com.google.cloud.run.v2.Job;
import com.google.cloud.run.v2.ListExecutionsResponse;
import com.google.cloud.run.v2.ListJobsResponse;
import com.google.cloud.run.v2.ListTasksResponse;
import com.google.cloud.run.v2.RunJobRequest;
import com.google.cloud.run.v2.Task;
import com.google.cloud.run.v2.TaskTemplate;
import com.google.iam.v1.Policy;
import com.google.iam.v1.TestIamPermissionsResponse;
import com.google.longrunning.Operation;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.ContainerTeardown;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.cloudrun.CloudRunExecutionCoordinator.Events;
import io.floci.gcp.services.cloudrun.CloudRunExecutionCoordinator.TaskHandle;
import io.floci.gcp.services.cloudrun.CloudRunExecutionCoordinator.TaskOutcome;
import io.floci.gcp.services.cloudrun.CloudRunExecutionCoordinator.TaskRunner;
import io.floci.gcp.services.iam.IamPolicyCodec;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Cloud Run Admin API v2 Jobs, Executions and Tasks.
 *
 * <p>Job records are guarded by a per-job monitor: create, patch, delete and the execution-creating part of
 * {@code jobs:run} (and of start/run execution tokens) check and mutate the job under it, and the execution
 * coordinators take it only to refresh {@code latestCreatedExecution}. Execution and task records are mutated only
 * by their {@link CloudRunExecutionCoordinator}; see its invariant. A launch registers the execution's coordinator
 * before it persists the execution and task records, under the job monitor, and a coordinator is rebuilt from the
 * stored records only under the same monitor and only when none is registered. So a request that races a launch
 * always reaches the launching coordinator, and a rebuilt coordinator always sees a complete, terminal record set.
 * Lock order: the job monitor may be held while a coordinator is registered, but no thread waits on a coordinator
 * while holding a job monitor.
 *
 * <p>An execution deleted before it finished leaves a tombstone until its operations complete, so that startup
 * reconciliation can complete the run and delete operations of an execution whose record is already gone.
 */
@ApplicationScoped
public class CloudRunJobsService implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(CloudRunJobsService.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MOCK_RUN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ATTEMPT_OVERHEAD = Duration.ofSeconds(30);

    private final StorageBackend<String, String> jobStore;
    private final StorageBackend<String, String> executionStore;
    private final StorageBackend<String, String> taskStore;
    private final StorageBackend<String, String> tombstoneStore;
    private final LongRunningOperationsService operations;
    private final IamService iamService;
    private final EmulatorConfig config;
    private final TaskRunner dockerRunner;
    private final CloudRunJobsRuntime runtime;
    private final Clock clock;
    private final ConcurrentHashMap<String, CloudRunExecutionCoordinator> coordinators = new ConcurrentHashMap<>();
    private final Set<CloudRunExecutionCoordinator> live = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> jobLocks = new ConcurrentHashMap<>();
    /**
     * Jobs whose record is deleted but whose executions are still being cleaned up. A name is added under the job
     * monitor together with the record deletion and removed once cleanup ends, and create and upsert reject a name
     * in this set under the same monitor, so a recreated job never shares execution names with the old job's
     * cleanup.
     */
    private final Set<String> deletingJobs = ConcurrentHashMap.newKeySet();

    @Inject
    public CloudRunJobsService(StorageFactory storageFactory,
                               LongRunningOperationsService operations,
                               IamService iamService,
                               EmulatorConfig config,
                               CloudRunJobsRuntime runtime) {
        this(storageFactory.createGlobal("cloudrun-jobs", "cloudrun-jobs.json",
                        new TypeReference<Map<String, String>>() {}),
                storageFactory.createGlobal("cloudrun-executions", "cloudrun-executions.json",
                        new TypeReference<Map<String, String>>() {}),
                storageFactory.createGlobal("cloudrun-tasks", "cloudrun-tasks.json",
                        new TypeReference<Map<String, String>>() {}),
                storageFactory.createGlobal("cloudrun-execution-tombstones", "cloudrun-execution-tombstones.json",
                        new TypeReference<Map<String, String>>() {}),
                operations, iamService, config, runtime, runtime, Clock.systemUTC());
    }

    CloudRunJobsService(StorageBackend<String, String> jobStore,
                        StorageBackend<String, String> executionStore,
                        StorageBackend<String, String> taskStore,
                        StorageBackend<String, String> tombstoneStore,
                        LongRunningOperationsService operations,
                        IamService iamService,
                        EmulatorConfig config,
                        TaskRunner dockerRunner,
                        CloudRunJobsRuntime runtime,
                        Clock clock) {
        this.jobStore = jobStore;
        this.executionStore = executionStore;
        this.taskStore = taskStore;
        this.tombstoneStore = tombstoneStore;
        this.operations = operations;
        this.iamService = iamService;
        this.config = config;
        this.dockerRunner = dockerRunner;
        this.runtime = runtime;
        this.clock = clock;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.LIBRARY_AFTER + 100) StartupEvent event) {
        if (runtime != null && dockerMode()) {
            runtime.removeOrphanedContainers();
        }
        reconcileInterruptedExecutions();
    }

    @Override
    public void stopManagedContainers() {
        List<CompletableFuture<Void>> acks = new ArrayList<>();
        for (CloudRunExecutionCoordinator coordinator : List.copyOf(live)) {
            coordinator.shutdown().ifPresent(acks::add);
        }
        for (CompletableFuture<Void> ack : acks) {
            try {
                ack.get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException e) {
                LOG.warnf("Cloud Run execution coordinator did not stop cleanly: %s", e.getMessage());
            }
        }
        if (runtime != null && dockerMode()) {
            runtime.stopAll();
        }
    }

    // ── Jobs ────────────────────────────────────────────────────────────────

    public Operation createJob(String project, String location, String jobId, String body, boolean validateOnly) {
        Job requested = ProtoJson.merge(body, Job.newBuilder()).build();
        String id = firstPresent(jobId, CloudRunRuntimeService.lastSegment(requested.getName()));
        if (id == null) {
            throw GcpException.invalidArgument("jobId query parameter is required");
        }
        String parent = parent(project, location);
        String name = parent + "/jobs/" + id;
        Timestamp now = now();
        Job job = populateJob(requested, name, now);
        validateTemplate(job.getTemplate());
        validateExecutionTokens(job);
        LOG.infof("create Cloud Run job name=%s validateOnly=%s", name, validateOnly);
        Launch launch;
        synchronized (jobLock(name)) {
            rejectIfDeleting(name);
            if (jobStore.get(name).isPresent()) {
                throw GcpException.alreadyExists("Resource '" + id + "' already exists.");
            }
            if (validateOnly) {
                return operations.doneTransient(parent, job, job);
            }
            launch = storeAndLaunchToken(parent, job, "", "", now);
        }
        return awaitLaunch(launch);
    }

    public Job getJob(String name) {
        return findJob(name).orElseThrow(() -> CloudRunJobTemplates.notFound(CloudRunJobTemplates.KIND_JOB, name));
    }

    public ListJobsResponse listJobs(String project, String location, int pageSize, String pageToken) {
        String prefix = parent(project, location) + "/jobs/";
        List<Job> jobs = jobStore.scan(key -> key.startsWith(prefix)).stream()
                .map(CloudRunJobsService::parseJob)
                .sorted(Comparator.comparing(Job::getName))
                .toList();
        PageToken.Page<Job> page = PageToken.paginate(jobs, pageSize, pageToken);
        ListJobsResponse.Builder response = ListJobsResponse.newBuilder().addAllJobs(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    public Operation updateJob(String name, String body, boolean validateOnly, boolean allowMissing) {
        Job requested = ProtoJson.merge(body, Job.newBuilder()).build();
        String parent = parentOf(name);
        Timestamp now = now();
        Launch launch;
        synchronized (jobLock(name)) {
            Optional<Job> existing = findJob(name);
            if (existing.isEmpty()) {
                if (!allowMissing) {
                    throw CloudRunJobTemplates.notFound(CloudRunJobTemplates.KIND_JOB, name);
                }
                rejectIfDeleting(name);
                Job job = populateJob(requested, name, now);
                validateTemplate(job.getTemplate());
                validateExecutionTokens(job);
                LOG.infof("upsert Cloud Run job name=%s validateOnly=%s", name, validateOnly);
                if (validateOnly) {
                    return operations.doneTransient(parent, job, job);
                }
                launch = storeAndLaunchToken(parent, job, "", "", now);
            } else {
                Job current = existing.get();
                Job updated = applyUpdate(current, requested);
                validateTemplate(updated.getTemplate());
                validateExecutionTokens(updated);
                boolean tokenChanged = tokenChanged(current, updated);
                boolean specChanged = !spec(current).equals(spec(updated));
                if (specChanged || tokenChanged) {
                    updated = updated.toBuilder()
                            .setGeneration(current.getGeneration() + 1)
                            .setObservedGeneration(current.getGeneration() + 1)
                            .setUpdateTime(now)
                            .setEtag(UUID.randomUUID().toString())
                            .build();
                } else {
                    updated = current;
                }
                LOG.infof("update Cloud Run job name=%s changed=%s validateOnly=%s", name, specChanged || tokenChanged,
                        validateOnly);
                if (validateOnly) {
                    return operations.doneTransient(parent, updated, updated);
                }
                launch = storeAndLaunchToken(parent, updated, current.getStartExecutionToken(),
                        current.getRunExecutionToken(), now);
            }
        }
        return awaitLaunch(launch);
    }

    public Operation deleteJob(String name, boolean validateOnly) {
        Job existing = getJob(name);
        Timestamp now = now();
        Job deleted = deletedJob(existing, now);
        LOG.infof("delete Cloud Run job name=%s validateOnly=%s", name, validateOnly);
        if (validateOnly) {
            return operations.doneTransient(parentOf(name), deleted, deleted);
        }
        List<String> executionNames;
        synchronized (jobLock(name)) {
            Job current = getJob(name);
            deleted = deletedJob(current, now);
            iamService.deleteResourceAndPolicy(name, () -> jobStore.delete(name));
            String prefix = name + "/executions/";
            executionNames = List.copyOf(executionStore.keys()).stream()
                    .filter(key -> key.startsWith(prefix))
                    .toList();
            deletingJobs.add(name);
        }
        try {
            for (String executionName : executionNames) {
                try {
                    awaitCommand(withCoordinator(executionName, coordinator -> coordinator.delete(false)));
                } catch (GcpException e) {
                    if (e.getHttpStatus() != 404) {
                        throw e;
                    }
                    LOG.debugf("Execution already deleted execution=%s", executionName);
                }
            }
        } finally {
            deletingJobs.remove(name);
        }
        return operations.done(parentOf(name), deleted, deleted);
    }

    public Operation runJob(String name, String body) {
        RunJobRequest request = ProtoJson.merge(body, RunJobRequest.newBuilder()).build();
        String parent = parentOf(name);
        Timestamp now = now();
        LOG.infof("run Cloud Run job name=%s validateOnly=%s", name, request.getValidateOnly());
        if (request.getValidateOnly()) {
            Job job = getJob(name);
            ExecutionTemplate effective = effectiveTemplate(job, request);
            Execution preview = newExecution(job, CloudRunJobTemplates.randomExecutionId(
                    CloudRunRuntimeService.lastSegment(name), ThreadLocalRandom.current()), effective, now);
            return operations.doneTransient(parent, preview, preview);
        }
        Launch launch;
        synchronized (jobLock(name)) {
            // The job is read once, under the monitor that patches hold, so the execution always runs the
            // template of the job it is recorded against: a concurrent patch lands entirely before or after it.
            Job current = getJob(name);
            ExecutionTemplate effective = effectiveTemplate(current, request);
            Execution execution = newExecution(current, uniqueExecutionId(current), effective, now);
            Operation operation = operations.pending(parent, execution);
            Job stored = withExecution(current, execution);
            jobStore.put(name, ProtoJson.print(stored));
            launch = new Launch(operation.getName(), stored, launchLocked(execution, operation.getName(), List.of()));
        }
        return awaitLaunch(launch);
    }

    public Policy getIamPolicy(String resource) {
        getJob(resource);
        return IamPolicyCodec.toProtoPolicy(iamService.getPolicy(resource));
    }

    public Policy setIamPolicy(String resource, Policy policy) {
        getJob(resource);
        return IamPolicyCodec.toProtoPolicy(iamService.setPolicy(resource, IamPolicyCodec.toStoredPolicy(policy)));
    }

    public TestIamPermissionsResponse testIamPermissions(String resource, List<String> permissions) {
        TestIamPermissionsResponse.Builder response = TestIamPermissionsResponse.newBuilder();
        if (findJob(resource).isPresent()) {
            response.addAllPermissions(iamService.testPermissions(resource, permissions));
        }
        return response.build();
    }

    // ── Executions and tasks ────────────────────────────────────────────────

    public Execution getExecution(String name) {
        return executionStore.get(name)
                .map(CloudRunJobsService::parseExecution)
                .orElseThrow(() -> CloudRunJobTemplates.notFound(CloudRunJobTemplates.KIND_EXECUTION, name));
    }

    public ListExecutionsResponse listExecutions(String jobName, int pageSize, String pageToken) {
        String jobId = CloudRunRuntimeService.lastSegment(jobName);
        String prefix = "-".equals(jobId)
                ? jobName.substring(0, jobName.length() - 1)
                : jobName + "/executions/";
        if (!"-".equals(jobId)) {
            getJob(jobName);
        }
        List<Execution> executions = executionStore.scan(key -> key.startsWith(prefix)).stream()
                .map(CloudRunJobsService::parseExecution)
                .sorted(Comparator.comparing((Execution execution) -> CloudRunJobTemplates.instant(
                                execution.getCreateTime()))
                        .thenComparing(Execution::getName)
                        .reversed())
                .toList();
        PageToken.Page<Execution> page = PageToken.paginate(executions, pageSize, pageToken);
        ListExecutionsResponse.Builder response = ListExecutionsResponse.newBuilder()
                .addAllExecutions(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    public Operation deleteExecution(String name, boolean validateOnly) {
        LOG.infof("delete Cloud Run execution name=%s validateOnly=%s", name, validateOnly);
        if (validateOnly) {
            Execution existing = getExecution(name);
            Timestamp now = now();
            Execution preview = existing.toBuilder()
                    .setGeneration(existing.getGeneration() + 1)
                    .setUpdateTime(now)
                    .setDeleteTime(now)
                    .setExpireTime(plusSeconds(now, CloudRunJobTemplates.EXPIRE_AFTER_DELETE_SECONDS))
                    .build();
            return operations.doneTransient(parentOf(name), preview, preview);
        }
        String operation = awaitCommand(withCoordinator(name, coordinator -> coordinator.delete(true)));
        return operations.get(operation);
    }

    public Operation cancelExecution(String name, String body) {
        CancelExecutionRequest request = ProtoJson.merge(body, CancelExecutionRequest.newBuilder()).build();
        LOG.infof("cancel Cloud Run execution name=%s validateOnly=%s", name, request.getValidateOnly());
        if (request.getValidateOnly()) {
            Execution existing = getExecution(name);
            if (existing.hasCompletionTime()) {
                throw notRunning(name);
            }
            return operations.doneTransient(parentOf(name), existing, existing);
        }
        String operation = awaitCommand(withCoordinator(name, CloudRunExecutionCoordinator::cancel));
        return operations.get(operation);
    }

    public Task getTask(String name) {
        return taskStore.get(name)
                .map(CloudRunJobsService::parseTask)
                .orElseThrow(() -> CloudRunJobTemplates.notFound(CloudRunJobTemplates.KIND_TASK, name));
    }

    /**
     * Lists tasks under {@code projects/{p}/locations/{l}/jobs/{job}/executions/{execution}}, where the job and
     * execution segments may each be the {@code -} wildcard.
     */
    public ListTasksResponse listTasks(String executionName, int pageSize, String pageToken) {
        String[] parent = executionName.split("/");
        String jobId = parent[5];
        String executionId = parent[7];
        if (!"-".equals(jobId) && !"-".equals(executionId)) {
            getExecution(executionName);
        } else if (!"-".equals(jobId)) {
            getJob(String.join("/", List.of(parent).subList(0, 6)));
        }
        String locationPrefix = String.join("/", List.of(parent).subList(0, 4)) + "/jobs/";
        List<Task> tasks = taskStore.scan(key -> key.startsWith(locationPrefix)).stream()
                .map(CloudRunJobsService::parseTask)
                .filter(task -> matchesSegment(task.getName(), 5, jobId)
                        && matchesSegment(task.getName(), 7, executionId))
                .sorted(Comparator.comparing((Task task) -> executionPrefix(task.getName()))
                        .thenComparingInt(Task::getIndex))
                .toList();
        PageToken.Page<Task> page = PageToken.paginate(tasks, pageSize, pageToken);
        ListTasksResponse.Builder response = ListTasksResponse.newBuilder().addAllTasks(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    // ── Startup reconciliation ──────────────────────────────────────────────

    /**
     * Executions that were not terminal when the emulator stopped are failed by a coordinator created for each of
     * them, so the reconciliation write enters through the same queue as every other execution mutation.
     */
    void reconcileInterruptedExecutions() {
        for (String name : List.copyOf(executionStore.keys())) {
            Optional<Execution> stored = executionStore.get(name).map(CloudRunJobsService::parseExecution);
            if (stored.isEmpty() || stored.get().hasCompletionTime()) {
                continue;
            }
            Execution execution = stored.get();
            try {
                PendingOperations pendingOperations = pendingOperations(execution);
                CloudRunExecutionCoordinator coordinator;
                synchronized (jobLock(jobNameOf(name))) {
                    if (coordinators.containsKey(name)) {
                        continue;
                    }
                    coordinator = newCoordinator(execution, tasksOf(name), pendingOperations.run(),
                            pendingOperations.job(), pendingOperations.other());
                    register(coordinator);
                }
                coordinator.reconcile();
                coordinator.start();
                LOG.infof("Reconciling Cloud Run execution interrupted by an emulator restart execution=%s", name);
            } catch (RuntimeException e) {
                LOG.warnf(e, "Could not reconcile interrupted Cloud Run execution=%s", name);
            }
        }
        for (String name : List.copyOf(tombstoneStore.keys())) {
            try {
                completeDeletedExecution(name);
            } catch (RuntimeException e) {
                LOG.warnf(e, "Could not complete the operations of deleted Cloud Run execution=%s", name);
            }
        }
    }

    /**
     * Completes the run, delete and job operations of an execution that was deleted while it was running and whose
     * containers had not stopped when the emulator stopped. The execution record is already gone, so the
     * operations resolve like the delete path: the execution is reported cancelled.
     */
    private void completeDeletedExecution(String name) {
        Optional<Execution> tombstone = tombstoneStore.get(name).map(CloudRunJobsService::parseExecution);
        if (tombstone.isEmpty() || executionStore.get(name).isPresent() || coordinators.containsKey(name)) {
            tombstoneStore.delete(name);
            return;
        }
        Execution deleted = tombstone.get();
        Timestamp now = now();
        Execution cancelled = deleted.toBuilder()
                .setCompletionTime(now)
                .setReconciling(false)
                .setObservedGeneration(deleted.getGeneration())
                .setRunningCount(0)
                .setCancelledCount(deleted.getTaskCount() - deleted.getSucceededCount() - deleted.getFailedCount())
                .clearConditions()
                .addAllConditions(deleted.getConditionsList().stream()
                        .map(condition -> "Completed".equals(condition.getType())
                                ? CloudRunJobTemplates.condition("Completed", Condition.State.CONDITION_FAILED,
                                        CloudRunJobTemplates.CANCELLED_MESSAGE, now).toBuilder()
                                        .setExecutionReason(Condition.ExecutionReason.CANCELLED)
                                        .build()
                                : condition)
                        .toList())
                .build();
        PendingOperations pendingOperations = pendingOperations(deleted);
        new StoreSink(jobNameOf(name), pendingOperations.run(), pendingOperations.job(), pendingOperations.other())
                .finished(cancelled, null);
        LOG.infof("Completed the operations of a Cloud Run execution deleted before an emulator restart execution=%s",
                name);
    }

    private record PendingOperations(String run, List<String> job, List<String> other) {}

    private PendingOperations pendingOperations(Execution execution) {
        String run = null;
        List<String> job = new ArrayList<>();
        List<String> other = new ArrayList<>();
        String jobName = jobNameOf(execution.getName());
        String executionId = CloudRunRuntimeService.lastSegment(execution.getName());
        for (Operation operation : operations.list(parentOf(execution.getName()), 0, null).getOperationsList()) {
            if (operation.getDone() || !operation.hasMetadata()) {
                continue;
            }
            try {
                if (operation.getMetadata().is(Execution.class)) {
                    Execution metadata = operation.getMetadata().unpack(Execution.class);
                    if (!metadata.getName().equals(execution.getName())) {
                        continue;
                    }
                    if (run == null && metadata.getGeneration() == 1) {
                        run = operation.getName();
                    } else {
                        other.add(operation.getName());
                    }
                } else if (operation.getMetadata().is(Job.class)) {
                    Job metadata = operation.getMetadata().unpack(Job.class);
                    if (metadata.getName().equals(jobName)
                            && metadata.getLatestCreatedExecution().getName().equals(executionId)) {
                        job.add(operation.getName());
                    }
                }
            } catch (InvalidProtocolBufferException e) {
                LOG.debugf("Skipping operation with unreadable metadata operation=%s", operation.getName());
            }
        }
        return new PendingOperations(run, job, other);
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /** An execution started by a request, and the operation the request returns. */
    private record Launch(String operationName, Job job, CloudRunExecutionCoordinator coordinator) {}

    /**
     * Stores {@code job} and starts the execution requested by a start or run execution token when the token
     * differs from the previously stored one. Must hold the job monitor.
     */
    private Launch storeAndLaunchToken(String parent, Job job, String previousStartToken, String previousRunToken,
                                       Timestamp now) {
        String jobId = CloudRunRuntimeService.lastSegment(job.getName());
        String startToken = job.getStartExecutionToken();
        String runToken = job.getRunExecutionToken();
        boolean startTokenChanged = !startToken.isEmpty() && !startToken.equals(previousStartToken);
        boolean runTokenChanged = !runToken.isEmpty() && !runToken.equals(previousRunToken);
        String token = startTokenChanged ? startToken : runTokenChanged ? runToken : null;
        if (token == null || executionStore.get(job.getName() + "/executions/"
                + CloudRunJobTemplates.tokenExecutionId(jobId, token)).isPresent()) {
            jobStore.put(job.getName(), ProtoJson.print(job));
            return new Launch(operations.done(parent, job, job).getName(), job, null);
        }
        Execution execution = newExecution(job, CloudRunJobTemplates.tokenExecutionId(jobId, token),
                job.getTemplate(), now);
        Job stored = withExecution(job, execution);
        jobStore.put(job.getName(), ProtoJson.print(stored));
        if (startTokenChanged) {
            CloudRunExecutionCoordinator coordinator = launchLocked(execution, null, List.of());
            return new Launch(operations.done(parent, stored, stored).getName(), stored, coordinator);
        }
        Operation operation = operations.pending(parent, stored);
        CloudRunExecutionCoordinator coordinator = launchLocked(execution, null, List.of(operation.getName()));
        return new Launch(operation.getName(), stored, coordinator);
    }

    private static Job withExecution(Job job, Execution execution) {
        return job.toBuilder()
                .setExecutionCount(job.getExecutionCount() + 1)
                .setLatestCreatedExecution(reference(execution))
                .build();
    }

    /**
     * Registers the coordinator of a new execution, persists the execution with its tasks, then starts the
     * coordinator. Commands that reach the coordinator between registration and start wait in its queue and are
     * applied once the records exist. The job referencing the execution must already be stored. Must hold the job
     * monitor.
     */
    private CloudRunExecutionCoordinator launchLocked(Execution execution, String runOperation,
                                                      List<String> jobOperations) {
        List<Task> tasks = newTasks(execution);
        CloudRunExecutionCoordinator coordinator = newCoordinator(execution, tasks, runOperation, jobOperations,
                List.of());
        register(coordinator);
        try {
            executionStore.put(execution.getName(), ProtoJson.print(execution));
            for (Task task : tasks) {
                taskStore.put(task.getName(), ProtoJson.print(task));
            }
        } catch (RuntimeException e) {
            unregister(coordinator);
            throw e;
        }
        coordinator.begin();
        coordinator.start();
        return coordinator;
    }

    private Operation awaitLaunch(Launch launch) {
        if (launch.coordinator() != null && !dockerMode()) {
            try {
                launch.coordinator().finished().get(MOCK_RUN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw GcpException.unavailable("Interrupted while running Cloud Run job execution");
            } catch (ExecutionException | TimeoutException e) {
                LOG.warnf("Mock Cloud Run execution did not finish promptly: %s", e.getMessage());
            }
        }
        return operations.get(launch.operationName());
    }

    private CloudRunExecutionCoordinator newCoordinator(Execution execution, List<Task> tasks, String runOperation,
                                                        List<String> jobOperations, List<String> otherOperations) {
        return new CloudRunExecutionCoordinator(execution, tasks, runner(),
                new StoreSink(jobNameOf(execution.getName()), runOperation, jobOperations, otherOperations),
                clock, dockerMode() ? runBound(execution) : null, this::unregister);
    }

    /**
     * Registers {@code coordinator} for command routing and for shutdown. Must hold the job monitor, and the
     * execution must not be stored yet (a launch) or must have no registered coordinator (a rebuild or startup
     * reconciliation). A launch may therefore replace only the coordinator of a deleted execution with the same
     * name that is still stopping its containers; that one stays in {@link #live} until it closes, so shutdown
     * still reaches it.
     */
    private void register(CloudRunExecutionCoordinator coordinator) {
        live.add(coordinator);
        CloudRunExecutionCoordinator replaced = coordinators.put(coordinator.name(), coordinator);
        if (replaced != null) {
            LOG.debugf("Replacing the coordinator of a deleted execution with the same name execution=%s",
                    coordinator.name());
        }
    }

    private void unregister(CloudRunExecutionCoordinator coordinator) {
        coordinators.remove(coordinator.name(), coordinator);
        live.remove(coordinator);
    }

    /**
     * Applies {@code action} to the execution's coordinator, rebuilding one from the stored records when none is
     * registered. The rebuild holds the job monitor, so it cannot interleave with a launch, and it happens only when
     * no coordinator is registered, which means the stored records are complete and terminal. Retries when the
     * coordinator it found closed before accepting the command.
     */
    private <T> T withCoordinator(String executionName,
                                  Function<CloudRunExecutionCoordinator, Optional<T>> action) {
        while (true) {
            CloudRunExecutionCoordinator coordinator = coordinators.get(executionName);
            boolean created = false;
            if (coordinator == null) {
                synchronized (jobLock(jobNameOf(executionName))) {
                    coordinator = coordinators.get(executionName);
                    if (coordinator == null) {
                        coordinator = restoreCoordinator(executionName);
                        register(coordinator);
                        created = true;
                    }
                }
            }
            Optional<T> result = action.apply(coordinator);
            if (created) {
                coordinator.start();
            }
            if (result.isPresent()) {
                return result.get();
            }
        }
    }

    private CloudRunExecutionCoordinator restoreCoordinator(String executionName) {
        Execution execution = getExecution(executionName);
        try {
            return newCoordinator(execution, tasksOf(executionName), null, List.of(), List.of());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Stored Cloud Run execution has incomplete task records execution=%s: %s", executionName,
                    e.getMessage());
            throw GcpException.internal("Execution " + executionName + " has incomplete task records.");
        }
    }

    private static <T> T awaitCommand(CompletableFuture<T> future) {
        try {
            return future.get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GcpException.unavailable("Interrupted while waiting for Cloud Run execution");
        } catch (TimeoutException e) {
            throw GcpException.deadlineExceeded("Timed out waiting for Cloud Run execution");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof GcpException gcpException) {
                throw gcpException;
            }
            throw GcpException.internal(e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        }
    }

    private TaskRunner runner() {
        return dockerMode() ? dockerRunner : InstantTaskRunner.INSTANCE;
    }

    /**
     * Bounds a run: every task attempt may use its full timeout plus the SIGTERM grace, over all retries and all
     * waves of {@code parallelism}, plus the container startup grace for image preparation.
     */
    private Duration runBound(Execution execution) {
        Duration timeout = CloudRunJobTemplates.duration(execution.getTemplate().getTimeout());
        int attempts = execution.getTemplate().getMaxRetries() + 1;
        int parallelism = Math.max(1, execution.getParallelism());
        int waves = Math.max(1, (execution.getTaskCount() + parallelism - 1) / parallelism);
        Duration perAttempt = timeout.plus(cleanupTimeout()).plus(ATTEMPT_OVERHEAD);
        return perAttempt.multipliedBy((long) attempts * waves).plus(startupTimeout());
    }

    /** The job template with the request's overrides applied, validated for Docker mode. Performs no I/O. */
    private ExecutionTemplate effectiveTemplate(Job job, RunJobRequest request) {
        ExecutionTemplate effective = request.hasOverrides()
                ? CloudRunJobTemplates.applyOverrides(job.getTemplate(), request.getOverrides())
                : job.getTemplate();
        validateTemplate(effective);
        return effective;
    }

    private static void validateExecutionTokens(Job job) {
        String jobId = CloudRunRuntimeService.lastSegment(job.getName());
        CloudRunJobTemplates.validateExecutionToken("startExecutionToken", jobId, job.getStartExecutionToken());
        CloudRunJobTemplates.validateExecutionToken("runExecutionToken", jobId, job.getRunExecutionToken());
    }

    private void rejectIfDeleting(String jobName) {
        if (deletingJobs.contains(jobName)) {
            throw GcpException.aborted("Job '" + CloudRunRuntimeService.lastSegment(jobName)
                    + "' is being deleted.");
        }
    }

    private void validateTemplate(ExecutionTemplate template) {
        if (dockerMode()) {
            CloudRunRuntimeService.validateSupported(template.getTemplate().getContainersList(),
                    template.getTemplate().getVolumesList());
        }
    }

    private boolean dockerMode() {
        return config != null && !config.services().cloudrun().mock();
    }

    private Duration cleanupTimeout() {
        return config == null ? Duration.ofSeconds(15) : config.services().cloudrun().execution().cleanupTimeout();
    }

    private Duration startupTimeout() {
        return config == null ? Duration.ofSeconds(240) : config.services().cloudrun().execution().startupTimeout();
    }

    private Object jobLock(String jobName) {
        return jobLocks.computeIfAbsent(jobName, key -> new Object());
    }

    private Optional<Job> findJob(String name) {
        return jobStore.get(name).map(CloudRunJobsService::parseJob);
    }

    private List<Task> tasksOf(String executionName) {
        String prefix = executionName + "/tasks/";
        return taskStore.scan(key -> key.startsWith(prefix)).stream()
                .map(CloudRunJobsService::parseTask)
                .sorted(Comparator.comparingInt(Task::getIndex))
                .toList();
    }

    private String uniqueExecutionId(Job job) {
        String jobId = CloudRunRuntimeService.lastSegment(job.getName());
        while (true) {
            String id = CloudRunJobTemplates.randomExecutionId(jobId, ThreadLocalRandom.current());
            if (executionStore.get(job.getName() + "/executions/" + id).isEmpty()) {
                return id;
            }
        }
    }

    private static Job populateJob(Job requested, String name, Timestamp now) {
        Job.Builder builder = requested.toBuilder()
                .setName(name)
                .setUid(UUID.randomUUID().toString())
                .setGeneration(1)
                .setObservedGeneration(1)
                .setCreateTime(now)
                .setUpdateTime(now)
                .clearDeleteTime()
                .clearExpireTime()
                .clearCreator()
                .clearLastModifier()
                .clearSatisfiesPzs()
                .setTemplate(CloudRunJobTemplates.withDefaults(requested.getTemplate()))
                .setTerminalCondition(CloudRunJobTemplates.condition("Ready", Condition.State.CONDITION_SUCCEEDED,
                        null, now))
                .clearConditions()
                .setExecutionCount(0)
                .setLatestCreatedExecution(ExecutionReference.getDefaultInstance())
                .setReconciling(false)
                .setEtag(UUID.randomUUID().toString());
        if (builder.getLaunchStage() == LaunchStage.LAUNCH_STAGE_UNSPECIFIED) {
            builder.setLaunchStage(LaunchStage.GA);
        }
        return builder.build();
    }

    private static Job applyUpdate(Job existing, Job requested) {
        Job.Builder builder = existing.toBuilder()
                .clearLabels()
                .putAllLabels(requested.getLabelsMap())
                .clearAnnotations()
                .putAllAnnotations(requested.getAnnotationsMap())
                .setClient(requested.getClient())
                .setClientVersion(requested.getClientVersion())
                .setLaunchStage(requested.getLaunchStage() == LaunchStage.LAUNCH_STAGE_UNSPECIFIED
                        ? LaunchStage.GA
                        : requested.getLaunchStage())
                .setTemplate(CloudRunJobTemplates.withDefaults(requested.getTemplate()));
        if (requested.hasBinaryAuthorization()) {
            builder.setBinaryAuthorization(requested.getBinaryAuthorization());
        } else {
            builder.clearBinaryAuthorization();
        }
        builder.clearCreateExecution();
        if (requested.getCreateExecutionCase() == Job.CreateExecutionCase.START_EXECUTION_TOKEN) {
            builder.setStartExecutionToken(requested.getStartExecutionToken());
        } else if (requested.getCreateExecutionCase() == Job.CreateExecutionCase.RUN_EXECUTION_TOKEN) {
            builder.setRunExecutionToken(requested.getRunExecutionToken());
        }
        return builder.build();
    }

    private static boolean tokenChanged(Job current, Job updated) {
        return (!updated.getStartExecutionToken().isEmpty()
                && !updated.getStartExecutionToken().equals(current.getStartExecutionToken()))
                || (!updated.getRunExecutionToken().isEmpty()
                && !updated.getRunExecutionToken().equals(current.getRunExecutionToken()));
    }

    /** The user-controlled part of a job, compared to decide whether a patch changes anything. */
    private static Job spec(Job job) {
        Job.Builder builder = Job.newBuilder()
                .putAllLabels(job.getLabelsMap())
                .putAllAnnotations(job.getAnnotationsMap())
                .setClient(job.getClient())
                .setClientVersion(job.getClientVersion())
                .setLaunchStage(job.getLaunchStage())
                .setTemplate(job.getTemplate());
        if (job.hasBinaryAuthorization()) {
            builder.setBinaryAuthorization(job.getBinaryAuthorization());
        }
        return builder.build();
    }

    private static Job deletedJob(Job job, Timestamp now) {
        return job.toBuilder()
                .setGeneration(job.getGeneration() + 1)
                .setUpdateTime(now)
                .setDeleteTime(now)
                .setExpireTime(plusSeconds(now, CloudRunJobTemplates.EXPIRE_AFTER_DELETE_SECONDS))
                .build();
    }

    private static Execution newExecution(Job job, String executionId, ExecutionTemplate template, Timestamp now) {
        return Execution.newBuilder()
                .setName(job.getName() + "/executions/" + executionId)
                .setUid(UUID.randomUUID().toString())
                .setGeneration(1)
                .putAllLabels(template.getLabelsMap())
                .putAllAnnotations(template.getAnnotationsMap())
                .setCreateTime(now)
                .setUpdateTime(now)
                .setLaunchStage(job.getLaunchStage())
                .setJob(CloudRunRuntimeService.lastSegment(job.getName()))
                .setParallelism(CloudRunJobTemplates.effectiveParallelism(template))
                .setTaskCount(template.getTaskCount())
                .setTemplate(template.getTemplate())
                .setReconciling(true)
                .addConditions(CloudRunJobTemplates.condition("Completed", Condition.State.CONDITION_PENDING,
                        null, null))
                .setEtag(UUID.randomUUID().toString())
                .build();
    }

    private static List<Task> newTasks(Execution execution) {
        String executionId = CloudRunRuntimeService.lastSegment(execution.getName());
        TaskTemplate template = execution.getTemplate();
        List<Task> tasks = new ArrayList<>();
        for (int index = 0; index < execution.getTaskCount(); index++) {
            tasks.add(Task.newBuilder()
                    .setName(execution.getName() + "/tasks/" + CloudRunJobTemplates.taskId(executionId, index))
                    .setGeneration(1)
                    .setCreateTime(execution.getCreateTime())
                    .setUpdateTime(execution.getCreateTime())
                    .setJob(execution.getJob())
                    .setExecution(executionId)
                    .addAllContainers(template.getContainersList())
                    .addAllVolumes(template.getVolumesList())
                    .setMaxRetries(template.getMaxRetries())
                    .setTimeout(template.getTimeout())
                    .setServiceAccount(template.getServiceAccount())
                    .setIndex(index)
                    .setReconciling(true)
                    .addConditions(CloudRunJobTemplates.condition("Started", Condition.State.CONDITION_PENDING,
                            null, null))
                    .addConditions(CloudRunJobTemplates.condition("Completed", Condition.State.CONDITION_PENDING,
                            null, null))
                    .setEtag(UUID.randomUUID().toString())
                    .build());
        }
        return tasks;
    }

    static ExecutionReference reference(Execution execution) {
        ExecutionReference.Builder reference = ExecutionReference.newBuilder()
                .setName(CloudRunRuntimeService.lastSegment(execution.getName()))
                .setCreateTime(execution.getCreateTime())
                .setCompletionStatus(completionStatus(execution));
        if (execution.hasCompletionTime()) {
            reference.setCompletionTime(execution.getCompletionTime());
        }
        if (execution.hasDeleteTime()) {
            reference.setDeleteTime(execution.getDeleteTime());
        }
        return reference.build();
    }

    private static ExecutionReference.CompletionStatus completionStatus(Execution execution) {
        if (!execution.hasCompletionTime()) {
            return execution.hasStartTime()
                    ? ExecutionReference.CompletionStatus.EXECUTION_RUNNING
                    : ExecutionReference.CompletionStatus.EXECUTION_PENDING;
        }
        for (Condition condition : execution.getConditionsList()) {
            if ("Completed".equals(condition.getType())) {
                if (condition.getState() == Condition.State.CONDITION_SUCCEEDED) {
                    return ExecutionReference.CompletionStatus.EXECUTION_SUCCEEDED;
                }
                return condition.getExecutionReason() == Condition.ExecutionReason.CANCELLED
                        ? ExecutionReference.CompletionStatus.EXECUTION_CANCELLED
                        : ExecutionReference.CompletionStatus.EXECUTION_FAILED;
            }
        }
        return ExecutionReference.CompletionStatus.EXECUTION_FAILED;
    }

    private static GcpException notRunning(String executionName) {
        return GcpException.failedPrecondition("Execution '" + CloudRunRuntimeService.lastSegment(executionName)
                + "' cannot be cancelled because it is not running.");
    }

    private static boolean matchesSegment(String name, int index, String expected) {
        if ("-".equals(expected)) {
            return true;
        }
        String[] parts = name.split("/");
        return parts.length > index && parts[index].equals(expected);
    }

    private static String executionPrefix(String taskName) {
        int tasks = taskName.indexOf("/tasks/");
        return tasks < 0 ? taskName : taskName.substring(0, tasks);
    }

    private static String jobNameOf(String executionName) {
        int executions = executionName.indexOf("/executions/");
        return executions < 0 ? executionName : executionName.substring(0, executions);
    }

    private static String parentOf(String name) {
        int jobs = name.indexOf("/jobs/");
        return jobs < 0 ? name : name.substring(0, jobs);
    }

    private static String parent(String project, String location) {
        return "projects/" + project + "/locations/" + location;
    }

    private static String firstPresent(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private Timestamp now() {
        return CloudRunJobTemplates.timestamp(clock.instant());
    }

    private static Timestamp plusSeconds(Timestamp timestamp, long seconds) {
        return CloudRunJobTemplates.timestamp(CloudRunJobTemplates.instant(timestamp).plusSeconds(seconds));
    }

    private static Job parseJob(String json) {
        return ProtoJson.merge(json, Job.newBuilder()).build();
    }

    private static Execution parseExecution(String json) {
        return ProtoJson.merge(json, Execution.newBuilder()).build();
    }

    private static Task parseTask(String json) {
        return ProtoJson.merge(json, Task.newBuilder()).build();
    }

    /** Persists coordinator writes and resolves the operations that wait on the execution. */
    private final class StoreSink implements CloudRunExecutionCoordinator.Sink {
        private final String jobName;
        private final CloudRunOperationGuard runOperation;
        private final List<String> jobOperations;
        private final List<String> otherOperations;

        StoreSink(String jobName, String runOperation, List<String> jobOperations, List<String> otherOperations) {
            this.jobName = jobName;
            this.runOperation = runOperation == null ? null : new CloudRunOperationGuard(operations, runOperation);
            this.jobOperations = List.copyOf(jobOperations);
            this.otherOperations = List.copyOf(otherOperations);
        }

        @Override
        public void save(Execution execution, List<Task> changedTasks) {
            for (Task task : changedTasks) {
                taskStore.put(task.getName(), ProtoJson.print(task));
            }
            executionStore.put(execution.getName(), ProtoJson.print(execution));
            syncJobReference(execution);
        }

        @Override
        public void delete(Execution execution, List<Task> tasks) {
            for (Task task : tasks) {
                taskStore.delete(task.getName());
            }
            if (!execution.hasCompletionTime()) {
                tombstoneStore.put(execution.getName(), ProtoJson.print(execution));
            }
            executionStore.delete(execution.getName());
            syncJobReference(execution);
        }

        @Override
        public String startOperation(Execution metadata) {
            return operations.pending(parentOf(metadata.getName()), metadata).getName();
        }

        @Override
        public void completeOperation(String operationName, Execution execution) {
            operations.complete(operationName, execution, execution);
        }

        @Override
        public void finished(Execution execution, Status error) {
            Optional<Job> job = syncJobReference(execution);
            if (runOperation != null) {
                if (error == null) {
                    runOperation.complete(execution, execution);
                } else {
                    runOperation.fail(error, execution);
                }
            }
            for (String operation : otherOperations) {
                operations.complete(operation, execution, execution);
            }
            for (String operation : jobOperations) {
                if (job.isPresent()) {
                    operations.complete(operation, job.get(), job.get());
                } else {
                    operations.fail(operation, Status.newBuilder()
                            .setCode(Code.NOT_FOUND_VALUE)
                            .setMessage(CloudRunJobTemplates.notFoundMessage(CloudRunJobTemplates.KIND_JOB, jobName))
                            .build(), null);
                }
            }
            tombstoneStore.delete(execution.getName());
            LOG.infof("Cloud Run execution finished execution=%s error=%s", execution.getName(),
                    error == null ? "none" : error.getMessage());
        }

        private Optional<Job> syncJobReference(Execution execution) {
            synchronized (jobLock(jobName)) {
                Optional<Job> job = findJob(jobName);
                if (job.isEmpty()) {
                    return job;
                }
                Job current = job.get();
                String executionId = CloudRunRuntimeService.lastSegment(execution.getName());
                if (!current.getLatestCreatedExecution().getName().equals(executionId)) {
                    return job;
                }
                ExecutionReference reference = reference(execution);
                if (reference.equals(current.getLatestCreatedExecution())) {
                    return job;
                }
                Job updated = current.toBuilder().setLatestCreatedExecution(reference).build();
                jobStore.put(jobName, ProtoJson.print(updated));
                return Optional.of(updated);
            }
        }
    }

    /** Mock mode: every task attempt starts and exits 0 immediately. */
    static final class InstantTaskRunner implements TaskRunner {
        static final InstantTaskRunner INSTANCE = new InstantTaskRunner();

        @Override
        public void prepare(Execution execution, Events events) {
            events.prepared();
        }

        @Override
        public TaskHandle launch(Execution execution, Task task, int attempt, Events events) {
            events.taskStarted(task.getIndex(), attempt);
            events.taskFinished(task.getIndex(), attempt, new TaskOutcome.Exited(0));
            return () -> LOG.tracef("Mock task %s already finished", task.getName());
        }
    }
}

package io.floci.gcp.services.cloudrun;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.api.LaunchStage;
import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.ExecutionEnvironment;
import com.google.cloud.run.v2.InstanceSplit;
import com.google.cloud.run.v2.InstanceSplitAllocationType;
import com.google.cloud.run.v2.InstanceSplitStatus;
import com.google.cloud.run.v2.ListRevisionsResponse;
import com.google.cloud.run.v2.ListWorkerPoolsResponse;
import com.google.cloud.run.v2.ResourceRequirements;
import com.google.cloud.run.v2.Revision;
import com.google.cloud.run.v2.RevisionScalingStatus;
import com.google.cloud.run.v2.WorkerPool;
import com.google.cloud.run.v2.WorkerPoolRevisionTemplate;
import com.google.cloud.run.v2.WorkerPoolScaling;
import com.google.iam.v1.Policy;
import com.google.iam.v1.TestIamPermissionsResponse;
import com.google.longrunning.Operation;
import com.google.protobuf.Timestamp;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpResourceNames;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.cloudrun.CloudRunWorkerPoolRuntime.DesiredWorkers;
import io.floci.gcp.services.iam.IamPolicyCodec;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * Cloud Run v2 worker pools and their revisions.
 *
 * <p>Concurrency invariant: for a worker pool name {@code N}, the stored pool record, every revision record
 * under {@code N/revisions/}, and the pool's IAM policy deletion are only checked and mutated while holding
 * {@code lockFor(N)}. Create, patch (including the {@code allowMissing} upsert), delete, revision delete and
 * the reconciler's readiness publication all take that lock, so their checks and writes never interleave.
 * Replica containers of {@code N} are only started and stopped by {@code N}'s reconciler, a serial chain of
 * tasks per pool name. A reconcile task reads the desired state under the lock, releases it for the Docker
 * work, and reacquires it to publish readiness only when the pool's generation is still the one it
 * converged on. Every committed mutation enqueues a reconcile task after it, so the last task always
 * converges on the last committed state (no containers once the pool is gone). Lock order: pool lock, then
 * the IAM policy lock taken inside {@link IamService#deleteResourceAndPolicy}. No path waits on the
 * reconciler while holding the pool lock.
 */
@ApplicationScoped
public class CloudRunWorkerPoolsService {

    private static final Logger LOG = Logger.getLogger(CloudRunWorkerPoolsService.class);
    private static final String SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Duration DELETED_RETENTION = Duration.ofDays(30);
    private static final String DEFAULT_CPU = "1000m";
    private static final String DEFAULT_MEMORY = "512Mi";
    private static final String DEPLOYING_MESSAGE = "Deploying Revision.";
    private static final String PROVISIONING_MESSAGE = "Provisioning revision instances to process workloads.";
    private static final String RETIRED_MESSAGE = "Revision retired.";

    private final StorageBackend<String, String> workerPoolStore;
    private final StorageBackend<String, String> revisionStore;
    private final LongRunningOperationsService operations;
    private final IamService iamService;
    private final EmulatorConfig config;
    private final CloudRunWorkerPoolRuntime workerRuntime;
    private final Map<String, Object> poolLocks = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> reconcileTails = new ConcurrentHashMap<>();
    private final ExecutorService reconcileExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService operationTimeouts = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "cloudrun-worker-pool-timeout");
                thread.setDaemon(true);
                return thread;
            });

    @Inject
    public CloudRunWorkerPoolsService(StorageFactory storageFactory,
                                      LongRunningOperationsService operations,
                                      IamService iamService,
                                      EmulatorConfig config,
                                      CloudRunWorkerPoolRuntime workerRuntime) {
        this(storageFactory.createGlobal("cloudrun-worker-pools", "cloudrun-worker-pools.json",
                        new TypeReference<Map<String, String>>() {}),
                storageFactory.createGlobal("cloudrun-revisions", "cloudrun-revisions.json",
                        new TypeReference<Map<String, String>>() {}),
                operations, iamService, config, workerRuntime);
    }

    CloudRunWorkerPoolsService(StorageBackend<String, String> workerPoolStore,
                               StorageBackend<String, String> revisionStore,
                               LongRunningOperationsService operations,
                               IamService iamService,
                               EmulatorConfig config,
                               CloudRunWorkerPoolRuntime workerRuntime) {
        this.workerPoolStore = workerPoolStore;
        this.revisionStore = revisionStore;
        this.operations = operations;
        this.iamService = iamService;
        this.config = config;
        this.workerRuntime = workerRuntime;
    }

    @PreDestroy
    void shutdown() {
        operationTimeouts.shutdownNow();
        reconcileExecutor.shutdownNow();
    }

    public Operation createWorkerPool(String project, String location, String workerPoolId,
                                      String body, boolean validateOnly) {
        WorkerPool requested = ProtoJson.merge(body, WorkerPool.newBuilder()).build();
        String id = firstPresent(workerPoolId, GcpResourceNames.lastSegment(requested.getName()));
        if (id == null) {
            throw GcpException.invalidArgument("workerPoolId query parameter is required");
        }
        String name = parent(project, location) + "/workerPools/" + id;
        synchronized (lockFor(name)) {
            return createLocked(name, requested, validateOnly);
        }
    }

    public WorkerPool getWorkerPool(String name) {
        return workerPoolStore.get(name)
                .map(CloudRunWorkerPoolsService::parsePool)
                .orElseThrow(() -> GcpException.notFound("Cloud Run worker pool not found: " + name));
    }

    public ListWorkerPoolsResponse listWorkerPools(String project, String location, int pageSize, String pageToken) {
        String prefix = parent(project, location) + "/workerPools/";
        List<WorkerPool> pools = workerPoolStore.scan(key -> key.startsWith(prefix)).stream()
                .map(CloudRunWorkerPoolsService::parsePool)
                .sorted(Comparator.comparing(WorkerPool::getName))
                .toList();
        PageToken.Page<WorkerPool> page = PageToken.paginate(pools, pageSize, pageToken);
        ListWorkerPoolsResponse.Builder response = ListWorkerPoolsResponse.newBuilder()
                .addAllWorkerPools(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    public Operation updateWorkerPool(String name, String body, String updateMask, boolean validateOnly,
                                      boolean allowMissing, boolean forceNewRevision) {
        WorkerPool requested = ProtoJson.merge(body, WorkerPool.newBuilder()).build();
        synchronized (lockFor(name)) {
            WorkerPool existing = workerPoolStore.get(name).map(CloudRunWorkerPoolsService::parsePool).orElse(null);
            if (existing == null) {
                if (!allowMissing) {
                    throw GcpException.notFound("Cloud Run worker pool not found: " + name);
                }
                return createLocked(name, requested, validateOnly);
            }

            List<String> mask = updateMaskPaths(updateMask);
            Timestamp now = timestampNow();
            WorkerPool merged = applyDefaults(applyUpdate(existing, requested, mask));
            boolean templateChanged = !merged.getTemplate().equals(existing.getTemplate());
            boolean newRevision = templateChanged || forceNewRevision;
            if (executionEnabled() && newRevision) {
                validateSupported(merged);
            }

            WorkerPool.Builder builder = merged.toBuilder()
                    .setGeneration(existing.getGeneration() + 1)
                    .setObservedGeneration(existing.getGeneration() + 1)
                    .setUpdateTime(now)
                    .setEtag(newEtag());
            Revision revision = null;
            if (newRevision) {
                String revisionId = nextRevisionId(poolId(name), existing.getLatestCreatedRevision(),
                        ThreadLocalRandom.current());
                builder.setLatestCreatedRevision(name + "/revisions/" + revisionId);
                revision = buildRevision(builder.build(), name + "/revisions/" + revisionId, now);
            }
            validateSplits(name, builder.getInstanceSplitsList(), revision);
            builder.clearInstanceSplitStatuses()
                    .addAllInstanceSplitStatuses(splitStatuses(builder.getInstanceSplitsList(),
                            GcpResourceNames.lastSegment(builder.getLatestCreatedRevision())));
            WorkerPool updated = builder.build();

            LOG.infof("update Cloud Run worker pool name=%s updateMask=%s newRevision=%s validateOnly=%s",
                    name, updateMask, newRevision, validateOnly);
            if (validateOnly) {
                WorkerPool settled = settlePool(updated, now);
                return operations.doneTransient(parentFromName(name), settled, settled);
            }
            if (!executionEnabled()) {
                if (revision != null) {
                    revisionStore.put(revision.getName(), ProtoJson.print(revision));
                }
                WorkerPool settled = settleLocked(updated, now);
                return operations.done(parentFromName(name), settled, settled);
            }

            WorkerPool reconciling = reconcilingPool(updated, newRevision ? DEPLOYING_MESSAGE : PROVISIONING_MESSAGE,
                    now);
            workerPoolStore.put(name, ProtoJson.print(reconciling));
            if (revision != null) {
                revisionStore.put(revision.getName(), ProtoJson.print(pendingRevision(revision, now)));
            }
            return startReconcile(name, reconciling, null);
        }
    }

    public Operation deleteWorkerPool(String name, boolean validateOnly) {
        synchronized (lockFor(name)) {
            WorkerPool existing = getWorkerPool(name);
            Timestamp now = timestampNow();
            WorkerPool deleted = existing.toBuilder()
                    .setGeneration(existing.getGeneration() + 1)
                    .setUpdateTime(now)
                    .setDeleteTime(now)
                    .setExpireTime(plus(now, DELETED_RETENTION))
                    .build();
            LOG.infof("delete Cloud Run worker pool name=%s validateOnly=%s", name, validateOnly);
            if (validateOnly) {
                return operations.doneTransient(parentFromName(name), deleted, deleted);
            }
            iamService.deleteResourceAndPolicy(name, () -> {
                workerPoolStore.delete(name);
                String revisionPrefix = name + "/revisions/";
                revisionStore.keys().stream()
                        .filter(key -> key.startsWith(revisionPrefix))
                        .toList()
                        .forEach(revisionStore::delete);
            });
            if (!executionEnabled()) {
                return operations.done(parentFromName(name), deleted, deleted);
            }
            return startReconcile(name, deleted, deleted);
        }
    }

    public Revision getRevision(String name) {
        return revisionStore.get(name)
                .map(CloudRunWorkerPoolsService::parseRevision)
                .orElseThrow(() -> GcpException.notFound("Cloud Run revision not found: " + name));
    }

    public ListRevisionsResponse listRevisions(String poolName, int pageSize, String pageToken) {
        List<Revision> revisions = poolRevisions(poolName).stream()
                .sorted(Comparator.comparing(Revision::getName).reversed())
                .toList();
        PageToken.Page<Revision> page = PageToken.paginate(revisions, pageSize, pageToken);
        ListRevisionsResponse.Builder response = ListRevisionsResponse.newBuilder()
                .addAllRevisions(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    public Operation deleteRevision(String poolName, String revisionId, boolean validateOnly) {
        String revisionName = poolName + "/revisions/" + revisionId;
        synchronized (lockFor(poolName)) {
            Revision existing = getRevision(revisionName);
            WorkerPool pool = workerPoolStore.get(poolName).map(CloudRunWorkerPoolsService::parsePool).orElse(null);
            if (pool != null && servingRevisionIds(pool.getInstanceSplitStatusesList()).contains(revisionId)) {
                throw GcpException.failedPrecondition("Revision \"" + revisionId
                        + "\" cannot be directly deleted because it is actively serving.");
            }
            Timestamp now = timestampNow();
            Revision deleted = existing.toBuilder()
                    .setGeneration(existing.getGeneration() + 1)
                    .setObservedGeneration(existing.getGeneration() + 1)
                    .setUpdateTime(now)
                    .setDeleteTime(now)
                    .setExpireTime(plus(now, DELETED_RETENTION))
                    .build();
            LOG.infof("delete Cloud Run worker pool revision name=%s validateOnly=%s", revisionName, validateOnly);
            if (validateOnly) {
                return operations.doneTransient(parentFromName(poolName), deleted, deleted);
            }
            revisionStore.delete(revisionName);
            return operations.done(parentFromName(poolName), deleted, deleted);
        }
    }

    public Policy getIamPolicy(String resource) {
        return IamPolicyCodec.toProtoPolicy(iamService.getPolicy(resource));
    }

    public Policy setIamPolicy(String resource, Policy policy) {
        return IamPolicyCodec.toProtoPolicy(iamService.setPolicy(resource, IamPolicyCodec.toStoredPolicy(policy)));
    }

    public TestIamPermissionsResponse testIamPermissions(String resource, List<String> permissions) {
        return TestIamPermissionsResponse.newBuilder()
                .addAllPermissions(iamService.testPermissions(resource, permissions))
                .build();
    }

    private Operation createLocked(String name, WorkerPool requested, boolean validateOnly) {
        String id = poolId(name);
        if (workerPoolStore.get(name).isPresent()) {
            throw GcpException.alreadyExists("Resource '" + id + "' already exists.");
        }
        Timestamp now = timestampNow();
        String revisionName = name + "/revisions/" + nextRevisionId(id, null, ThreadLocalRandom.current());
        WorkerPool.Builder builder = applyDefaults(requested).toBuilder()
                .setName(name)
                .setUid(UUID.randomUUID().toString())
                .setGeneration(1)
                .setObservedGeneration(1)
                .setCreateTime(now)
                .setUpdateTime(now)
                .clearDeleteTime()
                .clearExpireTime()
                .clearConditions()
                .clearLatestReadyRevision()
                .setLatestCreatedRevision(revisionName)
                .setEtag(newEtag());
        Revision revision = buildRevision(builder.build(), revisionName, now);
        if (executionEnabled()) {
            validateSupported(builder.build());
        }
        validateSplits(name, builder.getInstanceSplitsList(), revision);
        builder.clearInstanceSplitStatuses()
                .addAllInstanceSplitStatuses(splitStatuses(builder.getInstanceSplitsList(),
                        GcpResourceNames.lastSegment(revisionName)));
        WorkerPool pool = builder.build();

        LOG.infof("create Cloud Run worker pool name=%s validateOnly=%s", name, validateOnly);
        if (validateOnly) {
            WorkerPool settled = settlePool(pool, now);
            return operations.doneTransient(parentFromName(name), settled, settled);
        }
        if (!executionEnabled()) {
            revisionStore.put(revisionName, ProtoJson.print(revision));
            WorkerPool settled = settleLocked(pool, now);
            return operations.done(parentFromName(name), settled, settled);
        }

        WorkerPool reconciling = reconcilingPool(pool, DEPLOYING_MESSAGE, now);
        workerPoolStore.put(name, ProtoJson.print(reconciling));
        revisionStore.put(revisionName, ProtoJson.print(pendingRevision(revision, now)));
        return startReconcile(name, reconciling, null);
    }

    private Operation startReconcile(String name, WorkerPool metadata, WorkerPool fixedResponse) {
        Operation operation = operations.pending(parentFromName(name), metadata);
        CloudRunOperationGuard guard = new CloudRunOperationGuard(operations, operation.getName());
        Duration timeout = config.services().cloudrun().execution().operationTimeout();
        operationTimeouts.schedule(() -> {
            if (!guard.isTerminal()) {
                LOG.warnf("Cloud Run worker pool operation timed out operation=%s timeout=%s",
                        guard.operationName(), timeout);
                guard.fail(Status.newBuilder()
                        .setCode(Code.DEADLINE_EXCEEDED_VALUE)
                        .setMessage("Cloud Run operation timed out")
                        .build(), metadata);
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        enqueueReconcile(name, () -> reconcile(name, guard, metadata, fixedResponse));
        return operation;
    }

    private void enqueueReconcile(String name, Runnable task) {
        CompletableFuture<Void> next = reconcileTails.compute(name, (key, tail) -> {
            CompletableFuture<Void> previous = tail == null ? CompletableFuture.completedFuture(null) : tail;
            return previous.handle((ignored, error) -> null).thenRunAsync(task, reconcileExecutor);
        });
        next.whenComplete((ignored, error) -> reconcileTails.remove(name, next));
    }

    private void reconcile(String name, CloudRunOperationGuard guard, WorkerPool metadata, WorkerPool fixedResponse) {
        DesiredWorkers desired = null;
        try {
            desired = desiredWorkers(name);
            workerRuntime.apply(desired);
            WorkerPool published = publishReady(name, desired.generation());
            WorkerPool response = fixedResponse != null ? fixedResponse : published != null ? published : metadata;
            guard.complete(response, response);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            LOG.warnf(e, "Cloud Run worker pool reconcile failed name=%s", name);
            WorkerPool failed = desired == null ? null : publishFailure(name, desired.generation(), message);
            WorkerPool response = fixedResponse != null ? fixedResponse : failed != null ? failed : metadata;
            guard.fail(Status.newBuilder()
                    .setCode(Code.INTERNAL_VALUE)
                    .setMessage(message)
                    .build(), response);
        }
    }

    private DesiredWorkers desiredWorkers(String name) {
        synchronized (lockFor(name)) {
            String project = nameSegment(name, 1);
            String location = nameSegment(name, 3);
            WorkerPool pool = workerPoolStore.get(name).map(CloudRunWorkerPoolsService::parsePool).orElse(null);
            if (pool == null) {
                return new DesiredWorkers(project, location, name, -1, null, 0);
            }
            String servingId = servingRevision(pool.getInstanceSplitStatusesList());
            Revision revision = servingId == null
                    ? null
                    : revisionStore.get(name + "/revisions/" + servingId)
                            .map(CloudRunWorkerPoolsService::parseRevision)
                            .orElse(null);
            return new DesiredWorkers(project, location, name, pool.getGeneration(), revision,
                    pool.getScaling().getManualInstanceCount());
        }
    }

    private WorkerPool publishReady(String name, long generation) {
        synchronized (lockFor(name)) {
            WorkerPool pool = workerPoolStore.get(name).map(CloudRunWorkerPoolsService::parsePool).orElse(null);
            if (pool == null || pool.getGeneration() != generation) {
                return pool;
            }
            return settleLocked(pool, timestampNow());
        }
    }

    private WorkerPool publishFailure(String name, long generation, String message) {
        synchronized (lockFor(name)) {
            WorkerPool pool = workerPoolStore.get(name).map(CloudRunWorkerPoolsService::parsePool).orElse(null);
            if (pool == null || pool.getGeneration() != generation) {
                return pool;
            }
            Timestamp now = timestampNow();
            WorkerPool failed = pool.toBuilder()
                    .setTerminalCondition(condition("Ready", Condition.State.CONDITION_FAILED, message, now))
                    .setReconciling(false)
                    .build();
            workerPoolStore.put(name, ProtoJson.print(failed));
            revisionStore.get(pool.getLatestCreatedRevision())
                    .map(CloudRunWorkerPoolsService::parseRevision)
                    .filter(Revision::getReconciling)
                    .ifPresent(revision -> revisionStore.put(revision.getName(), ProtoJson.print(revision.toBuilder()
                            .clearConditions()
                            .addConditions(condition("Ready", Condition.State.CONDITION_FAILED, message, now))
                            .setReconciling(false)
                            .build())));
            return failed;
        }
    }

    /**
     * Marks the pool ready and brings each of its stored revisions to the serving or retired state. Caller
     * holds the pool lock.
     */
    private WorkerPool settleLocked(WorkerPool pool, Timestamp now) {
        WorkerPool settled = settlePool(pool, now);
        Set<String> serving = servingRevisionIds(settled.getInstanceSplitStatusesList());
        int desiredCount = settled.getScaling().getManualInstanceCount();
        for (Revision revision : poolRevisions(settled.getName())) {
            String revisionId = GcpResourceNames.lastSegment(revision.getName());
            Revision next = serving.contains(revisionId)
                    ? servingRevision(revision, desiredCount, now)
                    : retiredRevision(revision, now);
            if (!next.equals(revision)) {
                revisionStore.put(next.getName(), ProtoJson.print(next));
            }
        }
        workerPoolStore.put(settled.getName(), ProtoJson.print(settled));
        return settled;
    }

    private static WorkerPool settlePool(WorkerPool pool, Timestamp now) {
        return pool.toBuilder()
                .setObservedGeneration(pool.getGeneration())
                .setTerminalCondition(condition("Ready", Condition.State.CONDITION_SUCCEEDED, "", now))
                .clearConditions()
                .setReconciling(false)
                .setLatestReadyRevision(pool.getLatestCreatedRevision())
                .build();
    }

    private static WorkerPool reconcilingPool(WorkerPool pool, String message, Timestamp now) {
        return pool.toBuilder()
                .setTerminalCondition(condition("Ready", Condition.State.CONDITION_RECONCILING, message, now))
                .clearConditions()
                .setReconciling(true)
                .build();
    }

    private static Revision pendingRevision(Revision revision, Timestamp now) {
        return revision.toBuilder()
                .clearConditions()
                .addConditions(condition("Ready", Condition.State.CONDITION_PENDING, "", now))
                .setReconciling(true)
                .build();
    }

    private static Revision servingRevision(Revision revision, int desiredCount, Timestamp now) {
        Revision.Builder builder = revision.toBuilder()
                .setReconciling(false)
                .setScalingStatus(RevisionScalingStatus.newBuilder().setDesiredMinInstanceCount(desiredCount));
        if (!revision.getReconciling() && isActive(revision)) {
            return builder.build();
        }
        String elapsed = elapsedSeconds(revision.getCreateTime(), now);
        return builder.clearConditions()
                .addConditions(condition("Ready", Condition.State.CONDITION_SUCCEEDED,
                        "Deploying revision succeeded in " + elapsed + ".", now))
                .addConditions(condition("Active", Condition.State.CONDITION_SUCCEEDED, "", now).toBuilder()
                        .setSeverity(Condition.Severity.INFO))
                .addConditions(condition("ResourcesAvailable", Condition.State.CONDITION_SUCCEEDED,
                        "Provisioning imported containers completed.", now))
                .addConditions(condition("ContainerReady", Condition.State.CONDITION_SUCCEEDED,
                        "Container image import completed.", now))
                .addConditions(condition("MinInstancesProvisioned", Condition.State.CONDITION_SUCCEEDED,
                        "Min instances provisioned successfully  in " + elapsed + ".", now))
                .build();
    }

    private static Revision retiredRevision(Revision revision, Timestamp now) {
        if (isRetired(revision)) {
            return revision;
        }
        Condition ready = findCondition(revision, "Ready");
        String readyMessage = ready == null || ready.getState() != Condition.State.CONDITION_SUCCEEDED
                ? ""
                : ready.getMessage();
        Condition containerReady = findCondition(revision, "ContainerReady");
        Condition minInstances = findCondition(revision, "MinInstancesProvisioned");
        Revision.Builder builder = revision.toBuilder()
                .setReconciling(false)
                .clearScalingStatus()
                .clearConditions()
                .addConditions(retired(condition("Ready", Condition.State.CONDITION_SUCCEEDED, readyMessage, now)))
                .addConditions(retired(condition("Active", Condition.State.CONDITION_FAILED, RETIRED_MESSAGE, now)
                        .toBuilder().setSeverity(Condition.Severity.INFO).build()))
                .addConditions(retired(condition("ResourcesAvailable", Condition.State.CONDITION_RECONCILING,
                        RETIRED_MESSAGE, now)))
                .addConditions(containerReady != null ? containerReady
                        : condition("ContainerReady", Condition.State.CONDITION_SUCCEEDED,
                        "Container image import completed.", now));
        if (minInstances != null) {
            builder.addConditions(minInstances);
        }
        return builder.build();
    }

    private static Condition retired(Condition condition) {
        return condition.toBuilder().setRevisionReason(Condition.RevisionReason.RETIRED).build();
    }

    private static boolean isActive(Revision revision) {
        Condition active = findCondition(revision, "Active");
        return active != null && active.getState() == Condition.State.CONDITION_SUCCEEDED;
    }

    private static boolean isRetired(Revision revision) {
        Condition active = findCondition(revision, "Active");
        return active != null && active.getState() == Condition.State.CONDITION_FAILED
                && active.getRevisionReason() == Condition.RevisionReason.RETIRED;
    }

    private static Condition findCondition(Revision revision, String type) {
        for (Condition condition : revision.getConditionsList()) {
            if (condition.getType().equals(type)) {
                return condition;
            }
        }
        return null;
    }

    private List<Revision> poolRevisions(String poolName) {
        String prefix = poolName + "/revisions/";
        return revisionStore.scan(key -> key.startsWith(prefix)).stream()
                .map(CloudRunWorkerPoolsService::parseRevision)
                .toList();
    }

    private void validateSplits(String poolName, List<InstanceSplit> splits, Revision pendingRevision) {
        int total = 0;
        for (InstanceSplit split : splits) {
            if (split.getPercent() < 0) {
                throw GcpException.invalidArgument("Instance split percent must not be negative.");
            }
            total += split.getPercent();
            if (allocationType(split) == InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_REVISION) {
                String revisionId = GcpResourceNames.lastSegment(split.getRevision());
                boolean pending = pendingRevision != null
                        && GcpResourceNames.lastSegment(pendingRevision.getName()).equals(revisionId);
                if (!pending && revisionStore.get(poolName + "/revisions/" + revisionId).isEmpty()) {
                    throw GcpException.invalidArgument("Revision " + revisionId + " referenced by instanceSplits "
                            + "does not exist in worker pool " + poolId(poolName) + ".");
                }
            }
        }
        if (total != 100) {
            throw GcpException.invalidArgument("Instance split percentages must add up to 100, got " + total + ".");
        }
    }

    private void validateSupported(WorkerPool pool) {
        CloudRunRuntimeService.validateSupported(pool.getTemplate().getContainersList(),
                pool.getTemplate().getVolumesList());
    }

    private Object lockFor(String name) {
        return poolLocks.computeIfAbsent(name, key -> new Object());
    }

    private boolean executionEnabled() {
        return !config.services().cloudrun().mock();
    }

    /**
     * Next revision id {@code {pool}-{5-digit counter}-{3 random lowercase alphanumerics}}, counting on from
     * {@code previousRevision} (a full or short revision name; null or foreign names start at 00001).
     */
    static String nextRevisionId(String poolId, String previousRevision, RandomGenerator random) {
        int next = revisionCounter(poolId, previousRevision) + 1;
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            suffix.append(SUFFIX_ALPHABET.charAt(random.nextInt(SUFFIX_ALPHABET.length())));
        }
        return poolId + "-" + String.format(Locale.ROOT, "%05d", next) + "-" + suffix;
    }

    static int revisionCounter(String poolId, String revisionName) {
        if (revisionName == null || revisionName.isBlank()) {
            return 0;
        }
        String id = GcpResourceNames.lastSegment(revisionName);
        String prefix = poolId + "-";
        if (!id.startsWith(prefix)) {
            return 0;
        }
        String rest = id.substring(prefix.length());
        int dash = rest.indexOf('-');
        String counter = dash < 0 ? rest : rest.substring(0, dash);
        if (counter.isEmpty() || !counter.chars().allMatch(Character::isDigit)) {
            return 0;
        }
        try {
            return Integer.parseInt(counter);
        } catch (NumberFormatException e) {
            LOG.debugf("Cloud Run worker pool revision counter out of range revision=%s", revisionName);
            return 0;
        }
    }

    /**
     * {@code instanceSplitStatuses} for {@code splits}: a LATEST split names {@code latestRevisionId}, a
     * REVISION split names its own revision, always as a short revision id.
     */
    static List<InstanceSplitStatus> splitStatuses(List<InstanceSplit> splits, String latestRevisionId) {
        List<InstanceSplitStatus> statuses = new ArrayList<>();
        for (InstanceSplit split : splits) {
            InstanceSplitAllocationType type = allocationType(split);
            String revision = type == InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST
                    ? latestRevisionId
                    : GcpResourceNames.lastSegment(split.getRevision());
            statuses.add(InstanceSplitStatus.newBuilder()
                    .setType(type)
                    .setRevision(revision)
                    .setPercent(split.getPercent())
                    .build());
        }
        return statuses;
    }

    /**
     * The revision that runs containers: the one with the largest percent in {@code statuses}, the first
     * one on ties, or null when no revision receives instances.
     */
    static String servingRevision(List<InstanceSplitStatus> statuses) {
        String serving = null;
        int best = 0;
        for (InstanceSplitStatus status : statuses) {
            if (status.getPercent() > best && !status.getRevision().isBlank()) {
                best = status.getPercent();
                serving = status.getRevision();
            }
        }
        return serving;
    }

    static Set<String> servingRevisionIds(List<InstanceSplitStatus> statuses) {
        return statuses.stream()
                .map(InstanceSplitStatus::getRevision)
                .filter(revision -> !revision.isBlank())
                .collect(Collectors.toSet());
    }

    private static InstanceSplitAllocationType allocationType(InstanceSplit split) {
        if (split.getType() == InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_UNSPECIFIED
                || split.getType() == InstanceSplitAllocationType.UNRECOGNIZED) {
            return split.getRevision().isBlank()
                    ? InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST
                    : InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_REVISION;
        }
        return split.getType();
    }

    static WorkerPool applyDefaults(WorkerPool pool) {
        WorkerPool.Builder builder = pool.toBuilder().clearCustomAudiences();
        if (builder.getLaunchStage() == LaunchStage.LAUNCH_STAGE_UNSPECIFIED) {
            builder.setLaunchStage(LaunchStage.GA);
        }
        if (!builder.getScaling().hasManualInstanceCount()) {
            builder.setScaling(builder.getScaling().toBuilder().setManualInstanceCount(1));
        }
        if (builder.getInstanceSplitsCount() == 0) {
            builder.addInstanceSplits(InstanceSplit.newBuilder()
                    .setType(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST)
                    .setPercent(100));
        }
        WorkerPoolRevisionTemplate.Builder template = builder.getTemplate().toBuilder().clearContainers();
        for (Container container : builder.getTemplate().getContainersList()) {
            ResourceRequirements.Builder resources = container.getResources().toBuilder();
            if (!resources.containsLimits("cpu")) {
                resources.putLimits("cpu", DEFAULT_CPU);
            }
            if (!resources.containsLimits("memory")) {
                resources.putLimits("memory", DEFAULT_MEMORY);
            }
            template.addContainers(container.toBuilder().setResources(resources));
        }
        return builder.setTemplate(template).build();
    }

    static Revision buildRevision(WorkerPool pool, String revisionName, Timestamp now) {
        WorkerPoolRevisionTemplate template = pool.getTemplate();
        Revision.Builder builder = Revision.newBuilder()
                .setName(revisionName)
                .setUid(UUID.randomUUID().toString())
                .setGeneration(1)
                .setObservedGeneration(1)
                .setCreateTime(now)
                .setUpdateTime(now)
                .setLaunchStage(pool.getLaunchStage())
                .putAllLabels(template.getLabelsMap())
                .putAllAnnotations(template.getAnnotationsMap())
                .setServiceAccount(template.getServiceAccount())
                .addAllVolumes(template.getVolumesList())
                .setExecutionEnvironment(ExecutionEnvironment.EXECUTION_ENVIRONMENT_GEN2)
                .setEncryptionKey(template.getEncryptionKey())
                .setEncryptionKeyRevocationAction(template.getEncryptionKeyRevocationAction())
                .setEtag(newEtag());
        List<Container> containers = template.getContainersList();
        for (int i = 0; i < containers.size(); i++) {
            Container container = containers.get(i);
            if (container.getName().isBlank()) {
                container = container.toBuilder()
                        .setName(imageBaseName(container.getImage()) + "-" + (i + 1))
                        .build();
            }
            builder.addContainers(container);
        }
        if (template.hasVpcAccess()) {
            builder.setVpcAccess(template.getVpcAccess());
        }
        if (template.hasServiceMesh()) {
            builder.setServiceMesh(template.getServiceMesh());
        }
        if (template.hasEncryptionKeyShutdownDuration()) {
            builder.setEncryptionKeyShutdownDuration(template.getEncryptionKeyShutdownDuration());
        }
        if (template.hasNodeSelector()) {
            builder.setNodeSelector(template.getNodeSelector());
        }
        if (template.hasGpuZonalRedundancyDisabled()) {
            builder.setGpuZonalRedundancyDisabled(template.getGpuZonalRedundancyDisabled());
        }
        return builder.build();
    }

    static String imageBaseName(String image) {
        String withoutDigest = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
        String lastPath = withoutDigest.substring(withoutDigest.lastIndexOf('/') + 1);
        int tag = lastPath.indexOf(':');
        return tag < 0 ? lastPath : lastPath.substring(0, tag);
    }

    private static WorkerPool applyUpdate(WorkerPool existing, WorkerPool requested, List<String> mask) {
        boolean replaceAll = mask.isEmpty();
        WorkerPool.Builder builder = existing.toBuilder();
        if (replaceAll || masked(mask, "description")) {
            builder.setDescription(requested.getDescription());
        }
        if (replaceAll || masked(mask, "labels")) {
            builder.clearLabels().putAllLabels(requested.getLabelsMap());
        }
        if (replaceAll || masked(mask, "annotations")) {
            builder.clearAnnotations().putAllAnnotations(requested.getAnnotationsMap());
        }
        if (replaceAll || masked(mask, "client")) {
            builder.setClient(requested.getClient());
        }
        if (replaceAll || masked(mask, "client_version")) {
            builder.setClientVersion(requested.getClientVersion());
        }
        if (replaceAll || masked(mask, "launch_stage")) {
            builder.setLaunchStage(requested.getLaunchStage());
        }
        if (replaceAll || masked(mask, "binary_authorization")) {
            if (requested.hasBinaryAuthorization()) {
                builder.setBinaryAuthorization(requested.getBinaryAuthorization());
            } else {
                builder.clearBinaryAuthorization();
            }
        }
        if ((replaceAll && requested.hasTemplate()) || masked(mask, "template")) {
            builder.setTemplate(requested.getTemplate());
        }
        if (replaceAll || masked(mask, "instance_splits")) {
            builder.clearInstanceSplits().addAllInstanceSplits(requested.getInstanceSplitsList());
        }
        if (replaceAll || masked(mask, "scaling")) {
            builder.setScaling(requested.hasScaling() ? requested.getScaling() : WorkerPoolScaling.getDefaultInstance());
        }
        return builder.build();
    }

    private static List<String> updateMaskPaths(String updateMask) {
        if (updateMask == null || updateMask.isBlank()) {
            return List.of();
        }
        return Arrays.stream(updateMask.split(","))
                .map(String::trim)
                .filter(path -> !path.isBlank())
                .map(CloudRunWorkerPoolsService::snakeCase)
                .toList();
    }

    private static boolean masked(List<String> mask, String path) {
        return mask.stream().anyMatch(entry -> entry.equals(path) || entry.startsWith(path + "."));
    }

    private static String snakeCase(String path) {
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (Character.isUpperCase(c)) {
                normalized.append('_').append(Character.toLowerCase(c));
            } else {
                normalized.append(c);
            }
        }
        return normalized.toString();
    }

    private static Condition condition(String type, Condition.State state, String message, Timestamp now) {
        return Condition.newBuilder()
                .setType(type)
                .setState(state)
                .setMessage(message)
                .setLastTransitionTime(now)
                .build();
    }

    private static String elapsedSeconds(Timestamp from, Timestamp to) {
        long nanos = (to.getSeconds() - from.getSeconds()) * 1_000_000_000L + (to.getNanos() - from.getNanos());
        return String.format(Locale.ROOT, "%.2fs", Math.max(0, nanos) / 1_000_000_000.0);
    }

    private static WorkerPool parsePool(String json) {
        return ProtoJson.merge(json, WorkerPool.newBuilder()).build();
    }

    private static Revision parseRevision(String json) {
        return ProtoJson.merge(json, Revision.newBuilder()).build();
    }

    private static String firstPresent(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static String newEtag() {
        return UUID.randomUUID().toString();
    }

    private static Timestamp timestampNow() {
        Instant now = Instant.now();
        return Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
    }

    private static Timestamp plus(Timestamp timestamp, Duration duration) {
        return timestamp.toBuilder().setSeconds(timestamp.getSeconds() + duration.toSeconds()).build();
    }

    private static String parent(String project, String location) {
        return "projects/" + project + "/locations/" + location;
    }

    private static String parentFromName(String name) {
        int workerPools = name.indexOf("/workerPools/");
        return workerPools < 0 ? name : name.substring(0, workerPools);
    }

    private static String poolId(String poolName) {
        return GcpResourceNames.lastSegment(poolName);
    }

    private static String nameSegment(String name, int index) {
        String[] parts = name.split("/");
        return parts.length > index ? parts[index] : "";
    }
}

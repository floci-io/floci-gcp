package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.InstanceSplit;
import com.google.cloud.run.v2.InstanceSplitAllocationType;
import com.google.cloud.run.v2.InstanceSplitStatus;
import com.google.cloud.run.v2.Revision;
import com.google.cloud.run.v2.WorkerPool;
import com.google.cloud.run.v2.WorkerPoolScaling;
import com.google.longrunning.Operation;
import com.google.protobuf.Message;
import com.google.rpc.Status;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.cloudrun.CloudRunWorkerPoolRuntime.DesiredWorkers;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudRunWorkerPoolsServiceTest {

    private static final Pattern REVISION_ID = Pattern.compile("floci-wp-\\d{5}-[a-z0-9]{3}");
    private static final String PARENT = "projects/p/locations/l";
    private static final String POOL = PARENT + "/workerPools/wp";
    private static final String REVISION = POOL + "/revisions/wp-00001-abc";
    private static final String POOL_BODY = "{\"template\":{\"containers\":[{\"image\":\"busybox\"}]}}";
    private static final long AWAIT_SECONDS = 10;

    @Test
    void restartFailsPendingOperationsAndStartsReplicasOfStoredPools() {
        LongRunningOperationsService operations = operations();
        CloudRunWorkerPoolRuntime runtime = mock(CloudRunWorkerPoolRuntime.class);
        String leftoverRevision = "projects/p/locations/gone/workerPools/old/revisions/old-00001-xyz";
        when(runtime.removeLeftoverContainers()).thenReturn(Set.of(leftoverRevision));
        InMemoryStorage<String, String> pools = new InMemoryStorage<>();
        InMemoryStorage<String, String> revisions = new InMemoryStorage<>();
        pools.put(POOL, ProtoJson.print(reconcilingPool()));
        revisions.put(REVISION, ProtoJson.print(Revision.newBuilder().setName(REVISION).setReconciling(true).build()));
        Operation poolOperation = operations.pending(PARENT, reconcilingPool());
        Operation leftoverOperation = operations.pending("projects/p/locations/gone",
                WorkerPool.newBuilder().setName("projects/p/locations/gone/workerPools/old").build());
        Operation otherOperation = operations.pending(PARENT, Revision.newBuilder().setName(REVISION).build());
        CloudRunWorkerPoolsService service = new CloudRunWorkerPoolsService(pools, revisions, operations, null,
                config(false), runtime);

        try {
            service.recoverAfterRestart();

            for (Operation operation : List.of(poolOperation, leftoverOperation)) {
                Operation failed = operations.get(operation.getName());
                assertTrue(failed.getDone(), failed.toString());
                assertEquals(10, failed.getError().getCode());
                assertEquals("The emulator restarted before the operation completed.", failed.getError().getMessage());
            }
            assertFalse(operations.get(otherOperation.getName()).getDone());
            verify(runtime, timeout(5000)).apply(argThat(desired -> desired.revision() != null
                    && desired.revision().getName().equals(REVISION) && desired.requestedCount() == 1));
            WorkerPool recovered = awaitSettled(service);
            assertEquals(2, recovered.getObservedGeneration());
            assertEquals(Condition.State.CONDITION_SUCCEEDED, recovered.getTerminalCondition().getState());
            assertEquals(REVISION, recovered.getLatestReadyRevision());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void restartInMockModeSettlesReconcilingPoolsWithoutDocker() {
        LongRunningOperationsService operations = operations();
        CloudRunWorkerPoolRuntime runtime = mock(CloudRunWorkerPoolRuntime.class);
        InMemoryStorage<String, String> pools = new InMemoryStorage<>();
        InMemoryStorage<String, String> revisions = new InMemoryStorage<>();
        pools.put(POOL, ProtoJson.print(reconcilingPool()));
        revisions.put(REVISION, ProtoJson.print(Revision.newBuilder().setName(REVISION).setReconciling(true).build()));
        Operation poolOperation = operations.pending(PARENT, reconcilingPool());
        CloudRunWorkerPoolsService service = new CloudRunWorkerPoolsService(pools, revisions, operations, null,
                config(true), runtime);

        try {
            service.recoverAfterRestart();

            assertEquals(10, operations.get(poolOperation.getName()).getError().getCode());
            WorkerPool settled = service.getWorkerPool(POOL);
            assertFalse(settled.getReconciling());
            assertEquals(2, settled.getObservedGeneration());
            assertFalse(service.getRevision(REVISION).getReconciling());
            verify(runtime, never()).removeLeftoverContainers();
            verify(runtime, never()).apply(any());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void reconcileSupersededByPatchDoesNotMarkNewerGenerationReady() throws Exception {
        RecordingOperations operations = new RecordingOperations();
        ApplyGates gates = new ApplyGates(2);
        CloudRunWorkerPoolsService service = dockerService(operations, new InMemoryStorage<>(),
                new InMemoryStorage<>(), gates.runtime());

        try {
            Operation create = service.createWorkerPool("p", "l", "wp", POOL_BODY, false);
            assertEquals(1, gates.awaitEntered(0).generation());
            String revision = service.getWorkerPool(POOL).getLatestCreatedRevision();

            Operation patch = service.updateWorkerPool(POOL, scaleTo(2), "scaling", false, false, false);
            gates.release(0);
            DesiredWorkers second = gates.awaitEntered(1);

            assertEquals(2, second.generation());
            assertEquals(2, second.requestedCount());
            Operation createDone = operations.awaitDone(create.getName());
            assertFalse(createDone.hasError(), createDone.toString());
            assertStillReconciling(createDone.getResponse().unpack(WorkerPool.class), 2, 0);
            WorkerPool reconciling = service.getWorkerPool(POOL);
            assertStillReconciling(reconciling, 2, 0);
            assertEquals("", reconciling.getLatestReadyRevision());
            assertTrue(service.getRevision(revision).getReconciling());
            assertFalse(operations.get(patch.getName()).getDone());

            gates.release(1);
            Operation patchDone = operations.awaitDone(patch.getName());

            assertFalse(patchDone.hasError(), patchDone.toString());
            assertReady(patchDone.getResponse().unpack(WorkerPool.class), 2);
            assertReady(service.getWorkerPool(POOL), 2);
            assertFalse(service.getRevision(revision).getReconciling());
            assertEquals(2, gates.calls());
        } finally {
            gates.releaseAll();
            service.shutdown();
        }
    }

    @Test
    void reconcileSupersededByDeleteLeavesPoolGoneAndStopsReplicas() throws Exception {
        RecordingOperations operations = new RecordingOperations();
        ApplyGates gates = new ApplyGates(2);
        InMemoryStorage<String, String> pools = new InMemoryStorage<>();
        InMemoryStorage<String, String> revisions = new InMemoryStorage<>();
        CloudRunWorkerPoolsService service = dockerService(operations, pools, revisions, gates.runtime());

        try {
            Operation create = service.createWorkerPool("p", "l", "wp", POOL_BODY, false);
            assertEquals(1, gates.awaitEntered(0).generation());

            Operation delete = service.deleteWorkerPool(POOL, false);
            gates.release(0);
            DesiredWorkers stop = gates.awaitEntered(1);

            assertEquals(POOL, stop.poolName());
            assertNull(stop.revision());
            assertEquals(0, stop.requestedCount());
            Operation createDone = operations.awaitDone(create.getName());
            assertFalse(createDone.hasError(), createDone.toString());
            assertTrue(createDone.getResponse().unpack(WorkerPool.class).getReconciling());
            assertPoolGone(service, pools, revisions);
            assertFalse(operations.get(delete.getName()).getDone());

            gates.release(1);
            Operation deleteDone = operations.awaitDone(delete.getName());

            assertFalse(deleteDone.hasError(), deleteDone.toString());
            WorkerPool deleted = deleteDone.getResponse().unpack(WorkerPool.class);
            assertEquals(POOL, deleted.getName());
            assertEquals(2, deleted.getGeneration());
            assertTrue(deleted.hasDeleteTime());
            assertPoolGone(service, pools, revisions);
            assertEquals(2, gates.calls());
        } finally {
            gates.releaseAll();
            service.shutdown();
        }
    }

    @Test
    void reconcileSupersededByAnotherPatchDoesNotMarkLaterGenerationReady() throws Exception {
        RecordingOperations operations = new RecordingOperations();
        ApplyGates gates = new ApplyGates(3);
        CloudRunWorkerPoolsService service = dockerService(operations, new InMemoryStorage<>(),
                new InMemoryStorage<>(), gates.runtime());

        try {
            Operation create = service.createWorkerPool("p", "l", "wp", POOL_BODY, false);
            gates.awaitEntered(0);
            gates.release(0);
            assertFalse(operations.awaitDone(create.getName()).hasError());
            assertReady(service.getWorkerPool(POOL), 1);

            Operation first = service.updateWorkerPool(POOL, scaleTo(2), "scaling", false, false, false);
            assertEquals(2, gates.awaitEntered(1).generation());
            Operation second = service.updateWorkerPool(POOL, scaleTo(3), "scaling", false, false, false);
            gates.release(1);
            DesiredWorkers third = gates.awaitEntered(2);

            assertEquals(3, third.generation());
            assertEquals(3, third.requestedCount());
            Operation firstDone = operations.awaitDone(first.getName());
            assertFalse(firstDone.hasError(), firstDone.toString());
            assertStillReconciling(firstDone.getResponse().unpack(WorkerPool.class), 3, 1);
            assertStillReconciling(service.getWorkerPool(POOL), 3, 1);
            assertFalse(operations.get(second.getName()).getDone());

            gates.release(2);
            Operation secondDone = operations.awaitDone(second.getName());

            assertFalse(secondDone.hasError(), secondDone.toString());
            assertReady(secondDone.getResponse().unpack(WorkerPool.class), 3);
            WorkerPool ready = service.getWorkerPool(POOL);
            assertReady(ready, 3);
            assertEquals(3, ready.getScaling().getManualInstanceCount());
            assertEquals(3, gates.calls());
        } finally {
            gates.releaseAll();
            service.shutdown();
        }
    }

    private static void assertStillReconciling(WorkerPool pool, long generation, long observedGeneration) {
        assertTrue(pool.getReconciling(), pool.toString());
        assertEquals(generation, pool.getGeneration(), pool.toString());
        assertEquals(observedGeneration, pool.getObservedGeneration(), pool.toString());
        assertEquals(Condition.State.CONDITION_RECONCILING, pool.getTerminalCondition().getState(), pool.toString());
    }

    private static void assertReady(WorkerPool pool, long generation) {
        assertFalse(pool.getReconciling(), pool.toString());
        assertEquals(generation, pool.getGeneration(), pool.toString());
        assertEquals(generation, pool.getObservedGeneration(), pool.toString());
        assertEquals(Condition.State.CONDITION_SUCCEEDED, pool.getTerminalCondition().getState(), pool.toString());
        assertEquals(pool.getLatestCreatedRevision(), pool.getLatestReadyRevision(), pool.toString());
    }

    private static void assertPoolGone(CloudRunWorkerPoolsService service, InMemoryStorage<String, String> pools,
                                       InMemoryStorage<String, String> revisions) {
        GcpException notFound = assertThrows(GcpException.class, () -> service.getWorkerPool(POOL));
        assertEquals(404, notFound.getHttpStatus());
        assertTrue(pools.keys().isEmpty(), pools.keys().toString());
        assertTrue(revisions.keys().isEmpty(), revisions.keys().toString());
    }

    private static String scaleTo(int instances) {
        return "{\"scaling\":{\"manualInstanceCount\":" + instances + "}}";
    }

    private static CloudRunWorkerPoolsService dockerService(LongRunningOperationsService operations,
                                                            InMemoryStorage<String, String> pools,
                                                            InMemoryStorage<String, String> revisions,
                                                            CloudRunWorkerPoolRuntime runtime) {
        IamService iamService = mock(IamService.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(iamService).deleteResourceAndPolicy(anyString(), any());
        return new CloudRunWorkerPoolsService(pools, revisions, operations, iamService, config(false), runtime);
    }

    private static WorkerPool awaitSettled(CloudRunWorkerPoolsService service) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        WorkerPool pool = service.getWorkerPool(POOL);
        while (pool.getReconciling() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the pool to settle", e);
            }
            pool = service.getWorkerPool(POOL);
        }
        assertFalse(pool.getReconciling(), pool.toString());
        return pool;
    }

    private static WorkerPool reconcilingPool() {
        return WorkerPool.newBuilder()
                .setName(POOL)
                .setGeneration(2)
                .setObservedGeneration(1)
                .setReconciling(true)
                .setTerminalCondition(Condition.newBuilder()
                        .setType("Ready")
                        .setState(Condition.State.CONDITION_RECONCILING))
                .setScaling(WorkerPoolScaling.newBuilder().setManualInstanceCount(1))
                .setLatestCreatedRevision(REVISION)
                .addInstanceSplits(InstanceSplit.newBuilder()
                        .setType(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST)
                        .setPercent(100))
                .addInstanceSplitStatuses(InstanceSplitStatus.newBuilder()
                        .setType(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST)
                        .setRevision("wp-00001-abc")
                        .setPercent(100))
                .build();
    }

    private static LongRunningOperationsService operations() {
        return new LongRunningOperationsService(inMemoryStorageFactory());
    }

    private static StorageFactory inMemoryStorageFactory() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.createGlobal(anyString(), anyString(), any())).thenReturn(new InMemoryStorage<>());
        return storageFactory;
    }

    private static EmulatorConfig config(boolean mock) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudrun().mock()).thenReturn(mock);
        when(config.services().cloudrun().execution().operationTimeout()).thenReturn(Duration.ofMinutes(5));
        return config;
    }

    /**
     * Operations service that lets a test wait for an operation to finish without polling.
     */
    private static final class RecordingOperations extends LongRunningOperationsService {

        private final Map<String, CountDownLatch> finished = new ConcurrentHashMap<>();

        RecordingOperations() {
            super(inMemoryStorageFactory());
        }

        @Override
        public Operation complete(String name, Message response, Message metadata) {
            Operation operation = super.complete(name, response, metadata);
            finishedLatch(name).countDown();
            return operation;
        }

        @Override
        public Operation fail(String name, Status error, Message metadata) {
            Operation operation = super.fail(name, error, metadata);
            finishedLatch(name).countDown();
            return operation;
        }

        Operation awaitDone(String name) throws InterruptedException {
            assertTrue(finishedLatch(name).await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "Operation did not finish: " + name);
            return get(name);
        }

        private CountDownLatch finishedLatch(String name) {
            return finished.computeIfAbsent(name, key -> new CountDownLatch(1));
        }
    }

    /**
     * Mocked runtime whose n-th {@code apply} call records its desired state, signals that it started, and
     * blocks until the test releases it, so a test decides exactly where each reconcile task pauses.
     */
    private static final class ApplyGates {

        private final List<Gate> gates = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        ApplyGates(int expectedCalls) {
            for (int i = 0; i < expectedCalls; i++) {
                gates.add(new Gate(new CountDownLatch(1), new CountDownLatch(1), new AtomicReference<>()));
            }
        }

        CloudRunWorkerPoolRuntime runtime() {
            CloudRunWorkerPoolRuntime runtime = mock(CloudRunWorkerPoolRuntime.class);
            doAnswer(invocation -> {
                hold(invocation.getArgument(0));
                return null;
            }).when(runtime).apply(any());
            return runtime;
        }

        DesiredWorkers awaitEntered(int call) throws InterruptedException {
            Gate gate = gates.get(call);
            assertTrue(gate.entered().await(AWAIT_SECONDS, TimeUnit.SECONDS), "apply call " + call + " never started");
            return gate.desired().get();
        }

        void release(int call) {
            gates.get(call).released().countDown();
        }

        void releaseAll() {
            for (Gate gate : gates) {
                gate.released().countDown();
            }
        }

        int calls() {
            return calls.get();
        }

        private void hold(DesiredWorkers desired) throws InterruptedException {
            int call = calls.getAndIncrement();
            if (call >= gates.size()) {
                throw new AssertionError("Unexpected apply call " + call + ": " + desired);
            }
            Gate gate = gates.get(call);
            gate.desired().set(desired);
            gate.entered().countDown();
            if (!gate.released().await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("apply call " + call + " was never released");
            }
        }

        private record Gate(CountDownLatch entered, CountDownLatch released,
                            AtomicReference<DesiredWorkers> desired) {}
    }

    @Test
    void firstRevisionIdStartsCounterAtOneWithThreeCharacterSuffix() {
        String id = CloudRunWorkerPoolsService.nextRevisionId("floci-wp", null, new Random(1));

        assertTrue(REVISION_ID.matcher(id).matches(), id);
        assertTrue(id.startsWith("floci-wp-00001-"), id);
    }

    @Test
    void nextRevisionIdCountsOnFromFullOrShortPreviousName() {
        String fromFull = CloudRunWorkerPoolsService.nextRevisionId("floci-wp",
                "projects/p/locations/l/workerPools/floci-wp/revisions/floci-wp-00002-jw2", new Random(2));
        String fromShort = CloudRunWorkerPoolsService.nextRevisionId("floci-wp", "floci-wp-00041-abc",
                new Random(3));

        assertTrue(fromFull.startsWith("floci-wp-00003-"), fromFull);
        assertTrue(fromShort.startsWith("floci-wp-00042-"), fromShort);
    }

    @Test
    void revisionCounterIgnoresNamesOfOtherPools() {
        assertEquals(0, CloudRunWorkerPoolsService.revisionCounter("wp", "other-00007-abc"));
        assertEquals(0, CloudRunWorkerPoolsService.revisionCounter("wp", "wp-abcde-xyz"));
        assertEquals(7, CloudRunWorkerPoolsService.revisionCounter("wp", "wp-00007-xyz"));
    }

    @Test
    void revisionSuffixUsesOnlyLowercaseAlphanumerics() {
        Random random = new Random(42);
        for (int i = 0; i < 500; i++) {
            String id = CloudRunWorkerPoolsService.nextRevisionId("floci-wp", null, random);
            assertTrue(REVISION_ID.matcher(id).matches(), id);
        }
    }

    @Test
    void latestSplitStatusNamesTheConcreteRevision() {
        List<InstanceSplitStatus> statuses = CloudRunWorkerPoolsService.splitStatuses(List.of(
                InstanceSplit.newBuilder()
                        .setType(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST)
                        .setPercent(100)
                        .build()), "floci-wp-00003-jlb");

        assertEquals(1, statuses.size());
        assertEquals(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST, statuses.get(0).getType());
        assertEquals("floci-wp-00003-jlb", statuses.get(0).getRevision());
        assertEquals(100, statuses.get(0).getPercent());
    }

    @Test
    void revisionSplitStatusUsesShortRevisionNameAndServingRevisionHasLargestPercent() {
        List<InstanceSplitStatus> statuses = CloudRunWorkerPoolsService.splitStatuses(List.of(
                InstanceSplit.newBuilder()
                        .setType(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_REVISION)
                        .setRevision("projects/p/locations/l/workerPools/wp/revisions/wp-00001-bdg")
                        .setPercent(30)
                        .build(),
                InstanceSplit.newBuilder()
                        .setType(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST)
                        .setPercent(70)
                        .build()), "wp-00002-jw2");

        assertEquals("wp-00001-bdg", statuses.get(0).getRevision());
        assertEquals(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_REVISION, statuses.get(0).getType());
        assertEquals("wp-00002-jw2", statuses.get(1).getRevision());
        assertEquals("wp-00002-jw2", CloudRunWorkerPoolsService.servingRevision(statuses));
        assertEquals(2, CloudRunWorkerPoolsService.servingRevisionIds(statuses).size());
    }

    @Test
    void servingRevisionPrefersFirstOnTiesAndIsNullWithoutInstances() {
        List<InstanceSplitStatus> tied = List.of(
                InstanceSplitStatus.newBuilder().setRevision("wp-00001-aaa").setPercent(50).build(),
                InstanceSplitStatus.newBuilder().setRevision("wp-00002-bbb").setPercent(50).build());

        assertEquals("wp-00001-aaa", CloudRunWorkerPoolsService.servingRevision(tied));
        assertNull(CloudRunWorkerPoolsService.servingRevision(List.of()));
    }

    @Test
    void untypedSplitIsInferredFromRevisionPresence() {
        List<InstanceSplitStatus> statuses = CloudRunWorkerPoolsService.splitStatuses(List.of(
                InstanceSplit.newBuilder().setPercent(100).build()), "wp-00004-abc");

        assertEquals(InstanceSplitAllocationType.INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST, statuses.get(0).getType());
        assertEquals("wp-00004-abc", statuses.get(0).getRevision());
    }

    @Test
    void imageBaseNameStripsRegistryTagAndDigest() {
        assertEquals("busybox", CloudRunWorkerPoolsService.imageBaseName("docker.io/library/busybox"));
        assertEquals("busybox", CloudRunWorkerPoolsService.imageBaseName("busybox:1.36"));
        assertEquals("app", CloudRunWorkerPoolsService.imageBaseName("localhost:5000/team/app:v1"));
        assertEquals("busybox", CloudRunWorkerPoolsService.imageBaseName(
                "mirror.gcr.io/library/busybox@sha256:f97baa533a26513c453a362be331a43eb60214302f449c16571b23cea141dce4"));
    }
}

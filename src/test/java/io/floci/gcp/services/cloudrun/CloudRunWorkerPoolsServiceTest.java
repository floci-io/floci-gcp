package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.InstanceSplit;
import com.google.cloud.run.v2.InstanceSplitAllocationType;
import com.google.cloud.run.v2.InstanceSplitStatus;
import com.google.cloud.run.v2.Revision;
import com.google.cloud.run.v2.WorkerPool;
import com.google.cloud.run.v2.WorkerPoolScaling;
import com.google.longrunning.Operation;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
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
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.createGlobal(anyString(), anyString(), any())).thenReturn(new InMemoryStorage<>());
        return new LongRunningOperationsService(storageFactory);
    }

    private static EmulatorConfig config(boolean mock) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudrun().mock()).thenReturn(mock);
        return config;
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

package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.iam.model.StoredPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class IamBucketLifecycleServiceTest {

    private static final String BUCKET = "concurrent-lifecycle-bucket";
    private static final String RESOURCE = "buckets/" + BUCKET;

    @Test
    void deleteWinningRaceRejectsTheWaitingPolicyWrite() throws Exception {
        AtomicBoolean bucketExists = new AtomicBoolean(true);
        InMemoryStorage<String, StoredPolicy> policyStore = new InMemoryStorage<>();
        IamService iamService = iamService(policyStore, bucketExists);
        IamBucketLifecycleService lifecycleService = lifecycleService(iamService);
        iamService.setPolicy(RESOURCE, policy("roles/storage.admin"));
        CountDownLatch bucketDeleted = new CountDownLatch(1);
        CountDownLatch allowDeleteToFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> deletion = executor.submit(() -> lifecycleService.deleteBucketIfEmpty(BUCKET, () -> {
                bucketExists.set(false);
                bucketDeleted.countDown();
                await(allowDeleteToFinish);
                return true;
            }));
            assertTrue(bucketDeleted.await(5, TimeUnit.SECONDS));

            CountDownLatch writerStarted = new CountDownLatch(1);
            Future<?> writer = executor.submit(() -> {
                writerStarted.countDown();
                iamService.setPolicy(RESOURCE, policy("roles/storage.objectViewer"));
            });
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
            assertBlocked(writer);

            allowDeleteToFinish.countDown();
            assertTrue(deletion.get(5, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> writer.get(5, TimeUnit.SECONDS));
            assertInstanceOf(GcpException.class, failure.getCause());
            assertEquals(404, ((GcpException) failure.getCause()).getHttpStatus());
            assertTrue(policyStore.get("policy:" + RESOURCE).isEmpty());
        } finally {
            allowDeleteToFinish.countDown();
            shutdown(executor);
        }
    }

    @Test
    void policyWriteWinningRaceCompletesBeforeBucketDeletion() throws Exception {
        AtomicBoolean bucketExists = new AtomicBoolean(true);
        BlockingPolicyStorage policyStore = new BlockingPolicyStorage();
        IamService iamService = iamService(policyStore, bucketExists);
        IamBucketLifecycleService lifecycleService = lifecycleService(iamService);
        iamService.setPolicy(RESOURCE, policy("roles/storage.admin"));
        policyStore.blockNextPut();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(
                    () -> iamService.setPolicy(RESOURCE, policy("roles/storage.objectViewer")));
            assertTrue(policyStore.putStarted.await(5, TimeUnit.SECONDS));

            CountDownLatch deletionStarted = new CountDownLatch(1);
            Future<Boolean> deletion = executor.submit(() -> {
                deletionStarted.countDown();
                return lifecycleService.deleteBucketIfEmpty(BUCKET, () -> {
                    bucketExists.set(false);
                    return true;
                });
            });
            assertTrue(deletionStarted.await(5, TimeUnit.SECONDS));
            assertBlocked(deletion);

            policyStore.allowPut.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertTrue(deletion.get(5, TimeUnit.SECONDS));
            assertTrue(policyStore.get("policy:" + RESOURCE).isEmpty());
        } finally {
            policyStore.allowPut.countDown();
            shutdown(executor);
        }
    }

    @Test
    void createWinningRaceCompletesBeforeTheWaitingPolicyWrite() throws Exception {
        AtomicBoolean bucketExists = new AtomicBoolean();
        IamService iamService = iamService(new InMemoryStorage<>(), bucketExists);
        IamBucketLifecycleService lifecycleService = lifecycleService(iamService);
        CountDownLatch bucketCreated = new CountDownLatch(1);
        CountDownLatch allowCreateToFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> creation = executor.submit(() -> lifecycleService.createBucket(
                    BUCKET,
                    null,
                    () -> {
                        bucketExists.set(true);
                        bucketCreated.countDown();
                        await(allowCreateToFinish);
                        return BUCKET;
                    },
                    ignored -> bucketExists.set(false)));
            assertTrue(bucketCreated.await(5, TimeUnit.SECONDS));

            CountDownLatch writerStarted = new CountDownLatch(1);
            Future<?> writer = executor.submit(() -> {
                writerStarted.countDown();
                iamService.setPolicy(RESOURCE, policy("roles/storage.objectViewer"));
            });
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
            assertBlocked(writer);

            allowCreateToFinish.countDown();
            assertEquals(BUCKET, creation.get(5, TimeUnit.SECONDS));
            writer.get(5, TimeUnit.SECONDS);
            assertEquals("roles/storage.objectViewer",
                    iamService.getPolicy(RESOURCE).getBindings().get(0).get("role"));
        } finally {
            allowCreateToFinish.countDown();
            shutdown(executor);
        }
    }

    @Test
    void policyWriteAgainstMissingBucketFinishesBeforeWaitingCreate() throws Exception {
        AtomicBoolean bucketExists = new AtomicBoolean();
        CountDownLatch missingBucketChecked = new CountDownLatch(1);
        CountDownLatch allowMissingCheckToFinish = new CountDownLatch(1);
        IamService iamService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        iamService.registerPolicyResourceResolver("buckets/*", ignored -> {
            if (!bucketExists.get()) {
                missingBucketChecked.countDown();
                await(allowMissingCheckToFinish);
                throw GcpException.notFound("Bucket not found: " + BUCKET);
            }
        });
        IamBucketLifecycleService lifecycleService = lifecycleService(iamService);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(
                    () -> iamService.setPolicy(RESOURCE, policy("roles/storage.objectViewer")));
            assertTrue(missingBucketChecked.await(5, TimeUnit.SECONDS));

            CountDownLatch creationStarted = new CountDownLatch(1);
            Future<String> creation = executor.submit(() -> {
                creationStarted.countDown();
                return lifecycleService.createBucket(
                        BUCKET,
                        null,
                        () -> {
                            bucketExists.set(true);
                            return BUCKET;
                        },
                        ignored -> bucketExists.set(false));
            });
            assertTrue(creationStarted.await(5, TimeUnit.SECONDS));
            assertBlocked(creation);

            allowMissingCheckToFinish.countDown();
            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> writer.get(5, TimeUnit.SECONDS));
            assertInstanceOf(GcpException.class, failure.getCause());
            assertEquals(404, ((GcpException) failure.getCause()).getHttpStatus());
            assertEquals(BUCKET, creation.get(5, TimeUnit.SECONDS));
            assertTrue(bucketExists.get());
        } finally {
            allowMissingCheckToFinish.countDown();
            shutdown(executor);
        }
    }

    private static IamService iamService(InMemoryStorage<String, StoredPolicy> policyStore,
            AtomicBoolean bucketExists) {
        IamService iamService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), policyStore);
        iamService.registerPolicyResourceResolver("buckets/*", ignored -> {
            if (!bucketExists.get()) {
                throw GcpException.notFound("Bucket not found: " + BUCKET);
            }
        });
        return iamService;
    }

    private static IamBucketLifecycleService lifecycleService(IamService iamService) {
        return new IamBucketLifecycleService(iamService, mock(IamBucketPolicyBootstrapService.class));
    }

    private static StoredPolicy policy(String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", role, "members", List.of("serviceAccount:writer@example.test"))));
        return policy;
    }

    private static void assertBlocked(Future<?> future) {
        assertThrows(TimeoutException.class, () -> future.get(100, TimeUnit.MILLISECONDS));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting test latch", e);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static final class BlockingPolicyStorage extends InMemoryStorage<String, StoredPolicy> {
        private final AtomicBoolean blockNextPut = new AtomicBoolean();
        private final CountDownLatch putStarted = new CountDownLatch(1);
        private final CountDownLatch allowPut = new CountDownLatch(1);

        void blockNextPut() {
            blockNextPut.set(true);
        }

        @Override
        public void put(String key, StoredPolicy value) {
            if (blockNextPut.compareAndSet(true, false)) {
                putStarted.countDown();
                await(allowPut);
            }
            super.put(key, value);
        }
    }
}

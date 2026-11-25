package io.floci.gcp.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.LocationCatalog;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.testing.ThreadStates;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamWorkloadIdentityPoolServiceTest {

    @Test
    void poolCreationHoldsThePolicyLockUntilTheStalePolicyIsCleared() throws Exception {
        InMemoryStorage<String, StoredPolicy> policies = new InMemoryStorage<>();
        IamService iam = IamServices.withStores(new InMemoryStorage<>(), new InMemoryStorage<>(), policies);
        BlockingPoolStore poolStore = new BlockingPoolStore();
        IamWorkloadIdentityPoolService service = service(poolStore, iam);
        String poolId = "pool-race";
        String poolPolicy = "projects/p/locations/global/workloadIdentityPools/" + poolId;

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> creation = executor.submit(() -> service.createPool("p", "global", poolId,
                    new ObjectMapper().createObjectNode()));
            assertTrue(poolStore.putStarted.await(5, TimeUnit.SECONDS));

            AtomicReference<Thread> writer = new AtomicReference<>();
            Future<?> policyWrite = executor.submit(() -> {
                writer.set(Thread.currentThread());
                StoredPolicy policy = new StoredPolicy();
                policy.setBindings(List.of(Map.of(
                        "role", "roles/iam.workloadIdentityPoolViewer",
                        "members", List.of("serviceAccount:reader@p.iam.gserviceaccount.com"))));
                iam.setPolicy(poolPolicy, policy);
            });
            ThreadStates.awaitBlockedOrDone(writer, policyWrite);
            assertFalse(policyWrite.isDone(), "setIamPolicy must wait for the create's policy lock");

            poolStore.releasePut.countDown();
            creation.get(5, TimeUnit.SECONDS);
            policyWrite.get(5, TimeUnit.SECONDS);

            assertEquals("roles/iam.workloadIdentityPoolViewer",
                    iam.getPolicy(poolPolicy).getBindings().getFirst().get("role"));
        } finally {
            poolStore.releasePut.countDown();
            executor.shutdownNow();
        }
    }

    private static IamWorkloadIdentityPoolService service(BlockingPoolStore pools, IamService iam) {
        Instance<IamService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(iam);
        return new IamWorkloadIdentityPoolService(pools, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new ObjectMapper(), instance, null, LocationCatalog.lenient());
    }

    private static final class BlockingPoolStore extends InMemoryStorage<String, String> {
        private final CountDownLatch putStarted = new CountDownLatch(1);
        private final CountDownLatch releasePut = new CountDownLatch(1);
        private boolean blockNextPut = true;

        @Override
        public void put(String key, String value) {
            if (blockNextPut) {
                blockNextPut = false;
                putStarted.countDown();
                try {
                    if (!releasePut.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to publish the pool");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while publishing the pool", e);
                }
            }
            super.put(key, value);
        }
    }
}

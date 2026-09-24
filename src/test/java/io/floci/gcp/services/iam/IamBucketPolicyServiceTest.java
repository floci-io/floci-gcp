package io.floci.gcp.services.iam;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.iam.model.StoredPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamBucketPolicyServiceTest {

    @Test
    void authorizationAndPolicyWriteExcludeConcurrentRevocation() throws Exception {
        String bucket = "concurrent-policy-bucket";
        String resource = "buckets/" + bucket;
        IamService iamService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        GcsService gcsService = mock(GcsService.class);
        GcsIamAuthorizationService authorizationService = mock(GcsIamAuthorizationService.class);
        IamBucketPolicyService policyService = new IamBucketPolicyService(
                iamService,
                gcsService,
                authorizationService,
                mock(EmulatorConfig.class),
                mock(IamConditionEvaluator.class),
                mock(IamPrincipalResolver.class),
                mock(IamPolicyEvaluator.class));

        CountDownLatch authorizationCompleted = new CountDownLatch(1);
        CountDownLatch allowPolicyWrite = new CountDownLatch(1);
        when(gcsService.getBucket(bucket)).thenAnswer(invocation -> {
            authorizationCompleted.countDown();
            await(allowPolicyWrite);
            return new GcsBucket();
        });

        StoredPolicy restoringPolicy = policy("roles/storage.admin");
        StoredPolicy revokedPolicy = new StoredPolicy();
        revokedPolicy.setBindings(List.of());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(
                    () -> policyService.setPolicy(bucket, "Bearer caller", restoringPolicy));
            assertTrue(authorizationCompleted.await(5, TimeUnit.SECONDS));

            Future<?> revoker = executor.submit(() -> iamService.setPolicy(resource, revokedPolicy));
            assertThrows(TimeoutException.class, () -> revoker.get(100, TimeUnit.MILLISECONDS));

            allowPolicyWrite.countDown();
            writer.get(5, TimeUnit.SECONDS);
            revoker.get(5, TimeUnit.SECONDS);
            assertTrue(iamService.getPolicy(resource).getBindings().isEmpty());
        } finally {
            allowPolicyWrite.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static StoredPolicy policy(String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", role,
                "members", List.of("serviceAccount:caller@example.test"))));
        return policy;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting test latch", e);
        }
    }
}

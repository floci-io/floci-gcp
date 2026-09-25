package io.floci.gcp.services.iam;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.GcsAuthorizationService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class IamBucketPolicyServiceTest {

    @Test
    void authorizationAndPolicyWriteExcludeConcurrentRevocation() throws Exception {
        String bucket = "concurrent-policy-bucket";
        String resource = "buckets/" + bucket;
        IamService iamService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        GcsService gcsService = mock(GcsService.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.IamServiceConfig iamConfig = mock(EmulatorConfig.IamServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.iam()).thenReturn(iamConfig);
        when(iamConfig.authorizationMode()).thenReturn(EmulatorConfig.IamAuthorizationMode.ENFORCE);
        Instant now = Instant.parse("2026-09-25T12:00:00Z");
        CredentialTokenService tokenService = new CredentialTokenService(
                new InMemoryStorage<>(), Clock.fixed(now, ZoneOffset.UTC));
        StoredCredentialToken token = tokenService.mintImpersonatedToken(
                "caller@example.test", now.plusSeconds(600));
        String authorization = "Bearer " + token.getTokenValue();
        IamPrincipalResolver principalResolver = new IamPrincipalResolver(tokenService);
        IamConditionEvaluator conditionEvaluator = mock(IamConditionEvaluator.class);
        IamPolicyEvaluator policyEvaluator = new IamPolicyEvaluator(
                new IamRoleCatalog(), new IamResourceHierarchy(), conditionEvaluator);
        GcsIamAuthorizationService authorizationService = spy(new GcsIamAuthorizationService(
                new GcsAuthorizationService(tokenService), config, iamService,
                principalResolver, policyEvaluator));
        IamBucketPolicyService policyService = new IamBucketPolicyService(
                iamService,
                gcsService,
                authorizationService,
                config,
                conditionEvaluator,
                principalResolver,
                policyEvaluator);

        iamService.setPolicy(resource, policy("roles/storage.admin"));

        CountDownLatch authorizationCompleted = new CountDownLatch(1);
        CountDownLatch allowPolicyWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            authorizationCompleted.countDown();
            await(allowPolicyWrite);
            return null;
        }).when(authorizationService).requireBucketPermission(
                authorization, bucket, "storage.buckets.setIamPolicy");

        StoredPolicy restoringPolicy = policy("roles/storage.admin");
        StoredPolicy revokedPolicy = new StoredPolicy();
        revokedPolicy.setBindings(List.of());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(
                    () -> policyService.setPolicy(bucket, authorization, restoringPolicy));
            assertTrue(authorizationCompleted.await(5, TimeUnit.SECONDS));

            CountDownLatch revokerStarted = new CountDownLatch(1);
            Future<?> revoker = executor.submit(() -> {
                revokerStarted.countDown();
                iamService.setPolicy(resource, revokedPolicy);
            });
            assertTrue(revokerStarted.await(5, TimeUnit.SECONDS));
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

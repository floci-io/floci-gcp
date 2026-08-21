package io.floci.gcp.services.iam;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.credentials.GcsAuthorizationService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GcsIamAuthorizationServiceTest {

    @Test
    void copyAcquiresPolicyLocksBeforeTheStorageMutationLock() throws Exception {
        IamService iamService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        iamService.setPolicy("buckets/source", objectAdminPolicy());
        iamService.setPolicy("buckets/destination", objectAdminPolicy());
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.IamServiceConfig iamConfig = mock(EmulatorConfig.IamServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.iam()).thenReturn(iamConfig);
        when(iamConfig.authorizationMode()).thenReturn(EmulatorConfig.IamAuthorizationMode.ENFORCE);
        IamPrincipalResolver principalResolver = mock(IamPrincipalResolver.class);
        when(principalResolver.resolve(any())).thenReturn(
                new IamPrincipalResolver.Resolution(IamPrincipal.anonymous(), false));
        IamConditionEvaluator conditionEvaluator = mock(IamConditionEvaluator.class);
        GcsIamAuthorizationService authorizationService = new GcsIamAuthorizationService(
                mock(GcsAuthorizationService.class), config, iamService, principalResolver,
                new IamPolicyEvaluator(new IamRoleCatalog(), new IamResourceHierarchy(), conditionEvaluator));
        Object storageMutationLock = new Object();
        CountDownLatch mutationReady = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> copy;
            Future<?> policyWriter;
            synchronized (storageMutationLock) {
                copy = executor.submit(() -> authorizationService.authorizeObjectCopy(
                        null, "source", "source.txt", "destination", "destination.txt",
                        requireOverwritePermission -> {
                            mutationReady.countDown();
                            synchronized (storageMutationLock) {
                                return null;
                            }
                        }));
                assertTrue(mutationReady.await(5, TimeUnit.SECONDS));

                policyWriter = executor.submit(
                        () -> iamService.setPolicy("buckets/source", objectAdminPolicy()));
                assertThrows(TimeoutException.class, () -> policyWriter.get(100, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> copy.get(100, TimeUnit.MILLISECONDS));
            }
            copy.get(5, TimeUnit.SECONDS);
            policyWriter.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static StoredPolicy objectAdminPolicy() {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/storage.objectAdmin", "members", List.of("allUsers"))));
        return policy;
    }
}

package io.floci.gcp.services.iam;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.credentials.GcsAuthorizationService;
import io.floci.gcp.services.gcs.GcsIamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamAuthorizationRegistry;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.floci.gcp.services.iam.authorization.IamIdentityKind;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import io.floci.gcp.services.iam.authorization.IamRequestIdentity;
import io.floci.gcp.services.iam.model.StoredPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GcsIamAuthorizationServiceTest {

    @Test
    void disabledModeDoesNotResolveBucketForPermissionCheck() {
        GcsAuthorizationService cabAuthorization = mock(GcsAuthorizationService.class);
        IamAuthorizationService authorization = mock(IamAuthorizationService.class);
        GcsIamAuthorizationAdapter adapter = mock(GcsIamAuthorizationAdapter.class);
        GcsIamAuthorizationService service = new GcsIamAuthorizationService(
                cabAuthorization, authorization, adapter);

        service.requireBucketPermission(null, "bucket", "storage.buckets.get");

        verify(cabAuthorization).rejectDownscopedToken(null);
        verify(adapter, never()).bucketResource("bucket");
    }

    @Test
    void disabledModeReturnsRequestedPermissionsWithoutResolvingBucket() {
        GcsAuthorizationService cabAuthorization = mock(GcsAuthorizationService.class);
        IamAuthorizationService authorization = mock(IamAuthorizationService.class);
        GcsIamAuthorizationAdapter adapter = mock(GcsIamAuthorizationAdapter.class);
        GcsIamAuthorizationService service = new GcsIamAuthorizationService(
                cabAuthorization, authorization, adapter);
        List<String> requested = List.of("storage.buckets.get", "storage.objects.list");

        List<String> granted = service.testBucketPermissions(null, "bucket", requested);

        assertSame(requested, granted);
        verify(cabAuthorization).rejectDownscopedToken(null);
        verify(adapter, never()).bucketResource("bucket");
    }

    @Test
    void mutationRetriesWithCurrentProjectWhenBucketOwnershipChangesBeforeLocking() {
        GcsAuthorizationService cabAuthorization = mock(GcsAuthorizationService.class);
        IamAuthorizationService authorization = mock(IamAuthorizationService.class);
        GcsIamAuthorizationAdapter adapter = mock(GcsIamAuthorizationAdapter.class);
        GcsIamAuthorizationService service = new GcsIamAuthorizationService(
                cabAuthorization, authorization, adapter);
        when(authorization.enabled()).thenReturn(true);
        IamResource previous = IamResource.gcsBucket("bucket", "previous-project");
        IamResource current = IamResource.gcsBucket("bucket", "current-project");
        when(adapter.bucketResource("bucket")).thenReturn(previous, current, current, current);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(authorization).withPolicyLocks(any(IamResource.class), any());
        AtomicInteger actions = new AtomicInteger();

        String result = service.withBucketPermission(
                "authorization", "bucket", "storage.buckets.setIamPolicy", () -> {
                    actions.incrementAndGet();
                    return "written";
                });

        assertEquals("written", result);
        assertEquals(1, actions.get());
        verify(cabAuthorization).rejectDownscopedToken("authorization");
        verify(authorization, times(2)).withPolicyLocks(any(IamResource.class), any());
        verify(authorization).authorize(
                eq("authorization"), same(adapter),
                eq(new IamPermissionCheck("storage.buckets.setIamPolicy", current)));
    }

    @Test
    void movePermissionCanReplaceSourceReadAndDeletePermissions() {
        IamService iamService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        iamService.setPolicy("buckets/bucket", policyForRole("roles/test.moveOnly"));
        IamRoleCatalog roleCatalog = new IamRoleCatalog(Map.of(
                "roles/test.moveOnly", Set.of("storage.objects.move", "storage.objects.create")));
        GcsIamAuthorizationService authorizationService = authorizationService(iamService, roleCatalog);
        AtomicBoolean mutationInvoked = new AtomicBoolean();

        authorizationService.authorizeObjectMove(null, "bucket", "source.txt", "destination.txt",
                requireOverwritePermission -> {
                    mutationInvoked.set(true);
                    return null;
                });

        assertTrue(mutationInvoked.get());
    }

    @Test
    void sourceReadAndDeletePermissionsCanAuthorizeMoveWithoutMovePermission() {
        IamService iamService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        iamService.setPolicy("buckets/bucket", policyForRole("roles/test.readDelete"));
        IamRoleCatalog roleCatalog = new IamRoleCatalog(Map.of(
                "roles/test.readDelete", Set.of(
                        "storage.objects.get", "storage.objects.delete", "storage.objects.create")));
        GcsIamAuthorizationService authorizationService = authorizationService(iamService, roleCatalog);
        AtomicBoolean mutationInvoked = new AtomicBoolean();

        authorizationService.authorizeObjectMove(null, "bucket", "source.txt", "destination.txt",
                requireOverwritePermission -> {
                    mutationInvoked.set(true);
                    return null;
                });

        assertTrue(mutationInvoked.get());
    }

    @Test
    void copyAcquiresPolicyLocksBeforeTheStorageMutationLock() throws Exception {
        IamService iamService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        iamService.setPolicy("buckets/source", objectAdminPolicy());
        iamService.setPolicy("buckets/destination", objectAdminPolicy());
        GcsIamAuthorizationService authorizationService = authorizationService(iamService, new IamRoleCatalog());
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

    @Test
    void multipartCompletionAcquiresPolicyLockBeforeTheStorageMutationLock() throws Exception {
        IamService iamService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        iamService.setPolicy("buckets/bucket", objectAdminPolicy());
        GcsIamAuthorizationService authorizationService = authorizationService(iamService, new IamRoleCatalog());
        Object storageMutationLock = new Object();
        CountDownLatch mutationReady = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> completion;
            Future<?> policyWriter;
            synchronized (storageMutationLock) {
                completion = executor.submit(() -> authorizationService.authorizeMultipartCompletion(
                        null, "bucket", "object.txt", requireOverwritePermission -> {
                            mutationReady.countDown();
                            synchronized (storageMutationLock) {
                                return null;
                            }
                        }));
                assertTrue(mutationReady.await(5, TimeUnit.SECONDS));

                policyWriter = executor.submit(
                        () -> iamService.setPolicy("buckets/bucket", objectAdminPolicy()));
                assertThrows(TimeoutException.class, () -> policyWriter.get(100, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> completion.get(100, TimeUnit.MILLISECONDS));
            }
            completion.get(5, TimeUnit.SECONDS);
            policyWriter.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static GcsIamAuthorizationService authorizationService(
            IamService iamService, IamRoleCatalog roleCatalog) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.IamServiceConfig iamConfig = mock(EmulatorConfig.IamServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.iam()).thenReturn(iamConfig);
        when(iamConfig.authorizationMode()).thenReturn(EmulatorConfig.IamAuthorizationMode.ENFORCE);

        IamPrincipalResolver principalResolver = mock(IamPrincipalResolver.class);
        when(principalResolver.resolve(any())).thenReturn(new IamPrincipalResolver.Resolution(
                IamPrincipal.anonymous(), IamIdentityKind.ANONYMOUS));
        GcsIamAuthorizationAdapter adapter = mock(GcsIamAuthorizationAdapter.class);
        when(adapter.requiresPolicyEvaluation(IamIdentityKind.ANONYMOUS)).thenReturn(true);
        when(adapter.bucketResource(anyString())).thenAnswer(invocation ->
                IamResource.gcsBucket(invocation.getArgument(0)));
        when(adapter.objectResource(anyString(), anyString())).thenAnswer(invocation ->
                IamResource.gcsObject(invocation.getArgument(0), invocation.getArgument(1)));

        IamResourceHierarchy hierarchy = new IamResourceHierarchy();
        IamAuthorizationRegistry registry = new IamAuthorizationRegistry(List.of(adapter));
        IamAuthorizationService authorization = new IamAuthorizationService(
                config,
                principalResolver,
                new IamPolicyEvaluator(roleCatalog, hierarchy, mock(IamConditionEvaluator.class)),
                hierarchy,
                roleCatalog,
                mock(IamConditionEvaluator.class),
                iamService,
                registry,
                mock(IamRequestIdentity.class));
        return new GcsIamAuthorizationService(
                mock(GcsAuthorizationService.class), authorization, adapter);
    }

    private static StoredPolicy objectAdminPolicy() {
        return policyForRole("roles/storage.objectAdmin");
    }

    private static StoredPolicy policyForRole(String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", role, "members", List.of("allUsers"))));
        return policy;
    }
}

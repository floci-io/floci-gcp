package io.floci.gcp.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.HybridStorage;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.StorageException;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import io.floci.gcp.services.iam.model.StoredServiceAccountKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

import static org.junit.jupiter.api.Assertions.*;

class IamServiceTest {

    private IamService service;

    @BeforeEach
    void setUp() {
        service = new IamService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>());
    }

    @Test
    void createServiceAccountStoredAndRetrievable() {
        service.createServiceAccount("p1", "sa1", "Test SA", "");

        StoredServiceAccount sa = service.getServiceAccount("p1", "sa1@p1.iam.gserviceaccount.com");
        assertEquals("sa1@p1.iam.gserviceaccount.com", sa.getEmail());
        assertEquals("Test SA", sa.getDisplayName());
    }

    @Test
    void createServiceAccountDuplicateThrowsAlreadyExists() {
        service.createServiceAccount("p1", "sa1", "Test SA", "");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.createServiceAccount("p1", "sa1", "Duplicate", ""));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
    }

    @Test
    void getServiceAccountMissingThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.getServiceAccount("p1", "missing@p1.iam.gserviceaccount.com"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void listServiceAccountsFiltersByProject() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        service.createServiceAccount("p1", "sa2", "SA2", "");

        List<StoredServiceAccount> accounts = service.listServiceAccounts("p1");
        assertEquals(2, accounts.size());
    }

    @Test
    void deleteServiceAccountRemovedFromList() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        service.deleteServiceAccount("p1", "sa1@p1.iam.gserviceaccount.com");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.getServiceAccount("p1", "sa1@p1.iam.gserviceaccount.com"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void createKeyAndListKeys() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        StoredServiceAccountKey key = service.createKey("p1", "sa1@p1.iam.gserviceaccount.com");

        assertNotNull(key.getKeyId());
        assertNotNull(key.getName());

        List<StoredServiceAccountKey> keys = service.listKeys("p1", "sa1@p1.iam.gserviceaccount.com");
        assertEquals(1, keys.size());
        assertEquals(key.getKeyId(), keys.get(0).getKeyId());
    }

    @Test
    void deleteKeyRemovedFromList() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        StoredServiceAccountKey key = service.createKey("p1", "sa1@p1.iam.gserviceaccount.com");

        service.deleteKey("p1", "sa1@p1.iam.gserviceaccount.com", key.getKeyId());

        List<StoredServiceAccountKey> keys = service.listKeys("p1", "sa1@p1.iam.gserviceaccount.com");
        assertTrue(keys.isEmpty());
    }

    @Test
    void getPolicyReturnsEmptyBindingsByDefault() {
        service.createServiceAccount("p1", "sa1", "SA1", "");
        StoredPolicy policy = service.getPolicy("projects/p1/serviceAccounts/sa1@p1.iam.gserviceaccount.com");

        assertNotNull(policy);
        assertEquals(1, policy.getVersion());
        assertTrue(policy.getBindings() == null || policy.getBindings().isEmpty());
    }

    @Test
    void unsetPolicyCarriesEmptyPolicyEtag() {
        assertEquals("ACAB", service.getPolicy("projects/p1/resource/r1").getEtag());
    }

    @Test
    void setPolicyRotatesEtagOnEveryWrite() {
        StoredPolicy first = service.setPolicy("projects/p1/resource/r1", new StoredPolicy());
        String firstEtag = first.getEtag();
        assertNotNull(firstEtag);
        assertFalse(firstEtag.isEmpty());
        assertNotEquals("ACAB", firstEtag);

        StoredPolicy second = service.setPolicy("projects/p1/resource/r1", new StoredPolicy());
        assertNotEquals(firstEtag, second.getEtag());
    }

    @Test
    void setPolicyWithoutEtagIsBlindWrite() {
        service.setPolicy("projects/p1/resource/r1", new StoredPolicy());

        StoredPolicy blind = new StoredPolicy();
        assertEquals("", blind.getEtag());
        assertDoesNotThrow(() -> service.setPolicy("projects/p1/resource/r1", blind));
    }

    @Test
    void setPolicyWithCurrentEtagSucceeds() {
        service.setPolicy("projects/p1/resource/r1", new StoredPolicy());
        String current = service.getPolicy("projects/p1/resource/r1").getEtag();

        StoredPolicy update = new StoredPolicy();
        update.setEtag(current);
        assertDoesNotThrow(() -> service.setPolicy("projects/p1/resource/r1", update));
    }

    @Test
    void setPolicyWithStaleEtagIsAborted() {
        StoredPolicy reader = service.setPolicy("projects/p1/resource/r1", new StoredPolicy());
        String stale = reader.getEtag();
        service.setPolicy("projects/p1/resource/r1", new StoredPolicy());

        StoredPolicy lostUpdate = new StoredPolicy();
        lostUpdate.setEtag(stale);
        GcpException e = assertThrows(GcpException.class,
                () -> service.setPolicy("projects/p1/resource/r1", lostUpdate));
        assertEquals(409, e.getHttpStatus());
        assertEquals("ABORTED", e.getGcpStatus());
    }

    @Test
    void setPolicyAgainstUnsetPolicyAcceptsEmptyPolicyEtag() {
        StoredPolicy update = new StoredPolicy();
        update.setEtag("ACAB");
        assertDoesNotThrow(() -> service.setPolicy("projects/p1/resource/r1", update));
    }

    @Test
    void deletePolicyRemovesStoredPolicy() {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", "roles/viewer", "members", List.of("user:x@example.com"))));
        service.setPolicy("projects/p1/resource/r1", policy);

        service.deletePolicy("projects/p1/resource/r1");

        assertTrue(service.getPolicy("projects/p1/resource/r1").getBindings().isEmpty());
        assertEquals("ACAB", service.getPolicy("projects/p1/resource/r1").getEtag());
    }

    @Test
    void testPermissionsEchoesWhenNoResolverMatches() {
        assertEquals(List.of("a.b.c", "d.e.f"),
                service.testPermissions("projects/p1/gadgets/g1", List.of("a.b.c", "d.e.f")));
    }

    @Test
    void testPermissionsFailsOpenEmptyForMissingResolvedResource() {
        service.registerPolicyResourceResolver("projects/*/widgets/*", resource -> {
            throw GcpException.notFound("Widget not found: " + resource);
        });

        assertEquals(List.of(), service.testPermissions("projects/p1/widgets/w1", List.of("a.b.c")));
    }

    @Test
    void policiesSurviveServiceRestart() {
        InMemoryStorage<String, StoredPolicy> policyStore = new InMemoryStorage<>();
        IamService first = new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(), policyStore);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", "roles/pubsub.publisher", "members", List.of("user:x@example.com"))));
        first.setPolicy("projects/p1/topics/t1", policy);

        IamService second = new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(), policyStore);

        assertEquals("roles/pubsub.publisher",
                second.getPolicy("projects/p1/topics/t1").getBindings().get(0).get("role"));
    }

    @Test
    void resolverEnforcesExistenceOnlyForMatchingResources() {
        service.registerPolicyResourceResolver("projects/*/widgets/*", resource -> {
            throw GcpException.notFound("Widget not found: " + resource);
        });

        GcpException e = assertThrows(GcpException.class,
                () -> service.getPolicy("projects/p1/widgets/w1"));
        assertEquals(404, e.getHttpStatus());
        assertThrows(GcpException.class,
                () -> service.setPolicy("projects/p1/widgets/w1", new StoredPolicy()));

        assertDoesNotThrow(() -> service.getPolicy("projects/p1/gadgets/g1"));
        assertDoesNotThrow(() -> service.getPolicy("projects/p1/widgets/w1/sub/s1"));
    }

    @Test
    void deleteSerializesPolicyCleanupAgainstAConcurrentWriter() throws Exception {
        String resource = "buckets/concurrent-bucket";
        AtomicBoolean exists = new AtomicBoolean(true);
        service.registerPolicyResourceResolver("buckets/*", ignored -> {
            if (!exists.get()) {
                throw GcpException.notFound("Bucket not found");
            }
        });
        StoredPolicy initial = new StoredPolicy();
        initial.setBindings(List.of(Map.of("role", "roles/storage.admin", "members", List.of("allUsers"))));
        service.setPolicy(resource, initial);

        CountDownLatch resourceDeleted = new CountDownLatch(1);
        CountDownLatch allowDeleteToFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> service.deleteResourceAndPolicy(resource, () -> {
                exists.set(false);
                resourceDeleted.countDown();
                await(allowDeleteToFinish);
            }));
            assertTrue(resourceDeleted.await(5, TimeUnit.SECONDS));

            StoredPolicy concurrent = new StoredPolicy();
            concurrent.setBindings(List.of(Map.of(
                    "role", "roles/storage.objectViewer", "members", List.of("allUsers"))));
            Future<?> writer = executor.submit(() -> service.setPolicy(resource, concurrent));
            assertThrows(TimeoutException.class, () -> writer.get(100, TimeUnit.MILLISECONDS));

            allowDeleteToFinish.countDown();
            deletion.get(5, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> writer.get(5, TimeUnit.SECONDS));
            assertInstanceOf(GcpException.class, error.getCause());
            assertEquals(404, ((GcpException) error.getCause()).getHttpStatus());
        } finally {
            allowDeleteToFinish.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void createClearsPolicyLeftByAnOlderResourceWithTheSameName() {
        String resource = "buckets/recreated-bucket";
        StoredPolicy stale = new StoredPolicy();
        stale.setBindings(List.of(Map.of("role", "roles/storage.admin", "members", List.of("allUsers"))));
        service.setPolicy(resource, stale);

        String created = service.createResourceAndPolicy(
                resource, null, () -> "created", ignored -> { });

        assertEquals("created", created);
        assertTrue(service.getPolicy(resource).getBindings().isEmpty());
    }

    @Test
    void createCheckpointsInitialPolicyInHybridStorage(@TempDir Path tempDir) {
        Path policyPath = tempDir.resolve("iam-policies.json");
        TypeReference<Map<String, StoredPolicy>> policyType = new TypeReference<>() {};
        HybridStorage<String, StoredPolicy> policyStore = new HybridStorage<>(
                policyPath, policyType, TimeUnit.HOURS.toMillis(1));
        HybridStorage<String, StoredPolicy> restoredStore = new HybridStorage<>(
                policyPath, policyType, TimeUnit.HOURS.toMillis(1));
        try {
            IamService durableService = new IamService(
                    new InMemoryStorage<>(), new InMemoryStorage<>(), policyStore);
            StoredPolicy initialPolicy = new StoredPolicy();
            initialPolicy.setBindings(List.of(Map.of(
                    "role", "roles/storage.admin",
                    "members", List.of("serviceAccount:creator@example.test"))));

            durableService.createResourceAndPolicy(
                    "buckets/durable-bucket", initialPolicy, () -> "created", ignored -> { });

            restoredStore.load();
            IamService restoredService = new IamService(
                    new InMemoryStorage<>(), new InMemoryStorage<>(), restoredStore);
            assertEquals("roles/storage.admin",
                    restoredService.getPolicy("buckets/durable-bucket").getBindings().get(0).get("role"));
        } finally {
            restoredStore.shutdown();
            policyStore.shutdown();
        }
    }

    @Test
    void failedCreateCheckpointRollsBackResourceAndRestoresPreviousPolicy() {
        String resource = "buckets/checkpoint-failure";
        FailingCheckpointStorage policyStore = new FailingCheckpointStorage();
        IamService durableService = new IamService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), policyStore);
        StoredPolicy previousPolicy = new StoredPolicy();
        previousPolicy.setBindings(List.of(Map.of(
                "role", "roles/storage.objectViewer", "members", List.of("allUsers"))));
        durableService.setPolicy(resource, previousPolicy);
        StoredPolicy initialPolicy = new StoredPolicy();
        initialPolicy.setBindings(List.of(Map.of(
                "role", "roles/storage.admin", "members", List.of("serviceAccount:creator@example.test"))));
        AtomicBoolean resourceExists = new AtomicBoolean();

        assertThrows(StorageException.class, () -> durableService.createResourceAndPolicy(
                resource,
                initialPolicy,
                () -> {
                    resourceExists.set(true);
                    return "created";
                },
                ignored -> resourceExists.set(false)));

        assertFalse(resourceExists.get());
        assertEquals("roles/storage.objectViewer",
                durableService.getPolicy(resource).getBindings().get(0).get("role"));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting test latch", e);
        }
    }

    private static final class FailingCheckpointStorage extends InMemoryStorage<String, StoredPolicy> {
        @Override
        public void checkpoint() {
            throw new StorageException("checkpoint failed", new IllegalStateException("test failure"));
        }
    }
}

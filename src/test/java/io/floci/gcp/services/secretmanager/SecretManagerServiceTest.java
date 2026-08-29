package io.floci.gcp.services.secretmanager;

import com.google.iam.v1.Binding;
import com.google.iam.v1.Policy;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.StorageException;
import io.floci.gcp.services.iam.IamServices;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.secretmanager.model.StoredSecret;
import io.floci.gcp.services.secretmanager.model.StoredSecretVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SecretManagerServiceTest {

    private SecretManagerService service;

    @BeforeEach
    void setUp() {
        service = new SecretManagerService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                IamServices.inMemory());
    }

    @Test
    void createSecretStoredAndRetrievable() {
        service.createSecret("p1", "my-secret", "automatic");

        StoredSecret secret = service.getSecret("projects/p1/secrets/my-secret");
        assertEquals("projects/p1/secrets/my-secret", secret.getName());
    }

    @Test
    void createSecretDuplicateThrowsAlreadyExists() {
        service.createSecret("p1", "my-secret", "automatic");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.createSecret("p1", "my-secret", "automatic"));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
    }

    @Test
    void getSecretMissingThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.getSecret("projects/p1/secrets/missing"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void addSecretVersionCreatesEnabledVersionWithIncrementingNumber() {
        service.createSecret("p1", "s1", "automatic");

        byte[] payload = "value1".getBytes(StandardCharsets.UTF_8);
        StoredSecretVersion v1 = service.addSecretVersion("projects/p1/secrets/s1", payload, null);
        StoredSecretVersion v2 = service.addSecretVersion("projects/p1/secrets/s1", payload, null);

        assertEquals("ENABLED", v1.getState());
        assertEquals("ENABLED", v2.getState());
        assertEquals(1, v1.getVersionNumber());
        assertEquals(2, v2.getVersionNumber());
    }

    @Test
    void accessSecretVersionLatestReturnsPayload() {
        service.createSecret("p1", "s1", "automatic");
        byte[] payload = "my-password".getBytes(StandardCharsets.UTF_8);
        service.addSecretVersion("projects/p1/secrets/s1", payload, null);

        StoredSecretVersion version = service.accessSecretVersion(
                "projects/p1/secrets/s1/versions/latest");
        assertArrayEquals(payload, version.getPayload());
    }

    @Test
    void accessDisabledVersionThrowsFailedPrecondition() {
        service.createSecret("p1", "s1", "automatic");
        service.addSecretVersion("projects/p1/secrets/s1", new byte[]{1}, null);
        service.disableSecretVersion("projects/p1/secrets/s1/versions/1");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.accessSecretVersion("projects/p1/secrets/s1/versions/1"));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void destroySecretVersionClearsPayload() {
        service.createSecret("p1", "s1", "automatic");
        service.addSecretVersion("projects/p1/secrets/s1", new byte[]{1, 2, 3}, null);

        StoredSecretVersion destroyed = service.disableSecretVersion(
                "projects/p1/secrets/s1/versions/1");
        assertEquals("DISABLED", destroyed.getState());
    }

    @Test
    void enableSecretVersionAllowsAccess() {
        service.createSecret("p1", "s1", "automatic");
        byte[] payload = "re-enabled".getBytes(StandardCharsets.UTF_8);
        service.addSecretVersion("projects/p1/secrets/s1", payload, null);
        service.disableSecretVersion("projects/p1/secrets/s1/versions/1");

        service.enableSecretVersion("projects/p1/secrets/s1/versions/1");
        StoredSecretVersion version = service.accessSecretVersion(
                "projects/p1/secrets/s1/versions/1");
        assertArrayEquals(payload, version.getPayload());
    }

    @Test
    void deleteSecretCascadesVersions() {
        service.createSecret("p1", "s1", "automatic");
        service.addSecretVersion("projects/p1/secrets/s1", new byte[]{1}, null);

        service.deleteSecret("projects/p1/secrets/s1");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.getSecret("projects/p1/secrets/s1"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());

        assertThrows(GcpException.class,
                () -> service.listSecretVersions("projects/p1/secrets/s1"));
    }

    @Test
    void listSecretsFiltersByProject() {
        service.createSecret("p1", "s1", "automatic");
        service.createSecret("p1", "s2", "automatic");

        List<StoredSecret> secrets = service.listSecrets("p1");
        assertEquals(2, secrets.size());
        assertTrue(secrets.stream().allMatch(s -> s.getName().startsWith("projects/p1")));
    }

    @Test
    void iamPolicyRoundTripsRejectsStaleEtagAndIsClearedOnDelete() {
        String resource = "projects/p1/secrets/iam-target";
        service.createSecret("p1", "iam-target", "automatic");

        Policy saved = service.setIamPolicy(resource, Policy.newBuilder()
                .addBindings(Binding.newBuilder()
                        .setRole("roles/secretmanager.secretAccessor")
                        .addMembers("user:reader@example.com"))
                .build());

        assertEquals("roles/secretmanager.secretAccessor", service.getIamPolicy(resource)
                .getBindings(0).getRole());
        assertThrows(GcpException.class, () -> service.setIamPolicy(resource, Policy.newBuilder()
                .setEtag(com.google.protobuf.ByteString.copyFromUtf8("stale"))
                .build()));
        assertEquals(saved.getEtag(), service.getIamPolicy(resource).getEtag());

        service.deleteSecret(resource);
        assertThrows(GcpException.class, () -> service.getIamPolicy(resource));

        service.createSecret("p1", "iam-target", "automatic");
        assertTrue(service.getIamPolicy(resource).getBindingsList().isEmpty());
    }

    @Test
    void retainsDeletionIntentWhenDurabilityCheckpointFails() {
        String resource = "projects/p1/secrets/checkpoint-failure";
        var deletions = new FailingCheckpointStorage();
        SecretManagerService failingService = new SecretManagerService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), deletions, IamServices.inMemory());
        failingService.createSecret("p1", "checkpoint-failure", "automatic");
        failingService.setIamPolicy(resource, Policy.newBuilder()
                .addBindings(Binding.newBuilder().setRole("roles/secretmanager.secretAccessor")
                        .addMembers("user:reader@example.com"))
                .build());

        GcpException failure = assertThrows(GcpException.class, () -> failingService.deleteSecret(resource));
        assertEquals("UNAVAILABLE", failure.getGcpStatus());
        assertThrows(GcpException.class, () -> failingService.getSecret(resource));

        failingService.createSecret("p1", "checkpoint-failure", "automatic");
        assertTrue(failingService.getIamPolicy(resource).getBindingsList().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(DeletionBoundary.class)
    void everyFailedDeletionCheckpointRetainsIntentAndRecovers(DeletionBoundary boundary) {
        String resource = "projects/p1/secrets/boundary";
        var secrets = new FailingCheckpointStorage<String, StoredSecret>(boundary == DeletionBoundary.SECRET ? 1 : 0);
        var versions = new FailingCheckpointStorage<String, StoredSecretVersion>(boundary == DeletionBoundary.VERSION ? 1 : 0);
        var deletions = new FailingCheckpointStorage<String, String>(switch (boundary) {
            case INTENT -> 1;
            case MARKER_CLEAR -> 2;
            default -> 0;
        });
        var policies = new FailingCheckpointStorage<String, StoredPolicy>(boundary == DeletionBoundary.POLICY ? 1 : 0);
        SecretManagerService tested = new SecretManagerService(secrets, versions, deletions,
                IamServices.withStores(new InMemoryStorage<>(), new InMemoryStorage<>(), policies));
        tested.createSecret("p1", "boundary", "automatic");
        tested.addSecretVersion(resource, new byte[]{1}, null);
        tested.setIamPolicy(resource, policyWithBinding());

        GcpException failure = assertThrows(GcpException.class, () -> tested.deleteSecret(resource));

        assertEquals("UNAVAILABLE", failure.getGcpStatus());
        assertTrue(deletions.get(resource).isPresent(), "deletion intent must remain in memory");
        assertThrows(GcpException.class, () -> tested.getSecret(resource));
        assertTrue(tested.listSecrets("p1").isEmpty());

        tested.createSecret("p1", "boundary", "automatic");

        assertTrue(deletions.get(resource).isEmpty());
        assertTrue(tested.listSecretVersions(resource).isEmpty());
        assertTrue(tested.getIamPolicy(resource).getBindingsList().isEmpty());
    }

    @Test
    void deletionCheckpointsStoresInRecoveryOrder() {
        List<String> order = new ArrayList<>();
        var secrets = new RecordingCheckpointStorage<String, StoredSecret>("secret", order);
        var versions = new RecordingCheckpointStorage<String, StoredSecretVersion>("version", order);
        var deletions = new RecordingDeletionStorage(order);
        var policies = new RecordingCheckpointStorage<String, StoredPolicy>("policy", order);
        SecretManagerService tested = new SecretManagerService(secrets, versions, deletions,
                IamServices.withStores(new InMemoryStorage<>(), new InMemoryStorage<>(), policies));
        String resource = "projects/p1/secrets/ordered";
        tested.createSecret("p1", "ordered", "automatic");

        tested.deleteSecret(resource);

        assertEquals(List.of("intent", "policy", "version", "secret", "marker-clear"), order);
    }

    @Test
    void deleteSerializesRecreationAndDoesNotRetainOldVersionsOrPolicy() throws Exception {
        BlockingCheckpointStorage<String, String> deletions = new BlockingCheckpointStorage<>();
        SecretManagerService tested = new SecretManagerService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), deletions, IamServices.inMemory());
        String resource = "projects/p1/secrets/race";
        tested.createSecret("p1", "race", "automatic");
        tested.addSecretVersion(resource, new byte[]{1}, null);
        tested.setIamPolicy(resource, policyWithBinding());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> delete = executor.submit(() -> tested.deleteSecret(resource));
            assertTrue(deletions.awaitCheckpoint());
            Future<StoredSecret> recreate = executor.submit(() -> tested.createSecret("p1", "race", "automatic"));
            assertTimeoutPreemptively(Duration.ofMillis(200), () -> assertFalse(recreate.isDone()));

            deletions.releaseCheckpoint();
            delete.get(5, TimeUnit.SECONDS);
            assertEquals(resource, recreate.get(5, TimeUnit.SECONDS).getName());
        }

        assertTrue(tested.listSecretVersions(resource).isEmpty());
        assertTrue(tested.getIamPolicy(resource).getBindingsList().isEmpty());
    }

    @Test
    void deleteSerializesAllReadsVersionMutationsAndIamOperations() throws Exception {
        BlockingCheckpointStorage<String, String> deletions = new BlockingCheckpointStorage<>();
        SecretManagerService tested = new SecretManagerService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), deletions, IamServices.inMemory());
        String resource = "projects/p1/secrets/race";
        String version = resource + "/versions/1";
        tested.createSecret("p1", "race", "automatic");
        tested.addSecretVersion(resource, new byte[]{1}, null);
        tested.setIamPolicy(resource, policyWithBinding());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> delete = executor.submit(() -> tested.deleteSecret(resource));
            assertTrue(deletions.awaitCheckpoint());
            List<Future<?>> blocked = List.of(
                    executor.submit(() -> tested.getSecret(resource)),
                    executor.submit(() -> tested.updateSecret(resource)),
                    executor.submit(() -> tested.addSecretVersion(resource, new byte[]{2}, null)),
                    executor.submit(() -> tested.getSecretVersion(version)),
                    executor.submit(() -> tested.listSecretVersions(resource)),
                    executor.submit(() -> tested.accessSecretVersion(version)),
                    executor.submit(() -> tested.disableSecretVersion(version)),
                    executor.submit(() -> tested.enableSecretVersion(version)),
                    executor.submit(() -> tested.destroySecretVersion(version)),
                    executor.submit(() -> tested.getIamPolicy(resource)),
                    executor.submit(() -> tested.setIamPolicy(resource, Policy.getDefaultInstance())));
            Future<List<String>> permissions = executor.submit(
                    () -> tested.testIamPermissions(resource, List.of("secretmanager.secrets.get")));
            assertTimeoutPreemptively(Duration.ofMillis(200),
                    () -> assertTrue(blocked.stream().noneMatch(Future::isDone)));
            assertFalse(permissions.isDone());

            deletions.releaseCheckpoint();
            delete.get(5, TimeUnit.SECONDS);
            for (Future<?> operation : blocked) {
                assertNotFound(operation);
            }
            assertTrue(permissions.get(5, TimeUnit.SECONDS).isEmpty());
        }
    }

    private static Policy policyWithBinding() {
        return Policy.newBuilder()
                .addBindings(Binding.newBuilder().setRole("roles/secretmanager.secretAccessor")
                        .addMembers("user:reader@example.com"))
                .build();
    }

    private static void assertNotFound(Future<?> operation) throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> operation.get(5, TimeUnit.SECONDS));
        assertInstanceOf(GcpException.class, failure.getCause());
        assertEquals("NOT_FOUND", ((GcpException) failure.getCause()).getGcpStatus());
    }

    private enum DeletionBoundary {
        INTENT,
        POLICY,
        VERSION,
        SECRET,
        MARKER_CLEAR
    }

    private static final class FailingCheckpointStorage<K, V> extends InMemoryStorage<K, V> {
        private final int failureCall;
        private int calls;

        private FailingCheckpointStorage() {
            this(1);
        }

        private FailingCheckpointStorage(int failureCall) {
            this.failureCall = failureCall;
        }

        @Override
        public void checkpoint() {
            calls++;
            if (calls == failureCall) {
                throw new StorageException("injected checkpoint failure", null);
            }
        }
    }

    private static class RecordingCheckpointStorage<K, V> extends InMemoryStorage<K, V> {
        private final String name;
        private final List<String> order;

        private RecordingCheckpointStorage(String name, List<String> order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public void checkpoint() {
            order.add(name);
        }
    }

    private static final class RecordingDeletionStorage extends InMemoryStorage<String, String> {
        private final List<String> order;
        private int calls;

        private RecordingDeletionStorage(List<String> order) {
            this.order = order;
        }

        @Override
        public void checkpoint() {
            order.add(++calls == 1 ? "intent" : "marker-clear");
        }
    }

    private static final class BlockingCheckpointStorage<K, V> extends InMemoryStorage<K, V> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private boolean first = true;

        @Override
        public void checkpoint() {
            if (!first) {
                return;
            }
            first = false;
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release checkpoint");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        private boolean awaitCheckpoint() throws InterruptedException {
            return entered.await(5, TimeUnit.SECONDS);
        }

        private void releaseCheckpoint() {
            release.countDown();
        }
    }

    @Test
    void testIamPermissionsEchoesRequestedPermissionsForExistingSecret() {
        String resource = "projects/p1/secrets/permission-target";
        service.createSecret("p1", "permission-target", "automatic");

        assertEquals(List.of("secretmanager.secrets.get", "secretmanager.secrets.delete"),
                service.testIamPermissions(resource,
                        List.of("secretmanager.secrets.get", "secretmanager.secrets.delete")));
    }
}

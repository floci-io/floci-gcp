package io.floci.gcp.services.secretmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.iam.v1.Binding;
import com.google.iam.v1.Policy;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.HybridStorage;
import io.floci.gcp.core.storage.PersistentStorage;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.WalStorage;
import io.floci.gcp.services.iam.IamServices;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.secretmanager.model.StoredSecret;
import io.floci.gcp.services.secretmanager.model.StoredSecretVersion;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretManagerDeletionPersistenceTest {

    private static final long BACKGROUND_INTERVAL_MS = 60_000;

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @EnumSource(StorageMode.class)
    void pendingDeletionIsCompletedAfterRestartWithoutResurrectingState(StorageMode mode) {
        String resource = "projects/p1/secrets/recovery";
        Stores initial = openStores(mode);
        SecretManagerService service = initial.service();
        service.createSecret("p1", "recovery", "automatic");
        service.addSecretVersion(resource, new byte[]{1, 2, 3}, null);
        service.setIamPolicy(resource, Policy.newBuilder()
                .addBindings(Binding.newBuilder().setRole("roles/secretmanager.secretAccessor")
                        .addMembers("user:reader@example.com"))
                .build());
        initial.secrets.checkpoint();
        initial.versions.checkpoint();
        initial.policies.checkpoint();
        initial.deletions.put(resource, resource);
        initial.deletions.checkpoint();
        initial.close();

        Stores restarted = openStores(mode);
        SecretManagerService restartedService = restarted.service();
        restartedService.resumePendingDeletions();

        GcpException missing = assertThrows(GcpException.class,
                () -> restartedService.getSecret(resource));
        assertEquals("NOT_FOUND", missing.getGcpStatus());
        assertTrue(restarted.deletions.get(resource).isEmpty());
        restarted.close();

        Stores verified = openStores(mode);
        assertTrue(verified.secrets.get(resource).isEmpty());
        assertTrue(verified.versions.scan(key -> key.startsWith(resource + "/versions/")).isEmpty());
        assertTrue(verified.policies.get(resource).isEmpty());
        assertTrue(verified.deletions.get(resource).isEmpty());

        SecretManagerService verifiedService = verified.service();
        verifiedService.createSecret("p1", "recovery", "automatic");
        assertTrue(verifiedService.listSecretVersions(resource).isEmpty());
        assertTrue(verifiedService.getIamPolicy(resource).getBindingsList().isEmpty());
        verified.close();
    }

    private Stores openStores(StorageMode mode) {
        StorageBackend<String, StoredSecret> secrets = open(mode, "secrets",
                new TypeReference<Map<String, StoredSecret>>() {});
        StorageBackend<String, StoredSecretVersion> versions = open(mode, "versions",
                new TypeReference<Map<String, StoredSecretVersion>>() {});
        StorageBackend<String, String> deletions = open(mode, "deletions",
                new TypeReference<Map<String, String>>() {});
        StorageBackend<String, StoredPolicy> policies = open(mode, "policies",
                new TypeReference<Map<String, StoredPolicy>>() {});
        secrets.load();
        versions.load();
        deletions.load();
        policies.load();
        return new Stores(secrets, versions, deletions, policies);
    }

    private <V> StorageBackend<String, V> open(StorageMode mode, String name,
            TypeReference<Map<String, V>> type) {
        Path base = tempDir.resolve(mode.name().toLowerCase());
        return switch (mode) {
            case PERSISTENT -> new PersistentStorage<>(base.resolve(name + ".json"), type);
            case HYBRID -> new HybridStorage<>(base.resolve(name + ".json"), type, BACKGROUND_INTERVAL_MS);
            case WAL -> new WalStorage<>(base.resolve(name + ".json"), base.resolve(name + ".wal"), type,
                    BACKGROUND_INTERVAL_MS);
        };
    }

    private record Stores(StorageBackend<String, StoredSecret> secrets,
            StorageBackend<String, StoredSecretVersion> versions,
            StorageBackend<String, String> deletions,
            StorageBackend<String, StoredPolicy> policies) {

        private SecretManagerService service() {
            return new SecretManagerService(secrets, versions, deletions,
                    IamServices.withStores(new io.floci.gcp.core.storage.InMemoryStorage<>(),
                            new io.floci.gcp.core.storage.InMemoryStorage<>(), policies));
        }

        private void close() {
            closeStorage(secrets);
            closeStorage(versions);
            closeStorage(deletions);
            closeStorage(policies);
        }

        private static void closeStorage(StorageBackend<?, ?> storage) {
            if (storage instanceof HybridStorage<?, ?> hybrid) {
                hybrid.shutdown();
            } else if (storage instanceof WalStorage<?, ?> wal) {
                wal.shutdown();
            }
        }
    }

    private enum StorageMode {
        PERSISTENT,
        HYBRID,
        WAL
    }
}

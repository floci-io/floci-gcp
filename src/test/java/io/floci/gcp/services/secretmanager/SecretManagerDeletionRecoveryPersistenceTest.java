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
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import io.floci.gcp.services.iam.model.StoredServiceAccountKey;
import io.floci.gcp.services.secretmanager.model.StoredSecret;
import io.floci.gcp.services.secretmanager.model.StoredSecretVersion;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretManagerDeletionRecoveryPersistenceTest {

    @TempDir
    Path storagePath;

    @ParameterizedTest
    @EnumSource(StorageMode.class)
    void resumesInterruptedDeletionAfterRestart(StorageMode mode) {
        String resource = "projects/p1/secrets/persisted-delete";
        try (Stores first = new Stores(storagePath, mode)) {
            SecretManagerService service = first.service();
            service.createSecret("p1", "persisted-delete", "automatic");
            service.addSecretVersion(resource, new byte[]{1}, null);
            service.setIamPolicy(resource, Policy.newBuilder()
                    .addBindings(Binding.newBuilder()
                            .setRole("roles/secretmanager.secretAccessor")
                            .addMembers("user:reader@example.com"))
                    .build());
            first.flushResourceState();

            // This is the durable state immediately before the deletion cleanup begins.
            first.pendingDeletions.put(resource, resource);
            first.pendingDeletions.flush();
        }

        try (Stores restarted = new Stores(storagePath, mode)) {
            SecretManagerService service = restarted.service();
            service.resumePendingDeletions();

            assertThrows(GcpException.class, () -> service.getSecret(resource));
            assertTrue(service.listSecretVersions(resource).isEmpty());

            service.createSecret("p1", "persisted-delete", "automatic");
            assertTrue(service.getIamPolicy(resource).getBindingsList().isEmpty());
        }
    }

    private enum StorageMode {
        PERSISTENT, WAL, HYBRID
    }

    private static final class Stores implements AutoCloseable {
        private static final long FLUSH_INTERVAL_MS = 60_000L;

        private final List<StorageBackend<?, ?>> backends = new ArrayList<>();
        private final StorageBackend<String, StoredSecret> secrets;
        private final StorageBackend<String, StoredSecretVersion> versions;
        private final StorageBackend<String, String> pendingDeletions;
        private final StorageBackend<String, StoredPolicy> policies;
        private final SecretManagerService service;

        private Stores(Path storagePath, StorageMode mode) {
            secrets = create(storagePath, mode, "secrets", new TypeReference<Map<String, StoredSecret>>() {});
            versions = create(storagePath, mode, "versions", new TypeReference<Map<String, StoredSecretVersion>>() {});
            pendingDeletions = create(storagePath, mode, "deletions", new TypeReference<Map<String, String>>() {});
            StorageBackend<String, StoredServiceAccount> serviceAccounts = create(storagePath, mode, "service-accounts",
                    new TypeReference<Map<String, StoredServiceAccount>>() {});
            StorageBackend<String, StoredServiceAccountKey> serviceAccountKeys = create(storagePath, mode, "service-account-keys",
                    new TypeReference<Map<String, StoredServiceAccountKey>>() {});
            policies = create(storagePath, mode, "policies", new TypeReference<Map<String, StoredPolicy>>() {});
            service = new SecretManagerService(secrets, versions, pendingDeletions,
                    IamServices.withStores(serviceAccounts, serviceAccountKeys, policies));
        }

        private SecretManagerService service() {
            return service;
        }

        private void flushResourceState() {
            secrets.flush();
            versions.flush();
            policies.flush();
        }

        private <T> StorageBackend<String, T> create(Path storagePath, StorageMode mode, String name,
                TypeReference<Map<String, T>> type) {
            StorageBackend<String, T> backend = switch (mode) {
                case PERSISTENT -> new PersistentStorage<>(storagePath.resolve(name + ".json"), type);
                case WAL -> new WalStorage<>(storagePath.resolve(name + "-snapshot.json"),
                        storagePath.resolve(name + ".wal"), type, FLUSH_INTERVAL_MS);
                case HYBRID -> new HybridStorage<>(storagePath.resolve(name + ".json"), type, FLUSH_INTERVAL_MS);
            };
            backend.load();
            backends.add(backend);
            return backend;
        }

        @Override
        public void close() {
            for (StorageBackend<?, ?> backend : backends) {
                if (backend instanceof HybridStorage<?, ?> hybrid) {
                    hybrid.shutdown();
                } else if (backend instanceof WalStorage<?, ?> wal) {
                    wal.shutdown();
                } else {
                    backend.flush();
                }
            }
        }
    }
}

package io.floci.gcp.core.storage;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.RequestContext;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory that creates {@link ProjectAwareStorageBackend} instances based on configuration.
 * Every backend created via {@link #create} is wrapped in a project-aware decorator so
 * resources are automatically namespaced by the calling GCP project ID; globally
 * namespaced services (e.g. GCS buckets) use {@link #createGlobal} instead.
 * Tracks all created backends for lifecycle management.
 */
@ApplicationScoped
public class StorageFactory {

    private static final Logger LOG = Logger.getLogger(StorageFactory.class);

    private final EmulatorConfig config;
    private final List<StorageBackend<?, ?>> allBackends = new ArrayList<>();
    // A file path identifies one logical store: callers sharing a path are expected to agree on
    // its value type, storage mode, and scoping (global vs project-aware). The first create wins;
    // repeat calls reuse that backend.
    private final Map<Path, StorageBackend<?, ?>> backendsByPath = new HashMap<>();
    private final List<HybridStorage<?, ?>> hybridBackends = new ArrayList<>();
    private final List<WalStorage<?, ?>> walBackends = new ArrayList<>();

    @Inject
    Instance<RequestContext> requestContextInstance;

    @Inject
    public StorageFactory(EmulatorConfig config) {
        this.config = config;
    }

    /**
     * Create a global (non-project-scoped) backend for services whose resources are
     * globally namespaced (e.g. GCS, where bucket names are globally unique).
     *
     * @param serviceName   the service name (gcs, pubsub, iam, …)
     * @param fileName      the JSON file name for persistent storage
     * @param typeReference Jackson type reference for deserialization
     */
    public synchronized <V> StorageBackend<String, V> createGlobal(String serviceName, String fileName,
                                                       TypeReference<Map<String, V>> typeReference) {
        String mode = config.storage().mode();
        Path filePath = Path.of(config.storage().persistentPath()).resolve(fileName);

        StorageBackend<String, V> existing = findExisting(mode, serviceName, filePath);
        if (existing != null) {
            return existing;
        }

        LOG.debugv("Creating {0} global storage for service {1} (file: {2})", mode, serviceName, filePath);

        StorageBackend<String, V> inner = createInner(mode, fileName, filePath, typeReference);
        inner.load();
        allBackends.add(inner);
        backendsByPath.put(filePath, inner);
        return inner;
    }

    /**
     * Create a project-aware storage backend for the given service.
     * All keys are automatically prefixed with the current project ID derived from
     * the request context. Async workers should use the {@code *ForProject} overloads
     * on {@link ProjectAwareStorageBackend} with the project ID stored on the resource model.
     *
     * @param serviceName   the service name (cloudsql, bigquery, monitoring, …)
     * @param fileName      the JSON file name for persistent storage
     * @param typeReference Jackson type reference for deserialization
     */
    public synchronized <V> StorageBackend<String, V> create(String serviceName, String fileName,
                                                     TypeReference<Map<String, V>> typeReference) {
        String mode = config.storage().mode();
        Path filePath = Path.of(config.storage().persistentPath()).resolve(fileName);

        StorageBackend<String, V> existing = findExisting(mode, serviceName, filePath);
        if (existing != null) {
            return existing;
        }

        LOG.debugv("Creating {0} storage for service {1} (file: {2})", mode, serviceName, filePath);

        StorageBackend<String, V> inner = createInner(mode, fileName, filePath, typeReference);
        inner.load();

        ProjectAwareStorageBackend<V> backend = new ProjectAwareStorageBackend<>(
                inner, requestContextInstance, config.defaultProjectId());
        allBackends.add(backend);
        backendsByPath.put(filePath, backend);
        return backend;
    }

    /**
     * Reuse an existing backend bound to the same file. Handing out a second backend bound to
     * the same path creates a duplicate in-memory store; on shutdown the stale duplicate flushes
     * after the active instance and clobbers persisted state.
     */
    private <V> StorageBackend<String, V> findExisting(String mode, String serviceName, Path filePath) {
        StorageBackend<?, ?> existing = backendsByPath.get(filePath);
        if (existing == null) {
            return null;
        }
        LOG.debugv("Reusing existing {0} storage for service {1} (file: {2})", mode, serviceName, filePath);
        @SuppressWarnings("unchecked")
        StorageBackend<String, V> typed = (StorageBackend<String, V>) existing;
        return typed;
    }

    private <V> StorageBackend<String, V> createInner(String mode, String fileName, Path filePath,
                                                      TypeReference<Map<String, V>> typeReference) {
        Path basePath = Path.of(config.storage().persistentPath());
        return switch (mode) {
            case "memory" -> new InMemoryStorage<>();
            case "persistent" -> new PersistentStorage<>(filePath, typeReference);
            case "hybrid" -> {
                var hybrid = new HybridStorage<>(filePath, typeReference, 5000L);
                hybridBackends.add(hybrid);
                yield hybrid;
            }
            case "wal" -> {
                Path snapshotPath = basePath.resolve(fileName.replace(".json", "-snapshot.json"));
                Path walFilePath = basePath.resolve(fileName.replace(".json", ".wal"));
                long compactionInterval = config.storage().wal().compactionIntervalMs();
                var wal = new WalStorage<>(snapshotPath, walFilePath, typeReference, compactionInterval);
                walBackends.add(wal);
                yield wal;
            }
            default -> throw new IllegalArgumentException("Unknown storage mode: " + mode);
        };
    }

    /** Load all storage backends from disk. */
    public synchronized void loadAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.load();
        }
    }

    /** Flush all storage backends to disk. */
    public synchronized void flushAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.flush();
        }
    }

    /** Clear all storage backends. */
    public synchronized void clearAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.clear();
        }
        flushAll();
    }

    /** Shutdown all managed backends (stop schedulers, close connections). */
    public synchronized void shutdownAll() {
        for (HybridStorage<?, ?> hybrid : hybridBackends) {
            hybrid.shutdown();
        }
        for (WalStorage<?, ?> wal : walBackends) {
            wal.shutdown();
        }
        flushAll();
    }
}

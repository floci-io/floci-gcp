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
import java.util.List;
import java.util.Map;

/**
 * Creates {@link ProjectAwareStorageBackend} instances based on configuration.
 * Every backend is wrapped in a project-aware decorator so resources are automatically
 * namespaced by the calling GCP project ID.
 */
@ApplicationScoped
public class StorageFactory {

    private static final Logger LOG = Logger.getLogger(StorageFactory.class);

    private final EmulatorConfig config;
    private final List<StorageBackend<?, ?>> allBackends = new ArrayList<>();
    private final List<HybridStorage<?, ?>> hybridBackends = new ArrayList<>();
    private final List<WalStorage<?, ?>> walBackends = new ArrayList<>();

    @Inject
    Instance<RequestContext> requestContextInstance;

    @Inject
    public StorageFactory(EmulatorConfig config) {
        this.config = config;
    }

    /**
     * Creates a global (non-project-scoped) backend for services whose resources are
     * globally namespaced (e.g. GCS, where bucket names are globally unique).
     */
    public <V> StorageBackend<String, V> createGlobal(String serviceName, String fileName,
                                                       TypeReference<Map<String, V>> typeReference) {
        return createGlobal(serviceName, fileName, typeReference, config.storage().mode());
    }

    public <V> StorageBackend<String, V> createGlobal(String serviceName, String fileName,
                                                       TypeReference<Map<String, V>> typeReference,
                                                       String modeOverride) {
        String mode = modeOverride != null ? modeOverride : config.storage().mode();
        Path basePath = Path.of(config.storage().persistentPath());
        Path filePath = basePath.resolve(fileName);

        LOG.debugv("Creating {0} global storage for service {1}", mode, serviceName);

        StorageBackend<String, V> inner = switch (mode) {
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

        inner.load();
        allBackends.add(inner);
        return inner;
    }

    public <V> ProjectAwareStorageBackend<V> create(String serviceName, String fileName,
                                                     TypeReference<Map<String, V>> typeReference) {
        return create(serviceName, fileName, typeReference, config.storage().mode());
    }

    public <V> ProjectAwareStorageBackend<V> create(String serviceName, String fileName,
                                                     TypeReference<Map<String, V>> typeReference,
                                                     String modeOverride) {
        String mode = modeOverride != null ? modeOverride : config.storage().mode();
        Path basePath = Path.of(config.storage().persistentPath());
        Path filePath = basePath.resolve(fileName);

        LOG.debugv("Creating {0} storage for service {1}", mode, serviceName);

        StorageBackend<String, V> inner = switch (mode) {
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

        inner.load();

        ProjectAwareStorageBackend<V> backend = new ProjectAwareStorageBackend<>(
                inner, requestContextInstance, config.defaultProjectId());
        allBackends.add(backend);
        return backend;
    }

    public void loadAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.load();
        }
    }

    public void shutdownAll() {
        for (HybridStorage<?, ?> hybrid : hybridBackends) {
            hybrid.shutdown();
        }
        for (WalStorage<?, ?> wal : walBackends) {
            wal.shutdown();
        }
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.flush();
        }
    }
}

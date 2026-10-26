package io.floci.gcp.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * JSON file-backed write-through storage. Publish an in-memory map mutation only
 * after its atomic replacement has been synced to disk. All operations use the
 * same monitor so readers and competing storage mutations cannot observe an
 * uncommitted candidate. Stored values remain live for compatibility with
 * services that mutate a value and then call checkpoint.
 */
public class PersistentStorage<K, V> implements StorageBackend<K, V> {

    private static final Logger LOG = Logger.getLogger(PersistentStorage.class);

    private Map<K, V> store = new HashMap<>();
    private final Path filePath;
    private final ObjectMapper objectMapper;
    private final TypeReference<Map<K, V>> typeReference;
    private final PersistentStorageIO disk;
    private boolean unavailable;

    public PersistentStorage(Path filePath, TypeReference<Map<K, V>> typeReference) {
        this(filePath, typeReference, new PersistentStorageIO());
    }

    // Package-local seam for deterministic disk failure injection, not runtime configuration.
    PersistentStorage(Path filePath, TypeReference<Map<K, V>> typeReference, PersistentStorageIO disk) {
        this.filePath = filePath;
        this.typeReference = typeReference;
        this.disk = disk;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public synchronized void put(K key, V value) {
        Map<K, V> candidate = new HashMap<>(store);
        candidate.put(Objects.requireNonNull(key), Objects.requireNonNull(value));
        commit(candidate);
    }

    @Override
    public synchronized Optional<V> get(K key) {
        ensureLoaded();
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public synchronized void delete(K key) {
        Map<K, V> candidate = new HashMap<>(store);
        candidate.remove(key);
        commit(candidate);
    }

    @Override
    public synchronized void applyBatch(Map<K, V> puts, Set<K> deletes) {
        Map<K, V> candidate = new HashMap<>(store);
        puts.forEach((key, value) -> candidate.put(
                Objects.requireNonNull(key), Objects.requireNonNull(value)));
        deletes.forEach(candidate::remove);
        commit(candidate);
    }

    @Override
    public synchronized List<V> scan(Predicate<K> keyFilter) {
        ensureLoaded();
        return store.entrySet().stream()
                .filter(entry -> keyFilter.test(entry.getKey()))
                .map(Map.Entry::getValue)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public synchronized Set<K> keys() {
        ensureLoaded();
        return Set.copyOf(store.keySet());
    }

    @Override
    public synchronized void flush() {
        try {
            commit(store);
        } catch (StorageException e) {
            LOG.errorv(e, "Failed to flush persistent data at {0}", filePath);
        }
    }

    @Override
    public synchronized void checkpoint() {
        commit(store);
    }

    @Override
    public synchronized void load() {
        byte[] data;
        try {
            data = disk.read(filePath);
        } catch (NoSuchFileException e) {
            store = new HashMap<>();
            unavailable = false;
            return;
        } catch (IOException e) {
            unavailable = true;
            throw failure("load", e);
        }
        try {
            Map<K, V> loaded = objectMapper.readValue(data, typeReference);
            // Preserve the non-null key/value invariant of the previous ConcurrentHashMap.
            if (loaded == null || loaded.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null)) {
                throw new IOException("Invalid persistent map");
            }
            store = loaded;
            unavailable = false;
        } catch (IOException | IllegalArgumentException e) {
            unavailable = true;
            // Never quarantine, overwrite, or silently replace unreadable persisted state.
            throw failure("load", e);
        }
    }

    @Override
    public synchronized void clear() {
        commit(new HashMap<>());
    }

    private void commit(Map<K, V> candidate) {
        ensureLoaded();
        Map<K, V> snapshot = copy(candidate);
        try {
            disk.write(filePath, objectMapper.writeValueAsBytes(snapshot));
        } catch (IOException e) {
            // An atomic replacement may already have landed before a sync failed.
            // Refuse stale reads/writes until load reconciles the on-disk state.
            unavailable = true;
            throw failure("persist", e);
        }
        // Preserve the original live-value contract. Several services mutate
        // values obtained from get/scan and make those mutations durable with a
        // later checkpoint. The detached snapshot above is only for serialization.
        store = candidate;
    }

    private Map<K, V> copy(Map<K, V> source) {
        try {
            return objectMapper.convertValue(source, typeReference);
        } catch (IllegalArgumentException e) {
            throw failure("copy", e);
        }
    }

    private void ensureLoaded() {
        if (unavailable) {
            throw new StorageException("Persistent storage unavailable after storage failure", null);
        }
    }

    private StorageException failure(String operation, Exception cause) {
        LOG.errorv(cause, "Failed to {0} persistent data at {1}", operation, filePath);
        return new StorageException("Persistent storage " + operation + " failed", cause);
    }
}

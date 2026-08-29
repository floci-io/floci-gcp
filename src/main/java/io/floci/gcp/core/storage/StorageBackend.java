package io.floci.gcp.core.storage;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Generic storage abstraction for GCP emulator services.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface StorageBackend<K, V> {

    void put(K key, V value);

    Optional<V> get(K key);

    void delete(K key);

    /**
     * Return a new mutable list of values whose keys pass the filter. Callers may sort,
     * filter, or otherwise mutate the returned list without affecting the underlying store.
     */
    List<V> scan(Predicate<K> keyFilter);

    /** Return all keys in this store. */
    Set<K> keys();

    /** Persist data to disk if applicable. */
    void flush();

    /**
     * Writes prior mutations to the filesystem or throws when it cannot do so.
     * Existing {@link #flush()} remains best-effort for lifecycle and background use.
     *
     * <p>Implementations rename a fully written temp file, so a reader never observes a
     * partial state and the boundary survives process death. There is no fsync, so the
     * boundary does not survive host power loss. Assumes a single writing process per
     * data directory; a concurrent process writes whole-map snapshots that silently
     * discard this one's mutations.
     */
    default void checkpoint() {
        flush();
    }

    /** Load data from disk on startup. */
    void load();

    /** Clear all data. */
    void clear();
}

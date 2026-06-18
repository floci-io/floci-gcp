package io.floci.gcp.core.storage;

import io.floci.gcp.core.common.RequestContext;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Decorator over {@link StorageBackend} that transparently prefixes every key
 * with the current GCP project ID, providing per-project resource isolation.
 *
 * <p>On the request path the project ID is read from {@link RequestContext},
 * populated by {@code ProjectContextFilter}. Outside a request (async workers,
 * startup) the {@code defaultProjectId} is used.
 */
public class ProjectAwareStorageBackend<V> implements StorageBackend<String, V> {

    private final StorageBackend<String, V> delegate;
    private final Instance<RequestContext> requestContextInstance;
    private final String defaultProjectId;

    public ProjectAwareStorageBackend(StorageBackend<String, V> delegate,
                                      Instance<RequestContext> requestContextInstance,
                                      String defaultProjectId) {
        this.delegate = delegate;
        this.requestContextInstance = requestContextInstance;
        this.defaultProjectId = defaultProjectId;
    }

    @Override
    public void put(String key, V value) {
        delegate.put(prefixed(key), value);
    }

    @Override
    public Optional<V> get(String key) {
        return delegate.get(prefixed(key));
    }

    @Override
    public void delete(String key) {
        delegate.delete(prefixed(key));
    }

    @Override
    public List<V> scan(Predicate<String> keyFilter) {
        String prefix = projectId() + "/";
        return delegate.scan(k -> k.startsWith(prefix) && keyFilter.test(k.substring(prefix.length())));
    }

    @Override
    public Set<String> keys() {
        String prefix = projectId() + "/";
        return delegate.keys().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void flush() { delegate.flush(); }

    @Override
    public void load() { delegate.load(); }

    @Override
    public void clear() { delegate.clear(); }

    // --- Explicit-project methods for async workers ---

    public Optional<V> getForProject(String projectId, String key) {
        return delegate.get(projectId + "/" + key);
    }

    public void putForProject(String projectId, String key, V value) {
        delegate.put(projectId + "/" + key, value);
    }

    public void deleteForProject(String projectId, String key) {
        delegate.delete(projectId + "/" + key);
    }

    public List<V> scanForProject(String projectId, Predicate<String> keyFilter) {
        String prefix = projectId + "/";
        return delegate.scan(k -> k.startsWith(prefix) && keyFilter.test(k.substring(prefix.length())));
    }

    public Set<String> keysForProject(String projectId) {
        String prefix = projectId + "/";
        return delegate.keys().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    public Map<String, V> scanAllProjectsAsMap() {
        Map<String, V> result = new LinkedHashMap<>();
        for (String rawKey : delegate.keys()) {
            int slash = rawKey.indexOf('/');
            if (slash < 0) continue;
            String logicalKey = rawKey.substring(slash + 1);
            delegate.get(rawKey).ifPresent(v -> result.put(logicalKey, v));
        }
        return result;
    }

    public List<V> scanAllProjects(Predicate<String> keyFilter) {
        return delegate.keys().stream()
                .filter(rawKey -> {
                    int slash = rawKey.indexOf('/');
                    return slash >= 0 && keyFilter.test(rawKey.substring(slash + 1));
                })
                .flatMap(rawKey -> delegate.get(rawKey).stream())
                .toList();
    }

    // ---

    private String projectId() {
        if (requestContextInstance != null) {
            try {
                String id = requestContextInstance.get().getProjectId();
                if (id != null) {
                    return id;
                }
            } catch (ContextNotActiveException ignored) {
            }
        }
        return defaultProjectId;
    }

    private String prefixed(String key) {
        return projectId() + "/" + key;
    }
}

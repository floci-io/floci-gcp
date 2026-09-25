package io.floci.gcp.services.bigquery;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Source files of a running load job, held in memory while the SQL engine reads them through
 * {@link BigQueryInternalController}. Files are registered per job and released when the job
 * finishes.
 */
@ApplicationScoped
public class BigQueryLoadFiles {

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();

    public String register(byte[] data) {
        String id = UUID.randomUUID().toString().replace("-", "");
        files.put(id, data);
        return id;
    }

    public Optional<byte[]> get(String id) {
        return Optional.ofNullable(files.get(id));
    }

    public void release(List<String> ids) {
        ids.forEach(files::remove);
    }
}

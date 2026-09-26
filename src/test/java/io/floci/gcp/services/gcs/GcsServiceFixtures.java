package io.floci.gcp.services.gcs;

import io.floci.gcp.core.storage.InMemoryStorage;

/** Builds an in-memory {@link GcsService} for tests of other services that read and write buckets. */
public final class GcsServiceFixtures {

    private GcsServiceFixtures() {}

    public static GcsService inMemory(String defaultProjectId) {
        return new GcsService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), defaultProjectId);
    }
}

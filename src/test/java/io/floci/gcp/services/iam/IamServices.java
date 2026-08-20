package io.floci.gcp.services.iam;

import io.floci.gcp.core.storage.InMemoryStorage;

/** Test factory granting other packages access to the package-private constructor. */
public final class IamServices {

    private IamServices() {}

    public static IamService inMemory() {
        return new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
    }
}

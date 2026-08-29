package io.floci.gcp.services.iam;

import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import io.floci.gcp.services.iam.model.StoredServiceAccountKey;

/** Test factory granting other packages access to the package-private constructor. */
public final class IamServices {

    private IamServices() {}

    public static IamService inMemory() {
        return new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
    }

    public static IamService withStores(StorageBackend<String, StoredServiceAccount> serviceAccounts,
            StorageBackend<String, StoredServiceAccountKey> keys,
            StorageBackend<String, StoredPolicy> policies) {
        return new IamService(serviceAccounts, keys, policies);
    }
}

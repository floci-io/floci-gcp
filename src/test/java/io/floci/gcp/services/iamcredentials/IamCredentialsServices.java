package io.floci.gcp.services.iamcredentials;

import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.iam.IamService;

import java.time.Clock;

/** Test factory granting other packages access to the package-private constructor. */
public final class IamCredentialsServices {

    private IamCredentialsServices() {}

    public static IamCredentialsService over(IamService iamService, Clock clock) {
        return new IamCredentialsService(new CredentialTokenService(new InMemoryStorage<>(), clock), iamService, clock);
    }
}

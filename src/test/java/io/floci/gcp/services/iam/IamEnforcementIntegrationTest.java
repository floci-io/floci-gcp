package io.floci.gcp.services.iam;

import io.floci.gcp.testing.IamEnforceProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(IamEnforceProfile.class)
class IamEnforcementIntegrationTest extends IamEnforcementContract {
    @Override
    boolean enforced() {
        return true;
    }
}

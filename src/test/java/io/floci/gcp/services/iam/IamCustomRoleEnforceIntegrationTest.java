package io.floci.gcp.services.iam;

import io.floci.gcp.testing.IamEnforceProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(IamEnforceProfile.class)
class IamCustomRoleEnforceIntegrationTest extends IamCustomRoleEnforcementContract {
    @Override
    boolean enforced() {
        return true;
    }
}

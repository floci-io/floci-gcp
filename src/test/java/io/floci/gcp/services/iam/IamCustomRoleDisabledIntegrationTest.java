package io.floci.gcp.services.iam;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class IamCustomRoleDisabledIntegrationTest extends IamCustomRoleEnforcementContract {
    @Override
    boolean enforced() {
        return false;
    }
}

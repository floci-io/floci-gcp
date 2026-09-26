package io.floci.gcp.services.iam;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class IamEnforcementDisabledIntegrationTest extends IamEnforcementContract {
    @Override
    boolean enforced() {
        return false;
    }
}

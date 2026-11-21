package io.floci.gcp.services.pubsub;

import io.floci.gcp.testing.IamEnforceProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(IamEnforceProfile.class)
class PubSubIamEnforceIntegrationTest extends PubSubIamEnforcementContract {
    @Override
    boolean enforced() {
        return true;
    }
}

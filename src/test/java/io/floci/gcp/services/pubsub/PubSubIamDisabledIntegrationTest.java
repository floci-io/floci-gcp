package io.floci.gcp.services.pubsub;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class PubSubIamDisabledIntegrationTest extends PubSubIamEnforcementContract {
    @Override
    boolean enforced() {
        return false;
    }
}

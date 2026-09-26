package io.floci.gcp.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import java.util.Map;

@QuarkusTest
@TestProfile(IamEnforcementIntegrationTest.EnforceProfile.class)
class IamEnforcementIntegrationTest extends IamEnforcementContract {
    @Override
    boolean enforced() {
        return true;
    }

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }
}

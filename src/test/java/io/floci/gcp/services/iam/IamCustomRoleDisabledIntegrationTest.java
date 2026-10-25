package io.floci.gcp.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import java.util.Map;

@QuarkusTest
@TestProfile(IamCustomRoleDisabledIntegrationTest.DisabledProfile.class)
class IamCustomRoleDisabledIntegrationTest extends IamCustomRoleEnforcementContract {
    @Override
    boolean enforced() {
        return false;
    }

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "disabled");
        }
    }
}

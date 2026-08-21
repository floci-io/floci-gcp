package io.floci.gcp.services.iam;

import io.floci.gcp.config.EmulatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(IamAuthorizationModeConfigTest.EnforceAuthorizationProfile.class)
class IamAuthorizationModeConfigTest {

    @Inject
    EmulatorConfig config;

    @Test
    void acceptsEnforceAuthorizationModeWithoutChangingRequestBehavior() {
        assertEquals(EmulatorConfig.IamAuthorizationMode.ENFORCE,
                config.services().iam().authorizationMode());
    }

    public static class EnforceAuthorizationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }
}

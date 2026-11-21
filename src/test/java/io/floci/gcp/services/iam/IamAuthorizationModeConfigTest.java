package io.floci.gcp.services.iam;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.testing.IamEnforceProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(IamEnforceProfile.class)
class IamAuthorizationModeConfigTest {

    @Inject
    EmulatorConfig config;

    @Test
    void acceptsEnforceAuthorizationMode() {
        assertEquals(EmulatorConfig.IamAuthorizationMode.ENFORCE,
                config.services().iam().authorizationMode());
    }
}

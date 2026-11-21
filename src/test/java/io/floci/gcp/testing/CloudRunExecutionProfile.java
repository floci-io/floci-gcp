package io.floci.gcp.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Cloud Run running real containers, with a 2 s cleanup grace period. Shared so every class using it runs in one
 * app: Quarkus builds and starts a separate app for each profile class, even when two classes set the same
 * overrides.
 */
public class CloudRunExecutionProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci-gcp.services.cloudrun.mock", "false",
                "floci-gcp.services.cloudrun.execution.startup-timeout", "60s",
                "floci-gcp.services.cloudrun.execution.cleanup-timeout", "2s");
    }
}

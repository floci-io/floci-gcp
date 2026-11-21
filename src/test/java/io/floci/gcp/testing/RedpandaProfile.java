package io.floci.gcp.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Kafka on a real Redpanda container instead of the mock. Shared so every class using it runs in one app:
 * Quarkus builds and starts a separate app for each profile class, even when two classes set the same overrides.
 */
public class RedpandaProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci-gcp.services.kafka.mock", "false");
    }
}

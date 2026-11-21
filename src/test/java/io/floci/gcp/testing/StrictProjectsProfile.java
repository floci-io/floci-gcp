package io.floci.gcp.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Strict projects: requests for an unknown project are rejected. Shared so every class using it runs in one app:
 * Quarkus builds and starts a separate app for each profile class, even when two classes set the same overrides.
 */
public class StrictProjectsProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci-gcp.projects.strict", "true");
    }
}

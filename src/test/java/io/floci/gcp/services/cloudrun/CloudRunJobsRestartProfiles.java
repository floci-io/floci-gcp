package io.floci.gcp.services.cloudrun;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.nio.file.Path;
import java.util.Map;

/**
 * Two Quarkus profiles sharing one persistent storage directory, so that the emulator started for
 * {@link CloudRunJobsRestartPhase2RestIntegrationTest} loads the state left by
 * {@link CloudRunJobsRestartPhase1RestIntegrationTest}. Quarkus restarts the application between profiles and
 * orders profile groups by test class name, so phase 1 always runs first.
 */
final class CloudRunJobsRestartProfiles {

    static final Path STORAGE = Path.of("target", "cloudrun-jobs-restart-" + ProcessHandle.current().pid());
    static final Path MARKER = Path.of("target", "cloudrun-jobs-restart-" + ProcessHandle.current().pid() + ".marker");

    private CloudRunJobsRestartProfiles() {}

    private static Map<String, String> overrides(String cleanupTimeout) {
        return Map.of(
                "floci-gcp.storage.mode", "persistent",
                "floci-gcp.storage.persistent-path", STORAGE.toAbsolutePath().toString(),
                "floci-gcp.services.cloudrun.mock", "false",
                "floci-gcp.services.cloudrun.execution.startup-timeout", "60s",
                "floci-gcp.services.cloudrun.execution.cleanup-timeout", cleanupTimeout);
    }

    /**
     * A long SIGTERM grace keeps the task of the execution deleted in phase 1 stopping until the emulator stops,
     * so its run and delete operations are still pending across the restart.
     */
    public static class Phase1BeforeRestart implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return overrides("120s");
        }
    }

    public static class Phase2AfterRestart implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return overrides("2s");
        }
    }
}

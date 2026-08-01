package io.floci.gcp.core.storage;

import io.floci.gcp.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Validates at boot that the persistent storage path is usable whenever the emulator runs
 * with a non-memory storage mode. Without this check the failure surfaces lazily and
 * confusingly: services silently lose data at flush time instead of failing fast at startup.
 */
@ApplicationScoped
public class PersistentPathValidator {

    private final EmulatorConfig config;

    @Inject
    public PersistentPathValidator(EmulatorConfig config) {
        this.config = config;
    }

    /**
     * @throws IllegalStateException when persistence is enabled but the path is unusable
     */
    public void validateAtBoot() {
        String mode = config.storage().mode();
        if ("memory".equals(mode)) {
            return;
        }

        Path root = Path.of(config.storage().persistentPath());
        try {
            probeWritable(root);
        } catch (IOException | SecurityException e) {
            throw new IllegalStateException(
                    "Persistent storage path '" + root.toAbsolutePath()
                            + "' is not writable, but non-memory storage is enabled (mode=" + mode
                            + "). Fix the volume mount permissions (it may be read-only or root-owned),"
                            + " or point FLOCI_GCP_STORAGE_PERSISTENT_PATH at a writable directory.", e);
        }
    }

    static void probeWritable(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path probe = Files.createTempFile(dir, ".floci-gcp-write-probe", null);
        try {
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            // The write itself succeeded, so the path is writable; a failed cleanup
            // must not abort boot as a false "not writable".
            probe.toFile().deleteOnExit();
        }
    }
}

package io.floci.gcp.services.gcs;

import io.floci.gcp.config.EmulatorConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Permanently deletes soft-deleted GCS objects once their {@code hardDeleteTime} passes, so the
 * default seven-day soft delete policy does not hold deleted bytes for the life of the process.
 * Listing and restore already treat an expired object as gone; the sweep reclaims its storage.
 */
@ApplicationScoped
public class GcsSoftDeleteReaper {

    private static final Logger LOG = Logger.getLogger(GcsSoftDeleteReaper.class);

    private final GcsService gcsService;
    private final long sweepIntervalSeconds;
    private final boolean enabled;
    private final ScheduledExecutorService executor;

    @Inject
    public GcsSoftDeleteReaper(GcsService gcsService, EmulatorConfig config) {
        this.gcsService = gcsService;
        this.sweepIntervalSeconds = config.services().gcs().softDeleteSweepIntervalSeconds();
        this.enabled = config.services().gcs().enabled() && sweepIntervalSeconds > 0;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gcs-soft-delete-reaper");
            t.setDaemon(true);
            return t;
        });
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            LOG.debug("GCS soft delete reaper disabled by configuration");
            return;
        }
        executor.scheduleAtFixedRate(this::sweepSafely, sweepIntervalSeconds, sweepIntervalSeconds,
                TimeUnit.SECONDS);
        LOG.infov("GCS soft delete reaper started (sweep every {0}s)", sweepIntervalSeconds);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        executor.shutdownNow();
    }

    void sweepSafely() {
        try {
            sweep(Instant.now());
        } catch (Throwable t) {
            LOG.warnv("GCS soft delete sweep failed: {0}", t.getMessage());
        }
    }

    int sweep(Instant now) {
        return gcsService.purgeExpiredSoftDeletedObjects(now);
    }
}

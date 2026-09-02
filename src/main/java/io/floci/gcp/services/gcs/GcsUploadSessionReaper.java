package io.floci.gcp.services.gcs;

import io.floci.gcp.config.EmulatorConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drops abandoned GCS upload sessions so their buffered bytes are not held for the lifetime of
 * the process.
 *
 * A single background daemon thread ticks on a fixed interval and asks {@link GcsService} to
 * evict every resumable (REST) and streaming (gRPC) session whose last write is older than the
 * configured idle timeout. Sessions that are still being written to are untouched, and the
 * sweep takes the same per-session monitors the write paths hold, so an active upload cannot be
 * evicted mid-write.
 *
 * The idle timeout defaults to the real GCS resumable session window of seven days, so the
 * emulator's default behaviour matches GCP. Instances that are long-lived relative to that
 * window (CI containers, {@code compat-docker}, a dev container left up) should lower
 * {@code floci-gcp.services.gcs.upload-session-idle-timeout-seconds} to reclaim sooner.
 */
@ApplicationScoped
public class GcsUploadSessionReaper {

    private static final Logger LOG = Logger.getLogger(GcsUploadSessionReaper.class);

    private final GcsService gcsService;
    private final long idleTimeoutSeconds;
    private final long sweepIntervalSeconds;
    private final boolean enabled;
    private final ScheduledExecutorService executor;

    @Inject
    public GcsUploadSessionReaper(GcsService gcsService, EmulatorConfig config) {
        this.gcsService = gcsService;
        this.idleTimeoutSeconds = config.services().gcs().uploadSessionIdleTimeoutSeconds();
        this.sweepIntervalSeconds = config.services().gcs().uploadSessionSweepIntervalSeconds();
        this.enabled = config.services().gcs().enabled()
                && sweepIntervalSeconds > 0
                && idleTimeoutSeconds > 0;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gcs-upload-session-reaper");
            t.setDaemon(true);
            return t;
        });
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            LOG.debug("GCS upload session reaper disabled by configuration");
            return;
        }
        executor.scheduleAtFixedRate(this::sweepSafely, sweepIntervalSeconds, sweepIntervalSeconds,
                TimeUnit.SECONDS);
        LOG.infov("GCS upload session reaper started (sweep every {0}s, idle timeout {1}s)",
                sweepIntervalSeconds, idleTimeoutSeconds);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        executor.shutdownNow();
    }

    void sweepSafely() {
        try {
            sweep();
        } catch (Throwable t) {
            LOG.warnv("GCS upload session sweep failed: {0}", t.getMessage());
        }
    }

    int sweep() {
        return gcsService.evictExpiredUploadSessions(
                System.currentTimeMillis(), TimeUnit.SECONDS.toMillis(idleTimeoutSeconds));
    }
}

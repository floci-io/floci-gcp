package io.floci.gcp.services.bigquery;

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
 * Drops abandoned BigQuery resumable upload sessions so their buffered bytes are not held for the
 * lifetime of the process.
 *
 * Mirrors {@code GcsUploadSessionReaper}: a single daemon thread ticks on a fixed interval and asks
 * {@link BigQueryUploadController} to evict every session whose last write is older than the
 * configured idle timeout. The idle timeout defaults to the seven-day window of Google's resumable
 * upload protocol, which BigQuery media uploads share with GCS.
 */
@ApplicationScoped
public class BigQueryUploadSessionReaper {

    private static final Logger LOG = Logger.getLogger(BigQueryUploadSessionReaper.class);

    private final BigQueryUploadController uploads;
    private final long idleTimeoutSeconds;
    private final long sweepIntervalSeconds;
    private final boolean enabled;
    private final ScheduledExecutorService executor;

    @Inject
    public BigQueryUploadSessionReaper(BigQueryUploadController uploads, EmulatorConfig config) {
        this.uploads = uploads;
        this.idleTimeoutSeconds = config.services().bigquery().uploadSessionIdleTimeoutSeconds();
        this.sweepIntervalSeconds = config.services().bigquery().uploadSessionSweepIntervalSeconds();
        this.enabled = config.services().bigquery().enabled()
                && sweepIntervalSeconds > 0
                && idleTimeoutSeconds > 0;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bigquery-upload-session-reaper");
            t.setDaemon(true);
            return t;
        });
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            LOG.debug("BigQuery upload session reaper disabled by configuration");
            return;
        }
        executor.scheduleAtFixedRate(this::sweepSafely, sweepIntervalSeconds, sweepIntervalSeconds,
                TimeUnit.SECONDS);
        LOG.infov("BigQuery upload session reaper started (sweep every {0}s, idle timeout {1}s)",
                sweepIntervalSeconds, idleTimeoutSeconds);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        executor.shutdownNow();
    }

    void sweepSafely() {
        try {
            sweep();
        } catch (Throwable t) {
            LOG.warnv("BigQuery upload session sweep failed: {0}", t.getMessage());
        }
    }

    int sweep() {
        return uploads.evictExpiredSessions(System.currentTimeMillis(), TimeUnit.SECONDS.toMillis(idleTimeoutSeconds));
    }
}

package io.floci.gcp.services.scheduler;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.services.scheduler.model.StoredJob;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fires Cloud Scheduler jobs when their unix-cron schedule is due.
 *
 * A single background daemon thread ticks on a fixed interval, scans all persisted jobs, and
 * dispatches any ENABLED job whose next computed fire time has passed. Per-job "last fire"
 * state is kept in memory (by job name); restarts reset it, matching the emulator's loose
 * durability expectations. {@code RunJob} dispatches immediately via {@link SchedulerService}
 * independently of this tick.
 */
@ApplicationScoped
public class ScheduleDispatcher {

    private static final Logger LOG = Logger.getLogger(ScheduleDispatcher.class);

    private final SchedulerService schedulerService;
    private final long tickIntervalSeconds;
    private final boolean enabled;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<String, Instant> lastFireByName = new ConcurrentHashMap<>();

    @Inject
    public ScheduleDispatcher(SchedulerService schedulerService, EmulatorConfig config) {
        this.schedulerService = schedulerService;
        this.tickIntervalSeconds = config.services().scheduler().tickIntervalSeconds();
        this.enabled = config.services().scheduler().enabled()
                && config.services().scheduler().invocationEnabled();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler-dispatcher");
            t.setDaemon(true);
            return t;
        });
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            LOG.info("Scheduler dispatcher disabled by configuration");
            return;
        }
        executor.scheduleAtFixedRate(this::tickSafely, tickIntervalSeconds, tickIntervalSeconds, TimeUnit.SECONDS);
        LOG.infov("Scheduler dispatcher started (tick every {0}s)", tickIntervalSeconds);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        executor.shutdownNow();
    }

    void tickSafely() {
        try {
            tick(Instant.now());
        } catch (Throwable t) {
            LOG.warnv("Scheduler dispatcher tick failed: {0}", t.getMessage());
        }
    }

    void tick(Instant now) {
        List<StoredJob> jobs = schedulerService.listAllJobs();
        for (StoredJob job : jobs) {
            try {
                evaluate(job, now);
            } catch (Exception e) {
                LOG.warnv("Failed to evaluate job {0}: {1}", job.getName(), e.getMessage());
            }
        }
    }

    private void evaluate(StoredJob job, Instant now) {
        if (!"ENABLED".equalsIgnoreCase(job.getState())) {
            return;
        }
        if (job.getSchedule() == null || job.getSchedule().isBlank() || job.getTargetType() == null) {
            return;
        }

        Instant base = lastFireByName.computeIfAbsent(job.getName(), n -> seedBase(job, now));
        Instant nextFire;
        try {
            nextFire = SchedulerExpressionParser.nextCronFire(job.getSchedule(), base, job.getTimeZone());
        } catch (IllegalArgumentException e) {
            LOG.warnv("Unsupported schedule on job {0}: {1}", job.getName(), job.getSchedule());
            return;
        }
        if (now.isBefore(nextFire)) {
            return;
        }

        lastFireByName.put(job.getName(), now);
        LOG.infov("Firing job {0} (scheduled {1})", job.getName(), nextFire);
        schedulerService.fireJob(job, nextFire);
    }

    private static Instant seedBase(StoredJob job, Instant now) {
        if (job.getCreateTime() != null) {
            try {
                return Instant.parse(job.getCreateTime());
            } catch (Exception ignored) {
                // fall through
            }
        }
        return now.minusSeconds(1);
    }
}

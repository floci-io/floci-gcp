package io.floci.gcp.services.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.scheduler.model.StoredJob;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class SchedulerService {

    private static final Logger LOG = Logger.getLogger(SchedulerService.class);

    private final StorageBackend<String, StoredJob> jobStore;
    private final ScheduleInvoker invoker;

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final GrpcServerManager grpcServerManager;

    @Inject
    public SchedulerService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, GrpcServerManager grpcServerManager,
            ScheduleInvoker invoker) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
        this.invoker = invoker;
        this.jobStore = storageFactory.createGlobal("cloudscheduler-jobs", "cloudscheduler-jobs.json",
                new TypeReference<Map<String, StoredJob>>() {});
    }

    SchedulerService(StorageBackend<String, StoredJob> jobStore, ScheduleInvoker invoker) {
        this.jobStore = jobStore;
        this.invoker = invoker;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("scheduler")
                .enabled(config.services().scheduler().enabled())
                .storageKey("cloudscheduler")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(SchedulerController.class)
                .build());
        grpcServerManager.bind(new SchedulerController(this));
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────────

    public StoredJob createJob(String parent, StoredJob job) {
        String jobId = lastSegment(job.getName());
        if (jobId == null || jobId.isBlank()) {
            jobId = UUID.randomUUID().toString();
        }
        String name = parent + "/jobs/" + jobId;
        LOG.infof("createJob name=%s", name);
        if (jobStore.get(name).isPresent()) {
            throw GcpException.alreadyExists("Job already exists: " + name);
        }
        validateSchedule(job.getSchedule());

        String now = Instant.now().toString();
        job.setName(name);
        job.setState("ENABLED");
        job.setCreateTime(now);
        job.setUserUpdateTime(now);
        job.setScheduleTime(computeNextScheduleTime(job, Instant.now()));
        jobStore.put(name, job);
        return job;
    }

    public StoredJob getJob(String name) {
        LOG.debugf("getJob name=%s", name);
        return jobStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Job not found: " + name));
    }

    public List<StoredJob> listJobs(String project, String location) {
        LOG.debugf("listJobs project=%s location=%s", project, location);
        String prefix = "projects/" + project + "/locations/" + location + "/jobs/";
        return jobStore.scan(k -> k.startsWith(prefix));
    }

    public List<StoredJob> listAllJobs() {
        return jobStore.scan(k -> true);
    }

    public StoredJob updateJob(StoredJob incoming, List<String> updateMask) {
        String name = incoming.getName();
        LOG.infof("updateJob name=%s mask=%s", name, updateMask);
        StoredJob existing = jobStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Job not found: " + name));

        boolean all = updateMask == null || updateMask.isEmpty();
        if (all || maskHas(updateMask, "description")) {
            existing.setDescription(incoming.getDescription());
        }
        if (all || maskHas(updateMask, "schedule")) {
            validateSchedule(incoming.getSchedule());
            existing.setSchedule(incoming.getSchedule());
        }
        if (all || maskHas(updateMask, "time_zone", "timeZone")) {
            existing.setTimeZone(incoming.getTimeZone());
        }
        if (all || maskHas(updateMask, "retry_config", "retryConfig")) {
            existing.setRetryCount(incoming.getRetryCount());
            existing.setMaxRetryDurationSeconds(incoming.getMaxRetryDurationSeconds());
            existing.setMinBackoffSeconds(incoming.getMinBackoffSeconds());
            existing.setMaxBackoffSeconds(incoming.getMaxBackoffSeconds());
            existing.setMaxDoublings(incoming.getMaxDoublings());
        }
        if (all || maskHas(updateMask, "attempt_deadline", "attemptDeadline")) {
            existing.setAttemptDeadlineSeconds(incoming.getAttemptDeadlineSeconds());
        }
        if (all || maskHasTargetUpdate(updateMask)) {
            copyTarget(incoming, existing);
        }

        existing.setUserUpdateTime(Instant.now().toString());
        existing.setScheduleTime(computeNextScheduleTime(existing, Instant.now()));
        jobStore.put(name, existing);
        return existing;
    }

    public void deleteJob(String name) {
        LOG.infof("deleteJob name=%s", name);
        if (jobStore.get(name).isEmpty()) {
            throw GcpException.notFound("Job not found: " + name);
        }
        jobStore.delete(name);
    }

    // ── State + run ────────────────────────────────────────────────────────────────

    public StoredJob pauseJob(String name) {
        LOG.infof("pauseJob name=%s", name);
        StoredJob job = getJob(name);
        job.setState("PAUSED");
        jobStore.put(name, job);
        return job;
    }

    public StoredJob resumeJob(String name) {
        LOG.infof("resumeJob name=%s", name);
        StoredJob job = getJob(name);
        job.setState("ENABLED");
        job.setScheduleTime(computeNextScheduleTime(job, Instant.now()));
        jobStore.put(name, job);
        return job;
    }

    public StoredJob runJob(String name) {
        LOG.infof("runJob name=%s", name);
        StoredJob job = getJob(name);
        recordAttempt(job, invoker.invoke(job));
        jobStore.put(name, job);
        return job;
    }

    /** Used by the background dispatcher to fire a due job and persist the attempt result. */
    public void fireJob(StoredJob job, Instant scheduledFor) {
        recordAttempt(job, invoker.invoke(job));
        job.setScheduleTime(computeNextScheduleTime(job, scheduledFor));
        jobStore.put(job.getName(), job);
    }

    private void recordAttempt(StoredJob job, ScheduleInvoker.InvokeResult result) {
        job.setLastAttemptTime(Instant.now().toString());
        job.setStatusCode(result.code());
        job.setStatusMessage(result.message());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────

    private void validateSchedule(String schedule) {
        if (schedule == null || schedule.isBlank()) {
            return; // schedule is required except on UpdateJob; leave enforcement to GCP-parity tests
        }
        try {
            SchedulerExpressionParser.validate(schedule);
        } catch (Exception e) {
            throw GcpException.invalidArgument("Invalid schedule: " + schedule);
        }
    }

    private String computeNextScheduleTime(StoredJob job, Instant from) {
        if (job.getSchedule() == null || job.getSchedule().isBlank()) {
            return null;
        }
        try {
            return SchedulerExpressionParser.nextCronFire(job.getSchedule(), from, job.getTimeZone()).toString();
        } catch (Exception e) {
            LOG.warnf("Failed to compute next schedule time for %s: %s", job.getName(), e.getMessage());
            return null;
        }
    }

    private static void copyTarget(StoredJob from, StoredJob to) {
        to.setTargetType(from.getTargetType());
        to.setPubsubTopic(from.getPubsubTopic());
        to.setPubsubData(from.getPubsubData());
        to.setPubsubAttributes(from.getPubsubAttributes());
        to.setHttpUri(from.getHttpUri());
        to.setHttpMethod(from.getHttpMethod());
        to.setHttpHeaders(from.getHttpHeaders());
        to.setHttpBody(from.getHttpBody());
        to.setAppEngineHttpMethod(from.getAppEngineHttpMethod());
        to.setAppEngineRelativeUri(from.getAppEngineRelativeUri());
        to.setAppEngineHeaders(from.getAppEngineHeaders());
        to.setAppEngineBody(from.getAppEngineBody());
        to.setAppEngineService(from.getAppEngineService());
        to.setAppEngineVersion(from.getAppEngineVersion());
        to.setAppEngineInstance(from.getAppEngineInstance());
        to.setAppEngineHost(from.getAppEngineHost());
    }

    private static boolean maskHas(List<String> mask, String... paths) {
        for (String p : mask) {
            for (String want : paths) {
                if (p.equals(want)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean maskHasTargetUpdate(List<String> mask) {
        return maskHas(mask, "pubsub_target", "pubsubTarget", "http_target", "httpTarget",
                "app_engine_http_target", "appEngineHttpTarget");
    }

    private static String lastSegment(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        int idx = name.lastIndexOf('/');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }
}

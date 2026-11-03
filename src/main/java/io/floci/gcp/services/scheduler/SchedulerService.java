package io.floci.gcp.services.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.LocationCatalog;
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
    private final LocationCatalog locations;

    @Inject
    public SchedulerService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, GrpcServerManager grpcServerManager,
            ScheduleInvoker invoker, LocationCatalog locations) {
        this.serviceRegistry = serviceRegistry;
        this.locations = locations;
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
        this.locations = LocationCatalog.lenient();
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("scheduler")
                .enabled(config.services().scheduler().enabled())
                .storageKey("cloudscheduler")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(SchedulerController.class)
                .api("cloudscheduler.googleapis.com", "Cloud Scheduler API", SchedulerHttpController.class)
                .build());
        grpcServerManager.bind(new SchedulerController(this));
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────────

    public StoredJob createJob(String parent, StoredJob job) {
        locations.requireParentLocation(parent, LocationCatalog.Kind.REGION);
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
        validateHttpTarget(job);

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
        locations.requireListLocation(location, LocationCatalog.Kind.REGION);
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
        boolean replaceTarget = all || maskHasTargetUpdate(updateMask);
        boolean httpSubPaths = !replaceTarget && maskHasHttpSubPath(updateMask);

        // Everything that can reject the update runs first: storage may hand back the live job.
        if (all || maskHas(updateMask, "schedule")) {
            validateSchedule(incoming.getSchedule());
        }
        if (replaceTarget) {
            validateHttpTarget(incoming);
        } else if (httpSubPaths) {
            if (!"HTTP".equals(existing.getTargetType())) {
                throw GcpException.invalidArgument("update_mask http_target.* requires a job with an HTTP target");
            }
            if (httpMask(updateMask, "uri", "uri")) {
                validateHttpUri(incoming.getHttpUri());
            }
        }

        if (all || maskHas(updateMask, "description")) {
            existing.setDescription(incoming.getDescription());
        }
        if (all || maskHas(updateMask, "schedule")) {
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
        if (replaceTarget) {
            copyTarget(incoming, existing);
        } else if (httpSubPaths) {
            applyHttpTargetSubPaths(incoming, existing, updateMask);
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

    private static void validateHttpTarget(StoredJob job) {
        if ("HTTP".equals(job.getTargetType())) {
            validateHttpUri(job.getHttpUri());
        }
    }

    private static void validateHttpUri(String uri) {
        if (uri == null || !(uri.startsWith("http://") || uri.startsWith("https://"))) {
            throw GcpException.invalidArgument("HttpTarget.uri must begin with http:// or https://");
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
        to.setHttpOauthServiceAccountEmail(from.getHttpOauthServiceAccountEmail());
        to.setHttpOauthScope(from.getHttpOauthScope());
        to.setHttpOidcServiceAccountEmail(from.getHttpOidcServiceAccountEmail());
        to.setHttpOidcAudience(from.getHttpOidcAudience());
        to.setAppEngineHttpMethod(from.getAppEngineHttpMethod());
        to.setAppEngineRelativeUri(from.getAppEngineRelativeUri());
        to.setAppEngineHeaders(from.getAppEngineHeaders());
        to.setAppEngineBody(from.getAppEngineBody());
        to.setAppEngineService(from.getAppEngineService());
        to.setAppEngineVersion(from.getAppEngineVersion());
        to.setAppEngineInstance(from.getAppEngineInstance());
        to.setAppEngineHost(from.getAppEngineHost());
    }

    /**
     * Applies {@code http_target.*} masks, down to single token fields as gcloud sends them. A masked field missing
     * from the request is cleared. The tokens stay a oneof: setting one clears the other.
     */
    private static void applyHttpTargetSubPaths(StoredJob from, StoredJob to, List<String> mask) {
        if (httpMask(mask, "uri", "uri")) {
            to.setHttpUri(from.getHttpUri());
        }
        if (httpMask(mask, "http_method", "httpMethod")) {
            to.setHttpMethod(from.getHttpMethod());
        }
        if (httpMask(mask, "headers", "headers")) {
            to.setHttpHeaders(from.getHttpHeaders());
        }
        if (httpMask(mask, "body", "body")) {
            to.setHttpBody(from.getHttpBody());
        }
        boolean oauthToken = httpMask(mask, "oauth_token", "oauthToken");
        boolean oidcToken = httpMask(mask, "oidc_token", "oidcToken");
        boolean oauthEmail = oauthToken || httpMask(mask, "oauth_token.service_account_email", "oauthToken.serviceAccountEmail");
        boolean oauthScope = oauthToken || httpMask(mask, "oauth_token.scope", "oauthToken.scope");
        boolean oidcEmail = oidcToken || httpMask(mask, "oidc_token.service_account_email", "oidcToken.serviceAccountEmail");
        boolean oidcAudience = oidcToken || httpMask(mask, "oidc_token.audience", "oidcToken.audience");
        if (oauthEmail) {
            to.setHttpOauthServiceAccountEmail(from.getHttpOauthServiceAccountEmail());
        }
        if (oauthScope) {
            to.setHttpOauthScope(from.getHttpOauthScope());
        }
        if (oidcEmail) {
            to.setHttpOidcServiceAccountEmail(from.getHttpOidcServiceAccountEmail());
        }
        if (oidcAudience) {
            to.setHttpOidcAudience(from.getHttpOidcAudience());
        }
        boolean oauthSet = (oauthEmail && from.getHttpOauthServiceAccountEmail() != null)
                || (oauthScope && from.getHttpOauthScope() != null);
        boolean oidcSet = (oidcEmail && from.getHttpOidcServiceAccountEmail() != null)
                || (oidcAudience && from.getHttpOidcAudience() != null);
        if (oauthSet) {
            to.setHttpOidcServiceAccountEmail(null);
            to.setHttpOidcAudience(null);
        } else if (oidcSet) {
            to.setHttpOauthServiceAccountEmail(null);
            to.setHttpOauthScope(null);
        }
    }

    private static boolean httpMask(List<String> mask, String snakePath, String camelPath) {
        return maskHas(mask, "http_target." + snakePath, "httpTarget." + camelPath);
    }

    private static boolean maskHasHttpSubPath(List<String> mask) {
        return mask.stream().anyMatch(p -> p.startsWith("http_target.") || p.startsWith("httpTarget."));
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

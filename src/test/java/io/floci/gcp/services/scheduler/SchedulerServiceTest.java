package io.floci.gcp.services.scheduler;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.scheduler.model.StoredJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerServiceTest {

    private static final String PARENT = "projects/p1/locations/us-central1";
    private static final String NAME = PARENT + "/jobs/j1";

    private SchedulerService service;

    @BeforeEach
    void setUp() {
        // ScheduleInvoker with a null Pub/Sub backend; only the HTTP/no-op paths are exercised
        // here, and invoke() never throws (it records failures as a status instead).
        service = new SchedulerService(new InMemoryStorage<>(), new ScheduleInvoker(null, true));
    }

    private StoredJob pubsubJob(String jobId) {
        StoredJob job = new StoredJob();
        job.setName(jobId);
        job.setSchedule("*/5 * * * *");
        job.setTargetType("PUBSUB");
        job.setPubsubTopic("projects/p1/topics/t1");
        job.setPubsubData("hi".getBytes());
        return job;
    }

    @Test
    void createJobDefaultsToEnabledAndComputesScheduleTime() {
        StoredJob created = service.createJob(PARENT, pubsubJob("j1"));
        assertEquals(NAME, created.getName());
        assertEquals("ENABLED", created.getState());
        assertNotNull(created.getCreateTime());
        assertNotNull(created.getScheduleTime(), "next schedule time should be computed from cron");
    }

    @Test
    void createJobDuplicateThrowsAlreadyExists() {
        service.createJob(PARENT, pubsubJob("j1"));
        GcpException ex = assertThrows(GcpException.class, () -> service.createJob(PARENT, pubsubJob("j1")));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
    }

    @Test
    void createJobInvalidScheduleThrowsInvalidArgument() {
        StoredJob job = pubsubJob("bad");
        job.setSchedule("not a cron");
        GcpException ex = assertThrows(GcpException.class, () -> service.createJob(PARENT, job));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void getJobMissingThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class, () -> service.getJob(NAME));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void listJobsReturnsCreated() {
        service.createJob(PARENT, pubsubJob("j1"));
        service.createJob(PARENT, pubsubJob("j2"));
        List<StoredJob> jobs = service.listJobs("p1", "us-central1");
        assertEquals(2, jobs.size());
    }

    @Test
    void pauseThenResumeFlipsState() {
        service.createJob(PARENT, pubsubJob("j1"));
        assertEquals("PAUSED", service.pauseJob(NAME).getState());
        assertEquals("ENABLED", service.resumeJob(NAME).getState());
    }

    @Test
    void updateJobAppliesDescriptionViaMask() {
        service.createJob(PARENT, pubsubJob("j1"));
        StoredJob incoming = new StoredJob();
        incoming.setName(NAME);
        incoming.setDescription("updated");
        StoredJob updated = service.updateJob(incoming, List.of("description"));
        assertEquals("updated", updated.getDescription());
    }

    @Test
    void deleteJobRemovesIt() {
        service.createJob(PARENT, pubsubJob("j1"));
        service.deleteJob(NAME);
        GcpException ex = assertThrows(GcpException.class, () -> service.getJob(NAME));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void runJobRecordsAttempt() {
        StoredJob job = pubsubJob("j1");
        job.setTargetType("HTTP");
        job.setHttpUri("http://127.0.0.1:1/"); // connection refused -> recorded as failed attempt
        service.createJob(PARENT, job);
        StoredJob ran = service.runJob(NAME);
        assertNotNull(ran.getLastAttemptTime(), "runJob must record a last-attempt time");
    }

    private StoredJob httpOauthJob(String jobId) {
        StoredJob job = new StoredJob();
        job.setName(jobId);
        job.setSchedule("*/5 * * * *");
        job.setTargetType("HTTP");
        job.setHttpUri("https://example.test/a");
        job.setHttpMethod("POST");
        job.setHttpOauthServiceAccountEmail("old@p1.iam.gserviceaccount.com");
        job.setHttpOauthScope("scope-old");
        return job;
    }

    private StoredJob incomingHttp() {
        StoredJob incoming = new StoredJob();
        incoming.setName(NAME);
        incoming.setTargetType("HTTP");
        return incoming;
    }

    @Test
    void updateJobOauthTokenMaskReplacesOauthSettingsOnly() {
        service.createJob(PARENT, httpOauthJob("j1"));
        StoredJob incoming = incomingHttp();
        incoming.setHttpOauthServiceAccountEmail("new@p1.iam.gserviceaccount.com");
        incoming.setHttpOauthScope("scope-new");
        StoredJob updated = service.updateJob(incoming, List.of("http_target.oauth_token"));
        assertEquals("new@p1.iam.gserviceaccount.com", updated.getHttpOauthServiceAccountEmail());
        assertEquals("scope-new", updated.getHttpOauthScope());
        assertEquals("https://example.test/a", updated.getHttpUri());
        assertEquals("POST", updated.getHttpMethod());
    }

    @Test
    void updateJobOauthTokenMaskWithAbsentTokenClearsIt() {
        service.createJob(PARENT, httpOauthJob("j1"));
        StoredJob updated = service.updateJob(incomingHttp(), List.of("httpTarget.oauthToken"));
        assertNull(updated.getHttpOauthServiceAccountEmail());
        assertNull(updated.getHttpOauthScope());
        assertEquals("https://example.test/a", updated.getHttpUri());
    }

    @Test
    void updateJobOidcTokenMaskReplacesOauthTokenAsOneOf() {
        service.createJob(PARENT, httpOauthJob("j1"));
        StoredJob incoming = incomingHttp();
        incoming.setHttpOidcServiceAccountEmail("oidc@p1.iam.gserviceaccount.com");
        incoming.setHttpOidcAudience("aud");
        StoredJob updated = service.updateJob(incoming, List.of("http_target.oidc_token"));
        assertEquals("oidc@p1.iam.gserviceaccount.com", updated.getHttpOidcServiceAccountEmail());
        assertEquals("aud", updated.getHttpOidcAudience());
        assertNull(updated.getHttpOauthServiceAccountEmail());
        assertNull(updated.getHttpOauthScope());
    }

    @Test
    void updateJobNestedTokenMasksFromGcloudReachSingleFields() {
        service.createJob(PARENT, httpOauthJob("j1"));
        StoredJob scope = incomingHttp();
        scope.setHttpOauthScope("scope-new");
        StoredJob updated = service.updateJob(scope, List.of("httpTarget.oauthToken.scope"));
        assertEquals("scope-new", updated.getHttpOauthScope());
        assertEquals("old@p1.iam.gserviceaccount.com", updated.getHttpOauthServiceAccountEmail());

        StoredJob oidc = incomingHttp();
        oidc.setHttpOidcServiceAccountEmail("oidc@p1.iam.gserviceaccount.com");
        updated = service.updateJob(oidc, List.of("httpTarget.oidcToken.serviceAccountEmail"));
        assertEquals("oidc@p1.iam.gserviceaccount.com", updated.getHttpOidcServiceAccountEmail());
        assertNull(updated.getHttpOauthServiceAccountEmail());
        assertNull(updated.getHttpOauthScope());

        updated = service.updateJob(incomingHttp(), List.of("httpTarget.oidcToken.serviceAccountEmail",
                "httpTarget.oidcToken.audience", "httpTarget.oauthToken.serviceAccountEmail", "httpTarget.oauthToken.scope"));
        assertNull(updated.getHttpOidcServiceAccountEmail());
        assertNull(updated.getHttpOauthServiceAccountEmail());
        assertEquals("https://example.test/a", updated.getHttpUri());
    }

    @Test
    void updateJobHttpSubPathMasksLeaveOtherFieldsUntouched() {
        service.createJob(PARENT, httpOauthJob("j1"));
        StoredJob incoming = incomingHttp();
        incoming.setHttpUri("https://example.test/b");
        incoming.setHttpOidcServiceAccountEmail("unmasked@p1.iam.gserviceaccount.com");
        StoredJob updated = service.updateJob(incoming, List.of("http_target.uri"));
        assertEquals("https://example.test/b", updated.getHttpUri());
        assertEquals("POST", updated.getHttpMethod());
        assertEquals("old@p1.iam.gserviceaccount.com", updated.getHttpOauthServiceAccountEmail());
        assertNull(updated.getHttpOidcServiceAccountEmail());
    }

    @Test
    void updateJobHttpSubPathMaskOnNonHttpJobIsRejected() {
        service.createJob(PARENT, pubsubJob("j1"));
        GcpException ex = assertThrows(GcpException.class,
                () -> service.updateJob(incomingHttp(), List.of("http_target.oauth_token")));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void rejectedUpdateLeavesTheJobUnchanged() {
        service.createJob(PARENT, httpOauthJob("j1"));
        StoredJob badSchedule = incomingHttp();
        badSchedule.setDescription("changed");
        badSchedule.setSchedule("not a schedule");
        assertThrows(GcpException.class, () -> service.updateJob(badSchedule, List.of("description", "schedule")));
        assertNull(service.getJob(NAME).getDescription());

        StoredJob badUri = incomingHttp();
        badUri.setDescription("changed");
        badUri.setHttpUri("ftp://example.test");
        assertThrows(GcpException.class, () -> service.updateJob(badUri, List.of("description", "httpTarget.uri")));
        assertNull(service.getJob(NAME).getDescription());
        assertEquals("https://example.test/a", service.getJob(NAME).getHttpUri());

        service.createJob(PARENT, pubsubJob("j2"));
        StoredJob wrongTarget = incomingHttp();
        wrongTarget.setName(PARENT + "/jobs/j2");
        wrongTarget.setDescription("changed");
        assertThrows(GcpException.class, () -> service.updateJob(wrongTarget, List.of("description", "httpTarget.oauthToken")));
        assertNull(service.getJob(PARENT + "/jobs/j2").getDescription());
    }

    @Test
    void httpTargetUriMustBeHttpOrHttps() {
        for (String bad : new String[] {null, "", "example.test", "ftp://example.test"}) {
            StoredJob job = httpOauthJob("bad");
            job.setHttpUri(bad);
            GcpException ex = assertThrows(GcpException.class, () -> service.createJob(PARENT, job), String.valueOf(bad));
            assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        }
        service.createJob(PARENT, httpOauthJob("j1"));
        assertThrows(GcpException.class, () -> service.updateJob(incomingHttp(), List.of("httpTarget.uri")));
        assertThrows(GcpException.class, () -> service.updateJob(incomingHttp(), List.of("httpTarget")));
        assertThrows(GcpException.class, () -> service.updateJob(incomingHttp(), List.of()));
        assertEquals("https://example.test/a", service.getJob(NAME).getHttpUri());
    }
}

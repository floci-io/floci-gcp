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
}

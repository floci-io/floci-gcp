package io.floci.gcp.services.scheduler;

import com.google.cloud.scheduler.v1.CreateJobRequest;
import com.google.cloud.scheduler.v1.HttpTarget;
import com.google.cloud.scheduler.v1.Job;
import com.google.cloud.scheduler.v1.ListJobsRequest;
import com.google.cloud.scheduler.v1.ListJobsResponse;
import com.google.cloud.scheduler.v1.OAuthToken;
import com.google.cloud.scheduler.v1.OidcToken;
import com.google.cloud.scheduler.v1.PubsubTarget;
import com.google.protobuf.ByteString;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerControllerTest {

    private static final String PARENT = "projects/p1/locations/us-east1";

    private SchedulerService service;
    private SchedulerController controller;

    @BeforeEach
    void setUp() {
        service = new SchedulerService(new InMemoryStorage<>(), new ScheduleInvoker(null, true));
        controller = new SchedulerController(service);
    }

    private static Job job(String name) {
        return Job.newBuilder()
                .setName(name)
                .setSchedule("*/5 * * * *")
                .setPubsubTarget(PubsubTarget.newBuilder()
                        .setTopicName("projects/p1/topics/t1")
                        .setData(ByteString.copyFromUtf8("hi")))
                .build();
    }

    @Test
    void wellFormedParentCreatesAndListsJob() {
        RecordingObserver<Job> created = new RecordingObserver<>();
        controller.createJob(CreateJobRequest.newBuilder()
                .setParent(PARENT)
                .setJob(job(PARENT + "/jobs/j1"))
                .build(), created);
        assertNull(created.error);
        assertEquals(PARENT + "/jobs/j1", created.values.get(0).getName());

        RecordingObserver<ListJobsResponse> listed = new RecordingObserver<>();
        controller.listJobs(ListJobsRequest.newBuilder().setParent(PARENT).build(), listed);
        assertNull(listed.error);
        assertEquals(1, listed.values.get(0).getJobsCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"foo", "", "projects/p", "projects/p/regions/r"})
    void malformedParentIsInvalidArgument(String parent) {
        RecordingObserver<Job> created = new RecordingObserver<>();
        controller.createJob(CreateJobRequest.newBuilder()
                .setParent(parent)
                .setJob(job("j1"))
                .build(), created);
        assertInvalidArgument(created);
        assertTrue(service.listAllJobs().isEmpty());

        RecordingObserver<ListJobsResponse> listed = new RecordingObserver<>();
        controller.listJobs(ListJobsRequest.newBuilder().setParent(parent).build(), listed);
        assertInvalidArgument(listed);
    }

    private static void assertInvalidArgument(RecordingObserver<?> observer) {
        assertTrue(observer.values.isEmpty());
        StatusRuntimeException sre = assertInstanceOf(StatusRuntimeException.class, observer.error);
        assertEquals(Status.Code.INVALID_ARGUMENT, sre.getStatus().getCode());
    }

    @Test
    void httpTargetOauthAndOidcTokensRoundTrip() {
        HttpTarget oauth = HttpTarget.newBuilder().setUri("https://compute.googleapis.com/compute/v1/start")
                .setOauthToken(OAuthToken.newBuilder().setServiceAccountEmail("sa@p1.iam.gserviceaccount.com")
                        .setScope("https://www.googleapis.com/auth/cloud-platform")).build();
        Job stored = SchedulerController.toJobProto(SchedulerController.fromJobProto(
                Job.newBuilder().setName(PARENT + "/jobs/oauth").setSchedule("0 8 * * 6").setHttpTarget(oauth).build()));
        assertEquals(oauth.getOauthToken(), stored.getHttpTarget().getOauthToken());
        assertFalse(stored.getHttpTarget().hasOidcToken());

        HttpTarget oidc = HttpTarget.newBuilder().setUri("https://run.example/x")
                .setOidcToken(OidcToken.newBuilder().setServiceAccountEmail("sa@p1.iam.gserviceaccount.com")
                        .setAudience("https://run.example")).build();
        Job oidcJob = SchedulerController.toJobProto(SchedulerController.fromJobProto(
                Job.newBuilder().setName(PARENT + "/jobs/oidc").setSchedule("0 8 * * 6").setHttpTarget(oidc).build()));
        assertEquals(oidc.getOidcToken(), oidcJob.getHttpTarget().getOidcToken());
        assertFalse(oidcJob.getHttpTarget().hasOauthToken());
    }

    private static final class RecordingObserver<T> implements StreamObserver<T> {
        final List<T> values = new ArrayList<>();
        Throwable error;

        @Override
        public void onNext(T value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }

        @Override
        public void onCompleted() {
        }
    }
}

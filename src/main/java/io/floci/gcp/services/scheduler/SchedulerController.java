package io.floci.gcp.services.scheduler;

import com.google.cloud.scheduler.v1.AppEngineHttpTarget;
import com.google.cloud.scheduler.v1.AppEngineRouting;
import com.google.cloud.scheduler.v1.CloudSchedulerGrpc;
import com.google.cloud.scheduler.v1.CreateJobRequest;
import com.google.cloud.scheduler.v1.DeleteJobRequest;
import com.google.cloud.scheduler.v1.GetJobRequest;
import com.google.cloud.scheduler.v1.HttpMethod;
import com.google.cloud.scheduler.v1.HttpTarget;
import com.google.cloud.scheduler.v1.Job;
import com.google.cloud.scheduler.v1.ListJobsRequest;
import com.google.cloud.scheduler.v1.ListJobsResponse;
import com.google.cloud.scheduler.v1.PauseJobRequest;
import com.google.cloud.scheduler.v1.PubsubTarget;
import com.google.cloud.scheduler.v1.ResumeJobRequest;
import com.google.cloud.scheduler.v1.RetryConfig;
import com.google.cloud.scheduler.v1.RunJobRequest;
import com.google.cloud.scheduler.v1.UpdateJobRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpGrpcController;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.scheduler.model.StoredJob;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

public class SchedulerController extends CloudSchedulerGrpc.CloudSchedulerImplBase {

    private static final Logger LOG = Logger.getLogger(SchedulerController.class);

    private final SchedulerService service;

    SchedulerController(SchedulerService service) {
        this.service = service;
    }

    @Override
    public void listJobs(ListJobsRequest request, StreamObserver<ListJobsResponse> responseObserver) {
        LOG.debugf("listJobs parent=%s", request.getParent());
        try {
            String[] parts = parseParent(request.getParent());
            List<StoredJob> all = service.listJobs(parts[0], parts[1]);
            PageToken.Page<StoredJob> page = PageToken.paginate(all,
                    request.getPageSize(), request.getPageToken());
            ListJobsResponse.Builder response = ListJobsResponse.newBuilder();
            for (StoredJob j : page.items()) {
                response.addJobs(toJobProto(j));
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("listJobs failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getJob(GetJobRequest request, StreamObserver<Job> responseObserver) {
        LOG.debugf("getJob name=%s", request.getName());
        try {
            StoredJob job = service.getJob(request.getName());
            responseObserver.onNext(toJobProto(job));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("getJob failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void createJob(CreateJobRequest request, StreamObserver<Job> responseObserver) {
        LOG.infof("createJob parent=%s", request.getParent());
        try {
            StoredJob stored = service.createJob(request.getParent(), fromJobProto(request.getJob()));
            responseObserver.onNext(toJobProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("createJob failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void updateJob(UpdateJobRequest request, StreamObserver<Job> responseObserver) {
        LOG.infof("updateJob name=%s", request.getJob().getName());
        try {
            List<String> mask = request.hasUpdateMask()
                    ? request.getUpdateMask().getPathsList() : List.of();
            StoredJob stored = service.updateJob(fromJobProto(request.getJob()), mask);
            responseObserver.onNext(toJobProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("updateJob failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void deleteJob(DeleteJobRequest request, StreamObserver<Empty> responseObserver) {
        LOG.infof("deleteJob name=%s", request.getName());
        try {
            service.deleteJob(request.getName());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("deleteJob failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void pauseJob(PauseJobRequest request, StreamObserver<Job> responseObserver) {
        LOG.infof("pauseJob name=%s", request.getName());
        try {
            responseObserver.onNext(toJobProto(service.pauseJob(request.getName())));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("pauseJob failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void resumeJob(ResumeJobRequest request, StreamObserver<Job> responseObserver) {
        LOG.infof("resumeJob name=%s", request.getName());
        try {
            responseObserver.onNext(toJobProto(service.resumeJob(request.getName())));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("resumeJob failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void runJob(RunJobRequest request, StreamObserver<Job> responseObserver) {
        LOG.infof("runJob name=%s", request.getName());
        try {
            responseObserver.onNext(toJobProto(service.runJob(request.getName())));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("runJob failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    // ── Mapping ──────────────────────────────────────────────────────────────────

    static StoredJob fromJobProto(Job proto) {
        StoredJob job = new StoredJob();
        job.setName(proto.getName());
        job.setDescription(emptyToNull(proto.getDescription()));
        job.setSchedule(emptyToNull(proto.getSchedule()));
        job.setTimeZone(emptyToNull(proto.getTimeZone()));

        if (proto.hasPubsubTarget()) {
            PubsubTarget t = proto.getPubsubTarget();
            job.setTargetType("PUBSUB");
            job.setPubsubTopic(t.getTopicName());
            job.setPubsubData(t.getData().toByteArray());
            if (!t.getAttributesMap().isEmpty()) {
                job.setPubsubAttributes(t.getAttributesMap());
            }
        } else if (proto.hasHttpTarget()) {
            HttpTarget t = proto.getHttpTarget();
            job.setTargetType("HTTP");
            job.setHttpUri(t.getUri());
            job.setHttpMethod(t.getHttpMethod().name());
            if (!t.getHeadersMap().isEmpty()) {
                job.setHttpHeaders(t.getHeadersMap());
            }
            job.setHttpBody(t.getBody().toByteArray());
        } else if (proto.hasAppEngineHttpTarget()) {
            AppEngineHttpTarget t = proto.getAppEngineHttpTarget();
            job.setTargetType("APP_ENGINE");
            job.setAppEngineHttpMethod(t.getHttpMethod().name());
            job.setAppEngineRelativeUri(t.getRelativeUri());
            if (!t.getHeadersMap().isEmpty()) {
                job.setAppEngineHeaders(t.getHeadersMap());
            }
            job.setAppEngineBody(t.getBody().toByteArray());
            AppEngineRouting r = t.getAppEngineRouting();
            job.setAppEngineService(emptyToNull(r.getService()));
            job.setAppEngineVersion(emptyToNull(r.getVersion()));
            job.setAppEngineInstance(emptyToNull(r.getInstance()));
            job.setAppEngineHost(emptyToNull(r.getHost()));
        }

        if (proto.hasRetryConfig()) {
            RetryConfig rc = proto.getRetryConfig();
            job.setRetryCount(rc.getRetryCount());
            job.setMaxRetryDurationSeconds(rc.getMaxRetryDuration().getSeconds());
            job.setMinBackoffSeconds(rc.getMinBackoffDuration().getSeconds());
            job.setMaxBackoffSeconds(rc.getMaxBackoffDuration().getSeconds());
            job.setMaxDoublings(rc.getMaxDoublings());
        }
        if (proto.hasAttemptDeadline()) {
            job.setAttemptDeadlineSeconds(proto.getAttemptDeadline().getSeconds());
        }
        return job;
    }

    static Job toJobProto(StoredJob stored) {
        Job.Builder b = Job.newBuilder().setName(stored.getName());
        if (stored.getDescription() != null) {
            b.setDescription(stored.getDescription());
        }
        if (stored.getSchedule() != null) {
            b.setSchedule(stored.getSchedule());
        }
        if (stored.getTimeZone() != null) {
            b.setTimeZone(stored.getTimeZone());
        }

        if ("PUBSUB".equals(stored.getTargetType())) {
            PubsubTarget.Builder t = PubsubTarget.newBuilder();
            if (stored.getPubsubTopic() != null) {
                t.setTopicName(stored.getPubsubTopic());
            }
            if (stored.getPubsubData() != null) {
                t.setData(ByteString.copyFrom(stored.getPubsubData()));
            }
            if (stored.getPubsubAttributes() != null) {
                t.putAllAttributes(stored.getPubsubAttributes());
            }
            b.setPubsubTarget(t.build());
        } else if ("HTTP".equals(stored.getTargetType())) {
            HttpTarget.Builder t = HttpTarget.newBuilder();
            if (stored.getHttpUri() != null) {
                t.setUri(stored.getHttpUri());
            }
            t.setHttpMethod(parseHttpMethod(stored.getHttpMethod()));
            if (stored.getHttpHeaders() != null) {
                t.putAllHeaders(stored.getHttpHeaders());
            }
            if (stored.getHttpBody() != null) {
                t.setBody(ByteString.copyFrom(stored.getHttpBody()));
            }
            b.setHttpTarget(t.build());
        } else if ("APP_ENGINE".equals(stored.getTargetType())) {
            AppEngineHttpTarget.Builder t = AppEngineHttpTarget.newBuilder();
            t.setHttpMethod(parseHttpMethod(stored.getAppEngineHttpMethod()));
            if (stored.getAppEngineRelativeUri() != null) {
                t.setRelativeUri(stored.getAppEngineRelativeUri());
            }
            if (stored.getAppEngineHeaders() != null) {
                t.putAllHeaders(stored.getAppEngineHeaders());
            }
            if (stored.getAppEngineBody() != null) {
                t.setBody(ByteString.copyFrom(stored.getAppEngineBody()));
            }
            AppEngineRouting.Builder r = AppEngineRouting.newBuilder();
            if (stored.getAppEngineService() != null) {
                r.setService(stored.getAppEngineService());
            }
            if (stored.getAppEngineVersion() != null) {
                r.setVersion(stored.getAppEngineVersion());
            }
            if (stored.getAppEngineInstance() != null) {
                r.setInstance(stored.getAppEngineInstance());
            }
            if (stored.getAppEngineHost() != null) {
                r.setHost(stored.getAppEngineHost());
            }
            t.setAppEngineRouting(r.build());
            b.setAppEngineHttpTarget(t.build());
        }

        b.setRetryConfig(RetryConfig.newBuilder()
                .setRetryCount(stored.getRetryCount())
                .setMaxRetryDuration(seconds(stored.getMaxRetryDurationSeconds()))
                .setMinBackoffDuration(seconds(stored.getMinBackoffSeconds()))
                .setMaxBackoffDuration(seconds(stored.getMaxBackoffSeconds()))
                .setMaxDoublings(stored.getMaxDoublings())
                .build());
        if (stored.getAttemptDeadlineSeconds() > 0) {
            b.setAttemptDeadline(seconds(stored.getAttemptDeadlineSeconds()));
        }

        b.setState(parseState(stored.getState()));
        if (stored.getUserUpdateTime() != null) {
            b.setUserUpdateTime(toTimestamp(stored.getUserUpdateTime()));
        }
        if (stored.getScheduleTime() != null) {
            b.setScheduleTime(toTimestamp(stored.getScheduleTime()));
        }
        if (stored.getLastAttemptTime() != null) {
            b.setLastAttemptTime(toTimestamp(stored.getLastAttemptTime()));
        }
        if (stored.getStatusCode() != 0 || (stored.getStatusMessage() != null && !stored.getStatusMessage().isBlank())) {
            b.setStatus(com.google.rpc.Status.newBuilder()
                    .setCode(stored.getStatusCode())
                    .setMessage(stored.getStatusMessage() == null ? "" : stored.getStatusMessage())
                    .build());
        }
        return b.build();
    }

    private static HttpMethod parseHttpMethod(String name) {
        if (name == null || name.isBlank()) {
            return HttpMethod.HTTP_METHOD_UNSPECIFIED;
        }
        try {
            return HttpMethod.valueOf(name);
        } catch (IllegalArgumentException e) {
            return HttpMethod.HTTP_METHOD_UNSPECIFIED;
        }
    }

    private static Job.State parseState(String state) {
        if (state == null) {
            return Job.State.STATE_UNSPECIFIED;
        }
        try {
            return Job.State.valueOf(state);
        } catch (IllegalArgumentException e) {
            return Job.State.STATE_UNSPECIFIED;
        }
    }

    private static Duration seconds(long s) {
        return Duration.newBuilder().setSeconds(s).build();
    }

    private static Timestamp toTimestamp(String isoTime) {
        try {
            Instant instant = Instant.parse(isoTime);
            return Timestamp.newBuilder()
                    .setSeconds(instant.getEpochSecond())
                    .setNanos(instant.getNano())
                    .build();
        } catch (Exception e) {
            return Timestamp.getDefaultInstance();
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static String[] parseParent(String parent) {
        // parent = "projects/{project}/locations/{location}"
        String[] parts = parent.split("/");
        String project = parts.length > 1 ? parts[1] : parent;
        String location = parts.length > 3 ? parts[3] : "us-central1";
        return new String[]{project, location};
    }
}

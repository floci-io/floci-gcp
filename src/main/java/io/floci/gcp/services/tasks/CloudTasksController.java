package io.floci.gcp.services.tasks;

import com.google.cloud.tasks.v2.*;
import com.google.iam.v1.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpGrpcController;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.tasks.model.StoredQueue;
import io.floci.gcp.services.tasks.model.StoredTask;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class CloudTasksController extends CloudTasksGrpc.CloudTasksImplBase {

    private static final Logger LOG = Logger.getLogger(CloudTasksController.class);

    private final CloudTasksService service;

    CloudTasksController(CloudTasksService service) {
        this.service = service;
    }

    // ── Queues ─────────────────────────────────────────────────────────────────

    @Override
    public void listQueues(ListQueuesRequest request, StreamObserver<ListQueuesResponse> responseObserver) {
        LOG.debugf("listQueues parent=%s", request.getParent());
        try {
            String[] parts = parseParent(request.getParent());
            List<StoredQueue> all = service.listQueues(parts[0], parts[1]);
            PageToken.Page<StoredQueue> page = PageToken.paginate(all,
                    request.getPageSize(), request.getPageToken());
            ListQueuesResponse.Builder response = ListQueuesResponse.newBuilder();
            for (StoredQueue q : page.items()) {
                response.addQueues(toQueueProto(q));
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("listQueues failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getQueue(GetQueueRequest request, StreamObserver<Queue> responseObserver) {
        LOG.debugf("getQueue name=%s", request.getName());
        try {
            StoredQueue queue = service.getQueue(request.getName());
            responseObserver.onNext(toQueueProto(queue));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("getQueue failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void createQueue(CreateQueueRequest request, StreamObserver<Queue> responseObserver) {
        LOG.infof("createQueue parent=%s", request.getParent());
        try {
            String[] parts = parseParent(request.getParent());
            Queue q = request.getQueue();
            String queueId = extractLastSegment(q.getName());

            RateLimits rl = q.getRateLimits();
            RetryConfig rc = q.getRetryConfig();

            StoredQueue stored = service.createQueue(parts[0], parts[1], queueId,
                    rl.getMaxDispatchesPerSecond(),
                    rl.getMaxConcurrentDispatches(),
                    rc.getMaxAttempts());
            responseObserver.onNext(toQueueProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("createQueue failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void updateQueue(UpdateQueueRequest request, StreamObserver<Queue> responseObserver) {
        LOG.infof("updateQueue name=%s", request.getQueue().getName());
        try {
            Queue q = request.getQueue();
            RateLimits rl = q.getRateLimits();
            RetryConfig rc = q.getRetryConfig();
            StoredQueue stored = service.updateQueue(q.getName(),
                    rl.getMaxDispatchesPerSecond(),
                    rl.getMaxConcurrentDispatches(),
                    rc.getMaxAttempts());
            responseObserver.onNext(toQueueProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("updateQueue failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void deleteQueue(DeleteQueueRequest request, StreamObserver<Empty> responseObserver) {
        LOG.infof("deleteQueue name=%s", request.getName());
        try {
            service.deleteQueue(request.getName());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("deleteQueue failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void purgeQueue(PurgeQueueRequest request, StreamObserver<Queue> responseObserver) {
        LOG.infof("purgeQueue name=%s", request.getName());
        try {
            StoredQueue queue = service.purgeQueue(request.getName());
            responseObserver.onNext(toQueueProto(queue));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("purgeQueue failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void pauseQueue(PauseQueueRequest request, StreamObserver<Queue> responseObserver) {
        LOG.infof("pauseQueue name=%s", request.getName());
        try {
            StoredQueue queue = service.pauseQueue(request.getName());
            responseObserver.onNext(toQueueProto(queue));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("pauseQueue failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void resumeQueue(ResumeQueueRequest request, StreamObserver<Queue> responseObserver) {
        LOG.infof("resumeQueue name=%s", request.getName());
        try {
            StoredQueue queue = service.resumeQueue(request.getName());
            responseObserver.onNext(toQueueProto(queue));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("resumeQueue failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    // ── Tasks ──────────────────────────────────────────────────────────────────

    @Override
    public void listTasks(ListTasksRequest request, StreamObserver<ListTasksResponse> responseObserver) {
        LOG.debugf("listTasks parent=%s", request.getParent());
        try {
            List<StoredTask> all = service.listTasks(request.getParent());
            PageToken.Page<StoredTask> page = PageToken.paginate(all,
                    request.getPageSize(), request.getPageToken());
            ListTasksResponse.Builder response = ListTasksResponse.newBuilder();
            for (StoredTask t : page.items()) {
                response.addTasks(toTaskProto(t));
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("listTasks failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getTask(GetTaskRequest request, StreamObserver<Task> responseObserver) {
        LOG.debugf("getTask name=%s", request.getName());
        try {
            StoredTask task = service.getTask(request.getName());
            responseObserver.onNext(toTaskProto(task));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("getTask failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void createTask(CreateTaskRequest request, StreamObserver<Task> responseObserver) {
        LOG.infof("createTask parent=%s", request.getParent());
        try {
            Task t = request.getTask();
            String taskId = extractLastSegment(t.getName());

            String taskType;
            String httpMethod = null;
            String url = null;
            Map<String, String> headers = null;
            byte[] body = null;
            String appEngineHttpMethod = null;
            String relativeUri = null;
            String scheduleTime = null;

            if (t.hasHttpRequest()) {
                taskType = "HTTP";
                HttpRequest hr = t.getHttpRequest();
                httpMethod = hr.getHttpMethod().name();
                url = hr.getUrl();
                headers = hr.getHeadersMap();
                body = hr.getBody().toByteArray();
            } else if (t.hasAppEngineHttpRequest()) {
                taskType = "APP_ENGINE";
                AppEngineHttpRequest ae = t.getAppEngineHttpRequest();
                appEngineHttpMethod = ae.getHttpMethod().name();
                relativeUri = ae.getRelativeUri();
                headers = ae.getHeadersMap();
                body = ae.getBody().toByteArray();
            } else {
                taskType = "HTTP";
            }

            if (t.hasScheduleTime()) {
                scheduleTime = Instant.ofEpochSecond(
                        t.getScheduleTime().getSeconds(), t.getScheduleTime().getNanos()).toString();
            }

            StoredTask stored = service.createTask(request.getParent(), taskId, taskType,
                    httpMethod, url, headers, body, appEngineHttpMethod, relativeUri, scheduleTime);
            responseObserver.onNext(toTaskProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("createTask failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void deleteTask(DeleteTaskRequest request, StreamObserver<Empty> responseObserver) {
        LOG.infof("deleteTask name=%s", request.getName());
        try {
            service.deleteTask(request.getName());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("deleteTask failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void runTask(RunTaskRequest request, StreamObserver<Task> responseObserver) {
        LOG.infof("runTask name=%s", request.getName());
        try {
            StoredTask task = service.runTask(request.getName());
            responseObserver.onNext(toTaskProto(task));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("runTask failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    // ── IAM stubs ──────────────────────────────────────────────────────────────

    @Override
    public void getIamPolicy(GetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
        LOG.debugf("getIamPolicy resource=%s", request.getResource());
        responseObserver.onNext(Policy.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void setIamPolicy(SetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
        LOG.debugf("setIamPolicy resource=%s", request.getResource());
        responseObserver.onNext(request.getPolicy());
        responseObserver.onCompleted();
    }

    @Override
    public void testIamPermissions(TestIamPermissionsRequest request,
            StreamObserver<TestIamPermissionsResponse> responseObserver) {
        LOG.debugf("testIamPermissions resource=%s", request.getResource());
        responseObserver.onNext(TestIamPermissionsResponse.newBuilder()
                .addAllPermissions(request.getPermissionsList())
                .build());
        responseObserver.onCompleted();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static Queue toQueueProto(StoredQueue stored) {
        Queue.State state;
        try {
            state = Queue.State.valueOf(stored.getState());
        } catch (Exception e) {
            state = Queue.State.RUNNING;
        }
        Queue.Builder builder = Queue.newBuilder()
                .setName(stored.getName())
                .setState(state)
                .setRateLimits(RateLimits.newBuilder()
                        .setMaxDispatchesPerSecond(stored.getMaxDispatchesPerSecond())
                        .setMaxConcurrentDispatches(stored.getMaxConcurrentDispatches())
                        .setMaxBurstSize(stored.getMaxBurstSize())
                        .build())
                .setRetryConfig(RetryConfig.newBuilder()
                        .setMaxAttempts(stored.getMaxAttempts())
                        .build());
        if (stored.getPurgeTime() != null) {
            builder.setPurgeTime(toTimestamp(stored.getPurgeTime()));
        }
        return builder.build();
    }

    private static Task toTaskProto(StoredTask stored) {
        Task.Builder builder = Task.newBuilder()
                .setName(stored.getName())
                .setCreateTime(toTimestamp(stored.getCreateTime()))
                .setScheduleTime(toTimestamp(stored.getScheduleTime()))
                .setDispatchCount(stored.getDispatchCount())
                .setResponseCount(stored.getResponseCount());

        if ("APP_ENGINE".equals(stored.getTaskType())) {
            AppEngineHttpRequest.Builder ae = AppEngineHttpRequest.newBuilder();
            if (stored.getAppEngineHttpMethod() != null) {
                try {
                    ae.setHttpMethod(HttpMethod.valueOf(stored.getAppEngineHttpMethod()));
                } catch (Exception ignored) {
                }
            }
            if (stored.getRelativeUri() != null) {
                ae.setRelativeUri(stored.getRelativeUri());
            }
            if (stored.getHeaders() != null) {
                ae.putAllHeaders(stored.getHeaders());
            }
            if (stored.getBody() != null && stored.getBody().length > 0) {
                ae.setBody(ByteString.copyFrom(stored.getBody()));
            }
            builder.setAppEngineHttpRequest(ae.build());
        } else {
            HttpRequest.Builder hr = HttpRequest.newBuilder();
            if (stored.getUrl() != null) {
                hr.setUrl(stored.getUrl());
            }
            if (stored.getHttpMethod() != null) {
                try {
                    hr.setHttpMethod(HttpMethod.valueOf(stored.getHttpMethod()));
                } catch (Exception ignored) {
                }
            }
            if (stored.getHeaders() != null) {
                hr.putAllHeaders(stored.getHeaders());
            }
            if (stored.getBody() != null && stored.getBody().length > 0) {
                hr.setBody(ByteString.copyFrom(stored.getBody()));
            }
            builder.setHttpRequest(hr.build());
        }

        return builder.build();
    }

    private static Timestamp toTimestamp(String isoTime) {
        if (isoTime == null) {
            return Timestamp.getDefaultInstance();
        }
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

    private static String[] parseParent(String parent) {
        // parent = "projects/{project}/locations/{location}"
        String[] parts = parent.split("/");
        String project = parts.length > 1 ? parts[1] : parent;
        String location = parts.length > 3 ? parts[3] : "us-central1";
        return new String[]{project, location};
    }

    private static String extractLastSegment(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        int idx = name.lastIndexOf('/');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }
}

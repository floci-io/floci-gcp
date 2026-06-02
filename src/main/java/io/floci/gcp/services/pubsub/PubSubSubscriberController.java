package io.floci.gcp.services.pubsub;

import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.*;
import io.floci.gcp.core.common.GcpGrpcController;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.pubsub.model.StoredSnapshot;
import io.floci.gcp.services.pubsub.model.StoredSubscription;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class PubSubSubscriberController extends SubscriberGrpc.SubscriberImplBase {

    private static final Logger LOG = Logger.getLogger(PubSubSubscriberController.class);

    private final PubSubService service;

    PubSubSubscriberController(PubSubService service) {
        this.service = service;
    }

    @Override
    public void createSubscription(Subscription request, StreamObserver<Subscription> responseObserver) {
        LOG.infof("createSubscription name=%s topic=%s", request.getName(), request.getTopic());
        try {
            StoredSubscription stored = service.createSubscription(
                    request.getName(), request.getTopic(), request.getAckDeadlineSeconds());
            responseObserver.onNext(buildSubscription(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("createSubscription failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getSubscription(GetSubscriptionRequest request, StreamObserver<Subscription> responseObserver) {
        LOG.debugf("getSubscription name=%s", request.getSubscription());
        try {
            StoredSubscription stored = service.getSubscription(request.getSubscription());
            responseObserver.onNext(buildSubscription(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("getSubscription failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listSubscriptions(ListSubscriptionsRequest request,
            StreamObserver<ListSubscriptionsResponse> responseObserver) {
        LOG.debugf("listSubscriptions project=%s", request.getProject());
        try {
            String project = extractProjectId(request.getProject());
            List<StoredSubscription> all = service.listSubscriptions(project);
            PageToken.Page<StoredSubscription> page = PageToken.paginate(all,
                    request.getPageSize(), request.getPageToken());
            ListSubscriptionsResponse.Builder response = ListSubscriptionsResponse.newBuilder();
            for (StoredSubscription s : page.items()) {
                response.addSubscriptions(buildSubscription(s));
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("listSubscriptions failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void deleteSubscription(DeleteSubscriptionRequest request, StreamObserver<Empty> responseObserver) {
        LOG.infof("deleteSubscription name=%s", request.getSubscription());
        try {
            service.deleteSubscription(request.getSubscription());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("deleteSubscription failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void pull(PullRequest request, StreamObserver<PullResponse> responseObserver) {
        LOG.debugf("pull subscription=%s maxMessages=%d", request.getSubscription(), request.getMaxMessages());
        try {
            int max = request.getMaxMessages() > 0 ? request.getMaxMessages() : 1;
            List<ReceivedMessage> messages = service.pull(request.getSubscription(), max);
            LOG.debugf("pull subscription=%s returned=%d", request.getSubscription(), messages.size());
            responseObserver.onNext(PullResponse.newBuilder().addAllReceivedMessages(messages).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("pull failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void acknowledge(AcknowledgeRequest request, StreamObserver<Empty> responseObserver) {
        LOG.debugf("acknowledge subscription=%s ackIds=%d", request.getSubscription(), request.getAckIdsCount());
        try {
            service.acknowledge(request.getSubscription(), request.getAckIdsList());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("acknowledge failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void updateSubscription(UpdateSubscriptionRequest request, StreamObserver<Subscription> responseObserver) {
        LOG.infof("updateSubscription name=%s", request.getSubscription().getName());
        try {
            FieldMask mask = request.hasUpdateMask() ? request.getUpdateMask() : FieldMask.getDefaultInstance();
            StoredSubscription stored = service.updateSubscription(request.getSubscription(), mask);
            responseObserver.onNext(buildSubscription(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("updateSubscription failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void modifyPushConfig(ModifyPushConfigRequest request, StreamObserver<Empty> responseObserver) {
        LOG.infof("modifyPushConfig subscription=%s", request.getSubscription());
        try {
            service.modifyPushConfig(request.getSubscription(), request.getPushConfig());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("modifyPushConfig failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void modifyAckDeadline(ModifyAckDeadlineRequest request, StreamObserver<Empty> responseObserver) {
        LOG.debugf("modifyAckDeadline subscription=%s ackIds=%d deadline=%d",
                request.getSubscription(), request.getAckIdsCount(), request.getAckDeadlineSeconds());
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<StreamingPullRequest> streamingPull(
            StreamObserver<StreamingPullResponse> responseObserver) {
        AtomicReference<String> subscriptionRef = new AtomicReference<>();

        return new StreamObserver<>() {
            @Override
            public void onNext(StreamingPullRequest request) {
                try {
                    if (!request.getSubscription().isEmpty()) {
                        String sub = request.getSubscription();
                        subscriptionRef.set(sub);
                        LOG.infof("streamingPull opened subscription=%s", sub);
                    }
                    String sub = subscriptionRef.get();
                    if (sub == null) {
                        return;
                    }
                    if (!request.getAckIdsList().isEmpty()) {
                        LOG.debugf("streamingPull ack subscription=%s ackIds=%d", sub, request.getAckIdsCount());
                        service.acknowledge(sub, request.getAckIdsList());
                    }
                    List<ReceivedMessage> messages = service.pull(sub, 1000);
                    if (!messages.isEmpty()) {
                        LOG.debugf("streamingPull deliver subscription=%s messages=%d", sub, messages.size());
                        responseObserver.onNext(
                                StreamingPullResponse.newBuilder()
                                        .addAllReceivedMessages(messages)
                                        .build());
                    }
                } catch (Exception e) {
                    LOG.warnf("streamingPull error: %s", e.getMessage());
                    responseObserver.onError(GcpGrpcController.grpcException(e));
                }
            }

            @Override
            public void onError(Throwable t) {
                LOG.debugf("streamingPull stream closed by client: %s", t.getMessage());
                try {
                    responseObserver.onCompleted();
                } catch (Exception ignored) {}
            }

            @Override
            public void onCompleted() {
                LOG.debugf("streamingPull stream completed subscription=%s", subscriptionRef.get());
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void createSnapshot(CreateSnapshotRequest request, StreamObserver<Snapshot> responseObserver) {
        LOG.infof("createSnapshot name=%s subscription=%s", request.getName(), request.getSubscription());
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> labels = request.getLabelsMap().isEmpty() ? null
                    : new java.util.HashMap<>(request.getLabelsMap());
            StoredSnapshot stored = service.createSnapshot(request.getName(), request.getSubscription(), labels);
            responseObserver.onNext(buildSnapshot(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("createSnapshot failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getSnapshot(GetSnapshotRequest request, StreamObserver<Snapshot> responseObserver) {
        LOG.debugf("getSnapshot name=%s", request.getSnapshot());
        try {
            StoredSnapshot stored = service.getSnapshot(request.getSnapshot());
            responseObserver.onNext(buildSnapshot(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("getSnapshot failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listSnapshots(ListSnapshotsRequest request, StreamObserver<ListSnapshotsResponse> responseObserver) {
        LOG.debugf("listSnapshots project=%s", request.getProject());
        try {
            String project = extractProjectId(request.getProject());
            List<StoredSnapshot> all = service.listSnapshots(project);
            PageToken.Page<StoredSnapshot> page = PageToken.paginate(all,
                    request.getPageSize(), request.getPageToken());
            ListSnapshotsResponse.Builder resp = ListSnapshotsResponse.newBuilder();
            for (StoredSnapshot s : page.items()) {
                resp.addSnapshots(buildSnapshot(s));
            }
            if (page.nextPageToken() != null) {
                resp.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("listSnapshots failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void updateSnapshot(UpdateSnapshotRequest request, StreamObserver<Snapshot> responseObserver) {
        LOG.infof("updateSnapshot name=%s", request.getSnapshot().getName());
        try {
            Snapshot snap = request.getSnapshot();
            List<String> paths = request.hasUpdateMask() ? request.getUpdateMask().getPathsList() : List.of();
            Map<String, String> labels = snap.getLabelsMap().isEmpty() ? null
                    : new java.util.HashMap<>(snap.getLabelsMap());
            String expireTime = snap.hasExpireTime()
                    ? Instant.ofEpochSecond(snap.getExpireTime().getSeconds(),
                            snap.getExpireTime().getNanos()).toString()
                    : null;
            StoredSnapshot stored = service.updateSnapshot(snap.getName(), labels, expireTime, paths);
            responseObserver.onNext(buildSnapshot(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("updateSnapshot failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void deleteSnapshot(DeleteSnapshotRequest request, StreamObserver<Empty> responseObserver) {
        LOG.infof("deleteSnapshot name=%s", request.getSnapshot());
        try {
            service.deleteSnapshot(request.getSnapshot());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("deleteSnapshot failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void seek(SeekRequest request, StreamObserver<SeekResponse> responseObserver) {
        LOG.infof("seek subscription=%s", request.getSubscription());
        try {
            String snapshotName = request.hasSnapshot() ? request.getSnapshot() : null;
            service.seek(request.getSubscription(), snapshotName);
            responseObserver.onNext(SeekResponse.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("seek failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    private static Snapshot buildSnapshot(StoredSnapshot stored) {
        Snapshot.Builder builder = Snapshot.newBuilder()
                .setName(stored.getName())
                .setTopic(stored.getTopic());
        if (stored.getExpireTime() != null) {
            try {
                Instant expiry = Instant.parse(stored.getExpireTime());
                builder.setExpireTime(Timestamp.newBuilder()
                        .setSeconds(expiry.getEpochSecond())
                        .setNanos(expiry.getNano())
                        .build());
            } catch (Exception ignored) {}
        }
        if (stored.getLabels() != null) {
            builder.putAllLabels(stored.getLabels());
        }
        return builder.build();
    }

    private static Subscription buildSubscription(StoredSubscription stored) {
        return Subscription.newBuilder()
                .setName(stored.getName())
                .setTopic(stored.getTopic())
                .setAckDeadlineSeconds(stored.getAckDeadlineSeconds())
                .setDetached(stored.isDetached())
                .build();
    }

    private static String extractProjectId(String parent) {
        if (parent.startsWith("projects/")) {
            return parent.substring("projects/".length());
        }
        return parent;
    }
}

package io.floci.gcp.services.pubsub;

import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.BigQueryConfig;
import com.google.pubsub.v1.CreateSnapshotRequest;
import com.google.pubsub.v1.DeadLetterPolicy;
import com.google.pubsub.v1.DeleteSnapshotRequest;
import com.google.pubsub.v1.DeleteSubscriptionRequest;
import com.google.pubsub.v1.ExpirationPolicy;
import com.google.pubsub.v1.GetSnapshotRequest;
import com.google.pubsub.v1.GetSubscriptionRequest;
import com.google.pubsub.v1.ListSnapshotsRequest;
import com.google.pubsub.v1.ListSnapshotsResponse;
import com.google.pubsub.v1.ListSubscriptionsRequest;
import com.google.pubsub.v1.ListSubscriptionsResponse;
import com.google.pubsub.v1.ModifyAckDeadlineRequest;
import com.google.pubsub.v1.ModifyPushConfigRequest;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.PushConfig;
import com.google.pubsub.v1.ReceivedMessage;
import com.google.pubsub.v1.RetryPolicy;
import com.google.pubsub.v1.SeekRequest;
import com.google.pubsub.v1.SeekResponse;
import com.google.pubsub.v1.Snapshot;
import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.StreamingPullResponse;
import com.google.pubsub.v1.SubscriberGrpc;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.UpdateSnapshotRequest;
import com.google.pubsub.v1.UpdateSubscriptionRequest;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpGrpcController;
import io.floci.gcp.core.common.GcpResourceNames;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.iam.authorization.IamGrpcDeliveryAuthorization;
import io.floci.gcp.services.pubsub.model.StoredSnapshot;
import io.floci.gcp.services.pubsub.model.StoredSubscription;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
            DeadLetterPolicy deadLetterPolicy = request.getDeadLetterPolicy();
            StoredSubscription stored = service.createSubscription(
                    request.getName(), request.getTopic(), request.getAckDeadlineSeconds(),
                    request.getLabelsMap(), request.getRetainAckedMessages(),
                    request.hasMessageRetentionDuration()
                            ? Durations.toString(request.getMessageRetentionDuration()) : null,
                    request.getFilter(),
                    request.hasPushConfig() ? request.getPushConfig().getPushEndpoint() : null,
                    storedBigQueryConfig(request), storedExpirationPolicy(request),
                    SubscriptionRetryPolicy.toStored(request.hasRetryPolicy(), request.getRetryPolicy()),
                    deadLetterPolicy.getDeadLetterTopic(), deadLetterPolicy.getMaxDeliveryAttempts(),
                    request.getEnableMessageOrdering(), request.getEnableExactlyOnceDelivery());
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
            String project = GcpResourceNames.projectIdOf(request.getProject());
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
        try {
            service.modifyAckDeadline(request.getSubscription(), request.getAckIdsList(),
                    request.getAckDeadlineSeconds());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("modifyAckDeadline failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public StreamObserver<StreamingPullRequest> streamingPull(
            StreamObserver<StreamingPullResponse> responseObserver) {
        AtomicReference<String> subscriptionRef = new AtomicReference<>();
        AtomicInteger streamAckDeadlineSeconds = new AtomicInteger();
        AtomicReference<Runnable> unregisterRef = new AtomicReference<>();
        AtomicReference<IamGrpcDeliveryAuthorization.Delivery> deliveryAuthorizationRef =
                new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        Object deliveryLock = new Object();
        Context.current().addListener(context -> {
            synchronized (deliveryLock) {
                closeStreamingPull(unregisterRef, closed);
            }
        }, Runnable::run);

        return new StreamObserver<>() {
            @Override
            public void onNext(StreamingPullRequest request) {
                try {
                    synchronized (deliveryLock) {
                        if (closed.get()) {
                            return;
                        }
                        if (request.getStreamAckDeadlineSeconds() > 0) {
                            streamAckDeadlineSeconds.set(request.getStreamAckDeadlineSeconds());
                        }
                        if (!request.getSubscription().isEmpty()) {
                            String sub = request.getSubscription();
                            subscriptionRef.set(sub);
                            deliveryAuthorizationRef.set(IamGrpcDeliveryAuthorization.current());
                            Runnable previous = unregisterRef.getAndSet(
                                    service.registerMessageListener(
                                            sub,
                                            () -> deliverStreamingMessages(
                                                    sub, responseObserver, unregisterRef, closed, deliveryLock,
                                                    deliveryAuthorizationRef.get(), StreamingPullRequest.getDefaultInstance(),
                                                    streamAckDeadlineSeconds.get())));
                            if (previous != null) {
                                previous.run();
                            }
                            LOG.infof("streamingPull opened subscription=%s", sub);
                        }
                    }
                    String sub = subscriptionRef.get();
                    if (sub == null) {
                        return;
                    }
                    deliverStreamingMessages(sub, responseObserver, unregisterRef, closed, deliveryLock,
                            deliveryAuthorizationRef.get(), request, streamAckDeadlineSeconds.get());
                } catch (Exception e) {
                    LOG.warnf("streamingPull error: %s", e.getMessage());
                    failStreamingPull(responseObserver, unregisterRef, closed, deliveryLock, e);
                }
            }

            @Override
            public void onError(Throwable t) {
                LOG.debugf("streamingPull stream closed by client: %s", t.getMessage());
                completeStreamingPull(responseObserver, unregisterRef, closed, deliveryLock);
            }

            @Override
            public void onCompleted() {
                LOG.debugf("streamingPull stream completed subscription=%s", subscriptionRef.get());
                completeStreamingPull(responseObserver, unregisterRef, closed, deliveryLock);
            }
        };
    }

    private void modifyStreamingAckDeadlines(String sub, StreamingPullRequest request, List<Runnable> followUps) {
        if (request.getModifyDeadlineAckIdsCount() != request.getModifyDeadlineSecondsCount()) {
            throw GcpException.invalidArgument("modify_deadline_ack_ids has " + request.getModifyDeadlineAckIdsCount()
                    + " entries but modify_deadline_seconds has " + request.getModifyDeadlineSecondsCount()
                    + "; the two lists must have the same size.");
        }
        LOG.debugf("streamingPull modifyAckDeadline subscription=%s ackIds=%d",
                sub, request.getModifyDeadlineAckIdsCount());
        Map<Integer, List<String>> ackIdsByDeadline = new LinkedHashMap<>();
        for (int i = 0; i < request.getModifyDeadlineAckIdsCount(); i++) {
            ackIdsByDeadline.computeIfAbsent(request.getModifyDeadlineSeconds(i), k -> new ArrayList<>())
                    .add(request.getModifyDeadlineAckIds(i));
        }
        for (Map.Entry<Integer, List<String>> entry : ackIdsByDeadline.entrySet()) {
            followUps.add(service.modifyAckDeadlineWithoutNotification(sub, entry.getValue(), entry.getKey()));
        }
    }

    private void deliverStreamingMessages(String sub,
            StreamObserver<StreamingPullResponse> responseObserver,
            AtomicReference<Runnable> unregisterRef,
            AtomicBoolean closed,
            Object deliveryLock,
            IamGrpcDeliveryAuthorization.Delivery deliveryAuthorization,
            StreamingPullRequest request,
            int streamAckDeadlineSeconds) {
        List<Runnable> followUps = new ArrayList<>();
        try {
            Runnable delivery = () -> {
                synchronized (deliveryLock) {
                    if (closed.get()) {
                        return;
                    }
                    if (!request.getAckIdsList().isEmpty()) {
                        LOG.debugf("streamingPull ack subscription=%s ackIds=%d", sub, request.getAckIdsCount());
                        service.acknowledge(sub, request.getAckIdsList());
                    }
                    if (request.getModifyDeadlineAckIdsCount() > 0 || request.getModifyDeadlineSecondsCount() > 0) {
                        modifyStreamingAckDeadlines(sub, request, followUps);
                    }
                    List<ReceivedMessage> messages = service.pull(sub, 1000, streamAckDeadlineSeconds);
                    if (!messages.isEmpty()) {
                        LOG.debugf("streamingPull deliver subscription=%s messages=%d", sub, messages.size());
                        responseObserver.onNext(StreamingPullResponse.newBuilder()
                                .addAllReceivedMessages(messages).build());
                    }
                }
            };
            if (deliveryAuthorization == null) {
                delivery.run();
            } else {
                deliveryAuthorization.run(sub, delivery);
            }
        } catch (Exception e) {
            failStreamingPull(responseObserver, unregisterRef, closed, deliveryLock, e);
        } finally {
            followUps.forEach(Runnable::run);
        }
    }

    private void completeStreamingPull(StreamObserver<StreamingPullResponse> responseObserver,
            AtomicReference<Runnable> unregisterRef,
            AtomicBoolean closed,
            Object deliveryLock) {
        synchronized (deliveryLock) {
            if (closeStreamingPull(unregisterRef, closed)) {
                responseObserver.onCompleted();
            }
        }
    }

    private void failStreamingPull(StreamObserver<StreamingPullResponse> responseObserver,
            AtomicReference<Runnable> unregisterRef,
            AtomicBoolean closed,
            Object deliveryLock,
            Exception e) {
        synchronized (deliveryLock) {
            if (closeStreamingPull(unregisterRef, closed)) {
                responseObserver.onError(GcpGrpcController.grpcException(e));
            }
        }
    }

    private boolean closeStreamingPull(AtomicReference<Runnable> unregisterRef, AtomicBoolean closed) {
        if (!closed.compareAndSet(false, true)) {
            return false;
        }
        Runnable unregister = unregisterRef.getAndSet(null);
        if (unregister != null) {
            unregister.run();
        }
        return true;
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
            String project = GcpResourceNames.projectIdOf(request.getProject());
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
            Timestamp time = request.hasTime() ? request.getTime() : null;
            service.seek(request.getSubscription(), snapshotName, time);
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
        Subscription.Builder builder = Subscription.newBuilder()
                .setName(stored.getName())
                .setTopic(stored.getTopic())
                .setAckDeadlineSeconds(stored.getAckDeadlineSeconds())
                .setRetainAckedMessages(stored.isRetainAckedMessages())
                .setEnableMessageOrdering(stored.isEnableMessageOrdering())
                .setEnableExactlyOnceDelivery(stored.isEnableExactlyOnceDelivery())
                .setDetached(stored.isDetached());
        if (stored.getLabels() != null) {
            builder.putAllLabels(stored.getLabels());
        }
        if (stored.getFilter() != null) {
            builder.setFilter(stored.getFilter());
        }
        if (stored.getPushEndpoint() != null) {
            builder.setPushConfig(PushConfig.newBuilder().setPushEndpoint(stored.getPushEndpoint()).build());
        }
        if (stored.getMessageRetentionDuration() != null) {
            try {
                builder.setMessageRetentionDuration(Durations.parse(stored.getMessageRetentionDuration()));
            } catch (ParseException e) {
                LOG.debugf(e, "Ignoring invalid message retention duration for subscription %s: %s",
                        stored.getName(), stored.getMessageRetentionDuration());
            }
        }
        if (stored.getBigQueryConfig() != null) {
            Map<String, Object> config = stored.getBigQueryConfig();
            BigQueryConfig.Builder bigQuery = BigQueryConfig.newBuilder().setState(BigQueryConfig.State.ACTIVE);
            if (config.containsKey("table")) {
                bigQuery.setTable(stringValue(config, "table"));
            }
            if (config.containsKey("useTopicSchema")) {
                bigQuery.setUseTopicSchema(booleanValue(config, "useTopicSchema"));
            }
            if (config.containsKey("useTableSchema")) {
                bigQuery.setUseTableSchema(booleanValue(config, "useTableSchema"));
            }
            if (config.containsKey("writeMetadata")) {
                bigQuery.setWriteMetadata(booleanValue(config, "writeMetadata"));
            }
            if (config.containsKey("dropUnknownFields")) {
                bigQuery.setDropUnknownFields(booleanValue(config, "dropUnknownFields"));
            }
            if (config.containsKey("serviceAccountEmail")) {
                bigQuery.setServiceAccountEmail(stringValue(config, "serviceAccountEmail"));
            }
            builder.setBigqueryConfig(bigQuery.build());
        }
        if (stored.getExpirationPolicy() != null) {
            Object ttl = stored.getExpirationPolicy().get("ttl");
            if (ttl != null) {
                try {
                    builder.setExpirationPolicy(ExpirationPolicy.newBuilder()
                            .setTtl(Durations.parse(ttl.toString()))
                            .build());
                } catch (ParseException e) {
                    LOG.debugf(e, "Ignoring invalid expiration TTL for subscription %s: %s",
                            stored.getName(), ttl);
                }
            }
        }
        if (stored.getDeadLetterTopic() != null) {
            builder.setDeadLetterPolicy(DeadLetterPolicy.newBuilder()
                    .setDeadLetterTopic(stored.getDeadLetterTopic())
                    .setMaxDeliveryAttempts(stored.getMaxDeliveryAttempts())
                    .build());
        }
        RetryPolicy retryPolicy = SubscriptionRetryPolicy.toProto(stored.getRetryPolicy());
        if (retryPolicy != null) {
            builder.setRetryPolicy(retryPolicy);
        }
        return builder.build();
    }

    private static Map<String, Object> storedBigQueryConfig(Subscription request) {
        if (!request.hasBigqueryConfig()) {
            return null;
        }
        BigQueryConfig config = request.getBigqueryConfig();
        Map<String, Object> stored = new LinkedHashMap<>();
        if (!config.getTable().isEmpty()) {
            stored.put("table", config.getTable());
        }
        if (config.getUseTopicSchema()) {
            stored.put("useTopicSchema", true);
        }
        if (config.getUseTableSchema()) {
            stored.put("useTableSchema", true);
        }
        if (config.getWriteMetadata()) {
            stored.put("writeMetadata", true);
        }
        if (config.getDropUnknownFields()) {
            stored.put("dropUnknownFields", true);
        }
        if (!config.getServiceAccountEmail().isEmpty()) {
            stored.put("serviceAccountEmail", config.getServiceAccountEmail());
        }
        return stored;
    }

    private static Map<String, Object> storedExpirationPolicy(Subscription request) {
        if (!request.hasExpirationPolicy() || !request.getExpirationPolicy().hasTtl()) {
            return null;
        }
        return Map.of("ttl", Durations.toString(request.getExpirationPolicy().getTtl()));
    }

    private static String stringValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    private static boolean booleanValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(value.toString());
    }

}

package io.floci.gcp.services.pubsub;

import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.pubsub.v1.*;
import io.floci.gcp.core.common.GcpGrpcController;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.pubsub.model.StoredTopic;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.util.List;

public class PubSubPublisherController extends PublisherGrpc.PublisherImplBase {

    private static final Logger LOG = Logger.getLogger(PubSubPublisherController.class);

    private final PubSubService service;

    PubSubPublisherController(PubSubService service) {
        this.service = service;
    }

    @Override
    public void createTopic(Topic request, StreamObserver<Topic> responseObserver) {
        LOG.infof("createTopic name=%s", request.getName());
        try {
            StoredTopic stored = service.createTopic(request.getName());
            responseObserver.onNext(Topic.newBuilder().setName(stored.getName()).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("createTopic failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getTopic(GetTopicRequest request, StreamObserver<Topic> responseObserver) {
        LOG.debugf("getTopic name=%s", request.getTopic());
        try {
            StoredTopic stored = service.getTopic(request.getTopic());
            responseObserver.onNext(Topic.newBuilder().setName(stored.getName()).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("getTopic failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listTopics(ListTopicsRequest request, StreamObserver<ListTopicsResponse> responseObserver) {
        LOG.debugf("listTopics project=%s", request.getProject());
        try {
            String project = extractProjectId(request.getProject());
            List<StoredTopic> all = service.listTopics(project);
            PageToken.Page<StoredTopic> page = PageToken.paginate(all,
                    request.getPageSize(), request.getPageToken());
            ListTopicsResponse.Builder response = ListTopicsResponse.newBuilder();
            for (StoredTopic t : page.items()) {
                response.addTopics(Topic.newBuilder().setName(t.getName()).build());
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("listTopics failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void deleteTopic(DeleteTopicRequest request, StreamObserver<Empty> responseObserver) {
        LOG.infof("deleteTopic name=%s", request.getTopic());
        try {
            service.deleteTopic(request.getTopic());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("deleteTopic failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void publish(PublishRequest request, StreamObserver<PublishResponse> responseObserver) {
        LOG.infof("publish topic=%s count=%d", request.getTopic(), request.getMessagesCount());
        try {
            List<String> ids = service.publish(request.getTopic(), request.getMessagesList());
            responseObserver.onNext(PublishResponse.newBuilder().addAllMessageIds(ids).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("publish failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void updateTopic(UpdateTopicRequest request, StreamObserver<Topic> responseObserver) {
        LOG.infof("updateTopic name=%s", request.getTopic().getName());
        try {
            FieldMask mask = request.hasUpdateMask() ? request.getUpdateMask() : FieldMask.getDefaultInstance();
            io.floci.gcp.services.pubsub.model.StoredTopic stored =
                    service.updateTopic(request.getTopic().getName(), request.getTopic(), mask);
            responseObserver.onNext(Topic.newBuilder().setName(stored.getName()).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("updateTopic failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listTopicSubscriptions(ListTopicSubscriptionsRequest request,
            StreamObserver<ListTopicSubscriptionsResponse> responseObserver) {
        LOG.debugf("listTopicSubscriptions topic=%s", request.getTopic());
        try {
            List<String> names = service.listTopicSubscriptions(request.getTopic());
            responseObserver.onNext(ListTopicSubscriptionsResponse.newBuilder()
                    .addAllSubscriptions(names).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("listTopicSubscriptions failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void detachSubscription(DetachSubscriptionRequest request,
            StreamObserver<DetachSubscriptionResponse> responseObserver) {
        LOG.infof("detachSubscription subscription=%s", request.getSubscription());
        try {
            service.detachSubscription(request.getSubscription());
            responseObserver.onNext(DetachSubscriptionResponse.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("detachSubscription failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    private static String extractProjectId(String parent) {
        if (parent.startsWith("projects/")) {
            return parent.substring("projects/".length());
        }
        return parent;
    }
}

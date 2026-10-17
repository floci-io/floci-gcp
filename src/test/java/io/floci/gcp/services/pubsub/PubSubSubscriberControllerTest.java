package io.floci.gcp.services.pubsub;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.ModifyAckDeadlineRequest;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.ReceivedMessage;
import com.google.pubsub.v1.SeekRequest;
import com.google.pubsub.v1.SeekResponse;
import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.StreamingPullResponse;
import com.google.pubsub.v1.Subscription;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.iam.IamServices;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PubSubSubscriberControllerTest {

    private PubSubService service;
    private PubSubSubscriberController controller;

    @BeforeEach
    void setUp() {
        service = new PubSubService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                IamServices.inMemory());
        controller = new PubSubSubscriberController(service);
    }

    @Test
    void streamingPullReceivesMessagePublishedAfterStreamOpens() throws Exception {
        String topic = "projects/p1/topics/t1";
        String subscription = "projects/p1/subscriptions/s1";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);

        RecordingObserver<StreamingPullResponse> responseObserver = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> requestObserver = controller.streamingPull(responseObserver);

        requestObserver.onNext(StreamingPullRequest.newBuilder()
                .setSubscription(subscription)
                .build());

        service.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("hello"))
                .putAttributes("source", "streaming-pull-test")
                .build()));

        assertTrue(responseObserver.awaitValue(), "streaming pull should deliver published message");
        assertNull(responseObserver.error.get());
        StreamingPullResponse response = responseObserver.values.get(0);
        assertEquals(1, response.getReceivedMessagesCount());
        assertEquals("hello", response.getReceivedMessages(0).getMessage().getData().toStringUtf8());
        assertEquals("streaming-pull-test",
                response.getReceivedMessages(0).getMessage().getAttributesOrThrow("source"));

        requestObserver.onCompleted();
    }

    @Test
    void streamingPullOnlyReceivesMessagesMatchingTheSubscriptionFilter() throws Exception {
        String topic = "projects/p1/topics/t1";
        String subscription = "projects/p1/subscriptions/s1";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10, null, false, null,
                "attributes.event_type = \"match\"", null, null, null, null, null, 0, false, false);

        RecordingObserver<StreamingPullResponse> responseObserver = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> requestObserver = controller.streamingPull(responseObserver);

        requestObserver.onNext(StreamingPullRequest.newBuilder()
                .setSubscription(subscription)
                .build());

        service.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("dropped"))
                .putAttributes("event_type", "nomatch")
                .build()));

        assertFalse(responseObserver.awaitValue(),
                "streaming pull should not deliver a message the filter excludes");

        service.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("delivered"))
                .putAttributes("event_type", "match")
                .build()));

        assertTrue(responseObserver.awaitValue(), "streaming pull should deliver the matching message");
        assertNull(responseObserver.error.get());
        StreamingPullResponse response = responseObserver.values.get(0);
        assertEquals(1, response.getReceivedMessagesCount());
        assertEquals("delivered", response.getReceivedMessages(0).getMessage().getData().toStringUtf8());

        requestObserver.onCompleted();
    }

    @Test
    void modifyAckDeadlineOverGrpcWithZeroRedeliversTheMessageOnTheNextPull() {
        String topic = "projects/p1/topics/t1";
        String subscription = "projects/p1/subscriptions/s1";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        service.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("hello"))
                .build()));
        List<ReceivedMessage> first = service.pull(subscription, 1);
        assertEquals(1, first.size());
        assertTrue(service.pull(subscription, 1).isEmpty());

        RecordingObserver<Empty> responseObserver = new RecordingObserver<>();
        controller.modifyAckDeadline(ModifyAckDeadlineRequest.newBuilder()
                .setSubscription(subscription)
                .addAckIds(first.get(0).getAckId())
                .setAckDeadlineSeconds(0)
                .build(), responseObserver);

        assertNull(responseObserver.error.get());
        assertEquals(1, responseObserver.values.size());
        List<ReceivedMessage> redelivered = service.pull(subscription, 1);
        assertEquals(1, redelivered.size());
        assertEquals("hello", redelivered.get(0).getMessage().getData().toStringUtf8());
        assertEquals(first.get(0).getMessage().getMessageId(), redelivered.get(0).getMessage().getMessageId());
    }

    @Test
    void modifyAckDeadlineOverGrpcRejectsADeadlineOverTenMinutes() {
        String topic = "projects/p1/topics/t1";
        String subscription = "projects/p1/subscriptions/s1";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);

        RecordingObserver<Empty> responseObserver = new RecordingObserver<>();
        controller.modifyAckDeadline(ModifyAckDeadlineRequest.newBuilder()
                .setSubscription(subscription)
                .addAckIds("some-ack-id")
                .setAckDeadlineSeconds(601)
                .build(), responseObserver);

        Status status = ((StatusRuntimeException) responseObserver.error.get()).getStatus();
        assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
        assertEquals("Invalid ack deadline given (ack_deadline=601). "
                + "The ack deadline must be between 0 and 600 seconds.", status.getDescription());
        assertTrue(responseObserver.values.isEmpty());
    }

    @Test
    void streamingPullNackRedeliversTheMessageOnTheStream() throws Exception {
        String topic = "projects/p1/topics/t1";
        String subscription = "projects/p1/subscriptions/s1";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);

        RecordingObserver<StreamingPullResponse> responseObserver = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> requestObserver = controller.streamingPull(responseObserver);
        requestObserver.onNext(StreamingPullRequest.newBuilder()
                .setSubscription(subscription)
                .build());
        service.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("retry me"))
                .build()));
        assertTrue(responseObserver.awaitValues(1), "streaming pull should deliver the published message");
        ReceivedMessage first = responseObserver.values.get(0).getReceivedMessages(0);

        requestObserver.onNext(StreamingPullRequest.newBuilder()
                .addModifyDeadlineAckIds(first.getAckId())
                .addModifyDeadlineSeconds(0)
                .build());

        assertTrue(responseObserver.awaitValues(2), "a nack over the stream should redeliver the message");
        assertNull(responseObserver.error.get());
        StreamingPullResponse redelivered = responseObserver.values.get(1);
        assertEquals(1, redelivered.getReceivedMessagesCount());
        assertEquals(first.getMessage().getMessageId(), redelivered.getReceivedMessages(0).getMessage().getMessageId());
        assertNotEquals(first.getAckId(), redelivered.getReceivedMessages(0).getAckId());

        requestObserver.onCompleted();
    }

    @Test
    void streamingPullLeaseExtensionKeepsTheMessageOutstanding() throws Exception {
        String topic = "projects/p1/topics/t1";
        String subscription = "projects/p1/subscriptions/s1";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);

        RecordingObserver<StreamingPullResponse> responseObserver = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> requestObserver = controller.streamingPull(responseObserver);
        requestObserver.onNext(StreamingPullRequest.newBuilder()
                .setSubscription(subscription)
                .setStreamAckDeadlineSeconds(60)
                .build());
        service.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("keep me"))
                .build()));
        assertTrue(responseObserver.awaitValues(1), "streaming pull should deliver the published message");

        requestObserver.onNext(StreamingPullRequest.newBuilder()
                .addModifyDeadlineAckIds(responseObserver.values.get(0).getReceivedMessages(0).getAckId())
                .addModifyDeadlineSeconds(60)
                .build());

        assertFalse(responseObserver.awaitValues(2), "a lease extension must not redeliver the message");
        assertNull(responseObserver.error.get());
        assertTrue(service.pull(subscription, 10).isEmpty());

        requestObserver.onCompleted();
    }

    @Test
    void streamingPullAbortsWithInvalidArgumentWhenModifyDeadlineListsDiffer() {
        String topic = "projects/p1/topics/t1";
        String subscription = "projects/p1/subscriptions/s1";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);

        RecordingObserver<StreamingPullResponse> responseObserver = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> requestObserver = controller.streamingPull(responseObserver);
        requestObserver.onNext(StreamingPullRequest.newBuilder()
                .setSubscription(subscription)
                .build());

        requestObserver.onNext(StreamingPullRequest.newBuilder()
                .addModifyDeadlineAckIds("ack-1")
                .addModifyDeadlineAckIds("ack-2")
                .addModifyDeadlineSeconds(0)
                .build());

        Throwable error = responseObserver.error.get();
        assertNotNull(error);
        assertEquals(Status.Code.INVALID_ARGUMENT, ((StatusRuntimeException) error).getStatus().getCode());
    }

    @Test
    void createSubscriptionOverGrpcStoresTheFilter() {
        service.createTopic("projects/p1/topics/t1");

        RecordingObserver<Subscription> responseObserver = new RecordingObserver<>();
        controller.createSubscription(Subscription.newBuilder()
                .setName("projects/p1/subscriptions/s1")
                .setTopic("projects/p1/topics/t1")
                .setAckDeadlineSeconds(10)
                .setFilter("attributes.event_type = \"match\"")
                .build(), responseObserver);

        assertNull(responseObserver.error.get());
        assertEquals("attributes.event_type = \"match\"", responseObserver.values.get(0).getFilter());
        assertEquals("attributes.event_type = \"match\"",
                service.getSubscription("projects/p1/subscriptions/s1").getFilter());
    }

    @Test
    void createSubscriptionOverGrpcRejectsUnparseableFilter() {
        service.createTopic("projects/p1/topics/t1");

        RecordingObserver<Subscription> responseObserver = new RecordingObserver<>();
        controller.createSubscription(Subscription.newBuilder()
                .setName("projects/p1/subscriptions/s1")
                .setTopic("projects/p1/topics/t1")
                .setAckDeadlineSeconds(10)
                .setFilter("this is not a filter (((")
                .build(), responseObserver);

        Throwable error = responseObserver.error.get();
        assertNotNull(error);
        assertEquals(Status.Code.INVALID_ARGUMENT,
                ((StatusRuntimeException) error).getStatus().getCode());
        assertTrue(responseObserver.values.isEmpty());
    }

    @Test
    void seekOverGrpcWithTimeDropsMessagesPublishedBeforeIt() {
        String topic = "projects/p1/topics/t1";
        String subscription = "projects/p1/subscriptions/s1";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        service.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("before"))
                .build()));

        RecordingObserver<SeekResponse> responseObserver = new RecordingObserver<>();
        controller.seek(SeekRequest.newBuilder()
                .setSubscription(subscription)
                .setTime(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond() + 60))
                .build(), responseObserver);

        assertNull(responseObserver.error.get());
        assertEquals(1, responseObserver.values.size());
        assertTrue(service.pull(subscription, 10).isEmpty(), "a message published before the seek time is acknowledged");
    }

    @Test
    void seekOverGrpcWithInvalidTimeReturnsOneLineInvalidArgument() {
        String topic = "projects/p1/topics/t1";
        String subscription = "projects/p1/subscriptions/s1";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);

        RecordingObserver<SeekResponse> responseObserver = new RecordingObserver<>();
        controller.seek(SeekRequest.newBuilder()
                .setSubscription(subscription)
                .setTime(Timestamp.newBuilder().setSeconds(1L << 62))
                .build(), responseObserver);

        Status status = ((StatusRuntimeException) responseObserver.error.get()).getStatus();
        assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
        assertEquals("Invalid seek time: seconds=4611686018427387904 nanos=0", status.getDescription());
    }

    private static final class RecordingObserver<T> implements StreamObserver<T> {
        private final List<T> values = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void onNext(T value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error.set(t);
        }

        @Override
        public void onCompleted() {}

        private boolean awaitValue() throws InterruptedException {
            return awaitValues(1);
        }

        private boolean awaitValues(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (values.size() < count) {
                if (System.nanoTime() >= deadline) {
                    return false;
                }
                Thread.sleep(10);
            }
            return true;
        }
    }
}

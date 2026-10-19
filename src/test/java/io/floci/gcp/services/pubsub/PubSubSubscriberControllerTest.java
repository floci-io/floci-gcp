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
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.iam.IamServices;
import io.floci.gcp.services.iam.authorization.IamGrpcDeliveryAuthorization;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

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
    void streamingPullUsesDeliveryAuthorizationForRequestsAndPublishedMessages() throws Exception {
        String topic = "projects/p1/topics/delivery-check";
        String subscription = "projects/p1/subscriptions/delivery-check";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        AtomicInteger checks = new AtomicInteger();
        Context context = IamGrpcDeliveryAuthorization.withDelivery(Context.current(), (resource, delivery) -> {
            assertEquals(subscription, resource);
            checks.incrementAndGet();
            delivery.run();
        });
        Context previous = context.attach();
        try {
            RecordingObserver<StreamingPullResponse> responses = new RecordingObserver<>();
            StreamObserver<StreamingPullRequest> requests = controller.streamingPull(responses);
            requests.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription).build());
            assertEquals(1, checks.get());

            service.publish(topic, List.of(PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("published")).build()));
            assertTrue(responses.awaitValue());
            assertEquals(2, checks.get());

            requests.onNext(StreamingPullRequest.getDefaultInstance());
            assertEquals(3, checks.get());
            requests.onCompleted();
        } finally {
            context.detach(previous);
        }
    }

    @Test
    void streamingPullDoesNotAcknowledgeAfterDeliveryAuthorizationIsRevoked() throws Exception {
        String topic = "projects/p1/topics/ack-revoked";
        String subscription = "projects/p1/subscriptions/ack-revoked";
        PubSubService observed = spy(service);
        observed.createTopic(topic);
        observed.createSubscription(subscription, topic, 10);
        observed.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("delivered")).build()));
        AtomicBoolean allowed = new AtomicBoolean(true);
        Context context = IamGrpcDeliveryAuthorization.withDelivery(Context.current(), (resource, delivery) -> {
            assertEquals(subscription, resource);
            if (!allowed.get()) {
                throw GcpException.permissionDenied("subscription revoked");
            }
            delivery.run();
        });
        Context previous = context.attach();
        try {
            RecordingObserver<StreamingPullResponse> responses = new RecordingObserver<>();
            StreamObserver<StreamingPullRequest> requests =
                    new PubSubSubscriberController(observed).streamingPull(responses);
            requests.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription).build());
            assertTrue(responses.awaitValue());
            String ackId = responses.values.getFirst().getReceivedMessages(0).getAckId();

            allowed.set(false);
            requests.onNext(StreamingPullRequest.newBuilder().addAckIds(ackId)
                    .addModifyDeadlineAckIds(ackId).addModifyDeadlineSeconds(0).build());

            assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(responses.error.get()).getCode());
            verify(observed, never()).acknowledge(eq(subscription), anyList());
            verify(observed, never()).modifyAckDeadlineWithoutNotification(eq(subscription), anyList(), anyInt());
        } finally {
            context.detach(previous);
        }
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
    void streamingPullNackDoesNotDeadlockWithAnotherStreamWaitingForPolicyLock() throws Exception {
        String topic = "projects/p1/topics/concurrent-nack";
        String subscription = "projects/p1/subscriptions/concurrent-nack";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);

        Object policyLock = new Object();
        CountDownLatch policyHeld = new CountDownLatch(1);
        CountDownLatch requestWaitingForPolicy = new CountDownLatch(1);
        AtomicReference<Thread> requestThread = new AtomicReference<>();
        AtomicReference<Thread> nackThread = new AtomicReference<>();
        Context context = IamGrpcDeliveryAuthorization.withDelivery(Context.current(), (resource, delivery) -> {
            assertEquals(subscription, resource);
            if (Thread.currentThread() == requestThread.get()) {
                requestWaitingForPolicy.countDown();
            }
            synchronized (policyLock) {
                if (Thread.currentThread() == nackThread.get()) {
                    policyHeld.countDown();
                    try {
                        if (!requestWaitingForPolicy.await(2, TimeUnit.SECONDS)) {
                            throw new AssertionError("request did not reach policy authorization");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                delivery.run();
            }
        });
        Context previous = context.attach();
        RecordingObserver<StreamingPullResponse> firstResponses = new RecordingObserver<>();
        RecordingObserver<StreamingPullResponse> secondResponses = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> first;
        StreamObserver<StreamingPullRequest> second;
        try {
            first = controller.streamingPull(firstResponses);
            second = controller.streamingPull(secondResponses);
            first.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription).build());
            second.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription).build());
        } finally {
            context.detach(previous);
        }

        service.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("retry me")).build()));
        assertEquals(1, firstResponses.values.size());
        String ackId = firstResponses.values.getFirst().getReceivedMessages(0).getAckId();
        AtomicReference<Throwable> workerError = new AtomicReference<>();
        Thread pendingNack = Thread.ofVirtual().start(() -> {
            try {
                nackThread.set(Thread.currentThread());
                second.onNext(StreamingPullRequest.newBuilder()
                        .addModifyDeadlineAckIds(ackId).addModifyDeadlineSeconds(0).build());
            } catch (Throwable t) {
                workerError.set(t);
            }
        });
        assertTrue(policyHeld.await(2, TimeUnit.SECONDS));
        Thread pendingRequest = Thread.ofVirtual().start(() -> {
            try {
                requestThread.set(Thread.currentThread());
                first.onNext(StreamingPullRequest.getDefaultInstance());
            } catch (Throwable t) {
                workerError.set(t);
            }
        });

        pendingNack.join(2000);
        pendingRequest.join(2000);
        assertFalse(pendingNack.isAlive(), "nack listener must not wait on the other stream's delivery lock");
        assertFalse(pendingRequest.isAlive(), "pending stream request must finish after the policy lock is released");
        assertNull(workerError.get());
        assertEquals(1, firstResponses.values.size());
        assertEquals(1, secondResponses.values.size());
        assertNull(firstResponses.error.get());
        assertNull(secondResponses.error.get());
        first.onCompleted();
        second.onCompleted();
    }

    @Test
    void streamingPullNackNotifiesListenersAfterAuthorizationReturns() throws Exception {
        String topic = "projects/p1/topics/deferred-nack";
        String subscription = "projects/p1/subscriptions/deferred-nack";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        Object policyLock = new Object();
        Context context = IamGrpcDeliveryAuthorization.withDelivery(Context.current(), (resource, delivery) -> {
            synchronized (policyLock) {
                delivery.run();
            }
        });
        Context previous = context.attach();
        RecordingObserver<StreamingPullResponse> responses = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> requests;
        try {
            requests = controller.streamingPull(responses);
            requests.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription).build());
        } finally {
            context.detach(previous);
        }
        service.publish(topic, List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("retry")).build()));
        assertEquals(1, responses.values.size());
        String ackId = responses.values.getFirst().getReceivedMessages(0).getAckId();
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        AtomicBoolean listenerHeldPolicyLock = new AtomicBoolean(true);
        Runnable unregister = service.registerMessageListener(subscription, () -> {
            listenerCalled.set(true);
            listenerHeldPolicyLock.set(Thread.holdsLock(policyLock));
        });

        requests.onNext(StreamingPullRequest.newBuilder()
                .addModifyDeadlineAckIds(ackId).addModifyDeadlineSeconds(0).build());

        assertTrue(listenerCalled.get());
        assertFalse(listenerHeldPolicyLock.get());
        assertNull(responses.error.get());
        unregister.run();
        requests.onCompleted();
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

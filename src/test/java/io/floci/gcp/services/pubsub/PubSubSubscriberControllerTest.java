package io.floci.gcp.services.pubsub;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.BigQueryConfig;
import com.google.pubsub.v1.DeadLetterPolicy;
import com.google.pubsub.v1.ExpirationPolicy;
import com.google.pubsub.v1.GetSubscriptionRequest;
import com.google.pubsub.v1.ModifyAckDeadlineRequest;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.ReceivedMessage;
import com.google.pubsub.v1.RetryPolicy;
import com.google.pubsub.v1.SeekRequest;
import com.google.pubsub.v1.SeekResponse;
import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.StreamingPullResponse;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.UpdateSubscriptionRequest;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class PubSubSubscriberControllerTest {

    private PubSubService service;
    private PubSubSubscriberController controller;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        service = new PubSubService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                IamServices.inMemory(),
                clock);
        controller = new PubSubSubscriberController(service);
    }

    @Test
    void cancelledContextPreventsLateRequestsFromRegisteringAListener() throws Exception {
        PubSubService observed = spy(service);
        Context.CancellableContext context = Context.current().withCancellation();
        RecordingObserver<StreamingPullResponse> responses = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> requests = context.call(
                () -> new PubSubSubscriberController(observed).streamingPull(responses));
        context.cancel(null);

        requests.onNext(StreamingPullRequest.newBuilder()
                .setSubscription("projects/p1/subscriptions/s1").build());

        verify(observed, never()).registerMessageListener(any(), any());
        assertTrue(responses.values.isEmpty());
        assertNull(responses.error.get());
    }

    @Test
    void contextCancellationUnregistersListenerAndPreservesOutstandingLease() throws Exception {
        String topic = "projects/p1/topics/t1";
        String subscription = "projects/p1/subscriptions/s1";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        PubSubService observed = spy(service);
        AtomicInteger unregisters = new AtomicInteger();
        doAnswer(invocation -> {
            Runnable unregister = (Runnable) invocation.callRealMethod();
            return (Runnable) () -> {
                unregister.run();
                unregisters.incrementAndGet();
            };
        }).when(observed).registerMessageListener(eq(subscription), any());
        Context.CancellableContext context = Context.current().withCancellation();
        RecordingObserver<StreamingPullResponse> responses = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> requests = context.call(
                () -> new PubSubSubscriberController(observed).streamingPull(responses));
        requests.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription)
                .setStreamAckDeadlineSeconds(10).build());
        observed.publish(topic, List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("unacked")).build()));
        ReceivedMessage first = responses.values.getFirst().getReceivedMessages(0);

        context.cancel(null);
        requests.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription).build());
        requests.onError(Status.CANCELLED.asException());
        requests.onCompleted();
        assertEquals(1, unregisters.get(), "cleanup should run exactly once");

        observed.publish(topic, List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("recovery")).build()));
        assertEquals(1, responses.values.size(), "the cancelled stream must not consume newly published messages");
        List<ReceivedMessage> recovery = observed.pull(subscription, 10);
        assertEquals(1, recovery.size());
        assertEquals("recovery", recovery.getFirst().getMessage().getData().toStringUtf8());
        observed.acknowledge(subscription, List.of(recovery.getFirst().getAckId()));
        assertTrue(observed.pull(subscription, 10).isEmpty());

        clock.advance(Duration.ofSeconds(10));
        observed.expireLeases();
        List<ReceivedMessage> redelivery = observed.pull(subscription, 10);
        assertEquals(1, redelivery.size());
        assertEquals(first.getMessage().getMessageId(), redelivery.getFirst().getMessage().getMessageId());
        assertNotEquals(first.getAckId(), redelivery.getFirst().getAckId());
    }

    @Test
    void cancellationDuringListenerRegistrationStillUnregistersTheListener() throws Exception {
        String topic = "projects/p1/topics/t1";
        String subscription = "projects/p1/subscriptions/s1";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        PubSubService observed = spy(service);
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch finishRegistration = new CountDownLatch(1);
        CountDownLatch cancellationStarted = new CountDownLatch(1);
        AtomicInteger unregisters = new AtomicInteger();
        doAnswer(invocation -> {
            Runnable unregister = (Runnable) invocation.callRealMethod();
            registered.countDown();
            assertTrue(finishRegistration.await(5, TimeUnit.SECONDS));
            return (Runnable) () -> {
                unregister.run();
                unregisters.incrementAndGet();
            };
        }).when(observed).registerMessageListener(eq(subscription), any());
        Context.CancellableContext context = Context.current().withCancellation();
        context.addListener(ignored -> cancellationStarted.countDown(), Runnable::run);
        RecordingObserver<StreamingPullResponse> responses = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> requests = context.call(
                () -> new PubSubSubscriberController(observed).streamingPull(responses));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> registration = executor.submit(() -> requests.onNext(
                    StreamingPullRequest.newBuilder().setSubscription(subscription).build()));
            try {
                assertTrue(registered.await(5, TimeUnit.SECONDS));
                Future<?> cancellation = executor.submit(() -> context.cancel(null));
                assertTrue(cancellationStarted.await(5, TimeUnit.SECONDS));
                finishRegistration.countDown();
                registration.get(5, TimeUnit.SECONDS);
                cancellation.get(5, TimeUnit.SECONDS);
                assertEquals(1, unregisters.get());
                observed.publish(topic, List.of(PubsubMessage.newBuilder()
                        .setData(ByteString.copyFromUtf8("recovery")).build()));
                assertTrue(responses.values.isEmpty());
                assertEquals(1, observed.pull(subscription, 10).size());
            } finally {
                finishRegistration.countDown();
                context.cancel(null);
            }
        }
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

        assertTrue(responseObserver.staysBelow(1),
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

        assertTrue(responseObserver.staysBelow(2), "a lease extension must not redeliver the message");
        assertNull(responseObserver.error.get());
        assertTrue(service.pull(subscription, 10).isEmpty());

        requestObserver.onCompleted();
    }

    @Test
    void streamingPullReportsDeliveryAttemptsAndForwardsTheDeadLetterToAStream() throws Exception {
        String topic = "projects/p1/topics/t1";
        String deadLetterTopic = "projects/p1/topics/dlq";
        String subscription = "projects/p1/subscriptions/s1";
        String deadLetterSubscription = "projects/p1/subscriptions/dlq-sub";
        service.createTopic(topic);
        service.createTopic(deadLetterTopic);
        service.createSubscription(deadLetterSubscription, deadLetterTopic, 60);
        createDeadLetterSubscription(subscription, topic, deadLetterTopic);

        RecordingObserver<StreamingPullResponse> deadLetters = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> deadLetterStream = controller.streamingPull(deadLetters);
        deadLetterStream.onNext(StreamingPullRequest.newBuilder().setSubscription(deadLetterSubscription).build());
        RecordingObserver<StreamingPullResponse> responses = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> stream = controller.streamingPull(responses);
        stream.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription).build());
        service.publish(topic, List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertTrue(responses.awaitValues(attempt), "delivery " + attempt + " should reach the stream");
            ReceivedMessage received = responses.values.get(attempt - 1).getReceivedMessages(0);
            assertEquals(attempt, received.getDeliveryAttempt());
            stream.onNext(StreamingPullRequest.newBuilder()
                    .addModifyDeadlineAckIds(received.getAckId())
                    .addModifyDeadlineSeconds(0)
                    .build());
        }

        assertTrue(deadLetters.awaitValue(), "the dead letter should reach the dead-letter stream");
        assertEquals("5", deadLetters.values.get(0).getReceivedMessages(0).getMessage()
                .getAttributesMap().get("CloudPubSubDeadLetterSourceDeliveryCount"));
        assertTrue(responses.staysBelow(6), "a dead-lettered message must not come back on the source stream");
        assertNull(responses.error.get());
        stream.onCompleted();
        deadLetterStream.onCompleted();
    }

    @Test
    void streamingPullForwardsDeadLettersAfterReleasingTheStreamLock() throws Exception {
        String topic = "projects/p1/topics/t1";
        String deadLetterTopic = "projects/p1/topics/dlq";
        String subscription = "projects/p1/subscriptions/s1";
        String deadLetterSubscription = "projects/p1/subscriptions/dlq-sub";
        service.createTopic(topic);
        service.createTopic(deadLetterTopic);
        service.createSubscription(deadLetterSubscription, deadLetterTopic, 60);
        createDeadLetterSubscription(subscription, topic, deadLetterTopic);
        RecordingObserver<StreamingPullResponse> responses = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> stream = controller.streamingPull(responses);
        stream.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription).build());
        AtomicReference<String> streamLock = new AtomicReference<>("the dead letter was never published");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            // A delivery on the dead-letter subscription can take another stream's lock. If the forwarding
            // stream still held its own, two streams that dead-letter into each other's topics would deadlock.
            service.registerMessageListener(deadLetterSubscription, () -> {
                Future<?> otherRequest = executor.submit(() -> stream.onNext(StreamingPullRequest.getDefaultInstance()));
                try {
                    otherRequest.get(5, TimeUnit.SECONDS);
                    streamLock.set("free");
                } catch (TimeoutException e) {
                    streamLock.set("held while forwarding the dead letter");
                } catch (Exception e) {
                    streamLock.set(e.toString());
                }
            });
            service.publish(topic, List.of(PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("msg")).build()));
            for (int attempt = 1; attempt <= 5; attempt++) {
                assertTrue(responses.awaitValues(attempt), "delivery " + attempt + " should reach the stream");
                stream.onNext(StreamingPullRequest.newBuilder()
                        .addModifyDeadlineAckIds(responses.values.get(attempt - 1).getReceivedMessages(0).getAckId())
                        .addModifyDeadlineSeconds(0)
                        .build());
            }
        }

        assertEquals("free", streamLock.get());
        stream.onCompleted();
    }

    private void createDeadLetterSubscription(String subscription, String topic, String deadLetterTopic) {
        service.createSubscription(subscription, topic, 60, null, false, null, null, null,
                null, null, null, deadLetterTopic, 5, false, false);
    }

    @Test
    void streamingPullRedeliversOnTheStreamOnceTheStreamAckDeadlinePasses() throws Exception {
        String topic = "projects/p1/topics/t1";
        String subscription = "projects/p1/subscriptions/s1";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 60);

        RecordingObserver<StreamingPullResponse> responseObserver = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> requestObserver = controller.streamingPull(responseObserver);
        requestObserver.onNext(StreamingPullRequest.newBuilder()
                .setSubscription(subscription)
                .setStreamAckDeadlineSeconds(10)
                .build());
        service.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("expire me"))
                .build()));
        assertTrue(responseObserver.awaitValues(1), "streaming pull should deliver the published message");
        ReceivedMessage first = responseObserver.values.get(0).getReceivedMessages(0);

        clock.advance(Duration.ofSeconds(9));
        service.expireLeases();
        assertTrue(responseObserver.staysBelow(2), "the message must stay leased until the stream deadline");

        clock.advance(Duration.ofSeconds(1));
        service.expireLeases();
        assertTrue(responseObserver.awaitValues(2), "an expired lease should be redelivered on the stream");
        assertNull(responseObserver.error.get());
        ReceivedMessage redelivered = responseObserver.values.get(1).getReceivedMessages(0);
        assertEquals(first.getMessage().getMessageId(), redelivered.getMessage().getMessageId());
        assertNotEquals(first.getAckId(), redelivered.getAckId());

        requestObserver.onCompleted();
    }

    @Test
    void streamingPullAppliesAStreamAckDeadlineChangedOnALaterRequest() throws Exception {
        String topic = "projects/p1/topics/t1";
        String subscription = "projects/p1/subscriptions/s1";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 60);

        RecordingObserver<StreamingPullResponse> responseObserver = new RecordingObserver<>();
        StreamObserver<StreamingPullRequest> requestObserver = controller.streamingPull(responseObserver);
        requestObserver.onNext(StreamingPullRequest.newBuilder()
                .setSubscription(subscription)
                .setStreamAckDeadlineSeconds(60)
                .build());
        requestObserver.onNext(StreamingPullRequest.newBuilder()
                .setStreamAckDeadlineSeconds(10)
                .build());
        service.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("expire me"))
                .build()));
        assertTrue(responseObserver.awaitValues(1), "streaming pull should deliver the published message");

        clock.advance(Duration.ofSeconds(10));
        service.expireLeases();

        assertTrue(responseObserver.awaitValues(2), "the updated stream deadline should apply");
        requestObserver.onCompleted();
    }

    @Test
    void streamingPullLeaseOutlivesTheStreamUntilItsDeadline() throws Exception {
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
                .setData(ByteString.copyFromUtf8("crashed consumer"))
                .build()));
        assertTrue(responseObserver.awaitValues(1), "streaming pull should deliver the published message");
        requestObserver.onCompleted();

        clock.advance(Duration.ofSeconds(59));
        service.expireLeases();
        assertTrue(service.pull(subscription, 10).isEmpty());

        clock.advance(Duration.ofSeconds(1));
        service.expireLeases();
        assertEquals(1, service.pull(subscription, 10).size());
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
    void createSubscriptionOverGrpcStoresTheDeadLetterPolicy() {
        service.createTopic("projects/p1/topics/t1");
        service.createTopic("projects/p1/topics/dlq");

        RecordingObserver<Subscription> responseObserver = new RecordingObserver<>();
        controller.createSubscription(Subscription.newBuilder()
                .setName("projects/p1/subscriptions/s1")
                .setTopic("projects/p1/topics/t1")
                .setDeadLetterPolicy(DeadLetterPolicy.newBuilder().setDeadLetterTopic("projects/p1/topics/dlq"))
                .build(), responseObserver);

        assertNull(responseObserver.error.get());
        assertEquals("projects/p1/topics/dlq", responseObserver.values.get(0).getDeadLetterPolicy().getDeadLetterTopic());
        assertEquals(5, responseObserver.values.get(0).getDeadLetterPolicy().getMaxDeliveryAttempts());
        assertEquals("projects/p1/topics/dlq",
                service.getSubscription("projects/p1/subscriptions/s1").getDeadLetterTopic());
    }

    @Test
    void createSubscriptionOverGrpcStoresOrderingExactlyOnceAndRetryPolicy() {
        service.createTopic("projects/p1/topics/t1");

        RecordingObserver<Subscription> responseObserver = new RecordingObserver<>();
        controller.createSubscription(Subscription.newBuilder()
                .setName("projects/p1/subscriptions/s1")
                .setTopic("projects/p1/topics/t1")
                .setEnableMessageOrdering(true)
                .setEnableExactlyOnceDelivery(true)
                .setRetryPolicy(RetryPolicy.newBuilder()
                        // Qualified: com.google.protobuf.Duration clashes with the imported java.time.Duration.
                        .setMinimumBackoff(com.google.protobuf.Duration.newBuilder().setSeconds(1))
                        .setMaximumBackoff(com.google.protobuf.Duration.newBuilder().setSeconds(5).setNanos(500_000_000)))
                .build(), responseObserver);

        assertNull(responseObserver.error.get());
        Subscription created = responseObserver.values.get(0);
        assertTrue(created.getEnableMessageOrdering());
        assertTrue(created.getEnableExactlyOnceDelivery());
        assertEquals(1, created.getRetryPolicy().getMinimumBackoff().getSeconds());
        assertEquals(5, created.getRetryPolicy().getMaximumBackoff().getSeconds());
        assertEquals(500_000_000, created.getRetryPolicy().getMaximumBackoff().getNanos());

        RecordingObserver<Subscription> getObserver = new RecordingObserver<>();
        controller.getSubscription(GetSubscriptionRequest.newBuilder()
                .setSubscription("projects/p1/subscriptions/s1").build(), getObserver);
        assertNull(getObserver.error.get());
        assertEquals(created, getObserver.values.get(0));
        assertEquals(Map.of("minimumBackoff", "1s", "maximumBackoff", "5.500s"),
                service.getSubscription("projects/p1/subscriptions/s1").getRetryPolicy());
    }

    @Test
    void createSubscriptionOverGrpcPreservesStorageAndRetentionSettings() {
        service.createTopic("projects/p1/topics/t1");

        RecordingObserver<Subscription> createObserver = new RecordingObserver<>();
        controller.createSubscription(Subscription.newBuilder()
                .setName("projects/p1/subscriptions/s1")
                .setTopic("projects/p1/topics/t1")
                .putLabels("team", "analytics")
                .setRetainAckedMessages(true)
                .setMessageRetentionDuration(com.google.protobuf.Duration.newBuilder().setSeconds(604800))
                .setBigqueryConfig(BigQueryConfig.newBuilder()
                        .setTable("p1.dataset.events")
                        .setUseTableSchema(true)
                        .setWriteMetadata(true)
                        .setDropUnknownFields(true)
                        .setServiceAccountEmail("writer@p1.iam.gserviceaccount.com"))
                .setExpirationPolicy(ExpirationPolicy.newBuilder()
                        .setTtl(com.google.protobuf.Duration.newBuilder().setSeconds(2678400)))
                .build(), createObserver);

        assertNull(createObserver.error.get());
        Subscription created = createObserver.values.get(0);
        assertEquals(Map.of("team", "analytics"), created.getLabelsMap());
        assertTrue(created.getRetainAckedMessages());
        assertEquals(604800, created.getMessageRetentionDuration().getSeconds());
        assertEquals("p1.dataset.events", created.getBigqueryConfig().getTable());
        assertTrue(created.getBigqueryConfig().getUseTableSchema());
        assertTrue(created.getBigqueryConfig().getWriteMetadata());
        assertTrue(created.getBigqueryConfig().getDropUnknownFields());
        assertEquals("writer@p1.iam.gserviceaccount.com",
                created.getBigqueryConfig().getServiceAccountEmail());
        assertEquals(BigQueryConfig.State.ACTIVE, created.getBigqueryConfig().getState());
        assertEquals(2678400, created.getExpirationPolicy().getTtl().getSeconds());
        assertEquals(Map.of(
                "table", "p1.dataset.events",
                "useTableSchema", true,
                "writeMetadata", true,
                "dropUnknownFields", true,
                "serviceAccountEmail", "writer@p1.iam.gserviceaccount.com"),
                service.getSubscription("projects/p1/subscriptions/s1").getBigQueryConfig());

        RecordingObserver<Subscription> getObserver = new RecordingObserver<>();
        controller.getSubscription(GetSubscriptionRequest.newBuilder()
                .setSubscription("projects/p1/subscriptions/s1").build(), getObserver);
        assertNull(getObserver.error.get());
        assertEquals(created, getObserver.values.get(0));
    }

    @Test
    void getSubscriptionOverGrpcReturnsAServiceAccountStoredOverRest() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10,
                null, false, null, null, null,
                Map.of("serviceAccountEmail", "writer@p1.iam.gserviceaccount.com"),
                null, null, null, 0, false, false);

        RecordingObserver<Subscription> responseObserver = new RecordingObserver<>();
        controller.getSubscription(GetSubscriptionRequest.newBuilder()
                .setSubscription("projects/p1/subscriptions/s1").build(), responseObserver);

        assertNull(responseObserver.error.get());
        assertEquals("writer@p1.iam.gserviceaccount.com",
                responseObserver.values.get(0).getBigqueryConfig().getServiceAccountEmail());
    }

    @Test
    void getSubscriptionOverGrpcReturnsARetryPolicyStoredOverRest() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10,
                null, false, null, null, null, null, null,
                Map.of("minimumBackoff", "2s", "maximumBackoff", "30s"), null, 0, false, false);

        RecordingObserver<Subscription> responseObserver = new RecordingObserver<>();
        controller.getSubscription(GetSubscriptionRequest.newBuilder()
                .setSubscription("projects/p1/subscriptions/s1").build(), responseObserver);

        assertNull(responseObserver.error.get());
        RetryPolicy policy = responseObserver.values.get(0).getRetryPolicy();
        assertEquals(2, policy.getMinimumBackoff().getSeconds());
        assertEquals(30, policy.getMaximumBackoff().getSeconds());
    }

    @Test
    void updateSubscriptionOverGrpcReplacesTheRetryPolicy() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);

        RecordingObserver<Subscription> responseObserver = new RecordingObserver<>();
        controller.updateSubscription(UpdateSubscriptionRequest.newBuilder()
                .setSubscription(Subscription.newBuilder()
                        .setName("projects/p1/subscriptions/s1")
                        .setRetryPolicy(RetryPolicy.newBuilder()
                                .setMinimumBackoff(com.google.protobuf.Duration.newBuilder().setSeconds(3))))
                .setUpdateMask(FieldMask.newBuilder().addPaths("retry_policy"))
                .build(), responseObserver);

        assertNull(responseObserver.error.get());
        assertEquals(3, responseObserver.values.get(0).getRetryPolicy().getMinimumBackoff().getSeconds());
        assertEquals(600, responseObserver.values.get(0).getRetryPolicy().getMaximumBackoff().getSeconds());
        assertEquals(Map.of("minimumBackoff", "3s", "maximumBackoff", "600s"),
                service.getSubscription("projects/p1/subscriptions/s1").getRetryPolicy());
    }

    @Test
    void createSubscriptionOverGrpcKeepsAnEmptyRetryPolicyWithItsDefaults() {
        service.createTopic("projects/p1/topics/t1");

        RecordingObserver<Subscription> withPolicy = new RecordingObserver<>();
        controller.createSubscription(Subscription.newBuilder()
                .setName("projects/p1/subscriptions/with-policy")
                .setTopic("projects/p1/topics/t1")
                .setRetryPolicy(RetryPolicy.getDefaultInstance())
                .build(), withPolicy);
        RecordingObserver<Subscription> withoutPolicy = new RecordingObserver<>();
        controller.createSubscription(Subscription.newBuilder()
                .setName("projects/p1/subscriptions/without-policy")
                .setTopic("projects/p1/topics/t1")
                .build(), withoutPolicy);

        assertNull(withPolicy.error.get());
        Subscription created = withPolicy.values.get(0);
        assertTrue(created.hasRetryPolicy());
        assertEquals(10, created.getRetryPolicy().getMinimumBackoff().getSeconds());
        assertEquals(600, created.getRetryPolicy().getMaximumBackoff().getSeconds());
        assertNull(withoutPolicy.error.get());
        assertFalse(withoutPolicy.values.get(0).hasRetryPolicy());
        assertNull(service.getSubscription("projects/p1/subscriptions/without-policy").getRetryPolicy());
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

        /**
         * True when fewer than {@code count} responses arrived. Stream delivery runs on the thread that publishes,
         * nacks or expires leases, so anything due has already arrived; the short window only guards against a
         * delivery that turned asynchronous.
         */
        private boolean staysBelow(int count) throws InterruptedException {
            Thread.sleep(100);
            return values.size() < count;
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

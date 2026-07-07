package io.floci.gcp.services.pubsub;

import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.StreamingPullResponse;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                new InMemoryStorage<>());
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

    private static final class RecordingObserver<T> implements StreamObserver<T> {
        private final CountDownLatch valueLatch = new CountDownLatch(1);
        private final List<T> values = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void onNext(T value) {
            values.add(value);
            valueLatch.countDown();
        }

        @Override
        public void onError(Throwable t) {
            error.set(t);
        }

        @Override
        public void onCompleted() {}

        private boolean awaitValue() throws InterruptedException {
            return valueLatch.await(1, TimeUnit.SECONDS);
        }
    }
}

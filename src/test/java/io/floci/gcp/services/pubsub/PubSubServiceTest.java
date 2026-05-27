package io.floci.gcp.services.pubsub;

import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.ReceivedMessage;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.pubsub.model.StoredSnapshot;
import io.floci.gcp.services.pubsub.model.StoredSubscription;
import io.floci.gcp.services.pubsub.model.StoredTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PubSubServiceTest {

    private PubSubService service;

    @BeforeEach
    void setUp() {
        service = new PubSubService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>());
    }

    @Test
    void createTopicStoredAndRetrievable() {
        service.createTopic("projects/p1/topics/t1");

        StoredTopic topic = service.getTopic("projects/p1/topics/t1");
        assertEquals("projects/p1/topics/t1", topic.getName());
    }

    @Test
    void createTopicDuplicateThrowsAlreadyExists() {
        service.createTopic("projects/p1/topics/t1");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.createTopic("projects/p1/topics/t1"));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
    }

    @Test
    void getTopicMissingThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.getTopic("projects/p1/topics/missing"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void listTopicsFiltersByProject() {
        service.createTopic("projects/p1/topics/a");
        service.createTopic("projects/p1/topics/b");
        service.createTopic("projects/p2/topics/c");

        List<StoredTopic> topics = service.listTopics("p1");
        assertEquals(2, topics.size());
        assertTrue(topics.stream().allMatch(t -> t.getName().startsWith("projects/p1")));
    }

    @Test
    void deleteTopicCascadesSubscriptions() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);

        service.deleteTopic("projects/p1/topics/t1");

        assertThrows(GcpException.class, () -> service.getTopic("projects/p1/topics/t1"));
        List<StoredSubscription> subs = service.listSubscriptions("projects/p1");
        assertTrue(subs.stream().noneMatch(s -> s.getName().equals("projects/p1/subscriptions/s1")));
    }

    @Test
    void createSubscriptionOnMissingTopicThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.createSubscription("projects/p1/subscriptions/s1",
                        "projects/p1/topics/missing", 10));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void publishToMissingTopicThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.publish("projects/p1/topics/missing",
                        List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("hi")).build())));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void pullReturnsPublishedMessages() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);

        List<String> ids = service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("hello")).build()));
        assertFalse(ids.isEmpty());

        List<ReceivedMessage> messages = service.pull("projects/p1/subscriptions/s1", 10);
        assertEquals(1, messages.size());
        assertEquals("hello", messages.get(0).getMessage().getData().toStringUtf8());
    }

    @Test
    void acknowledgeRemovesMessageFromQueue() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));

        List<ReceivedMessage> first = service.pull("projects/p1/subscriptions/s1", 10);
        assertFalse(first.isEmpty());

        service.acknowledge("projects/p1/subscriptions/s1",
                List.of(first.get(0).getAckId()));

        List<ReceivedMessage> second = service.pull("projects/p1/subscriptions/s1", 10);
        assertTrue(second.isEmpty());
    }
}

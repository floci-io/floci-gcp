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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PubSubServiceTest {

    private PubSubService service;
    private InMemoryStorage<String, StoredSubscription> subStore;

    @BeforeEach
    void setUp() {
        subStore = new InMemoryStorage<>();
        service = new PubSubService(
                new InMemoryStorage<>(),
                subStore,
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
    void publishSkipsSubscriptionWhoseFilterDoesNotMatch() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1",
                "attributes.event_type = \"match\"");

        service.publish("projects/p1/topics/t1", List.of(message("body", "event_type", "nomatch")));

        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());
    }

    @Test
    void publishDeliversToSubscriptionWhoseFilterMatches() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1",
                "attributes.event_type = \"match\"");

        service.publish("projects/p1/topics/t1", List.of(message("body", "event_type", "match")));

        List<ReceivedMessage> messages = service.pull("projects/p1/subscriptions/s1", 10);
        assertEquals(1, messages.size());
        assertEquals("body", messages.get(0).getMessage().getData().toStringUtf8());
    }

    @Test
    void publishDeliversEveryMessageToSubscriptionWithoutFilter() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);

        service.publish("projects/p1/topics/t1", List.of(
                message("first", "event_type", "a"),
                message("second", "event_type", "b"),
                PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("third")).build()));

        assertEquals(3, service.pull("projects/p1/subscriptions/s1", 10).size());
    }

    @Test
    void publishFansOutIndependentlyPerSubscriptionFilter() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/matching", "projects/p1/topics/t1",
                "attributes.event_type = \"a\"");
        createFilteredSubscription("projects/p1/subscriptions/other", "projects/p1/topics/t1",
                "attributes.event_type = \"b\"");
        service.createSubscription("projects/p1/subscriptions/unfiltered", "projects/p1/topics/t1", 10);

        service.publish("projects/p1/topics/t1", List.of(message("body", "event_type", "a")));

        assertEquals(1, service.pull("projects/p1/subscriptions/matching", 10).size());
        assertTrue(service.pull("projects/p1/subscriptions/other", 10).isEmpty());
        assertEquals(1, service.pull("projects/p1/subscriptions/unfiltered", 10).size());
    }

    @Test
    void createSubscriptionRejectsUnparseableFilter() {
        service.createTopic("projects/p1/topics/t1");

        GcpException ex = assertThrows(GcpException.class,
                () -> createFilteredSubscription("projects/p1/subscriptions/s1",
                        "projects/p1/topics/t1", "this is not a filter ((("));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertThrows(GcpException.class, () -> service.getSubscription("projects/p1/subscriptions/s1"));
    }

    @Test
    void createSubscriptionRejectsFilterOverTheByteLimit() {
        service.createTopic("projects/p1/topics/t1");
        String filter = "attributes.name = \"" + "x".repeat(300) + "\"";

        GcpException ex = assertThrows(GcpException.class,
                () -> createFilteredSubscription("projects/p1/subscriptions/s1",
                        "projects/p1/topics/t1", filter));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void createSubscriptionStoresValidFilter() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1",
                "attributes.event_type = \"a\"");

        assertEquals("attributes.event_type = \"a\"",
                service.getSubscription("projects/p1/subscriptions/s1").getFilter());
    }

    @Test
    void updateSubscriptionRejectsChangingTheFilter() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1",
                "attributes.event_type = \"a\"");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.updateSubscription("projects/p1/subscriptions/s1", 0, null, null, null,
                        "attributes.event_type = \"b\"", null, null, null, null, null, null, null, null,
                        List.of("filter")));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertEquals("attributes.event_type = \"a\"",
                service.getSubscription("projects/p1/subscriptions/s1").getFilter());
    }

    @Test
    void updateSubscriptionRejectsRemovingTheFilter() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1",
                "attributes.event_type = \"a\"");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.updateSubscription("projects/p1/subscriptions/s1", 0, null, null, null,
                        "", null, null, null, null, null, null, null, null, List.of("filter")));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertEquals("attributes.event_type = \"a\"",
                service.getSubscription("projects/p1/subscriptions/s1").getFilter());
    }

    @Test
    void updateSubscriptionRejectsAddingAFilterToAnUnfilteredSubscription() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);

        GcpException ex = assertThrows(GcpException.class,
                () -> service.updateSubscription("projects/p1/subscriptions/s1", 0, null, null, null,
                        "attributes.event_type = \"a\"", null, null, null, null, null, null, null, null,
                        List.of("filter")));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertNull(service.getSubscription("projects/p1/subscriptions/s1").getFilter());
    }

    @Test
    void updateSubscriptionRejectsTheFilterInTheMaskEvenWhenTheValueIsUnchanged() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1",
                "attributes.event_type = \"a\"");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.updateSubscription("projects/p1/subscriptions/s1", 0,
                        Map.of("env", "local"), null, null, "attributes.event_type = \"a\"", null, null,
                        null, null, null, null, null, null, List.of("filter", "labels")));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());

        StoredSubscription unchanged = service.getSubscription("projects/p1/subscriptions/s1");
        assertEquals("attributes.event_type = \"a\"", unchanged.getFilter());
        assertNull(unchanged.getLabels());
    }

    @Test
    void updateSubscriptionIgnoresAFilterOutsideTheUpdateMask() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1",
                "attributes.event_type = \"a\"");

        StoredSubscription updated = service.updateSubscription("projects/p1/subscriptions/s1", 0,
                Map.of("env", "local"), null, null, "attributes.event_type = \"ignored\"", null, null,
                null, null, null, null, null, null, List.of("labels"));

        assertEquals("local", updated.getLabels().get("env"));
        assertEquals("attributes.event_type = \"a\"", updated.getFilter());
    }

    @Test
    void updateSubscriptionWithoutUpdateMaskPreservesTheFilter() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1",
                "attributes.event_type = \"a\"");

        StoredSubscription updated = service.updateSubscription("projects/p1/subscriptions/s1", 0,
                Map.of("env", "local"), null, null, null, null, null, null, null, null, null, null, null,
                null);

        assertEquals("attributes.event_type = \"a\"", updated.getFilter());
    }

    @Test
    void updateSubscriptionViaFieldMaskRejectsChangingTheFilter() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1",
                "attributes.event_type = \"a\"");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.updateSubscription(
                        com.google.pubsub.v1.Subscription.newBuilder()
                                .setName("projects/p1/subscriptions/s1")
                                .setFilter("attributes.event_type = \"b\"")
                                .build(),
                        com.google.protobuf.FieldMask.newBuilder().addPaths("filter").build()));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertEquals("attributes.event_type = \"a\"",
                service.getSubscription("projects/p1/subscriptions/s1").getFilter());
    }

    @Test
    void updateSubscriptionViaFieldMaskRejectsTheFilterInTheMaskEvenWhenTheValueIsUnchanged() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1",
                "attributes.event_type = \"a\"");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.updateSubscription(
                        com.google.pubsub.v1.Subscription.newBuilder()
                                .setName("projects/p1/subscriptions/s1")
                                .setFilter("attributes.event_type = \"a\"")
                                .setAckDeadlineSeconds(30)
                                .build(),
                        com.google.protobuf.FieldMask.newBuilder()
                                .addPaths("filter")
                                .addPaths("ack_deadline_seconds")
                                .build()));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertEquals(10, service.getSubscription("projects/p1/subscriptions/s1").getAckDeadlineSeconds());
    }

    @Test
    void rejectedUpdateLeavesEarlierFieldsUnchanged() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);

        assertThrows(GcpException.class,
                () -> service.updateSubscription("projects/p1/subscriptions/s1", 30,
                        Map.of("env", "local"), null, null, "attributes.event_type = \"a\"", null, null,
                        null, null, null, null, null, null,
                        List.of("ackDeadlineSeconds", "labels", "filter")));

        StoredSubscription unchanged = service.getSubscription("projects/p1/subscriptions/s1");
        assertEquals(10, unchanged.getAckDeadlineSeconds());
        assertNull(unchanged.getLabels());
        assertNull(unchanged.getFilter());
    }

    @Test
    void rejectedUpdateViaFieldMaskLeavesEarlierFieldsUnchanged() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);

        assertThrows(GcpException.class,
                () -> service.updateSubscription(
                        com.google.pubsub.v1.Subscription.newBuilder()
                                .setName("projects/p1/subscriptions/s1")
                                .setAckDeadlineSeconds(30)
                                .putLabels("env", "local")
                                .setFilter("attributes.event_type = \"a\"")
                                .build(),
                        com.google.protobuf.FieldMask.newBuilder()
                                .addPaths("ack_deadline_seconds")
                                .addPaths("labels")
                                .addPaths("filter")
                                .build()));

        StoredSubscription unchanged = service.getSubscription("projects/p1/subscriptions/s1");
        assertEquals(10, unchanged.getAckDeadlineSeconds());
        assertNull(unchanged.getLabels());
        assertNull(unchanged.getFilter());
    }

    @Test
    void updateSubscriptionDoesNotValidateFilterOutsideTheUpdateMask() {
        service.createTopic("projects/p1/topics/t1");
        StoredSubscription corrupted =
                new StoredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        corrupted.setFilter("this is not a filter (((");
        subStore.put(corrupted.getName(), corrupted);

        StoredSubscription updated = service.updateSubscription("projects/p1/subscriptions/s1", 0,
                Map.of("env", "local"), null, null, null, null, null, null, null, null, null, null, null,
                List.of("labels"));

        assertEquals("local", updated.getLabels().get("env"));
        assertEquals("this is not a filter (((", updated.getFilter());
    }

    @Test
    void publishTreatsPersistedUnparseableFilterAsNoMatchWithoutFailing() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/healthy", "projects/p1/topics/t1", 10);

        StoredSubscription corrupted =
                new StoredSubscription("projects/p1/subscriptions/corrupted", "projects/p1/topics/t1", 10);
        corrupted.setFilter("this is not a filter (((");
        subStore.put(corrupted.getName(), corrupted);

        service.publish("projects/p1/topics/t1", List.of(message("body", "event_type", "a")));

        assertEquals(1, service.pull("projects/p1/subscriptions/healthy", 10).size());
        assertTrue(service.pull("projects/p1/subscriptions/corrupted", 10).isEmpty());
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

    private void createFilteredSubscription(String name, String topic, String filter) {
        service.createSubscription(name, topic, 10, null, false, null, filter,
                null, null, null, null, null, 0, false, false);
    }

    private static PubsubMessage message(String data, String attributeKey, String attributeValue) {
        return PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(data))
                .putAttributes(attributeKey, attributeValue)
                .build();
    }
}

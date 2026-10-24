package io.floci.gcp.services.pubsub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.ListTopicSnapshotsRequest;
import com.google.pubsub.v1.ListTopicSnapshotsResponse;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.ReceivedMessage;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.IamServices;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.pubsub.model.StoredSnapshot;
import io.floci.gcp.services.pubsub.model.StoredSubscription;
import io.floci.gcp.services.pubsub.model.StoredTopic;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PubSubServiceTest {

    private PubSubService service;
    private IamService iamService;
    private InMemoryStorage<String, StoredSubscription> subStore;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        subStore = new InMemoryStorage<>();
        iamService = IamServices.inMemory();
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        service = new PubSubService(
                new InMemoryStorage<>(),
                subStore,
                new InMemoryStorage<>(),
                iamService,
                clock);
    }

    @AfterEach
    void tearDown() {
        service.shutdownExecutors();
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
    void updateSubscriptionWithoutUpdateMaskAcceptsTheExistingFilterInTheBody() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1",
                "attributes.event_type = \"a\"");

        StoredSubscription updated = service.updateSubscription("projects/p1/subscriptions/s1", 0,
                Map.of("env", "local"), null, null, "attributes.event_type = \"a\"", null, null, null,
                null, null, null, null, null, null);

        assertEquals("local", updated.getLabels().get("env"));
        assertEquals("attributes.event_type = \"a\"", updated.getFilter());
    }

    @Test
    void updateSubscriptionWithoutUpdateMaskRejectsADifferentFilterInTheBody() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1",
                "attributes.event_type = \"a\"");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.updateSubscription("projects/p1/subscriptions/s1", 0, null, null, null,
                        "attributes.event_type = \"b\"", null, null, null, null, null, null, null, null,
                        null));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertEquals("attributes.event_type = \"a\"",
                service.getSubscription("projects/p1/subscriptions/s1").getFilter());
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
    void updateSubscriptionViaFieldMaskIgnoresAFilterOutsideTheUpdateMask() {
        service.createTopic("projects/p1/topics/t1");
        createFilteredSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1",
                "attributes.event_type = \"a\"");

        StoredSubscription updated = service.updateSubscription(
                com.google.pubsub.v1.Subscription.newBuilder()
                        .setName("projects/p1/subscriptions/s1")
                        .setFilter("attributes.event_type = \"ignored\"")
                        .putLabels("env", "local")
                        .build(),
                com.google.protobuf.FieldMask.newBuilder().addPaths("labels").build());

        assertEquals("local", updated.getLabels().get("env"));
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

    // ── ModifyAckDeadline ──────────────────────────────────────────────────────

    @Test
    void modifyAckDeadlineWithZeroRequeuesTheMessageForTheNextPull() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        List<ReceivedMessage> first = service.pull("projects/p1/subscriptions/s1", 10);
        assertEquals(1, first.size());
        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());

        service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(first.get(0).getAckId()), 0);

        List<ReceivedMessage> redelivered = service.pull("projects/p1/subscriptions/s1", 10);
        assertEquals(1, redelivered.size());
        assertEquals(first.get(0).getMessage().getMessageId(), redelivered.get(0).getMessage().getMessageId());
        assertNotEquals(first.get(0).getAckId(), redelivered.get(0).getAckId());
        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());
    }

    @Test
    void modifyAckDeadlineWithPositiveDeadlineKeepsTheMessageOutstanding() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        String ackId = service.pull("projects/p1/subscriptions/s1", 10).get(0).getAckId();

        service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(ackId), 600);
        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());

        service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(ackId), 0);
        assertEquals(1, service.pull("projects/p1/subscriptions/s1", 10).size());
    }

    @Test
    void modifyAckDeadlineRequeuesNackedMessagesInPublishOrderAheadOfTheBacklog() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1", List.of(
                PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("m1")).build(),
                PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("m2")).build(),
                PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("m3")).build()));
        List<ReceivedMessage> first = service.pull("projects/p1/subscriptions/s1", 2);
        assertEquals(List.of("m1", "m2"), first.stream()
                .map(m -> m.getMessage().getData().toStringUtf8()).toList());

        service.modifyAckDeadline("projects/p1/subscriptions/s1",
                List.of(first.get(1).getAckId(), first.get(0).getAckId()), 0);

        assertEquals(List.of("m1", "m2", "m3"), service.pull("projects/p1/subscriptions/s1", 10).stream()
                .map(m -> m.getMessage().getData().toStringUtf8()).toList());
    }

    @Test
    void modifyAckDeadlineIgnoresAcknowledgedAndUnknownAckIds() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        String ackId = service.pull("projects/p1/subscriptions/s1", 10).get(0).getAckId();
        service.acknowledge("projects/p1/subscriptions/s1", List.of(ackId));

        service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(ackId, "never-issued"), 0);

        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());
    }

    @Test
    void modifyAckDeadlineAfterDetachDoesNotResurrectTheMessage() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        String ackId = service.pull("projects/p1/subscriptions/s1", 10).get(0).getAckId();
        service.detachSubscription("projects/p1/subscriptions/s1");

        service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(ackId), 0);

        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());
    }

    // ── Ack deadline expiry ────────────────────────────────────────────────────

    @Test
    void unackedMessageIsRedeliveredOnceTheAckDeadlinePasses() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        ReceivedMessage first = service.pull("projects/p1/subscriptions/s1", 10).get(0);

        clock.advance(Duration.ofSeconds(9));
        service.expireLeases();
        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());

        clock.advance(Duration.ofSeconds(1));
        service.expireLeases();
        List<ReceivedMessage> redelivered = service.pull("projects/p1/subscriptions/s1", 10);
        assertEquals(1, redelivered.size());
        assertEquals(first.getMessage().getMessageId(), redelivered.get(0).getMessage().getMessageId());
        assertNotEquals(first.getAckId(), redelivered.get(0).getAckId());
    }

    @Test
    void ackedMessageIsNotRedeliveredAfterTheAckDeadline() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        String ackId = service.pull("projects/p1/subscriptions/s1", 10).get(0).getAckId();
        service.acknowledge("projects/p1/subscriptions/s1", List.of(ackId));

        clock.advance(Duration.ofSeconds(10));
        service.expireLeases();

        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());
    }

    @Test
    void acknowledgeWithAnExpiredAckIdStillRedeliversTheMessage() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        String ackId = service.pull("projects/p1/subscriptions/s1", 10).get(0).getAckId();

        clock.advance(Duration.ofSeconds(10));
        service.acknowledge("projects/p1/subscriptions/s1", List.of(ackId));
        service.expireLeases();

        assertEquals(1, service.pull("projects/p1/subscriptions/s1", 10).size());
    }

    @Test
    void acknowledgeWithTheFirstAckIdDoesNotDropTheRedeliveredMessage() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        String firstAckId = service.pull("projects/p1/subscriptions/s1", 10).get(0).getAckId();
        clock.advance(Duration.ofSeconds(10));
        service.expireLeases();
        String secondAckId = service.pull("projects/p1/subscriptions/s1", 10).get(0).getAckId();

        service.acknowledge("projects/p1/subscriptions/s1", List.of(firstAckId));
        service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(secondAckId), 0);

        assertEquals(1, service.pull("projects/p1/subscriptions/s1", 10).size());
    }

    @Test
    void modifyAckDeadlineExtendsTheLeaseFromNow() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        String ackId = service.pull("projects/p1/subscriptions/s1", 10).get(0).getAckId();
        clock.advance(Duration.ofSeconds(5));
        service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(ackId), 30);

        clock.advance(Duration.ofSeconds(29));
        service.expireLeases();
        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());

        clock.advance(Duration.ofSeconds(1));
        service.expireLeases();
        assertEquals(1, service.pull("projects/p1/subscriptions/s1", 10).size());
    }

    @Test
    void modifyAckDeadlineCanShortenTheLease() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 60);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        String ackId = service.pull("projects/p1/subscriptions/s1", 10).get(0).getAckId();

        service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(ackId), 3);
        clock.advance(Duration.ofSeconds(3));
        service.expireLeases();

        assertEquals(1, service.pull("projects/p1/subscriptions/s1", 10).size());
    }

    @Test
    void modifyAckDeadlineDoesNotReviveAnExpiredLease() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        String ackId = service.pull("projects/p1/subscriptions/s1", 10).get(0).getAckId();

        clock.advance(Duration.ofSeconds(10));
        service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(ackId), 600);
        service.expireLeases();

        assertEquals(1, service.pull("projects/p1/subscriptions/s1", 10).size());
    }

    @Test
    void changingTheSubscriptionAckDeadlineKeepsTheDeadlineOfMessagesAlreadyPulled() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        service.pull("projects/p1/subscriptions/s1", 10);

        service.updateSubscription("projects/p1/subscriptions/s1", 60, null, null, null, null, null,
                null, null, null, null, null, null, null, List.of("ackDeadlineSeconds"));
        clock.advance(Duration.ofSeconds(10));
        service.expireLeases();

        assertEquals(1, service.pull("projects/p1/subscriptions/s1", 10).size());
    }

    @Test
    void streamAckDeadlineReplacesTheSubscriptionAckDeadline() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 60);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        service.pull("projects/p1/subscriptions/s1", 10, 20);

        clock.advance(Duration.ofSeconds(20));
        service.expireLeases();

        assertEquals(1, service.pull("projects/p1/subscriptions/s1", 10).size());
    }

    @Test
    void streamAckDeadlineUnderTenSecondsIsTreatedAsTen() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 60);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        service.pull("projects/p1/subscriptions/s1", 10, 5);

        clock.advance(Duration.ofSeconds(9));
        service.expireLeases();
        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());

        clock.advance(Duration.ofSeconds(1));
        service.expireLeases();
        assertEquals(1, service.pull("projects/p1/subscriptions/s1", 10).size());
    }

    @Test
    void expiredMessagesAreRedeliveredInPublishOrderAheadOfTheBacklog() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1", List.of(
                PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("m1")).build(),
                PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("m2")).build(),
                PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("m3")).build()));
        service.pull("projects/p1/subscriptions/s1", 2);

        clock.advance(Duration.ofSeconds(10));
        service.expireLeases();

        assertEquals(List.of("m1", "m2", "m3"), service.pull("projects/p1/subscriptions/s1", 10).stream()
                .map(m -> m.getMessage().getData().toStringUtf8()).toList());
    }

    @Test
    void leaseExpiryWakesStreamingListenersOnlyWhenSomethingExpired() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        service.pull("projects/p1/subscriptions/s1", 10);
        AtomicInteger wakeups = new AtomicInteger();
        Runnable unregister = service.registerMessageListener("projects/p1/subscriptions/s1", wakeups::incrementAndGet);

        service.expireLeases();
        assertEquals(0, wakeups.get());

        clock.advance(Duration.ofSeconds(10));
        service.expireLeases();
        assertEquals(1, wakeups.get());

        service.expireLeases();
        assertEquals(1, wakeups.get());
        unregister.run();
    }

    @Test
    void leaseExpiryAfterDeleteAndRecreateDoesNotLeakIntoTheNewSubscription() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("old")).build()));
        service.pull("projects/p1/subscriptions/s1", 10);
        service.deleteSubscription("projects/p1/subscriptions/s1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);

        clock.advance(Duration.ofSeconds(10));
        service.expireLeases();

        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());
    }

    @Test
    void leaseExpiryAfterDetachDoesNotResurrectTheMessage() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg")).build()));
        service.pull("projects/p1/subscriptions/s1", 10);
        service.detachSubscription("projects/p1/subscriptions/s1");

        clock.advance(Duration.ofSeconds(10));
        service.expireLeases();

        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());
    }

    @Test
    void deliveryAttemptCountsNacksAndExpiredDeadlines() {
        service.createTopic("projects/p1/topics/t1");
        service.createTopic("projects/p1/topics/dlq");
        createDeadLetterSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/dlq", 10);
        publish("msg");

        ReceivedMessage first = pullOne("projects/p1/subscriptions/s1");
        assertEquals(1, first.getDeliveryAttempt());
        service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(first.getAckId()), 0);
        assertEquals(2, pullOne("projects/p1/subscriptions/s1").getDeliveryAttempt());
        clock.advance(Duration.ofSeconds(60));
        service.expireLeases();
        assertEquals(3, pullOne("projects/p1/subscriptions/s1").getDeliveryAttempt());
    }

    @Test
    void deliveryAttemptStaysZeroWithoutADeadLetterPolicy() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 60);
        publish("msg");

        ReceivedMessage first = pullOne("projects/p1/subscriptions/s1");
        assertEquals(0, first.getDeliveryAttempt());
        service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(first.getAckId()), 0);
        assertEquals(0, pullOne("projects/p1/subscriptions/s1").getDeliveryAttempt());
    }

    @Test
    void deliveryAttemptIsCountedPerSubscription() {
        service.createTopic("projects/p1/topics/t1");
        service.createTopic("projects/p1/topics/dlq");
        createDeadLetterSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/dlq", 5);
        createDeadLetterSubscription("projects/p1/subscriptions/s2", "projects/p1/topics/dlq", 5);
        publish("msg");

        for (int i = 0; i < 2; i++) {
            service.modifyAckDeadline("projects/p1/subscriptions/s1",
                    List.of(pullOne("projects/p1/subscriptions/s1").getAckId()), 0);
        }

        assertEquals(3, pullOne("projects/p1/subscriptions/s1").getDeliveryAttempt());
        assertEquals(1, pullOne("projects/p1/subscriptions/s2").getDeliveryAttempt());
    }

    @Test
    void nackedMessageMovesToTheDeadLetterTopicAfterMaxDeliveryAttempts() {
        service.createTopic("projects/p1/topics/t1");
        service.createTopic("projects/p1/topics/dlq");
        service.createSubscription("projects/p1/subscriptions/dlq-sub", "projects/p1/topics/dlq", 60);
        createDeadLetterSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/dlq", 5);
        service.publish("projects/p1/topics/t1", List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("msg"))
                .putAttributes("k", "v")
                .setOrderingKey("key1")
                .build()));

        PubsubMessage original = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            ReceivedMessage received = pullOne("projects/p1/subscriptions/s1");
            assertEquals(attempt, received.getDeliveryAttempt());
            original = received.getMessage();
            service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(received.getAckId()), 0);
        }

        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());
        List<ReceivedMessage> dead = service.pull("projects/p1/subscriptions/dlq-sub", 10);
        assertEquals(1, dead.size());
        assertEquals(0, dead.get(0).getDeliveryAttempt());
        PubsubMessage deadLetter = dead.get(0).getMessage();
        assertNotEquals(original.getMessageId(), deadLetter.getMessageId());
        assertEquals("msg", deadLetter.getData().toStringUtf8());
        assertEquals("key1", deadLetter.getOrderingKey());
        Map<String, String> attributes = deadLetter.getAttributesMap();
        assertEquals(5, attributes.size());
        assertEquals("v", attributes.get("k"));
        assertEquals("5", attributes.get("CloudPubSubDeadLetterSourceDeliveryCount"));
        assertEquals("s1", attributes.get("CloudPubSubDeadLetterSourceSubscription"));
        assertEquals("p1", attributes.get("CloudPubSubDeadLetterSourceSubscriptionProject"));
        String sourcePublishTime = attributes.get("CloudPubSubDeadLetterSourceTopicPublishTime");
        assertTrue(sourcePublishTime.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+00:00"),
                sourcePublishTime);
        Instant publishTime = Instant.ofEpochSecond(
                original.getPublishTime().getSeconds(), original.getPublishTime().getNanos());
        assertEquals(publishTime.truncatedTo(ChronoUnit.MILLIS), OffsetDateTime.parse(sourcePublishTime).toInstant());
    }

    @Test
    void expiredMessageMovesToTheDeadLetterTopicAfterMaxDeliveryAttempts() {
        service.createTopic("projects/p1/topics/t1");
        service.createTopic("projects/p1/topics/dlq");
        service.createSubscription("projects/p1/subscriptions/dlq-sub", "projects/p1/topics/dlq", 60);
        createDeadLetterSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/dlq", 5);
        publish("msg");

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(attempt, pullOne("projects/p1/subscriptions/s1").getDeliveryAttempt());
            clock.advance(Duration.ofSeconds(60));
            service.expireLeases();
        }

        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());
        List<ReceivedMessage> dead = service.pull("projects/p1/subscriptions/dlq-sub", 10);
        assertEquals(1, dead.size());
        assertEquals("5", dead.get(0).getMessage().getAttributesMap().get("CloudPubSubDeadLetterSourceDeliveryCount"));
    }

    @Test
    void deliveryAttemptKeepsCountingWhileTheDeadLetterTopicIsMissing() {
        service.createTopic("projects/p1/topics/t1");
        service.createTopic("projects/p1/topics/dlq");
        createDeadLetterSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/dlq", 5);
        service.deleteTopic("projects/p1/topics/dlq");
        publish("msg");

        for (int attempt = 1; attempt <= 7; attempt++) {
            ReceivedMessage received = pullOne("projects/p1/subscriptions/s1");
            assertEquals(attempt, received.getDeliveryAttempt());
            service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(received.getAckId()), 0);
        }
    }

    @Test
    void deadLetterTopicDeletedBeforeForwardingRedeliversTheMessage() {
        AtomicReference<String> deleteOnNextGet = new AtomicReference<>();
        InMemoryStorage<String, StoredTopic> topicStore = new InMemoryStorage<>() {
            @Override
            public Optional<StoredTopic> get(String key) {
                Optional<StoredTopic> topic = super.get(key);
                if (key.equals(deleteOnNextGet.get())) {
                    deleteOnNextGet.set(null);
                    delete(key);
                }
                return topic;
            }
        };
        service.shutdownExecutors();
        service = new PubSubService(topicStore, subStore, new InMemoryStorage<>(), iamService, clock);
        service.createTopic("projects/p1/topics/t1");
        service.createTopic("projects/p1/topics/dlq");
        createDeadLetterSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/dlq", 5);
        publish("msg");
        for (int attempt = 1; attempt <= 4; attempt++) {
            service.modifyAckDeadline("projects/p1/subscriptions/s1",
                    List.of(pullOne("projects/p1/subscriptions/s1").getAckId()), 0);
        }
        ReceivedMessage last = pullOne("projects/p1/subscriptions/s1");
        assertEquals(5, last.getDeliveryAttempt());

        // The threshold check still sees the topic, and the publish after it does not.
        deleteOnNextGet.set("projects/p1/topics/dlq");
        service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(last.getAckId()), 0);

        assertNull(deleteOnNextGet.get());
        assertTrue(topicStore.get("projects/p1/topics/dlq").isEmpty());
        ReceivedMessage redelivered = pullOne("projects/p1/subscriptions/s1");
        assertEquals("msg", redelivered.getMessage().getData().toStringUtf8());
        assertEquals(6, redelivered.getDeliveryAttempt());
    }

    @Test
    void seekKeepsTheDeliveryAttempt() {
        service.createTopic("projects/p1/topics/t1");
        service.createTopic("projects/p1/topics/dlq");
        createDeadLetterSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/dlq", 5);
        publish("msg");
        service.modifyAckDeadline("projects/p1/subscriptions/s1",
                List.of(pullOne("projects/p1/subscriptions/s1").getAckId()), 0);
        assertEquals(2, pullOne("projects/p1/subscriptions/s1").getDeliveryAttempt());

        service.seek("projects/p1/subscriptions/s1", null, secondsFromNow(-60));

        assertEquals(3, pullOne("projects/p1/subscriptions/s1").getDeliveryAttempt());
    }

    @Test
    void maxDeliveryAttemptsDefaultsToFive() {
        service.createTopic("projects/p1/topics/t1");
        service.createTopic("projects/p1/topics/dlq");
        createDeadLetterSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/dlq", 0);
        assertEquals(5, service.getSubscription("projects/p1/subscriptions/s1").getMaxDeliveryAttempts());

        service.createSubscription("projects/p1/subscriptions/s2", "projects/p1/topics/t1", 60);
        StoredSubscription updated = service.updateSubscription(com.google.pubsub.v1.Subscription.newBuilder()
                        .setName("projects/p1/subscriptions/s2")
                        .setDeadLetterPolicy(com.google.pubsub.v1.DeadLetterPolicy.newBuilder()
                                .setDeadLetterTopic("projects/p1/topics/dlq"))
                        .build(),
                com.google.protobuf.FieldMask.newBuilder().addPaths("dead_letter_policy").build());
        assertEquals("projects/p1/topics/dlq", updated.getDeadLetterTopic());
        assertEquals(5, updated.getMaxDeliveryAttempts());
    }

    @Test
    void updateWithoutADeadLetterPolicyInTheMaskRemovesIt() {
        service.createTopic("projects/p1/topics/t1");
        service.createTopic("projects/p1/topics/dlq");
        createDeadLetterSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/dlq", 5);

        StoredSubscription updated = service.updateSubscription(com.google.pubsub.v1.Subscription.newBuilder()
                        .setName("projects/p1/subscriptions/s1")
                        .build(),
                com.google.protobuf.FieldMask.newBuilder().addPaths("dead_letter_policy").build());

        assertNull(updated.getDeadLetterTopic());
        publish("msg");
        assertEquals(0, pullOne("projects/p1/subscriptions/s1").getDeliveryAttempt());
    }

    @Test
    void subscriptionSavedWithZeroMaxDeliveryAttemptsDeadLettersAfterFive() {
        service.createTopic("projects/p1/topics/t1");
        service.createTopic("projects/p1/topics/dlq");
        service.createSubscription("projects/p1/subscriptions/dlq-sub", "projects/p1/topics/dlq", 60);
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 60);
        StoredSubscription saved = subStore.get("projects/p1/subscriptions/s1").orElseThrow();
        saved.setDeadLetterTopic("projects/p1/topics/dlq");
        saved.setMaxDeliveryAttempts(0);
        subStore.put("projects/p1/subscriptions/s1", saved);
        publish("msg");

        for (int attempt = 1; attempt <= 4; attempt++) {
            service.modifyAckDeadline("projects/p1/subscriptions/s1",
                    List.of(pullOne("projects/p1/subscriptions/s1").getAckId()), 0);
            assertTrue(service.pull("projects/p1/subscriptions/dlq-sub", 10).isEmpty());
        }
        service.modifyAckDeadline("projects/p1/subscriptions/s1",
                List.of(pullOne("projects/p1/subscriptions/s1").getAckId()), 0);

        assertEquals(1, service.pull("projects/p1/subscriptions/dlq-sub", 10).size());
    }

    private void createDeadLetterSubscription(String name, String deadLetterTopic, int maxDeliveryAttempts) {
        service.createSubscription(name, "projects/p1/topics/t1", 60, null, false, null, null, null,
                null, null, null, deadLetterTopic, maxDeliveryAttempts, false, false);
    }

    private void publish(String data) {
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(data)).build()));
    }

    private ReceivedMessage pullOne(String subscription) {
        List<ReceivedMessage> received = service.pull(subscription, 1);
        assertEquals(1, received.size(), "expected one message on " + subscription);
        return received.get(0);
    }

    @Test
    void modifyAckDeadlineRejectsADeadlineOutsideZeroToSixHundredSeconds() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);

        GcpException tooLong = assertThrows(GcpException.class,
                () -> service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of("ack"), 601));
        assertEquals("INVALID_ARGUMENT", tooLong.getGcpStatus());
        assertEquals("Invalid ack deadline given (ack_deadline=601). "
                + "The ack deadline must be between 0 and 600 seconds.", tooLong.getMessage());

        GcpException negative = assertThrows(GcpException.class,
                () -> service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of("ack"), -1));
        assertEquals("INVALID_ARGUMENT", negative.getGcpStatus());
        assertEquals("Invalid ack deadline given (ack_deadline=-1). "
                + "The ack deadline must be between 0 and 600 seconds.", negative.getMessage());
    }

    @Test
    void modifyAckDeadlineRejectsAnEmptyAckIdList() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);

        GcpException ex = assertThrows(GcpException.class,
                () -> service.modifyAckDeadline("projects/p1/subscriptions/s1", List.of(), 10));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertEquals("You have not specified an ack ID in the request.", ex.getMessage());
    }

    @Test
    void modifyAckDeadlineOnMissingSubscriptionThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.modifyAckDeadline("projects/p1/subscriptions/missing", List.of("ack"), 0));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    // ── IAM policies ───────────────────────────────────────────────────────────

    @Test
    void topicPolicyRoundTripsWithBindingOrderPreserved() {
        service.createTopic("projects/p1/topics/t1");

        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(
                Map.of("role", "roles/pubsub.publisher", "members", List.of("serviceAccount:a@p1.iam.gserviceaccount.com")),
                Map.of("role", "roles/pubsub.viewer", "members", List.of("user:b@example.com", "user:c@example.com"))));
        iamService.setPolicy("projects/p1/topics/t1", policy);

        StoredPolicy read = iamService.getPolicy("projects/p1/topics/t1");
        assertEquals(2, read.getBindings().size());
        assertEquals("roles/pubsub.publisher", read.getBindings().get(0).get("role"));
        assertEquals("roles/pubsub.viewer", read.getBindings().get(1).get("role"));
        assertEquals(List.of("user:b@example.com", "user:c@example.com"), read.getBindings().get(1).get("members"));
    }

    @Test
    void subscriptionPolicyIndependentOfTopicWithSameShortName() {
        service.createTopic("projects/p1/topics/shared");
        service.createSubscription("projects/p1/subscriptions/shared", "projects/p1/topics/shared", 10);

        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", "roles/pubsub.subscriber", "members", List.of("user:s@example.com"))));
        iamService.setPolicy("projects/p1/subscriptions/shared", policy);

        assertTrue(iamService.getPolicy("projects/p1/topics/shared").getBindings().isEmpty());
        assertEquals(1, iamService.getPolicy("projects/p1/subscriptions/shared").getBindings().size());
    }

    @Test
    void unsetPolicyOnExistingTopicReturnsEmptyNotError() {
        service.createTopic("projects/p1/topics/t1");

        StoredPolicy policy = iamService.getPolicy("projects/p1/topics/t1");
        assertTrue(policy.getBindings().isEmpty());
        assertEquals("ACAB", policy.getEtag());
    }

    @Test
    void policyOnMissingTopicIsNotFound() {
        GcpException get = assertThrows(GcpException.class,
                () -> iamService.getPolicy("projects/p1/topics/missing"));
        assertEquals(404, get.getHttpStatus());

        GcpException set = assertThrows(GcpException.class,
                () -> iamService.setPolicy("projects/p1/topics/missing", new StoredPolicy()));
        assertEquals(404, set.getHttpStatus());
    }

    @Test
    void policiesAreProjectScoped() {
        service.createTopic("projects/p1/topics/t");
        service.createTopic("projects/p2/topics/t");

        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", "roles/pubsub.publisher", "members", List.of("user:p1@example.com"))));
        iamService.setPolicy("projects/p1/topics/t", policy);

        assertEquals(1, iamService.getPolicy("projects/p1/topics/t").getBindings().size());
        assertTrue(iamService.getPolicy("projects/p2/topics/t").getBindings().isEmpty());
    }

    @Test
    void deletingTopicClearsPolicySoRecreateStartsEmpty() {
        service.createTopic("projects/p1/topics/t1");
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", "roles/pubsub.publisher", "members", List.of("user:x@example.com"))));
        iamService.setPolicy("projects/p1/topics/t1", policy);

        service.deleteTopic("projects/p1/topics/t1");
        service.createTopic("projects/p1/topics/t1");

        assertTrue(iamService.getPolicy("projects/p1/topics/t1").getBindings().isEmpty());
    }

    @Test
    void deletingSubscriptionClearsPolicy() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", "roles/pubsub.subscriber", "members", List.of("user:x@example.com"))));
        iamService.setPolicy("projects/p1/subscriptions/s1", policy);

        service.deleteSubscription("projects/p1/subscriptions/s1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);

        assertTrue(iamService.getPolicy("projects/p1/subscriptions/s1").getBindings().isEmpty());
    }

    @Test
    void conditionBlockRoundTripsUnchanged() {
        service.createTopic("projects/p1/topics/t1");

        Map<String, Object> condition = Map.of(
                "expression", "request.time < timestamp('2030-01-01T00:00:00Z')",
                "title", "expiry",
                "description", "temporary grant",
                "location", "policy.tf:12");
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/pubsub.publisher",
                "members", List.of("user:x@example.com"),
                "condition", condition)));
        iamService.setPolicy("projects/p1/topics/t1", policy);

        StoredPolicy read = iamService.getPolicy("projects/p1/topics/t1");
        assertEquals(condition, read.getBindings().get(0).get("condition"));
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

    @Test
    void failedPushDeliveryIsRetried() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch retryLatch =
                new java.util.concurrent.CountDownLatch(1);

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/hook", exchange -> {
            int attempt = attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();

            if (attempt == 1) {
                exchange.sendResponseHeaders(500, -1);
            } else {
                retryLatch.countDown();
                exchange.sendResponseHeaders(204, -1);
            }

            exchange.close();
        });

        server.start();

        try {
            service.createTopic("projects/p/topics/retry");

            String endpoint = "http" + "://" + "localhost:"
                    + server.getAddress().getPort()
                    + "/hook";

            service.createSubscription(
                    "projects/p/subscriptions/retry",
                    "projects/p/topics/retry",
                    10,
                    null,
                    false,
                    null,
                    null,
                    endpoint,
                    null,
                    null,
                    null,
                    null,
                    0,
                    false,
                    false);

            PubsubMessage message = PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("retry-body"))
                    .build();

            service.publish(
                    "projects/p/topics/retry",
                    List.of(message));

            assertTrue(
                    retryLatch.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "Timed out waiting for push retry");

            assertEquals(2, attempts.get());

            assertEquals(
                    0,
                    service.pull(
                            "projects/p/subscriptions/retry",
                            10).size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedPushDeliveryDoesNotRetryAfterSubscriptionDeletion() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();

        java.util.concurrent.CountDownLatch firstAttempt =
                new java.util.concurrent.CountDownLatch(1);

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/hook", exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            firstAttempt.countDown();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        server.start();

        try {
            service.createTopic("projects/p/topics/retry-delete");

            String endpoint = "http" + "://" + "localhost:"
                    + server.getAddress().getPort()
                    + "/hook";

            String subscription = "projects/p/subscriptions/retry-delete";

            service.createSubscription(
                    subscription,
                    "projects/p/topics/retry-delete",
                    10,
                    null,
                    false,
                    null,
                    null,
                    endpoint,
                    null,
                    null,
                    null,
                    null,
                    0,
                    false,
                    false);

            PubsubMessage message = PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("retry-delete-body"))
                    .build();

            service.publish(
                    "projects/p/topics/retry-delete",
                    List.of(message));

            assertTrue(
                    firstAttempt.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "Timed out waiting for initial push delivery");

            service.deleteSubscription(subscription);

            Thread.sleep(1500);

            assertEquals(1, attempts.get(),
                    "Deleted subscription must not receive a scheduled retry");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedPushDeliveryDoesNotRetryAfterSubscriptionDetachment() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch firstAttempt =
                new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            firstAttempt.countDown();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            service.createTopic("projects/p/topics/retry-detach");
            String endpoint = "http" + "://" + "localhost:"
                    + server.getAddress().getPort()
                    + "/hook";
            String subscription = "projects/p/subscriptions/retry-detach";
            service.createSubscription(
                    subscription,
                    "projects/p/topics/retry-detach",
                    10,
                    null,
                    false,
                    null,
                    null,
                    endpoint,
                    null,
                    null,
                    null,
                    null,
                    0,
                    false,
                    false);
            PubsubMessage message = PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("retry-detach-body"))
                    .build();
            service.publish(
                    "projects/p/topics/retry-detach",
                    List.of(message));
            assertTrue(
                    firstAttempt.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "Timed out waiting for initial push delivery");

            service.detachSubscription(subscription);

            Thread.sleep(1500);

            assertEquals(1, attempts.get(),
                    "Detached subscription must not receive a scheduled retry");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pushDeliveryEnvelopeContainsBothIdentityFieldVariants() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> requestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            received.countDown();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        try {
            service.createTopic("projects/p/topics/envelope-fields");
            String endpoint = "http" + "://" + "localhost:"
                    + server.getAddress().getPort() + "/hook";
            String subscription = "projects/p/subscriptions/envelope-fields";

            service.createSubscription(
                    subscription,
                    "projects/p/topics/envelope-fields",
                    10,
                    null,
                    false,
                    null,
                    null,
                    endpoint,
                    null,
                    null,
                    null,
                    null,
                    0,
                    false,
                    false);

            PubsubMessage message = PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("envelope-body"))
                    .build();

            service.publish(
                    "projects/p/topics/envelope-fields",
                    List.of(message));

            assertTrue(
                    received.await(5, TimeUnit.SECONDS),
                    "Timed out waiting for push delivery");

            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(requestBody.get());
            com.fasterxml.jackson.databind.JsonNode pushedMessage =
                    root.get("message");

            assertEquals(
                    pushedMessage.get("messageId").asText(),
                    pushedMessage.get("message_id").asText());
            assertEquals(
                    pushedMessage.get("publishTime").asText(),
                    pushedMessage.get("publish_time").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pushDeliveryRetriesForNonAcknowledgement2xxStatus() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch firstAttempt = new CountDownLatch(1);

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            attempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            firstAttempt.countDown();
            exchange.sendResponseHeaders(203, -1);
            exchange.close();
        });
        server.start();

        try {
            service.createTopic("projects/p/topics/non-ack-2xx");
            String endpoint = "http" + "://" + "localhost:"
                    + server.getAddress().getPort() + "/hook";
            String subscription = "projects/p/subscriptions/non-ack-2xx";

            service.createSubscription(
                    subscription,
                    "projects/p/topics/non-ack-2xx",
                    10,
                    null,
                    false,
                    null,
                    null,
                    endpoint,
                    null,
                    null,
                    null,
                    null,
                    0,
                    false,
                    false);

            PubsubMessage message = PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("non-ack-2xx"))
                    .build();

            service.publish(
                    "projects/p/topics/non-ack-2xx",
                    List.of(message));

            assertTrue(
                    firstAttempt.await(5, TimeUnit.SECONDS),
                    "Timed out waiting for initial push delivery");

            Thread.sleep(1500);

            assertTrue(
                    attempts.get() >= 2,
                    "A 203 response must trigger a retry");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedPushDeliveryDoesNotRetryToReplacementSubscription() throws Exception {
        java.util.concurrent.atomic.AtomicInteger oldAttempts =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger replacementAttempts =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch firstAttempt =
                new java.util.concurrent.CountDownLatch(1);

        HttpServer oldServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        oldServer.createContext("/hook", exchange -> {
            oldAttempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            firstAttempt.countDown();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });

        HttpServer replacementServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        replacementServer.createContext("/hook", exchange -> {
            replacementAttempts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        oldServer.start();
        replacementServer.start();

        try {
            service.createTopic("projects/p/topics/retry-replacement");

            String oldEndpoint = "http" + "://" + "localhost:"
                    + oldServer.getAddress().getPort()
                    + "/hook";
            String replacementEndpoint = "http" + "://" + "localhost:"
                    + replacementServer.getAddress().getPort()
                    + "/hook";
            String subscription =
                    "projects/p/subscriptions/retry-replacement";

            service.createSubscription(
                    subscription,
                    "projects/p/topics/retry-replacement",
                    10,
                    null,
                    false,
                    null,
                    null,
                    oldEndpoint,
                    null,
                    null,
                    null,
                    null,
                    0,
                    false,
                    false);

            PubsubMessage message = PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("retry-replacement-body"))
                    .build();

            service.publish(
                    "projects/p/topics/retry-replacement",
                    List.of(message));

            assertTrue(
                    firstAttempt.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "Timed out waiting for initial push delivery");

            service.deleteSubscription(subscription);

            service.createSubscription(
                    subscription,
                    "projects/p/topics/retry-replacement",
                    10,
                    null,
                    false,
                    null,
                    null,
                    replacementEndpoint,
                    null,
                    null,
                    null,
                    null,
                    0,
                    false,
                    false);

            Thread.sleep(1500);

            assertEquals(1, oldAttempts.get(),
                    "Original subscription should only receive the initial attempt");
            assertEquals(0, replacementAttempts.get(),
                    "Replacement subscription must not receive a retry for the old subscription");
        } finally {
            oldServer.stop(0);
            replacementServer.stop(0);
        }
    }

    @Test
    void pushSubscriptionDeliversMessageToPushEndpoint() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        java.util.concurrent.CountDownLatch deliveryLatch =
                new java.util.concurrent.CountDownLatch(1);

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/hook", exchange -> {
            body.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            deliveryLatch.countDown();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        server.start();

        try {
            service.createTopic("projects/p/topics/t");

            String endpoint = "http" + "://" + "localhost:"
                    + server.getAddress().getPort()
                    + "/hook";

            service.createSubscription(
                    "projects/p/subscriptions/s",
                    "projects/p/topics/t",
                    10,
                    null,
                    false,
                    null,
                    null,
                    endpoint,
                    null,
                    null,
                    null,
                    null,
                    0,
                    false,
                    false);

            PubsubMessage message = PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("body"))
                    .putAttributes("key", "value")
                    .setOrderingKey("orders")
                    .build();

            service.publish("projects/p/topics/t", List.of(message));

            assertTrue(
                    deliveryLatch.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "Timed out waiting for push delivery");
            assertNotNull(body.get());

            JsonNode envelope = new ObjectMapper().readTree(body.get());

            assertEquals(
                    "Ym9keQ==",
                    envelope.get("message").get("data").asText());
            assertEquals(
                    "value",
                    envelope.get("message")
                            .get("attributes")
                            .get("key")
                            .asText());
            assertEquals(
                    "projects/p/subscriptions/s",
                    envelope.get("subscription").asText());
            assertTrue(envelope.get("message").has("messageId"));
            assertTrue(envelope.get("message").has("publishTime"));
            assertEquals(
                    "orders",
                    envelope.get("message").get("orderingKey").asText());

            assertEquals(
                    0,
                    service.pull(
                            "projects/p/subscriptions/s",
                            10).size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void seekToTimePurgesMessagesPublishedBeforeIt() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);

        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("old")).build()));

        Timestamp seekTime = Timestamp.newBuilder()
                .setSeconds(clock.instant().getEpochSecond() + 60)
                .build();
        service.seek("projects/p1/subscriptions/s1", null, seekTime);

        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("new")).build()));

        List<ReceivedMessage> messages = service.pull("projects/p1/subscriptions/s1", 10);
        assertEquals(1, messages.size());
        assertEquals("new", messages.get(0).getMessage().getData().toStringUtf8());
    }

    @Test
    void seekToPastTimeRedeliversUnackedMessagesPublishedAfterIt() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);

        Timestamp seekTime = Timestamp.newBuilder()
                .setSeconds(clock.instant().getEpochSecond() - 60)
                .build();
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("in-flight")).build()));
        assertEquals(1, service.pull("projects/p1/subscriptions/s1", 10).size());

        service.seek("projects/p1/subscriptions/s1", null, seekTime);

        List<ReceivedMessage> messages = service.pull("projects/p1/subscriptions/s1", 10);
        assertEquals(1, messages.size());
        assertEquals("in-flight", messages.get(0).getMessage().getData().toStringUtf8());
    }

    @Test
    void ackBeforeSeekKeepsMessageAcknowledged() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("acked")).build()));
        List<ReceivedMessage> first = service.pull("projects/p1/subscriptions/s1", 10);
        service.acknowledge("projects/p1/subscriptions/s1", List.of(first.get(0).getAckId()));

        service.seek("projects/p1/subscriptions/s1", null, secondsFromNow(-60));

        assertEquals(0, service.pull("projects/p1/subscriptions/s1", 10).size());
    }

    @Test
    void ackAfterSeekWithOldAckIdDoesNotDropRedeliveredMessage() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("m")).build()));
        String oldAckId = service.pull("projects/p1/subscriptions/s1", 10).get(0).getAckId();

        service.seek("projects/p1/subscriptions/s1", null, secondsFromNow(-60));
        service.acknowledge("projects/p1/subscriptions/s1", List.of(oldAckId));

        List<ReceivedMessage> again = service.pull("projects/p1/subscriptions/s1", 10);
        assertEquals(1, again.size());
        assertNotEquals(oldAckId, again.get(0).getAckId());
    }

    @Test
    void seekThatRequeuesMessagesWakesStreamingListeners() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("m")).build()));
        service.pull("projects/p1/subscriptions/s1", 10);
        AtomicInteger wakeups = new AtomicInteger();
        Runnable unregister = service.registerMessageListener("projects/p1/subscriptions/s1", wakeups::incrementAndGet);

        service.seek("projects/p1/subscriptions/s1", null, secondsFromNow(60));
        assertEquals(0, wakeups.get());

        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("n")).build()));
        service.pull("projects/p1/subscriptions/s1", 10);
        wakeups.set(0);
        service.seek("projects/p1/subscriptions/s1", null, secondsFromNow(-60));
        assertEquals(1, wakeups.get());
        unregister.run();
    }

    @Test
    void seekWithOutOfRangeTimeIsRejectedAndKeepsInFlightMessage() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("m")).build()));
        assertEquals(1, service.pull("projects/p1/subscriptions/s1", 10).size());

        Timestamp invalid = Timestamp.newBuilder().setSeconds(Long.MAX_VALUE).build();
        assertThrows(GcpException.class, () -> service.seek("projects/p1/subscriptions/s1", null, invalid));

        assertEquals(0, service.pull("projects/p1/subscriptions/s1", 10).size());
        service.seek("projects/p1/subscriptions/s1", null, secondsFromNow(-60));
        assertEquals(1, service.pull("projects/p1/subscriptions/s1", 10).size());
    }

    @Test
    void seekToPastTimeRedeliversInPublishOrder() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        List<PubsubMessage> batch = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            batch.add(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("o" + i)).build());
        }
        service.publish("projects/p1/topics/t1", batch);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("o8")).build()));
        assertEquals(9, service.pull("projects/p1/subscriptions/s1", 10).size());

        service.seek("projects/p1/subscriptions/s1", null, secondsFromNow(-60));

        List<String> redelivered = service.pull("projects/p1/subscriptions/s1", 10).stream()
                .map(m -> m.getMessage().getData().toStringUtf8())
                .toList();
        assertEquals(List.of("o0", "o1", "o2", "o3", "o4", "o5", "o6", "o7", "o8"), redelivered);
    }

    @Test
    void seekToExactPublishTimeKeepsTheMessage() {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("at")).build()));
        Timestamp publishTime = service.pull("projects/p1/subscriptions/s1", 10).get(0).getMessage().getPublishTime();

        service.seek("projects/p1/subscriptions/s1", null, publishTime);

        List<ReceivedMessage> messages = service.pull("projects/p1/subscriptions/s1", 10);
        assertEquals(1, messages.size());
        assertEquals("at", messages.get(0).getMessage().getData().toStringUtf8());
    }

    @Test
    void seekDuringAPullStillDropsTheMessageThePullTookFromTheQueue() throws Exception {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("old")).build()));
        CountDownLatch pullTookTheMessage = new CountDownLatch(1);
        CountDownLatch releasePull = new CountDownLatch(1);
        // pull reads the clock after taking the message from the queue and before leasing it.
        clock.onNextRead(() -> {
            pullTookTheMessage.countDown();
            await(releasePull);
        });
        AtomicReference<Thread> seekThread = new AtomicReference<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<List<ReceivedMessage>> pull =
                        executor.submit(() -> service.pull("projects/p1/subscriptions/s1", 10));
                assertTrue(pullTookTheMessage.await(5, TimeUnit.SECONDS));
                Future<?> seek = executor.submit(() -> {
                    seekThread.set(Thread.currentThread());
                    service.seek("projects/p1/subscriptions/s1", null, secondsFromNow(60));
                });
                awaitSeekDoneOrBlocked(seekThread, seek);
                releasePull.countDown();
                assertEquals(1, pull.get(5, TimeUnit.SECONDS).size());
                seek.get(5, TimeUnit.SECONDS);
            } finally {
                releasePull.countDown();
            }
        }

        clock.advance(Duration.ofSeconds(10));
        service.expireLeases();
        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());
    }

    @Test
    void seekWaitsForALeaseExpiryInProgressAndDropsWhatItRequeued() throws Exception {
        service.createTopic("projects/p1/topics/t1");
        service.createSubscription("projects/p1/subscriptions/s1", "projects/p1/topics/t1", 10);
        service.publish("projects/p1/topics/t1",
                List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("old")).build()));
        service.pull("projects/p1/subscriptions/s1", 10);
        clock.advance(Duration.ofSeconds(10));
        CountDownLatch expiryStarted = new CountDownLatch(1);
        CountDownLatch releaseExpiry = new CountDownLatch(1);
        clock.onNextRead(() -> {
            expiryStarted.countDown();
            await(releaseExpiry);
        });
        AtomicReference<Thread> seekThread = new AtomicReference<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            try {
                Future<?> expiry = executor.submit(service::expireLeases);
                assertTrue(expiryStarted.await(5, TimeUnit.SECONDS));
                Future<?> seek = executor.submit(() -> {
                    seekThread.set(Thread.currentThread());
                    service.seek("projects/p1/subscriptions/s1", null, secondsFromNow(60));
                });
                awaitSeekDoneOrBlocked(seekThread, seek);
                assertFalse(seek.isDone(), "seek ran while the expiry sweep was moving leases");
                releaseExpiry.countDown();
                expiry.get(5, TimeUnit.SECONDS);
                seek.get(5, TimeUnit.SECONDS);
            } finally {
                releaseExpiry.countDown();
            }
        }

        assertTrue(service.pull("projects/p1/subscriptions/s1", 10).isEmpty());
    }

    // ── Seek to a snapshot ─────────────────────────────────────────────────────

    private static final String TOPIC = "projects/p1/topics/t1";
    private static final String SUB = "projects/p1/subscriptions/s1";
    private static final String SNAPSHOT = "projects/p1/snapshots/snap";

    @Test
    void seekToSnapshotRestoresMessagesAckedSinceIt() {
        service.createTopic(TOPIC);
        service.createSubscription(SUB, TOPIC, 10);
        publish("a");
        publish("b");
        service.createSnapshot(SNAPSHOT, SUB, null);
        service.acknowledge(SUB, service.pull(SUB, 10).stream().map(ReceivedMessage::getAckId).toList());
        assertTrue(service.pull(SUB, 10).isEmpty());

        service.seek(SUB, SNAPSHOT, null);

        assertEquals(List.of("a", "b"), dataOf(service.pull(SUB, 10)));
    }

    @Test
    void seekToSnapshotIncludesLaterPublishesAndNotMessagesAckedBeforeIt() {
        service.createTopic(TOPIC);
        service.createSubscription(SUB, TOPIC, 10);
        publish("before");
        service.acknowledge(SUB, List.of(pullOne(SUB).getAckId()));
        service.createSnapshot(SNAPSHOT, SUB, null);
        publish("after");
        service.acknowledge(SUB, List.of(pullOne(SUB).getAckId()));

        service.seek(SUB, SNAPSHOT, null);

        assertEquals(List.of("after"), dataOf(service.pull(SUB, 10)));
    }

    @Test
    void seekToSnapshotMakesInFlightMessagesUnackedAndKeepsTheirDeliveryAttempt() {
        service.createTopic(TOPIC);
        service.createTopic("projects/p1/topics/dlq");
        createDeadLetterSubscription(SUB, "projects/p1/topics/dlq", 5);
        publish("msg");
        assertEquals(1, pullOne(SUB).getDeliveryAttempt());
        service.createSnapshot(SNAPSHOT, SUB, null);

        service.seek(SUB, SNAPSHOT, null);

        assertEquals(2, pullOne(SUB).getDeliveryAttempt());
        assertTrue(service.pull(SUB, 10).isEmpty());
    }

    @Test
    void seekToSnapshotAppliesTheSeekingSubscriptionsFilter() {
        service.createTopic(TOPIC);
        service.createSubscription(SUB, TOPIC, 10);
        createFilteredSubscription("projects/p1/subscriptions/red", TOPIC, "attributes.color = \"red\"");
        service.createSnapshot(SNAPSHOT, SUB, null);
        service.publish(TOPIC, List.of(message("r", "color", "red"), message("b", "color", "blue")));
        service.acknowledge("projects/p1/subscriptions/red",
                List.of(pullOne("projects/p1/subscriptions/red").getAckId()));

        service.seek("projects/p1/subscriptions/red", SNAPSHOT, null);

        assertEquals(List.of("r"), dataOf(service.pull("projects/p1/subscriptions/red", 10)));
    }

    @Test
    void seekToASnapshotOfAnotherTopicIsRejected() {
        service.createTopic(TOPIC);
        service.createTopic("projects/p1/topics/other");
        service.createSubscription(SUB, TOPIC, 10);
        service.createSubscription("projects/p1/subscriptions/other", "projects/p1/topics/other", 10);
        service.createSnapshot(SNAPSHOT, "projects/p1/subscriptions/other", null);
        publish("kept");

        GcpException e = assertThrows(GcpException.class, () -> service.seek(SUB, SNAPSHOT, null));

        assertEquals("INVALID_ARGUMENT", e.getGcpStatus());
        assertEquals(List.of("kept"), dataOf(service.pull(SUB, 10)));
    }

    @Test
    void seekToAnExpiredOrDeletedSnapshotIsNotFound() {
        service.createTopic(TOPIC);
        service.createSubscription(SUB, TOPIC, 10);
        service.createSnapshot(SNAPSHOT, SUB, null);
        clock.advance(Duration.ofDays(7).plusSeconds(1));
        assertEquals("NOT_FOUND",
                assertThrows(GcpException.class, () -> service.seek(SUB, SNAPSHOT, null)).getGcpStatus());

        service.createSnapshot("projects/p1/snapshots/gone", SUB, null);
        service.deleteSnapshot("projects/p1/snapshots/gone");
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.seek(SUB, "projects/p1/snapshots/gone", null)).getGcpStatus());
    }

    @Test
    void snapshotExpiryFollowsTheOldestUnackedMessage() {
        service.createTopic(TOPIC);
        service.createSubscription(SUB, TOPIC, 10);
        Instant published = clock.instant();
        publish("old");
        clock.advance(Duration.ofDays(2));

        service.createSubscription("projects/p1/subscriptions/empty", TOPIC, 10);

        StoredSnapshot snapshot = service.createSnapshot(SNAPSHOT, SUB, null);
        StoredSnapshot empty = service.createSnapshot("projects/p1/snapshots/empty",
                "projects/p1/subscriptions/empty", null);

        assertEquals(published.plus(Duration.ofDays(7)), Instant.parse(snapshot.getExpireTime()));
        assertEquals(clock.instant().plus(Duration.ofDays(7)), Instant.parse(empty.getExpireTime()));
    }

    @Test
    void aSnapshotThatWouldExpireWithinTheHourIsRefused() {
        service.createTopic(TOPIC);
        service.createSubscription(SUB, TOPIC, 10);
        publish("ancient");
        clock.advance(Duration.ofDays(7).minusMinutes(30));

        GcpException e = assertThrows(GcpException.class, () -> service.createSnapshot(SNAPSHOT, SUB, null));

        assertEquals("FAILED_PRECONDITION", e.getGcpStatus());
        assertTrue(service.listSnapshots("p1").isEmpty());
    }

    @Test
    void listTopicSnapshotsNamesTheSnapshotsOnATopic() {
        service.createTopic(TOPIC);
        service.createTopic("projects/p1/topics/other");
        service.createSubscription(SUB, TOPIC, 10);
        service.createSubscription("projects/p1/subscriptions/other", "projects/p1/topics/other", 10);
        service.createSnapshot(SNAPSHOT, SUB, null);
        service.createSnapshot("projects/p1/snapshots/other", "projects/p1/subscriptions/other", null);

        assertEquals(List.of(SNAPSHOT), service.listTopicSnapshots(TOPIC));
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.listTopicSnapshots("projects/p1/topics/missing")).getGcpStatus());
    }

    @Test
    void seekNeedsATargetAndAnAttachedSubscription() {
        service.createTopic(TOPIC);
        service.createSubscription(SUB, TOPIC, 10);
        assertEquals("INVALID_ARGUMENT",
                assertThrows(GcpException.class, () -> service.seek(SUB, null, null)).getGcpStatus());

        service.detachSubscription(SUB);
        assertEquals("FAILED_PRECONDITION", assertThrows(GcpException.class,
                () -> service.seek(SUB, null, secondsFromNow(-60))).getGcpStatus());
    }

    @Test
    void aPublishDuringASnapshotSeekWaitsForItAndIsDeliveredOnce() throws Exception {
        service.createTopic(TOPIC);
        service.createSubscription(SUB, TOPIC, 10);
        service.createSnapshot(SNAPSHOT, SUB, null);
        publish("before");
        Thread publisher = new Thread(() -> publish("during"));

        synchronized (service.topicLock(TOPIC)) {
            publisher.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (publisher.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.BLOCKED, publisher.getState(), "publish must wait for the topic lock");
            service.seek(SUB, SNAPSHOT, null);
        }
        publisher.join(5_000);

        assertEquals(List.of("before", "during"), dataOf(service.pull(SUB, 10)));
    }

    @Test
    void aSnapshotOfAPushSubscriptionCapturesMessagesStillAwaitingAcknowledgement() throws Exception {
        List<String> pushed = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean accept = new AtomicBoolean();
        CountDownLatch rejected = new CountDownLatch(1);
        CountDownLatch replayed = new CountDownLatch(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            JsonNode envelope = new ObjectMapper().readTree(exchange.getRequestBody());
            String data = new String(Base64.getDecoder().decode(
                    envelope.path("message").path("data").asText()), StandardCharsets.UTF_8);
            if (accept.get()) {
                pushed.add(data);
                exchange.sendResponseHeaders(204, -1);
                replayed.countDown();
            } else {
                exchange.sendResponseHeaders(500, -1);
                rejected.countDown();
            }
            exchange.close();
        });
        server.start();
        try {
            service.createTopic(TOPIC);
            service.createSubscription(SUB, TOPIC, 10, null, false, null, null,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                    null, null, null, null, 0, false, false);
            publish("unacked");
            assertTrue(rejected.await(10, TimeUnit.SECONDS));

            service.createSnapshot(SNAPSHOT, SUB, null);
            accept.set(true);
            service.seek(SUB, SNAPSHOT, null);

            assertTrue(replayed.await(10, TimeUnit.SECONDS), "retried and replayed: " + pushed);
            assertEquals(List.of("unacked", "unacked"), List.copyOf(pushed));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aPushMessageStaysCapturedWhileAnotherDeliveryOfItIsInFlight() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch firstArrived = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        CountDownLatch thirdArrived = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int request = requests.incrementAndGet();
            if (request == 1) {
                firstArrived.countDown();
                await(releaseFirst);
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            if (request == 2) {
                secondDone.countDown();
            } else if (request == 3) {
                thirdArrived.countDown();
            }
        });
        server.start();
        try {
            service.createTopic(TOPIC);
            service.createSubscription(SUB, TOPIC, 10, null, false, null, null,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                    null, null, null, null, 0, false, false);
            service.createSnapshot(SNAPSHOT, SUB, null);
            String messageId = service.publish(TOPIC, List.of(PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("twice")).build())).getFirst();
            assertTrue(firstArrived.await(10, TimeUnit.SECONDS));
            // The publish's delivery is held open; the seek pushes the same message again and that one is acked.
            service.seek(SUB, SNAPSHOT, null);
            assertTrue(secondDone.await(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (service.pendingPushDeliveries(SUB, messageId) != 1 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, service.pendingPushDeliveries(SUB, messageId), "only the held delivery is in flight");

            service.createSnapshot("projects/p1/snapshots/in-flight", SUB, null);
            releaseFirst.countDown();
            service.seek(SUB, "projects/p1/snapshots/in-flight", null);

            assertTrue(thirdArrived.await(10, TimeUnit.SECONDS), "the message still in flight was captured");
        } finally {
            releaseFirst.countDown();
            server.stop(0);
        }
    }

    @Test
    void aSnapshotTakenBeforeAnyPullAfterARestartCapturesTheQueue() {
        service.createTopic(TOPIC);
        service.createSubscription(SUB, TOPIC, 10);
        PubSubService restarted = new PubSubService(new InMemoryStorage<>(), subStore, new InMemoryStorage<>(),
                iamService, clock);
        try {
            restarted.createTopic(TOPIC);
            restarted.publish(TOPIC, List.of(PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("queued")).build()));
            restarted.createSnapshot(SNAPSHOT, SUB, null);
            restarted.acknowledge(SUB, restarted.pull(SUB, 10).stream().map(ReceivedMessage::getAckId).toList());

            restarted.seek(SUB, SNAPSHOT, null);

            assertEquals(List.of("queued"), dataOf(restarted.pull(SUB, 10)));
        } finally {
            restarted.shutdownExecutors();
        }
    }

    @Test
    void listTopicSnapshotsOverGrpcPages() {
        service.createTopic(TOPIC);
        service.createSubscription(SUB, TOPIC, 10);
        service.createSnapshot("projects/p1/snapshots/a", SUB, null);
        service.createSnapshot("projects/p1/snapshots/b", SUB, null);
        PubSubPublisherController controller = new PubSubPublisherController(service);
        List<ListTopicSnapshotsResponse> responses = new ArrayList<>();
        StreamObserver<ListTopicSnapshotsResponse> observer = new StreamObserver<>() {
            @Override public void onNext(ListTopicSnapshotsResponse value) { responses.add(value); }
            @Override public void onError(Throwable t) { throw new AssertionError(t); }
            @Override public void onCompleted() { }
        };

        controller.listTopicSnapshots(ListTopicSnapshotsRequest.newBuilder().setTopic(TOPIC).setPageSize(1).build(),
                observer);
        controller.listTopicSnapshots(ListTopicSnapshotsRequest.newBuilder().setTopic(TOPIC).setPageSize(1)
                .setPageToken(responses.get(0).getNextPageToken()).build(), observer);

        assertEquals(1, responses.get(0).getSnapshotsCount());
        assertFalse(responses.get(0).getNextPageToken().isEmpty());
        assertEquals(1, responses.get(1).getSnapshotsCount());
        assertNotEquals(responses.get(0).getSnapshots(0), responses.get(1).getSnapshots(0));
    }

    @Test
    void seekToSnapshotPushesTheRestoredMessagesToAPushSubscription() throws Exception {
        List<String> pushed = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch twice = new CountDownLatch(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            JsonNode envelope = new ObjectMapper().readTree(exchange.getRequestBody());
            pushed.add(new String(Base64.getDecoder().decode(
                    envelope.path("message").path("data").asText()), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            twice.countDown();
        });
        server.start();
        try {
            service.createTopic(TOPIC);
            service.createSubscription(SUB, TOPIC, 10, null, false, null, null,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                    null, null, null, null, 0, false, false);
            service.createSnapshot(SNAPSHOT, SUB, null);
            publish("pushed");

            service.seek(SUB, SNAPSHOT, null);

            assertTrue(twice.await(10, TimeUnit.SECONDS), "pushed on publish and again on seek: " + pushed);
            assertEquals(List.of("pushed", "pushed"), List.copyOf(pushed));
        } finally {
            server.stop(0);
        }
    }

    private static List<String> dataOf(List<ReceivedMessage> messages) {
        return messages.stream().map(m -> m.getMessage().getData().toStringUtf8()).toList();
    }

    private static void awaitSeekDoneOrBlocked(AtomicReference<Thread> seekThread, Future<?> seek) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!seek.isDone() && !isBlockedInSeek(seekThread.get())) {
            assertTrue(System.nanoTime() < deadline, "seek neither finished nor waited for the lease lock");
            Thread.onSpinWait();
        }
    }

    private static boolean isBlockedInSeek(Thread thread) {
        if (thread == null || thread.getState() != Thread.State.BLOCKED) {
            return false;
        }
        StackTraceElement[] stack = thread.getStackTrace();
        return stack.length > 0
                && stack[0].getClassName().equals(PubSubService.class.getName())
                && stack[0].getMethodName().equals("seek");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private Timestamp secondsFromNow(long seconds) {
        return Timestamp.newBuilder().setSeconds(clock.instant().getEpochSecond() + seconds).build();
    }
}

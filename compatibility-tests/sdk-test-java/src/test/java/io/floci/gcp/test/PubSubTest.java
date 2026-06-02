package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.DetachSubscriptionRequest;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.ProjectTopicName;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.PushConfig;
import com.google.pubsub.v1.ReceivedMessage;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.Topic;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PubSubTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String TOPIC_ID = TestFixtures.uniqueName("test-topic");
    private static final String SUBSCRIPTION_ID = TestFixtures.uniqueName("test-sub");

    private static ManagedChannel channel;
    private static TransportChannelProvider channelProvider;
    private static NoCredentialsProvider credentialsProvider;
    private static TopicAdminClient topicAdminClient;
    private static SubscriptionAdminClient subscriptionAdminClient;

    @BeforeAll
    static void setUp() throws IOException {
        String emulatorHost = System.getenv().getOrDefault("PUBSUB_EMULATOR_HOST", "localhost:4588");

        channel = ManagedChannelBuilder.forTarget(emulatorHost)
                .usePlaintext()
                .build();

        channelProvider = FixedTransportChannelProvider.create(
                GrpcTransportChannel.create(channel));
        credentialsProvider = NoCredentialsProvider.create();

        topicAdminClient = TopicAdminClient.create(
                TopicAdminSettings.newBuilder()
                        .setTransportChannelProvider(channelProvider)
                        .setCredentialsProvider(credentialsProvider)
                        .build());

        subscriptionAdminClient = SubscriptionAdminClient.create(
                SubscriptionAdminSettings.newBuilder()
                        .setTransportChannelProvider(channelProvider)
                        .setCredentialsProvider(credentialsProvider)
                        .build());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (subscriptionAdminClient != null) {
            subscriptionAdminClient.close();
        }
        if (topicAdminClient != null) {
            topicAdminClient.close();
        }
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(1)
    void createTopic() {
        ProjectTopicName topicName = ProjectTopicName.of(PROJECT_ID, TOPIC_ID);
        Topic topic = topicAdminClient.createTopic(topicName);
        assertThat(topic.getName()).isEqualTo(topicName.toString());
    }

    @Test
    @Order(2)
    void createSubscription() {
        ProjectTopicName topicName = ProjectTopicName.of(PROJECT_ID, TOPIC_ID);
        ProjectSubscriptionName subscriptionName = ProjectSubscriptionName.of(PROJECT_ID, SUBSCRIPTION_ID);

        Subscription subscription = subscriptionAdminClient.createSubscription(
                subscriptionName,
                topicName,
                PushConfig.getDefaultInstance(),
                10);

        assertThat(subscription.getName()).isEqualTo(subscriptionName.toString());
        assertThat(subscription.getTopic()).isEqualTo(topicName.toString());
    }

    @Test
    @Order(3)
    void getTopic() {
        ProjectTopicName topicName = ProjectTopicName.of(PROJECT_ID, TOPIC_ID);
        Topic topic = topicAdminClient.getTopic(topicName);
        assertThat(topic.getName()).isEqualTo(topicName.toString());
    }

    @Test
    @Order(4)
    void listTopics() {
        List<String> topicNames = new ArrayList<>();
        topicAdminClient.listTopics("projects/" + PROJECT_ID)
                .iterateAll()
                .forEach(t -> topicNames.add(t.getName()));

        assertThat(topicNames).contains(ProjectTopicName.of(PROJECT_ID, TOPIC_ID).toString());
    }

    @Test
    @Order(5)
    void getSubscription() {
        ProjectSubscriptionName subscriptionName = ProjectSubscriptionName.of(PROJECT_ID, SUBSCRIPTION_ID);
        Subscription subscription = subscriptionAdminClient.getSubscription(subscriptionName);
        assertThat(subscription.getName()).isEqualTo(subscriptionName.toString());
        assertThat(subscription.getTopic()).isEqualTo(ProjectTopicName.of(PROJECT_ID, TOPIC_ID).toString());
    }

    @Test
    @Order(6)
    void listSubscriptions() {
        List<String> subscriptionNames = new ArrayList<>();
        subscriptionAdminClient.listSubscriptions("projects/" + PROJECT_ID)
                .iterateAll()
                .forEach(s -> subscriptionNames.add(s.getName()));

        assertThat(subscriptionNames).contains(ProjectSubscriptionName.of(PROJECT_ID, SUBSCRIPTION_ID).toString());
    }

    @Test
    @Order(7)
    void publishMessages() throws Exception {
        ProjectTopicName topicName = ProjectTopicName.of(PROJECT_ID, TOPIC_ID);

        Publisher publisher = Publisher.newBuilder(topicName)
                .setChannelProvider(channelProvider)
                .setCredentialsProvider(credentialsProvider)
                .build();

        try {
            PubsubMessage message1 = PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("Hello, GCP Pub/Sub!"))
                    .putAllAttributes(Map.of("key1", "value1", "source", "test"))
                    .build();

            PubsubMessage message2 = PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("Second message"))
                    .putAllAttributes(Map.of("key2", "value2"))
                    .build();

            String messageId1 = publisher.publish(message1).get(10, TimeUnit.SECONDS);
            String messageId2 = publisher.publish(message2).get(10, TimeUnit.SECONDS);

            assertThat(messageId1).isNotBlank();
            assertThat(messageId2).isNotBlank();
            assertThat(messageId1).isNotEqualTo(messageId2);
        } finally {
            publisher.shutdown();
            publisher.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(8)
    void pullMessagesAndVerifyContent() throws IOException {
        ProjectSubscriptionName subscriptionName = ProjectSubscriptionName.of(PROJECT_ID, SUBSCRIPTION_ID);

        SubscriberStubSettings subscriberStubSettings = SubscriberStubSettings.newBuilder()
                .setTransportChannelProvider(channelProvider)
                .setCredentialsProvider(credentialsProvider)
                .build();

        try (GrpcSubscriberStub subscriberStub = GrpcSubscriberStub.create(subscriberStubSettings)) {
            PullRequest pullRequest = PullRequest.newBuilder()
                    .setSubscription(subscriptionName.toString())
                    .setMaxMessages(10)
                    .build();

            PullResponse pullResponse = subscriberStub.pullCallable().call(pullRequest);
            List<ReceivedMessage> receivedMessages = pullResponse.getReceivedMessagesList();

            assertThat(receivedMessages).hasSizeGreaterThanOrEqualTo(2);

            List<String> messageContents = new ArrayList<>();
            List<String> ackIds = new ArrayList<>();

            for (ReceivedMessage receivedMessage : receivedMessages) {
                messageContents.add(receivedMessage.getMessage().getData().toStringUtf8());
                ackIds.add(receivedMessage.getAckId());
            }

            assertThat(messageContents).contains("Hello, GCP Pub/Sub!", "Second message");

            AcknowledgeRequest acknowledgeRequest = AcknowledgeRequest.newBuilder()
                    .setSubscription(subscriptionName.toString())
                    .addAllAckIds(ackIds)
                    .build();
            subscriberStub.acknowledgeCallable().call(acknowledgeRequest);
        }
    }

    @Test
    @Order(9)
    void publishMultipleMessagesAndVerifyCount() throws Exception {
        ProjectTopicName topicName = ProjectTopicName.of(PROJECT_ID, TOPIC_ID);
        ProjectSubscriptionName subscriptionName = ProjectSubscriptionName.of(PROJECT_ID, SUBSCRIPTION_ID);

        Publisher publisher = Publisher.newBuilder(topicName)
                .setChannelProvider(channelProvider)
                .setCredentialsProvider(credentialsProvider)
                .build();

        try {
            for (int i = 1; i <= 3; i++) {
                publisher.publish(PubsubMessage.newBuilder()
                        .setData(ByteString.copyFromUtf8("batch-msg-" + i))
                        .build()).get(10, TimeUnit.SECONDS);
            }
        } finally {
            publisher.shutdown();
            publisher.awaitTermination(5, TimeUnit.SECONDS);
        }

        SubscriberStubSettings subscriberStubSettings = SubscriberStubSettings.newBuilder()
                .setTransportChannelProvider(channelProvider)
                .setCredentialsProvider(credentialsProvider)
                .build();

        try (GrpcSubscriberStub subscriberStub = GrpcSubscriberStub.create(subscriberStubSettings)) {
            PullResponse pullResponse = subscriberStub.pullCallable().call(
                    PullRequest.newBuilder()
                            .setSubscription(subscriptionName.toString())
                            .setMaxMessages(10)
                            .build());

            assertThat(pullResponse.getReceivedMessagesList()).hasSize(3);

            List<String> ackIds = pullResponse.getReceivedMessagesList().stream()
                    .map(ReceivedMessage::getAckId).toList();

            subscriberStub.acknowledgeCallable().call(
                    AcknowledgeRequest.newBuilder()
                            .setSubscription(subscriptionName.toString())
                            .addAllAckIds(ackIds)
                            .build());
        }
    }

    @Test
    @Order(10)
    void acknowledgeRemovesMessagesFromQueue() throws Exception {
        ProjectTopicName topicName = ProjectTopicName.of(PROJECT_ID, TOPIC_ID);
        ProjectSubscriptionName subscriptionName = ProjectSubscriptionName.of(PROJECT_ID, SUBSCRIPTION_ID);

        Publisher publisher = Publisher.newBuilder(topicName)
                .setChannelProvider(channelProvider)
                .setCredentialsProvider(credentialsProvider)
                .build();

        try {
            publisher.publish(PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8("ack-test"))
                    .build()).get(10, TimeUnit.SECONDS);
        } finally {
            publisher.shutdown();
            publisher.awaitTermination(5, TimeUnit.SECONDS);
        }

        SubscriberStubSettings subscriberStubSettings = SubscriberStubSettings.newBuilder()
                .setTransportChannelProvider(channelProvider)
                .setCredentialsProvider(credentialsProvider)
                .build();

        try (GrpcSubscriberStub subscriberStub = GrpcSubscriberStub.create(subscriberStubSettings)) {
            PullRequest pullRequest = PullRequest.newBuilder()
                    .setSubscription(subscriptionName.toString())
                    .setMaxMessages(10)
                    .build();

            PullResponse pullResponse = subscriberStub.pullCallable().call(pullRequest);
            assertThat(pullResponse.getReceivedMessagesList()).isNotEmpty();

            List<String> ackIds = pullResponse.getReceivedMessagesList().stream()
                    .map(ReceivedMessage::getAckId).toList();

            subscriberStub.acknowledgeCallable().call(
                    AcknowledgeRequest.newBuilder()
                            .setSubscription(subscriptionName.toString())
                            .addAllAckIds(ackIds)
                            .build());

            PullResponse emptyResponse = subscriberStub.pullCallable().call(pullRequest);
            assertThat(emptyResponse.getReceivedMessagesList()).isEmpty();
        }
    }

    @Test
    @Order(11)
    void deleteSubscription() {
        ProjectSubscriptionName subscriptionName = ProjectSubscriptionName.of(PROJECT_ID, SUBSCRIPTION_ID);
        subscriptionAdminClient.deleteSubscription(subscriptionName);

        List<String> subscriptionNames = new ArrayList<>();
        subscriptionAdminClient
                .listSubscriptions("projects/" + PROJECT_ID)
                .iterateAll()
                .forEach(s -> subscriptionNames.add(s.getName()));

        assertThat(subscriptionNames).doesNotContain(subscriptionName.toString());
    }

    @Test
    @Order(12)
    void deleteTopic() {
        ProjectTopicName topicName = ProjectTopicName.of(PROJECT_ID, TOPIC_ID);
        topicAdminClient.deleteTopic(topicName);

        List<String> topicNames = new ArrayList<>();
        topicAdminClient
                .listTopics("projects/" + PROJECT_ID)
                .iterateAll()
                .forEach(t -> topicNames.add(t.getName()));

        assertThat(topicNames).doesNotContain(topicName.toString());
    }

    @Test
    @Order(13)
    void detachSubscription() {
        String topicId = TestFixtures.uniqueName("detach-topic");
        String subId = TestFixtures.uniqueName("detach-sub");
        ProjectTopicName topicName = ProjectTopicName.of(PROJECT_ID, topicId);
        ProjectSubscriptionName subName = ProjectSubscriptionName.of(PROJECT_ID, subId);

        topicAdminClient.createTopic(topicName);
        subscriptionAdminClient.createSubscription(
                subName, topicName, PushConfig.getDefaultInstance(), 10);
        try {
            assertThat(subscriptionAdminClient.getSubscription(subName).getDetached()).isFalse();

            topicAdminClient.detachSubscription(DetachSubscriptionRequest.newBuilder()
                    .setSubscription(subName.toString())
                    .build());

            assertThat(subscriptionAdminClient.getSubscription(subName).getDetached()).isTrue();
        } finally {
            try { subscriptionAdminClient.deleteSubscription(subName); } catch (Exception ignored) {}
            try { topicAdminClient.deleteTopic(topicName); } catch (Exception ignored) {}
        }
    }
}

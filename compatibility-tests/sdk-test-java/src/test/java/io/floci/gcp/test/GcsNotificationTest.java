package io.floci.gcp.test;

import com.google.api.gax.core.CredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Notification;
import com.google.cloud.storage.NotificationInfo;
import com.google.cloud.storage.Storage;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.ProjectTopicName;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GcsNotificationTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String BUCKET_NAME = TestFixtures.uniqueName("notif-bucket");
    private static final String TOPIC_ID = TestFixtures.uniqueName("gcs-notif-topic");
    private static final String SUBSCRIPTION_ID = TestFixtures.uniqueName("gcs-notif-sub");
    private static final String OBJECT_NAME = "notification/trigger.txt";

    private static Storage storage;
    private static ManagedChannel channel;
    private static TransportChannelProvider channelProvider;
    private static CredentialsProvider credentialsProvider;
    private static TopicAdminClient topicAdminClient;
    private static SubscriptionAdminClient subscriptionAdminClient;

    private static String notificationId;

    @BeforeAll
    static void setUp() throws IOException {
        storage = TestFixtures.storageClient();

        String emulatorHost = System.getenv().getOrDefault("PUBSUB_EMULATOR_HOST", "localhost:4588");
        channel = ManagedChannelBuilder.forTarget(emulatorHost).usePlaintext().build();
        channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel));
        credentialsProvider = TestFixtures.credentialsProvider();

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
        try { storage.delete(BlobId.of(BUCKET_NAME, OBJECT_NAME)); } catch (Exception ignored) {}
        try { storage.get(BUCKET_NAME).delete(); } catch (Exception ignored) {}
        try { subscriptionAdminClient.deleteSubscription(ProjectSubscriptionName.of(PROJECT_ID, SUBSCRIPTION_ID)); } catch (Exception ignored) {}
        try { topicAdminClient.deleteTopic(ProjectTopicName.of(PROJECT_ID, TOPIC_ID)); } catch (Exception ignored) {}
        if (subscriptionAdminClient != null) subscriptionAdminClient.close();
        if (topicAdminClient != null) topicAdminClient.close();
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (storage instanceof AutoCloseable closeable) closeable.close();
    }

    @Test
    @Order(1)
    void createTopicAndSubscription() {
        ProjectTopicName topicName = ProjectTopicName.of(PROJECT_ID, TOPIC_ID);
        Topic topic = topicAdminClient.createTopic(topicName);
        assertThat(topic.getName()).isEqualTo(topicName.toString());

        Subscription sub = subscriptionAdminClient.createSubscription(
                ProjectSubscriptionName.of(PROJECT_ID, SUBSCRIPTION_ID),
                topicName,
                PushConfig.getDefaultInstance(),
                10);
        assertThat(sub.getName()).contains(SUBSCRIPTION_ID);
    }

    @Test
    @Order(2)
    void createBucketAndNotification() {
        storage.create(BucketInfo.of(BUCKET_NAME));

        String topicName = ProjectTopicName.of(PROJECT_ID, TOPIC_ID).toString();
        NotificationInfo notifInfo = NotificationInfo.newBuilder(topicName)
                .setEventTypes(NotificationInfo.EventType.OBJECT_FINALIZE,
                        NotificationInfo.EventType.OBJECT_DELETE)
                .setPayloadFormat(NotificationInfo.PayloadFormat.JSON_API_V1)
                .build();

        Notification notification = storage.createNotification(BUCKET_NAME, notifInfo);
        assertThat(notification).isNotNull();
        assertThat(notification.getTopic()).isEqualTo(topicName);
        notificationId = notification.getNotificationId();
        assertThat(notificationId).isNotBlank();
    }

    @Test
    @Order(3)
    void listNotificationsReturnCreated() {
        List<Notification> notifications = storage.listNotifications(BUCKET_NAME);
        assertThat(notifications).isNotEmpty();
        assertThat(notifications.stream().anyMatch(n -> notificationId.equals(n.getNotificationId()))).isTrue();
    }

    @Test
    @Order(4)
    void getNotificationById() {
        Notification notification = storage.getNotification(BUCKET_NAME, notificationId);
        assertThat(notification).isNotNull();
        assertThat(notification.getNotificationId()).isEqualTo(notificationId);
    }

    @Test
    @Order(5)
    void uploadObjectTriggersNotification() throws IOException {
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME))
                .setContentType("text/plain")
                .build();
        storage.create(info, "trigger event".getBytes(StandardCharsets.UTF_8));

        SubscriberStubSettings subscriberSettings = SubscriberStubSettings.newBuilder()
                .setTransportChannelProvider(channelProvider)
                .setCredentialsProvider(credentialsProvider)
                .build();

        try (GrpcSubscriberStub subscriberStub = GrpcSubscriberStub.create(subscriberSettings)) {
            PullResponse response = subscriberStub.pullCallable().call(
                    PullRequest.newBuilder()
                            .setSubscription(ProjectSubscriptionName.of(PROJECT_ID, SUBSCRIPTION_ID).toString())
                            .setMaxMessages(10)
                            .build());

            List<ReceivedMessage> messages = response.getReceivedMessagesList();
            assertThat(messages).isNotEmpty();

            boolean hasFinalize = messages.stream().anyMatch(m ->
                    "OBJECT_FINALIZE".equals(m.getMessage().getAttributesOrDefault("eventType", "")));
            assertThat(hasFinalize).isTrue();

            boolean hasBucket = messages.stream().anyMatch(m ->
                    BUCKET_NAME.equals(m.getMessage().getAttributesOrDefault("bucketId", "")));
            assertThat(hasBucket).isTrue();

            List<String> ackIds = messages.stream().map(ReceivedMessage::getAckId).toList();
            subscriberStub.acknowledgeCallable().call(
                    AcknowledgeRequest.newBuilder()
                            .setSubscription(ProjectSubscriptionName.of(PROJECT_ID, SUBSCRIPTION_ID).toString())
                            .addAllAckIds(ackIds)
                            .build());
        }
    }

    @Test
    @Order(6)
    void deleteObjectTriggersNotification() throws IOException {
        storage.delete(BlobId.of(BUCKET_NAME, OBJECT_NAME));

        SubscriberStubSettings subscriberSettings = SubscriberStubSettings.newBuilder()
                .setTransportChannelProvider(channelProvider)
                .setCredentialsProvider(credentialsProvider)
                .build();

        try (GrpcSubscriberStub subscriberStub = GrpcSubscriberStub.create(subscriberSettings)) {
            PullResponse response = subscriberStub.pullCallable().call(
                    PullRequest.newBuilder()
                            .setSubscription(ProjectSubscriptionName.of(PROJECT_ID, SUBSCRIPTION_ID).toString())
                            .setMaxMessages(10)
                            .build());

            List<ReceivedMessage> messages = response.getReceivedMessagesList();
            assertThat(messages).isNotEmpty();

            boolean hasDelete = messages.stream().anyMatch(m ->
                    "OBJECT_DELETE".equals(m.getMessage().getAttributesOrDefault("eventType", "")));
            assertThat(hasDelete).isTrue();

            List<String> ackIds = messages.stream().map(ReceivedMessage::getAckId).toList();
            subscriberStub.acknowledgeCallable().call(
                    AcknowledgeRequest.newBuilder()
                            .setSubscription(ProjectSubscriptionName.of(PROJECT_ID, SUBSCRIPTION_ID).toString())
                            .addAllAckIds(ackIds)
                            .build());
        }
    }

    @Test
    @Order(7)
    void deleteNotificationConfig() {
        storage.deleteNotification(BUCKET_NAME, notificationId);

        List<Notification> remaining = storage.listNotifications(BUCKET_NAME);
        boolean stillPresent = remaining.stream()
                .anyMatch(n -> notificationId.equals(n.getNotificationId()));
        assertThat(stillPresent).isFalse();
    }
}

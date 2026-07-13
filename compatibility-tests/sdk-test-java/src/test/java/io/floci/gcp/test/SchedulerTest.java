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
import com.google.cloud.scheduler.v1.CloudSchedulerClient;
import com.google.cloud.scheduler.v1.Job;
import com.google.cloud.scheduler.v1.PubsubTarget;
import com.google.protobuf.ByteString;
import com.google.protobuf.FieldMask;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.ProjectTopicName;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.PushConfig;
import com.google.pubsub.v1.ReceivedMessage;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchedulerTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String LOCATION = "us-central1";
    private static final String PARENT = "projects/" + PROJECT_ID + "/locations/" + LOCATION;

    private static final String JOB_ID = TestFixtures.uniqueName("test-job");
    private static final String JOB_NAME = PARENT + "/jobs/" + JOB_ID;
    private static final String TOPIC_ID = TestFixtures.uniqueName("sched-topic");
    private static final String SUB_ID = TestFixtures.uniqueName("sched-sub");
    private static final String PAYLOAD = "scheduled-tick";

    private static CloudSchedulerClient schedulerClient;
    private static ManagedChannel channel;
    private static TransportChannelProvider channelProvider;
    private static TopicAdminClient topicAdminClient;
    private static SubscriptionAdminClient subscriptionAdminClient;

    @BeforeAll
    static void setUp() throws IOException {
        String emulatorHost = System.getenv().getOrDefault("PUBSUB_EMULATOR_HOST", "localhost:4588");
        channel = ManagedChannelBuilder.forTarget(emulatorHost).usePlaintext().build();
        channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel));
        CredentialsProvider creds = TestFixtures.credentialsProvider();

        schedulerClient = TestFixtures.cloudSchedulerClient();
        topicAdminClient = TopicAdminClient.create(TopicAdminSettings.newBuilder()
                .setTransportChannelProvider(channelProvider).setCredentialsProvider(creds).build());
        subscriptionAdminClient = SubscriptionAdminClient.create(SubscriptionAdminSettings.newBuilder()
                .setTransportChannelProvider(channelProvider).setCredentialsProvider(creds).build());

        topicAdminClient.createTopic(ProjectTopicName.of(PROJECT_ID, TOPIC_ID));
        subscriptionAdminClient.createSubscription(
                ProjectSubscriptionName.of(PROJECT_ID, SUB_ID),
                ProjectTopicName.of(PROJECT_ID, TOPIC_ID),
                PushConfig.getDefaultInstance(), 10);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (schedulerClient != null) schedulerClient.close();
        if (subscriptionAdminClient != null) subscriptionAdminClient.close();
        if (topicAdminClient != null) topicAdminClient.close();
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static String topicName() {
        return ProjectTopicName.of(PROJECT_ID, TOPIC_ID).toString();
    }

    @Test
    @Order(1)
    void createJobWithPubsubTarget() {
        Job job = schedulerClient.createJob(PARENT, Job.newBuilder()
                .setName(JOB_NAME)
                .setSchedule("*/5 * * * *")
                .setPubsubTarget(PubsubTarget.newBuilder()
                        .setTopicName(topicName())
                        .setData(ByteString.copyFromUtf8(PAYLOAD))
                        .build())
                .build());

        assertThat(job.getName()).isEqualTo(JOB_NAME);
        assertThat(job.getState()).isEqualTo(Job.State.ENABLED);
        assertThat(job.getPubsubTarget().getTopicName()).isEqualTo(topicName());
    }

    @Test
    @Order(2)
    void getJob() {
        Job job = schedulerClient.getJob(JOB_NAME);
        assertThat(job.getSchedule()).isEqualTo("*/5 * * * *");
        assertThat(job.getPubsubTarget().getData().toStringUtf8()).isEqualTo(PAYLOAD);
    }

    @Test
    @Order(3)
    void listJobsContainsCreated() {
        List<String> names = new ArrayList<>();
        schedulerClient.listJobs(PARENT).iterateAll().forEach(j -> names.add(j.getName()));
        assertThat(names).contains(JOB_NAME);
    }

    @Test
    @Order(4)
    void pauseThenResume() {
        assertThat(schedulerClient.pauseJob(JOB_NAME).getState()).isEqualTo(Job.State.PAUSED);
        assertThat(schedulerClient.resumeJob(JOB_NAME).getState()).isEqualTo(Job.State.ENABLED);
    }

    @Test
    @Order(5)
    void runJobPublishesToPubsub() throws IOException {
        schedulerClient.runJob(JOB_NAME);

        SubscriberStubSettings settings = SubscriberStubSettings.newBuilder()
                .setTransportChannelProvider(channelProvider)
                .setCredentialsProvider(TestFixtures.credentialsProvider())
                .build();
        try (GrpcSubscriberStub stub = GrpcSubscriberStub.create(settings)) {
            String sub = ProjectSubscriptionName.of(PROJECT_ID, SUB_ID).toString();
            PullResponse resp = stub.pullCallable().call(PullRequest.newBuilder()
                    .setSubscription(sub).setMaxMessages(10).build());

            List<String> contents = resp.getReceivedMessagesList().stream()
                    .map(m -> m.getMessage().getData().toStringUtf8()).toList();
            assertThat(contents).contains(PAYLOAD);

            List<String> ackIds = resp.getReceivedMessagesList().stream()
                    .map(ReceivedMessage::getAckId).toList();
            stub.acknowledgeCallable().call(AcknowledgeRequest.newBuilder()
                    .setSubscription(sub).addAllAckIds(ackIds).build());
        }
    }

    @Test
    @Order(6)
    void updateJobDescription() {
        Job updated = schedulerClient.updateJob(
                Job.newBuilder().setName(JOB_NAME).setDescription("updated").build(),
                FieldMask.newBuilder().addPaths("description").build());
        assertThat(updated.getDescription()).isEqualTo("updated");
    }

    @Test
    @Order(7)
    void deleteJob() {
        schedulerClient.deleteJob(JOB_NAME);
        assertThatThrownBy(() -> schedulerClient.getJob(JOB_NAME)).isInstanceOf(RuntimeException.class);
    }
}

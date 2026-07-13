package io.floci.gcp.services.pubsub;

import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.SubscriberGrpc;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.Topic;
import com.google.pubsub.v1.PublisherGrpc;
import io.floci.gcp.core.common.TokenRegistry;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(PubSubGrpcSubscriberIamEnforcementIntegrationTest.CtfIamProfile.class)
class PubSubGrpcSubscriberIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String PROJECT_NAME = "projects/" + PROJECT;
    private static final String PLAYER_EMAIL = "player@test-project.iam.gserviceaccount.com";
    private static final String PLAYER_TOKEN = "player-token";
    private static final String ROOT_TOKEN = "root-token-test";
    private static final int GRPC_PORT = 4588;

    @Inject
    TokenRegistry tokenRegistry;

    @Inject
    IamService iamService;

    private ManagedChannel channel;
    private String topicName;
    private String subscriptionName;

    @BeforeEach
    void setUp() {
        tokenRegistry.register(PLAYER_TOKEN, PLAYER_EMAIL);
        iamService.setPolicy(PROJECT_NAME, new StoredPolicy());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        topicName = PROJECT_NAME + "/topics/ctf-sub-grpc-" + suffix;
        subscriptionName = PROJECT_NAME + "/subscriptions/ctf-sub-grpc-" + suffix;
        int port = io.restassured.RestAssured.port > 0
                ? io.restassured.RestAssured.port
                : GRPC_PORT;
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();

        PublisherGrpc.PublisherBlockingStub rootPublisher = publisherStub(ROOT_TOKEN);
        rootPublisher.createTopic(Topic.newBuilder().setName(topicName).build());
        subscriberStub(ROOT_TOKEN).createSubscription(Subscription.newBuilder()
                .setName(subscriptionName)
                .setTopic(topicName)
                .build());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            try {
                subscriberStub(ROOT_TOKEN).deleteSubscription(
                        com.google.pubsub.v1.DeleteSubscriptionRequest.newBuilder()
                                .setSubscription(subscriptionName)
                                .build());
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            try {
                publisherStub(ROOT_TOKEN).deleteTopic(
                        com.google.pubsub.v1.DeleteTopicRequest.newBuilder().setTopic(topicName).build());
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void playerWithoutBindingIsDeniedPull() {
        SubscriberGrpc.SubscriberBlockingStub stub = subscriberStub(PLAYER_TOKEN);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.pull(PullRequest.newBuilder()
                        .setSubscription(subscriptionName)
                        .setMaxMessages(1)
                        .build()));

        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        assertTrue(ex.getStatus().getDescription() != null
                && ex.getStatus().getDescription().contains("pubsub.subscriptions.consume"));
    }

    @Test
    void subscriberCanPullEvenWhenEmpty() {
        bindPlayer("roles/pubsub.subscriber");
        SubscriberGrpc.SubscriberBlockingStub stub = subscriberStub(PLAYER_TOKEN);

        try {
            PullResponse response = stub.pull(PullRequest.newBuilder()
                    .setSubscription(subscriptionName)
                    .setMaxMessages(1)
                    .build());
            assertTrue(response != null);
        } catch (StatusRuntimeException ex) {
            assertNotEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode(),
                    "subscriber should pass IAM on Pull, got: " + ex.getStatus());
        }
    }

    @Test
    void publisherIsDeniedPull() {
        bindPlayer("roles/pubsub.publisher");
        SubscriberGrpc.SubscriberBlockingStub stub = subscriberStub(PLAYER_TOKEN);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.pull(PullRequest.newBuilder()
                        .setSubscription(subscriptionName)
                        .setMaxMessages(1)
                        .build()));

        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        assertTrue(ex.getStatus().getDescription() != null
                && ex.getStatus().getDescription().contains("pubsub.subscriptions.consume"));
    }

    private PublisherGrpc.PublisherBlockingStub publisherStub(String token) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + token);
        return PublisherGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private SubscriberGrpc.SubscriberBlockingStub subscriberStub(String token) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + token);
        return SubscriberGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private void bindPlayer(String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", role,
                "members", List.of("serviceAccount:" + PLAYER_EMAIL))));
        iamService.setPolicy(PROJECT_NAME, policy);
    }

    public static class CtfIamProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.auth.validate-tokens", "true",
                    "floci-gcp.auth.root-service-account",
                    "operator@test-project.iam.gserviceaccount.com",
                    "floci-gcp.auth.root-access-token", ROOT_TOKEN,
                    "floci-gcp.services.iam.enforcement-enabled", "true",
                    "floci-gcp.services.iam.strict-enforcement-enabled", "true");
        }
    }
}

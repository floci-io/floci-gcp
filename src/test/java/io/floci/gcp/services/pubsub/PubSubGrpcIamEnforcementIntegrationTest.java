package io.floci.gcp.services.pubsub;

import com.google.protobuf.ByteString;
import com.google.pubsub.v1.ListTopicsRequest;
import com.google.pubsub.v1.ListTopicsResponse;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.PublishResponse;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PublisherGrpc;
import com.google.pubsub.v1.Topic;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(PubSubGrpcIamEnforcementIntegrationTest.CtfIamProfile.class)
class PubSubGrpcIamEnforcementIntegrationTest {

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

    @BeforeEach
    void setUp() {
        tokenRegistry.register(PLAYER_TOKEN, PLAYER_EMAIL);
        iamService.setPolicy(PROJECT_NAME, new StoredPolicy());
        topicName = PROJECT_NAME + "/topics/ctf-grpc-" + UUID.randomUUID().toString().substring(0, 8);
        int port = io.restassured.RestAssured.port > 0
                ? io.restassured.RestAssured.port
                : GRPC_PORT;
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();

        PublisherGrpc.PublisherBlockingStub root = stubWithBearer(ROOT_TOKEN);
        root.createTopic(Topic.newBuilder().setName(topicName).build());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            try {
                stubWithBearer(ROOT_TOKEN).deleteTopic(
                        com.google.pubsub.v1.DeleteTopicRequest.newBuilder().setTopic(topicName).build());
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void playerWithoutBindingIsDeniedListTopics() {
        PublisherGrpc.PublisherBlockingStub stub = stubWithBearer(PLAYER_TOKEN);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.listTopics(ListTopicsRequest.newBuilder().setProject(PROJECT_NAME).build()));

        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        assertTrue(ex.getStatus().getDescription() != null
                && ex.getStatus().getDescription().contains("pubsub.topics.list"));
    }

    @Test
    void playerWithPubSubAdminCanListTopics() {
        bindPlayer("roles/pubsub.admin");
        PublisherGrpc.PublisherBlockingStub stub = stubWithBearer(PLAYER_TOKEN);

        ListTopicsResponse response = stub.listTopics(
                ListTopicsRequest.newBuilder().setProject(PROJECT_NAME).build());

        assertTrue(response != null);
        assertTrue(response.getTopicsList().stream().anyMatch(t -> topicName.equals(t.getName())));
    }

    @Test
    void playerWithPublisherCanPublish() {
        bindPlayer("roles/pubsub.publisher");
        PublisherGrpc.PublisherBlockingStub stub = stubWithBearer(PLAYER_TOKEN);

        PublishResponse response = stub.publish(PublishRequest.newBuilder()
                .setTopic(topicName)
                .addMessages(PubsubMessage.newBuilder()
                        .setData(ByteString.copyFromUtf8("hello-grpc"))
                        .build())
                .build());

        assertEquals(1, response.getMessageIdsCount());
    }

    @Test
    void rootTokenAlwaysCanListTopics() {
        PublisherGrpc.PublisherBlockingStub stub = stubWithBearer(ROOT_TOKEN);

        ListTopicsResponse response = stub.listTopics(
                ListTopicsRequest.newBuilder().setProject(PROJECT_NAME).build());

        assertTrue(response != null);
    }

    private PublisherGrpc.PublisherBlockingStub stubWithBearer(String token) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + token);
        return PublisherGrpc.newBlockingStub(channel)
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

package io.floci.gcp.services.tasks;

import com.google.cloud.tasks.v2.CloudTasksGrpc;
import com.google.cloud.tasks.v2.ListQueuesRequest;
import com.google.cloud.tasks.v2.ListQueuesResponse;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CloudTasksGrpcIamEnforcementIntegrationTest.CtfIamProfile.class)
class CloudTasksGrpcIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String LOCATION = "us-central1";
    private static final String PARENT = "projects/" + PROJECT + "/locations/" + LOCATION;
    private static final String PLAYER_EMAIL = "player@test-project.iam.gserviceaccount.com";
    private static final String PLAYER_TOKEN = "player-token";
    private static final String ROOT_TOKEN = "root-token-test";
    private static final int GRPC_PORT = 4588;

    @Inject
    TokenRegistry tokenRegistry;

    @Inject
    IamService iamService;

    private ManagedChannel channel;

    @BeforeEach
    void setUp() {
        tokenRegistry.register(PLAYER_TOKEN, PLAYER_EMAIL);
        iamService.setPolicy("projects/" + PROJECT, new StoredPolicy());
        int port = io.restassured.RestAssured.port > 0
                ? io.restassured.RestAssured.port
                : GRPC_PORT;
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void playerWithoutBindingIsDeniedListQueues() {
        CloudTasksGrpc.CloudTasksBlockingStub stub = stubWithBearer(PLAYER_TOKEN);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.listQueues(ListQueuesRequest.newBuilder().setParent(PARENT).build()));

        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        assertTrue(ex.getStatus().getDescription() != null
                && ex.getStatus().getDescription().contains("cloudtasks.queues.list"));
    }

    @Test
    void playerWithCloudTasksViewerCanListQueues() {
        bindPlayer("roles/cloudtasks.viewer");
        CloudTasksGrpc.CloudTasksBlockingStub stub = stubWithBearer(PLAYER_TOKEN);

        ListQueuesResponse response = stub.listQueues(
                ListQueuesRequest.newBuilder().setParent(PARENT).build());

        assertTrue(response != null);
    }

    @Test
    void rootTokenAlwaysCanListQueues() {
        CloudTasksGrpc.CloudTasksBlockingStub stub = stubWithBearer(ROOT_TOKEN);

        ListQueuesResponse response = stub.listQueues(
                ListQueuesRequest.newBuilder().setParent(PARENT).build());

        assertTrue(response != null);
    }

    private CloudTasksGrpc.CloudTasksBlockingStub stubWithBearer(String token) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + token);
        return CloudTasksGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private void bindPlayer(String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", role,
                "members", List.of("serviceAccount:" + PLAYER_EMAIL))));
        iamService.setPolicy("projects/" + PROJECT, policy);
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

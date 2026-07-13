package io.floci.gcp.services.secretmanager;

import com.google.cloud.secretmanager.v1.AccessSecretVersionRequest;
import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.AddSecretVersionRequest;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.ListSecretsRequest;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceGrpc;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.protobuf.ByteString;
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
@TestProfile(SecretManagerGrpcAccessIamEnforcementIntegrationTest.CtfIamProfile.class)
class SecretManagerGrpcAccessIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String PARENT = "projects/" + PROJECT;
    private static final String PLAYER_EMAIL = "player@test-project.iam.gserviceaccount.com";
    private static final String PLAYER_TOKEN = "player-token";
    private static final String ROOT_TOKEN = "root-token-test";
    private static final int GRPC_PORT = 4588;

    @Inject
    TokenRegistry tokenRegistry;

    @Inject
    IamService iamService;

    private ManagedChannel channel;
    private String secretId;
    private String versionName;

    @BeforeEach
    void setUp() {
        tokenRegistry.register(PLAYER_TOKEN, PLAYER_EMAIL);
        iamService.setPolicy(PARENT, new StoredPolicy());
        secretId = "ctf-grpc-secret-" + UUID.randomUUID().toString().substring(0, 8);
        int port = io.restassured.RestAssured.port > 0
                ? io.restassured.RestAssured.port
                : GRPC_PORT;
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();

        SecretManagerServiceGrpc.SecretManagerServiceBlockingStub root = stubWithBearer(ROOT_TOKEN);
        root.createSecret(CreateSecretRequest.newBuilder()
                .setParent(PARENT)
                .setSecretId(secretId)
                .setSecret(Secret.newBuilder()
                        .setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.getDefaultInstance())
                                .build())
                        .build())
                .build());
        versionName = root.addSecretVersion(AddSecretVersionRequest.newBuilder()
                .setParent(PARENT + "/secrets/" + secretId)
                .setPayload(SecretPayload.newBuilder()
                        .setData(ByteString.copyFromUtf8("ctf-grpc-value"))
                        .build())
                .build()).getName();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            try {
                stubWithBearer(ROOT_TOKEN).deleteSecret(
                        com.google.cloud.secretmanager.v1.DeleteSecretRequest.newBuilder()
                                .setName(PARENT + "/secrets/" + secretId)
                                .build());
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void playerWithoutBindingIsDeniedAccessSecretVersion() {
        SecretManagerServiceGrpc.SecretManagerServiceBlockingStub stub = stubWithBearer(PLAYER_TOKEN);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                        .setName(versionName)
                        .build()));

        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        assertTrue(ex.getStatus().getDescription() != null
                && ex.getStatus().getDescription().contains("secretmanager.versions.access"));
    }

    @Test
    void secretAccessorCanAccessVersionButIsDeniedListSecrets() {
        bindPlayer("roles/secretmanager.secretAccessor");
        SecretManagerServiceGrpc.SecretManagerServiceBlockingStub stub = stubWithBearer(PLAYER_TOKEN);

        AccessSecretVersionResponse response = stub.accessSecretVersion(
                AccessSecretVersionRequest.newBuilder().setName(versionName).build());
        assertEquals("ctf-grpc-value", response.getPayload().getData().toStringUtf8());

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.listSecrets(ListSecretsRequest.newBuilder().setParent(PARENT).build()));
        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        assertTrue(ex.getStatus().getDescription() != null
                && ex.getStatus().getDescription().contains("secretmanager.secrets.list"));
    }

    private SecretManagerServiceGrpc.SecretManagerServiceBlockingStub stubWithBearer(String token) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + token);
        return SecretManagerServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private void bindPlayer(String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", role,
                "members", List.of("serviceAccount:" + PLAYER_EMAIL))));
        iamService.setPolicy(PARENT, policy);
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

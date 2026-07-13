package io.floci.gcp.services.cloudkms;

import com.google.cloud.kms.v1.CreateCryptoKeyRequest;
import com.google.cloud.kms.v1.CreateKeyRingRequest;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.EncryptRequest;
import com.google.cloud.kms.v1.EncryptResponse;
import com.google.cloud.kms.v1.KeyManagementServiceGrpc;
import com.google.cloud.kms.v1.KeyRing;
import com.google.cloud.kms.v1.ListKeyRingsRequest;
import com.google.cloud.kms.v1.ListKeyRingsResponse;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CloudKmsGrpcCryptoIamEnforcementIntegrationTest.CtfIamProfile.class)
class CloudKmsGrpcCryptoIamEnforcementIntegrationTest {

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
    private String cryptoKeyName;

    @BeforeEach
    void setUp() {
        tokenRegistry.register(PLAYER_TOKEN, PLAYER_EMAIL);
        iamService.setPolicy("projects/" + PROJECT, new StoredPolicy());
        int port = io.restassured.RestAssured.port > 0
                ? io.restassured.RestAssured.port
                : GRPC_PORT;
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();

        String keyRingId = "ctf-grpc-kr-" + UUID.randomUUID().toString().substring(0, 8);
        String cryptoKeyId = "ctf-grpc-key-" + UUID.randomUUID().toString().substring(0, 8);
        KeyManagementServiceGrpc.KeyManagementServiceBlockingStub root = stubWithBearer(ROOT_TOKEN);
        root.createKeyRing(CreateKeyRingRequest.newBuilder()
                .setParent(PARENT)
                .setKeyRingId(keyRingId)
                .setKeyRing(KeyRing.getDefaultInstance())
                .build());
        cryptoKeyName = root.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(PARENT + "/keyRings/" + keyRingId)
                .setCryptoKeyId(cryptoKeyId)
                .setCryptoKey(CryptoKey.newBuilder()
                        .setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT)
                        .build())
                .build()).getName();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void encrypterDecrypterCanEncryptButIsDeniedListKeyRings() {
        bindPlayer("roles/cloudkms.cryptoKeyEncrypterDecrypter");
        KeyManagementServiceGrpc.KeyManagementServiceBlockingStub stub = stubWithBearer(PLAYER_TOKEN);

        EncryptResponse response = stub.encrypt(EncryptRequest.newBuilder()
                .setName(cryptoKeyName)
                .setPlaintext(ByteString.copyFrom("ctf-secret", StandardCharsets.UTF_8))
                .build());
        assertTrue(response.getCiphertext().size() > 0);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.listKeyRings(ListKeyRingsRequest.newBuilder().setParent(PARENT).build()));
        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        assertTrue(ex.getStatus().getDescription() != null
                && ex.getStatus().getDescription().contains("cloudkms.keyRings.list"));
    }

    @Test
    void adminCanListKeyRingsButIsDeniedEncrypt() {
        bindPlayer("roles/cloudkms.admin");
        KeyManagementServiceGrpc.KeyManagementServiceBlockingStub stub = stubWithBearer(PLAYER_TOKEN);

        ListKeyRingsResponse list = stub.listKeyRings(
                ListKeyRingsRequest.newBuilder().setParent(PARENT).build());
        assertTrue(list != null);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.encrypt(EncryptRequest.newBuilder()
                        .setName(cryptoKeyName)
                        .setPlaintext(ByteString.copyFrom("ctf-secret", StandardCharsets.UTF_8))
                        .build()));
        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        assertTrue(ex.getStatus().getDescription() != null
                && ex.getStatus().getDescription().contains("cloudkms.cryptoKeyVersions.useToEncrypt"));
    }

    private KeyManagementServiceGrpc.KeyManagementServiceBlockingStub stubWithBearer(String token) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + token);
        return KeyManagementServiceGrpc.newBlockingStub(channel)
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

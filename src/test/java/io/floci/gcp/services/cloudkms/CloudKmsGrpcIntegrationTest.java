package io.floci.gcp.services.cloudkms;

import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.CreateCryptoKeyRequest;
import com.google.cloud.kms.v1.CreateKeyRingRequest;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.CryptoKeyVersion;
import com.google.cloud.kms.v1.CryptoKeyVersionTemplate;
import com.google.cloud.kms.v1.DecryptRequest;
import com.google.cloud.kms.v1.EncryptRequest;
import com.google.cloud.kms.v1.KeyManagementServiceGrpc;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cloud KMS over gRPC on the shared port. */
@QuarkusTest
class CloudKmsGrpcIntegrationTest {

    private static final String LOCATION = "projects/kms-crc-grpc-it/locations/global";

    @TestHTTPResource
    URI endpoint;

    private ManagedChannel channel;
    private KeyManagementServiceGrpc.KeyManagementServiceBlockingStub kms;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        kms = KeyManagementServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void close() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private static long crc32c(ByteString data) {
        CRC32C crc = new CRC32C();
        crc.update(data.toByteArray());
        return crc.getValue();
    }

    @Test
    void decryptVerifiesCiphertextAndAadChecksums() {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("ring").build());
        String key = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/ring").setCryptoKeyId("decrypt-crc")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT))
                .build()).getName();
        ByteString aad = ByteString.copyFrom("context", StandardCharsets.UTF_8);
        ByteString ciphertext = kms.encrypt(EncryptRequest.newBuilder().setName(key)
                .setPlaintext(ByteString.copyFrom("hello", StandardCharsets.UTF_8))
                .setAdditionalAuthenticatedData(aad).build()).getCiphertext();
        DecryptRequest decrypt = DecryptRequest.newBuilder().setName(key).setCiphertext(ciphertext)
                .setAdditionalAuthenticatedData(aad)
                .setCiphertextCrc32C(Int64Value.of(crc32c(ciphertext)))
                .setAdditionalAuthenticatedDataCrc32C(Int64Value.of(crc32c(aad))).build();

        assertEquals("hello", kms.decrypt(decrypt).getPlaintext().toStringUtf8());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.decrypt(decrypt.toBuilder().setCiphertextCrc32C(Int64Value.of(1)).build()))
                .getStatus().getCode());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.decrypt(decrypt.toBuilder().setAdditionalAuthenticatedDataCrc32C(Int64Value.of(1)).build()))
                .getStatus().getCode());
    }

    @Test
    void asymmetricSignVerifiesTheDataChecksum() {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("sign-ring").build());
        String key = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/sign-ring").setCryptoKeyId("sign-data")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256)))
                .build()).getName();
        ByteString data = ByteString.copyFrom("payload", StandardCharsets.UTF_8);
        AsymmetricSignRequest sign = AsymmetricSignRequest.newBuilder().setName(key + "/cryptoKeyVersions/1")
                .setData(data).setDataCrc32C(Int64Value.of(crc32c(data))).build();

        assertTrue(kms.asymmetricSign(sign).getVerifiedDataCrc32C());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.asymmetricSign(sign.toBuilder().setDataCrc32C(Int64Value.of(1)).build()))
                .getStatus().getCode());
    }
}

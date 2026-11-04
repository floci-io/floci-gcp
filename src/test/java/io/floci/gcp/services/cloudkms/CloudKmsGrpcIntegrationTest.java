package io.floci.gcp.services.cloudkms;

import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.CreateCryptoKeyRequest;
import com.google.cloud.kms.v1.CreateKeyRingRequest;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.CryptoKeyVersion;
import com.google.cloud.kms.v1.CryptoKeyVersionTemplate;
import com.google.cloud.kms.v1.DecryptRequest;
import com.google.cloud.kms.v1.Digest;
import com.google.cloud.kms.v1.EncryptRequest;
import com.google.cloud.kms.v1.GetCryptoKeyRequest;
import com.google.cloud.kms.v1.GetPublicKeyRequest;
import com.google.cloud.kms.v1.KeyManagementServiceGrpc;
import com.google.cloud.kms.v1.MacSignRequest;
import com.google.cloud.kms.v1.MacSignResponse;
import com.google.cloud.kms.v1.MacVerifyRequest;
import com.google.cloud.kms.v1.MacVerifyResponse;
import com.google.cloud.kms.v1.ProtectionLevel;
import com.google.cloud.kms.v1.UpdateCryptoKeyRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Int64Value;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
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
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void asymmetricSignRejectsDigestAndDataTogether() throws Exception {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("sign-both-ring").build());
        String key = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/sign-both-ring").setCryptoKeyId("sign-both")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256)))
                .build()).getName();
        ByteString data = ByteString.copyFrom("payload", StandardCharsets.UTF_8);
        ByteString sha256 = ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(data.toByteArray()));
        AsymmetricSignRequest sign = AsymmetricSignRequest.newBuilder().setName(key + "/cryptoKeyVersions/1")
                .setDigest(Digest.newBuilder().setSha256(sha256)).setData(data).build();

        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.asymmetricSign(sign)).getStatus().getCode());
    }

    @Test
    void asymmetricSignRejectsSha384DigestAndDataTogether() throws Exception {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("sign-both-384-ring").build());
        String key = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/sign-both-384-ring").setCryptoKeyId("sign-both-384")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256)))
                .build()).getName();
        ByteString data = ByteString.copyFrom("payload", StandardCharsets.UTF_8);
        ByteString sha384 = ByteString.copyFrom(MessageDigest.getInstance("SHA-384").digest(data.toByteArray()));
        AsymmetricSignRequest sign = AsymmetricSignRequest.newBuilder().setName(key + "/cryptoKeyVersions/1")
                .setDigest(Digest.newBuilder().setSha384(sha384)).setData(data).build();

        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.asymmetricSign(sign)).getStatus().getCode());
    }

    @Test
    void asymmetricSignTakesASha512DigestForASha512Key() throws Exception {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("sign-512-ring").build());
        String key = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/sign-512-ring").setCryptoKeyId("pss-512")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.RSA_SIGN_PSS_4096_SHA512)))
                .build()).getName();
        String version = key + "/cryptoKeyVersions/1";
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        ByteString sha512 = ByteString.copyFrom(MessageDigest.getInstance("SHA-512").digest(data));

        ByteString signature = kms.asymmetricSign(AsymmetricSignRequest.newBuilder().setName(version)
                .setDigest(Digest.newBuilder().setSha512(sha512))
                .setDigestCrc32C(Int64Value.of(crc32c(sha512))).build()).getSignature();
        String pem = kms.getPublicKey(GetPublicKeyRequest.newBuilder().setName(version).build()).getPem();
        Signature verifier = Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64,
                PSSParameterSpec.TRAILER_FIELD_BC));
        verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "")))));
        verifier.update(data);
        assertTrue(verifier.verify(signature.toByteArray()), "the signature covers the SHA-512 digest");

        ByteString sha256 = ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(data));
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.asymmetricSign(AsymmetricSignRequest.newBuilder().setName(version)
                        .setDigest(Digest.newBuilder().setSha256(sha256)).build())).getStatus().getCode());
    }

    @Test
    void macSignAndMacVerifyRoundTripWithChecksums() {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("mac-ring").build());
        CryptoKey key = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/mac-ring").setCryptoKeyId("mac")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.MAC)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.HMAC_SHA256)))
                .build());
        assertEquals(CryptoKey.CryptoKeyPurpose.MAC, key.getPurpose());
        assertEquals(CryptoKeyVersion.CryptoKeyVersionAlgorithm.HMAC_SHA256, key.getVersionTemplate().getAlgorithm());
        String version = key.getName() + "/cryptoKeyVersions/1";
        ByteString data = ByteString.copyFrom("payload", StandardCharsets.UTF_8);

        MacSignResponse signed = kms.macSign(MacSignRequest.newBuilder().setName(version)
                .setData(data).setDataCrc32C(Int64Value.of(crc32c(data))).build());
        assertEquals(version, signed.getName());
        assertEquals(32, signed.getMac().size());
        assertEquals(crc32c(signed.getMac()), signed.getMacCrc32C().getValue());
        assertTrue(signed.getVerifiedDataCrc32C());
        assertEquals(ProtectionLevel.SOFTWARE, signed.getProtectionLevel());

        MacVerifyRequest verify = MacVerifyRequest.newBuilder().setName(version).setData(data)
                .setDataCrc32C(Int64Value.of(crc32c(data))).setMac(signed.getMac())
                .setMacCrc32C(Int64Value.of(crc32c(signed.getMac()))).build();
        MacVerifyResponse verified = kms.macVerify(verify);
        assertTrue(verified.getSuccess());
        assertTrue(verified.getVerifiedSuccessIntegrity());
        assertTrue(verified.getVerifiedDataCrc32C());
        assertTrue(verified.getVerifiedMacCrc32C());
        assertEquals(version, verified.getName());
        assertEquals(ProtectionLevel.SOFTWARE, verified.getProtectionLevel());

        ByteString otherData = ByteString.copyFrom("other", StandardCharsets.UTF_8);
        MacVerifyResponse rejected = kms.macVerify(MacVerifyRequest.newBuilder().setName(version)
                .setData(otherData).setMac(signed.getMac()).build());
        assertFalse(rejected.getSuccess());
        assertFalse(rejected.getVerifiedSuccessIntegrity());
        assertFalse(rejected.getVerifiedDataCrc32C());

        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.macSign(MacSignRequest.newBuilder().setName(version).setData(data)
                        .setDataCrc32C(Int64Value.of(1)).build())).getStatus().getCode());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.macVerify(verify.toBuilder().setMacCrc32C(Int64Value.of(1)).build()))
                .getStatus().getCode());
    }

    @Test
    void createStoresTheRotationScheduleDestroyDurationAndLabels() {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("fields-ring").build());
        CryptoKey created = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/fields-ring").setCryptoKeyId("scheduled")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT)
                        .setRotationPeriod(Durations.fromDays(90))
                        .setNextRotationTime(Timestamps.parseUnchecked("2027-01-01T00:00:00Z"))
                        .setDestroyScheduledDuration(Durations.fromDays(7))
                        .putLabels("team", "repro"))
                .build());

        for (CryptoKey key : List.of(created,
                kms.getCryptoKey(GetCryptoKeyRequest.newBuilder().setName(created.getName()).build()))) {
            assertEquals(Durations.fromDays(90), key.getRotationPeriod());
            assertEquals(Timestamps.parseUnchecked("2027-01-01T00:00:00Z"), key.getNextRotationTime());
            assertEquals(Durations.fromDays(7), key.getDestroyScheduledDuration());
            assertEquals(Map.of("team", "repro"), key.getLabelsMap());
        }

        CryptoKey plain = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/fields-ring").setCryptoKeyId("plain")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT))
                .build());
        assertEquals(Durations.fromDays(30), plain.getDestroyScheduledDuration());
        assertFalse(plain.hasRotationPeriod());
        assertFalse(plain.hasNextRotationTime());
    }

    @Test
    void createRejectsRotationOnAKeyThatCannotRotate() {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("bad-ring").build());
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                        .setParent(LOCATION + "/keyRings/bad-ring").setCryptoKeyId("sign")
                        .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN)
                                .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder().setAlgorithm(
                                        CryptoKeyVersion.CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256))
                                .setRotationPeriod(Durations.fromDays(30))
                                .setNextRotationTime(Timestamps.parseUnchecked("2027-01-01T00:00:00Z")))
                        .build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
    }

    @Test
    void updateAppliesTheMaskedRotationFields() {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("update-ring").build());
        CryptoKey created = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/update-ring").setCryptoKeyId("rotate")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT)
                        .putLabels("team", "a"))
                .build());

        CryptoKey updated = kms.updateCryptoKey(UpdateCryptoKeyRequest.newBuilder()
                .setCryptoKey(CryptoKey.newBuilder().setName(created.getName())
                        .setRotationPeriod(Durations.fromDays(2))
                        .setNextRotationTime(Timestamps.parseUnchecked("2027-02-01T00:00:00Z")))
                .setUpdateMask(FieldMask.newBuilder().addPaths("rotation_period").addPaths("next_rotation_time"))
                .build());
        assertEquals(Durations.fromDays(2), updated.getRotationPeriod());
        assertEquals(Timestamps.parseUnchecked("2027-02-01T00:00:00Z"), updated.getNextRotationTime());
        assertEquals(Map.of("team", "a"), updated.getLabelsMap());

        CryptoKey cleared = kms.updateCryptoKey(UpdateCryptoKeyRequest.newBuilder()
                .setCryptoKey(CryptoKey.newBuilder().setName(created.getName()))
                .setUpdateMask(FieldMask.newBuilder().addPaths("rotation_period").addPaths("next_rotation_time"))
                .build());
        assertFalse(cleared.hasRotationPeriod());
        assertFalse(cleared.hasNextRotationTime());
    }
}

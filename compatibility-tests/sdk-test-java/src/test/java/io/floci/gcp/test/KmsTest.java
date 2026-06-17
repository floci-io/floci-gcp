package io.floci.gcp.test;

import com.google.cloud.kms.v1.*;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KmsTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String LOCATION = "us-central1";
    private static final String KEY_RING_ID = TestFixtures.uniqueName("test-kr");

    private static KeyManagementServiceClient client;

    @BeforeAll
    static void setUp() throws IOException {
        client = TestFixtures.kmsClient();
        client.createKeyRing(LocationName.of(PROJECT_ID, LOCATION), KEY_RING_ID,
                KeyRing.newBuilder().build());
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private static KeyRingName keyRing() {
        return KeyRingName.of(PROJECT_ID, LOCATION, KEY_RING_ID);
    }

    @Test
    @Order(1)
    void keyRingCreated() {
        KeyRing kr = client.getKeyRing(keyRing());
        assertThat(kr.getName()).endsWith(KEY_RING_ID);
    }

    @Test
    @Order(2)
    void symmetricEncryptDecryptRoundTrip() {
        CryptoKey key = client.createCryptoKey(keyRing(), TestFixtures.uniqueName("sym"),
                CryptoKey.newBuilder()
                        .setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT)
                        .build());
        CryptoKeyName name = CryptoKeyName.parse(key.getName());

        ByteString plaintext = ByteString.copyFromUtf8("envelope-encryption-payload");
        EncryptResponse enc = client.encrypt(name, plaintext);
        assertThat(enc.getName()).contains("cryptoKeyVersions/1");
        assertThat(enc.getCiphertext()).isNotEqualTo(plaintext);

        DecryptResponse dec = client.decrypt(name, enc.getCiphertext());
        assertThat(dec.getPlaintext()).isEqualTo(plaintext);
        assertThat(dec.getUsedPrimary()).isTrue();
    }

    @Test
    @Order(3)
    void rotatePrimaryVersion() {
        CryptoKey key = client.createCryptoKey(keyRing(), TestFixtures.uniqueName("rot"),
                CryptoKey.newBuilder()
                        .setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT)
                        .build());
        CryptoKeyName name = CryptoKeyName.parse(key.getName());

        CryptoKeyVersion v2 = client.createCryptoKeyVersion(name, CryptoKeyVersion.newBuilder().build());
        client.updateCryptoKeyPrimaryVersion(name.toString(),
                v2.getName().substring(v2.getName().lastIndexOf('/') + 1));

        EncryptResponse enc = client.encrypt(name, ByteString.copyFromUtf8("data"));
        assertThat(enc.getName()).endsWith("cryptoKeyVersions/2");
    }

    @Test
    @Order(4)
    void decryptWithWrongKeyFails() {
        CryptoKey k1 = client.createCryptoKey(keyRing(), TestFixtures.uniqueName("a"),
                CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT).build());
        CryptoKey k2 = client.createCryptoKey(keyRing(), TestFixtures.uniqueName("b"),
                CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT).build());

        EncryptResponse enc = client.encrypt(CryptoKeyName.parse(k1.getName()),
                ByteString.copyFromUtf8("secret"));

        Throwable thrown = catchThrowable(() ->
                client.decrypt(CryptoKeyName.parse(k2.getName()), enc.getCiphertext()));
        assertThat(thrown).isNotNull();
    }

    @Test
    @Order(5)
    void ecSignAndVerifyWithPublicKey() throws Exception {
        CryptoKey key = client.createCryptoKey(keyRing(), TestFixtures.uniqueName("ec"),
                CryptoKey.newBuilder()
                        .setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256)
                                .build())
                        .build());
        CryptoKeyVersionName versionName = CryptoKeyVersionName.parse(key.getName() + "/cryptoKeyVersions/1");

        byte[] data = "ecdsa-message".getBytes();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        AsymmetricSignResponse signResponse = client.asymmetricSign(versionName,
                Digest.newBuilder().setSha256(ByteString.copyFrom(digest)).build());

        PublicKey publicKey = parsePem(client.getPublicKey(versionName).getPem(), "EC");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(data);
        assertThat(verifier.verify(signResponse.getSignature().toByteArray())).isTrue();
    }

    @Test
    @Order(6)
    void rsaPkcs1SignAndVerifyWithPublicKey() throws Exception {
        CryptoKey key = client.createCryptoKey(keyRing(), TestFixtures.uniqueName("rsasign"),
                CryptoKey.newBuilder()
                        .setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.RSA_SIGN_PKCS1_2048_SHA256)
                                .build())
                        .build());
        CryptoKeyVersionName versionName = CryptoKeyVersionName.parse(key.getName() + "/cryptoKeyVersions/1");

        byte[] data = "rsa-message".getBytes();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        AsymmetricSignResponse signResponse = client.asymmetricSign(versionName,
                Digest.newBuilder().setSha256(ByteString.copyFrom(digest)).build());

        PublicKey publicKey = parsePem(client.getPublicKey(versionName).getPem(), "RSA");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(data);
        assertThat(verifier.verify(signResponse.getSignature().toByteArray())).isTrue();
    }

    @Test
    @Order(7)
    void rsaOaepAsymmetricDecryptRoundTrip() throws Exception {
        CryptoKey key = client.createCryptoKey(keyRing(), TestFixtures.uniqueName("rsadec"),
                CryptoKey.newBuilder()
                        .setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_DECRYPT)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.RSA_DECRYPT_OAEP_2048_SHA256)
                                .build())
                        .build());
        CryptoKeyVersionName versionName = CryptoKeyVersionName.parse(key.getName() + "/cryptoKeyVersions/1");

        PublicKey publicKey = parsePem(client.getPublicKey(versionName).getPem(), "RSA");
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] plaintext = "asymmetric-secret".getBytes();
        ByteString ciphertext = ByteString.copyFrom(cipher.doFinal(plaintext));

        AsymmetricDecryptResponse response = client.asymmetricDecrypt(versionName, ciphertext);
        assertThat(response.getPlaintext().toByteArray()).isEqualTo(plaintext);
    }

    @Test
    @Order(8)
    void generateRandomBytes() {
        GenerateRandomBytesResponse response = client.generateRandomBytes(
                LocationName.of(PROJECT_ID, LOCATION).toString(), 32, ProtectionLevel.SOFTWARE);
        assertThat(response.getData().size()).isEqualTo(32);
    }

    private static PublicKey parsePem(String pem, String algorithm) throws Exception {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der));
    }
}

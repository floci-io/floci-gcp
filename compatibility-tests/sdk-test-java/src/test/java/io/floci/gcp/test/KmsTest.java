package io.floci.gcp.test;

import com.google.cloud.kms.v1.*;
import com.google.cloud.location.GetLocationRequest;
import com.google.cloud.location.ListLocationsRequest;
import com.google.cloud.location.Location;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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

    @Test
    @Order(9)
    void listAndGetLocations() {
        List<String> ids = new ArrayList<>();
        client.listLocations(ListLocationsRequest.newBuilder()
                        .setName("projects/" + PROJECT_ID).build())
                .iterateAll().forEach(location -> ids.add(location.getLocationId()));
        // 43 catalog regions plus KMS's global and three multi-regions: gRPC carries no API identity.
        assertThat(ids).contains("us-central1", "europe-west1", "global", "us", "europe", "asia").hasSize(47);

        Location location = client.getLocation(
                GetLocationRequest.newBuilder()
                        .setName("projects/" + PROJECT_ID + "/locations/" + LOCATION).build());
        assertThat(location.getLocationId()).isEqualTo(LOCATION);
        assertThat(location.getLabelsMap()).containsEntry("cloud.googleapis.com/region", LOCATION);

        Location global = client.getLocation(
                GetLocationRequest.newBuilder()
                        .setName("projects/" + PROJECT_ID + "/locations/global").build());
        assertThat(global.getLocationId()).isEqualTo("global");
    }

    @Test
    @Order(10)
    void cryptoKeyKeepsItsRotationScheduleDestroyDurationAndLabels() {
        Duration ninetyDays = Duration.newBuilder().setSeconds(7_776_000).build();
        Timestamp nextRotation = Timestamp.newBuilder().setSeconds(1_798_761_600).build(); // 2027-01-01T00:00:00Z
        Duration sevenDays = Duration.newBuilder().setSeconds(604_800).build();
        CryptoKey created = client.createCryptoKey(keyRing(), TestFixtures.uniqueName("sched"),
                CryptoKey.newBuilder()
                        .setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT)
                        .setRotationPeriod(ninetyDays)
                        .setNextRotationTime(nextRotation)
                        .setDestroyScheduledDuration(sevenDays)
                        .putLabels("team", "compat")
                        .build());

        CryptoKey fetched = client.getCryptoKey(CryptoKeyName.parse(created.getName()));
        assertThat(fetched.getRotationPeriod()).isEqualTo(ninetyDays);
        assertThat(fetched.getNextRotationTime()).isEqualTo(nextRotation);
        assertThat(fetched.getDestroyScheduledDuration()).isEqualTo(sevenDays);
        assertThat(fetched.getLabelsMap()).containsEntry("team", "compat");

        CryptoKey updated = client.updateCryptoKey(
                CryptoKey.newBuilder().setName(created.getName())
                        .setRotationPeriod(Duration.newBuilder().setSeconds(172_800))
                        .setNextRotationTime(nextRotation)
                        .build(),
                FieldMask.newBuilder().addPaths("rotation_period").addPaths("next_rotation_time").build());
        assertThat(updated.getRotationPeriod().getSeconds()).isEqualTo(172_800);
        assertThat(updated.getDestroyScheduledDuration()).isEqualTo(sevenDays);
        assertThat(updated.getLabelsMap()).containsEntry("team", "compat");

        CryptoKey plain = client.createCryptoKey(keyRing(), TestFixtures.uniqueName("plain"),
                CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT).build());
        assertThat(plain.getDestroyScheduledDuration().getSeconds()).isEqualTo(2_592_000);
        assertThat(plain.hasRotationPeriod()).isFalse();
    }

    @Test
    @Order(11)
    void importJobWrapsSymmetricKeyForImportOnlyKey() throws Exception {
        ImportJob job = client.createImportJob(keyRing(), TestFixtures.uniqueName("job"),
                ImportJob.newBuilder()
                        .setImportMethod(ImportJob.ImportMethod.RSA_OAEP_3072_SHA1_AES_256)
                        .setProtectionLevel(ProtectionLevel.SOFTWARE)
                        .build());
        assertThat(job.getState()).isEqualTo(ImportJob.ImportJobState.ACTIVE);
        assertThat(client.getImportJob(job.getName()).getPublicKey().getPem()).startsWith("-----BEGIN PUBLIC KEY-----");

        CryptoKey key = client.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(keyRing().toString())
                .setCryptoKeyId(TestFixtures.uniqueName("imported-sym"))
                .setCryptoKey(CryptoKey.newBuilder()
                        .setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT)
                        .setImportOnly(true))
                .setSkipInitialVersionCreation(true)
                .build());
        assertThat(key.getImportOnly()).isTrue();

        byte[] aesKey = KeyGenerator.getInstance("AES").generateKey().getEncoded();
        CryptoKeyVersion version = client.importCryptoKeyVersion(ImportCryptoKeyVersionRequest.newBuilder()
                .setParent(key.getName())
                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.GOOGLE_SYMMETRIC_ENCRYPTION)
                .setImportJob(job.getName())
                .setWrappedKey(ByteString.copyFrom(rsaAesWrap(job, aesKey)))
                .build());
        assertThat(version.getState()).isEqualTo(CryptoKeyVersion.CryptoKeyVersionState.ENABLED);
        assertThat(version.getImportJob()).isEqualTo(job.getName());

        CryptoKeyName name = CryptoKeyName.parse(key.getName());
        ByteString plaintext = ByteString.copyFromUtf8("imported-key-payload");
        DecryptResponse dec = client.decrypt(name, client.encrypt(name, plaintext).getCiphertext());
        assertThat(dec.getPlaintext()).isEqualTo(plaintext);
    }

    @Test
    @Order(12)
    void importedEcKeySignsForOriginalPublicKey() throws Exception {
        ImportJob job = client.createImportJob(keyRing(), TestFixtures.uniqueName("job"),
                ImportJob.newBuilder()
                        .setImportMethod(ImportJob.ImportMethod.RSA_OAEP_4096_SHA256_AES_256)
                        .setProtectionLevel(ProtectionLevel.SOFTWARE)
                        .build());
        CryptoKey key = client.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(keyRing().toString())
                .setCryptoKeyId(TestFixtures.uniqueName("imported-ec"))
                .setCryptoKey(CryptoKey.newBuilder()
                        .setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN)
                        .setImportOnly(true)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256)))
                .setSkipInitialVersionCreation(true)
                .build());

        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = gen.generateKeyPair();
        CryptoKeyVersion version = client.importCryptoKeyVersion(ImportCryptoKeyVersionRequest.newBuilder()
                .setParent(key.getName())
                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256)
                .setImportJob(job.getName())
                .setWrappedKey(ByteString.copyFrom(rsaAesWrap(job, pair.getPrivate().getEncoded())))
                .build());
        assertThat(version.getState()).isEqualTo(CryptoKeyVersion.CryptoKeyVersionState.ENABLED);

        byte[] data = "signed-by-imported-key".getBytes();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        AsymmetricSignResponse signed = client.asymmetricSign(CryptoKeyVersionName.parse(version.getName()),
                Digest.newBuilder().setSha256(ByteString.copyFrom(digest)).build());
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pair.getPublic());
        verifier.update(data);
        assertThat(verifier.verify(signed.getSignature().toByteArray())).isTrue();
    }

    /** Client-side wrapping for the {@code *_AES_256} import methods, as the GCP import docs describe it. */
    private static byte[] rsaAesWrap(ImportJob job, byte[] targetKey) throws Exception {
        boolean sha1 = job.getImportMethod().name().contains("_SHA1_");
        MGF1ParameterSpec mgf1 = sha1 ? MGF1ParameterSpec.SHA1 : MGF1ParameterSpec.SHA256;
        byte[] ephemeral = KeyGenerator.getInstance("AES").generateKey().getEncoded();
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, parsePem(job.getPublicKey().getPem(), "RSA"),
                new OAEPParameterSpec(sha1 ? "SHA-1" : "SHA-256", "MGF1", mgf1, PSource.PSpecified.DEFAULT));
        byte[] wrappedEphemeral = rsa.doFinal(ephemeral);
        Cipher kwp = Cipher.getInstance("AES/KWP/NoPadding");
        kwp.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ephemeral, "AES"));
        byte[] wrappedTarget = kwp.doFinal(targetKey);
        byte[] out = new byte[wrappedEphemeral.length + wrappedTarget.length];
        System.arraycopy(wrappedEphemeral, 0, out, 0, wrappedEphemeral.length);
        System.arraycopy(wrappedTarget, 0, out, wrappedEphemeral.length, wrappedTarget.length);
        return out;
    }

    private static PublicKey parsePem(String pem, String algorithm) throws Exception {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der));
    }
}

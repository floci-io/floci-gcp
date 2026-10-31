package io.floci.gcp.services.cloudkms;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKey;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKeyVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

class CloudKmsServiceTest {

    private static final String LOCATION = "projects/p1/locations/us-central1";

    private CloudKmsService service;

    @BeforeEach
    void setUp() {
        service = new CloudKmsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>());
    }

    private StoredCryptoKey symmetricKey(String id) {
        service.createKeyRing(LOCATION, "kr");
        return service.createCryptoKey(LOCATION + "/keyRings/kr", id, "ENCRYPT_DECRYPT", null, false);
    }

    @Test
    void createKeyRingDuplicateThrowsAlreadyExists() {
        service.createKeyRing(LOCATION, "kr");
        GcpException ex = assertThrows(GcpException.class, () -> service.createKeyRing(LOCATION, "kr"));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
    }

    @Test
    void createSymmetricKeyAutoCreatesPrimaryVersion() {
        StoredCryptoKey key = symmetricKey("k1");
        assertEquals("GOOGLE_SYMMETRIC_ENCRYPTION", key.getAlgorithm());
        assertEquals(Integer.valueOf(1), key.getPrimaryVersion());
        assertEquals(1, service.listCryptoKeyVersions(key.getName()).size());
    }

    @Test
    void encryptDecryptRoundTrip() {
        StoredCryptoKey key = symmetricKey("k1");
        byte[] plaintext = "hello kms".getBytes(StandardCharsets.UTF_8);

        CloudKmsService.EncryptResult enc = service.encrypt(key.getName(), plaintext, new byte[0]);
        assertEquals(key.getName() + "/cryptoKeyVersions/1", enc.versionName());

        CloudKmsService.DecryptResult dec = service.decrypt(key.getName(), enc.ciphertext(), new byte[0]);
        assertArrayEquals(plaintext, dec.plaintext());
        assertTrue(dec.usedPrimary());
    }

    @Test
    void encryptDecryptWithAadRoundTrip() {
        StoredCryptoKey key = symmetricKey("k1");
        byte[] plaintext = "data".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "context".getBytes(StandardCharsets.UTF_8);

        CloudKmsService.EncryptResult enc = service.encrypt(key.getName(), plaintext, aad);
        assertArrayEquals(plaintext, service.decrypt(key.getName(), enc.ciphertext(), aad).plaintext());

        GcpException ex = assertThrows(GcpException.class,
                () -> service.decrypt(key.getName(), enc.ciphertext(), "wrong".getBytes(StandardCharsets.UTF_8)));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void decryptWithDifferentKeyFails() {
        StoredCryptoKey k1 = symmetricKey("k1");
        StoredCryptoKey k2 = service.createCryptoKey(
                LOCATION + "/keyRings/kr", "k2", "ENCRYPT_DECRYPT", null, false);
        CloudKmsService.EncryptResult enc = service.encrypt(k1.getName(), "x".getBytes(StandardCharsets.UTF_8), new byte[0]);

        GcpException ex = assertThrows(GcpException.class,
                () -> service.decrypt(k2.getName(), enc.ciphertext(), new byte[0]));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void rotatePrimaryVersionAffectsEncrypt() {
        StoredCryptoKey key = symmetricKey("k1");
        StoredCryptoKeyVersion v2 = service.createCryptoKeyVersion(key.getName());
        service.updateCryptoKeyPrimaryVersion(key.getName(), String.valueOf(v2.getVersionNumber()));

        CloudKmsService.EncryptResult enc = service.encrypt(key.getName(), "z".getBytes(StandardCharsets.UTF_8), new byte[0]);
        assertEquals(key.getName() + "/cryptoKeyVersions/2", enc.versionName());
        assertTrue(service.decrypt(key.getName(), enc.ciphertext(), new byte[0]).usedPrimary());
    }

    @Test
    void disabledVersionRejectsEncrypt() {
        StoredCryptoKey key = symmetricKey("k1");
        service.updateCryptoKeyVersionState(key.getName() + "/cryptoKeyVersions/1", "DISABLED");
        GcpException ex = assertThrows(GcpException.class,
                () -> service.encrypt(key.getName(), "x".getBytes(StandardCharsets.UTF_8), new byte[0]));
        assertEquals("FAILED_PRECONDITION", ex.getGcpStatus());
    }

    @Test
    void destroyThenRestoreVersion() {
        StoredCryptoKey key = symmetricKey("k1");
        String versionName = key.getName() + "/cryptoKeyVersions/1";
        assertEquals("DESTROY_SCHEDULED", service.destroyCryptoKeyVersion(versionName).getState());
        assertEquals("DISABLED", service.restoreCryptoKeyVersion(versionName).getState());
    }

    @Test
    void ecSignVerifiesWithPublicKey() throws Exception {
        service.createKeyRing(LOCATION, "kr");
        StoredCryptoKey key = service.createCryptoKey(
                LOCATION + "/keyRings/kr", "ec", "ASYMMETRIC_SIGN", "EC_SIGN_P256_SHA256", false);
        String versionName = key.getName() + "/cryptoKeyVersions/1";

        byte[] data = "message-to-sign".getBytes(StandardCharsets.UTF_8);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
        byte[] signature = service.asymmetricSign(versionName, digest);

        PublicKey publicKey = publicKeyFrom(service.getPublicKeyVersion(versionName), "EC");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(data);
        assertTrue(verifier.verify(signature));
    }

    @Test
    void rsaPkcs1SignVerifiesWithPublicKey() throws Exception {
        service.createKeyRing(LOCATION, "kr");
        StoredCryptoKey key = service.createCryptoKey(
                LOCATION + "/keyRings/kr", "rsa", "ASYMMETRIC_SIGN", "RSA_SIGN_PKCS1_2048_SHA256", false);
        String versionName = key.getName() + "/cryptoKeyVersions/1";

        byte[] data = "rsa-message".getBytes(StandardCharsets.UTF_8);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
        byte[] signature = service.asymmetricSign(versionName, digest);

        PublicKey publicKey = publicKeyFrom(service.getPublicKeyVersion(versionName), "RSA");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(data);
        assertTrue(verifier.verify(signature));
    }

    @Test
    void rsaOaepAsymmetricDecryptRoundTrip() throws Exception {
        service.createKeyRing(LOCATION, "kr");
        StoredCryptoKey key = service.createCryptoKey(
                LOCATION + "/keyRings/kr", "rsadec", "ASYMMETRIC_DECRYPT", "RSA_DECRYPT_OAEP_2048_SHA256", false);
        String versionName = key.getName() + "/cryptoKeyVersions/1";

        PublicKey publicKey = publicKeyFrom(service.getPublicKeyVersion(versionName), "RSA");
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, new javax.crypto.spec.OAEPParameterSpec(
                "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256,
                javax.crypto.spec.PSource.PSpecified.DEFAULT));
        byte[] plaintext = "secret-payload".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = cipher.doFinal(plaintext);

        assertArrayEquals(plaintext, service.asymmetricDecrypt(versionName, ciphertext));
    }

    @Test
    void generateRandomBytesRespectsBounds() {
        assertEquals(32, service.generateRandomBytes(32).length);
        assertThrows(GcpException.class, () -> service.generateRandomBytes(4));
        assertThrows(GcpException.class, () -> service.generateRandomBytes(2048));
    }

    @Test
    void getPublicKeyRejectsSymmetricKey() {
        StoredCryptoKey key = symmetricKey("k1");
        GcpException ex = assertThrows(GcpException.class,
                () -> service.getPublicKeyVersion(key.getName() + "/cryptoKeyVersions/1"));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void macSignMatchesAnHmacOverTheVersionKeyForEveryAlgorithm() throws Exception {
        service.createKeyRing(LOCATION, "kr");
        Map<String, String> jcaNames = Map.of("HMAC_SHA256", "HmacSHA256", "HMAC_SHA1", "HmacSHA1",
                "HMAC_SHA384", "HmacSHA384", "HMAC_SHA512", "HmacSHA512", "HMAC_SHA224", "HmacSHA224");
        Map<String, Integer> keyBytes = Map.of("HMAC_SHA256", 32, "HMAC_SHA1", 20,
                "HMAC_SHA384", 48, "HMAC_SHA512", 64, "HMAC_SHA224", 28);
        byte[] data = "message-to-mac".getBytes(StandardCharsets.UTF_8);
        for (String algorithm : jcaNames.keySet()) {
            StoredCryptoKey key = service.createCryptoKey(LOCATION + "/keyRings/kr", algorithm.toLowerCase(),
                    "MAC", algorithm, false);
            String versionName = key.getName() + "/cryptoKeyVersions/1";
            byte[] material = Base64.getDecoder().decode(service.getCryptoKeyVersion(versionName).getKeyMaterialBase64());

            Mac expected = Mac.getInstance(jcaNames.get(algorithm));
            expected.init(new SecretKeySpec(material, jcaNames.get(algorithm)));

            assertEquals(keyBytes.get(algorithm), material.length, algorithm);
            assertArrayEquals(expected.doFinal(data), service.macSign(versionName, data), algorithm);
            assertNull(key.getPrimaryVersion(), algorithm);
        }
    }

    @Test
    void macVerifyAcceptsOnlyTheMacOfTheSameDataAndVersion() {
        service.createKeyRing(LOCATION, "kr");
        StoredCryptoKey key = service.createCryptoKey(LOCATION + "/keyRings/kr", "mac", "MAC", "HMAC_SHA256", false);
        String v1 = key.getName() + "/cryptoKeyVersions/1";
        String v2 = service.createCryptoKeyVersion(key.getName()).getName();
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] mac = service.macSign(v1, data);

        assertTrue(service.macVerify(v1, data, mac));
        assertFalse(service.macVerify(v1, "payload!".getBytes(StandardCharsets.UTF_8), mac));
        assertFalse(service.macVerify(v2, data, mac));
        assertFalse(service.macVerify(v1, data, Arrays.copyOf(mac, mac.length - 1)));
        assertFalse(service.macVerify(v1, data, new byte[0]));
    }

    @Test
    void macKeysNeedAnHmacAlgorithmAndOnlyMacKeysMac() {
        String symmetricVersion = symmetricKey("sym").getName() + "/cryptoKeyVersions/1";
        String ring = LOCATION + "/keyRings/kr";
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> service.createCryptoKey(ring, "no-alg", "MAC", null, false)).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> service.createCryptoKey(ring, "wrong-alg", "MAC", "EC_SIGN_P256_SHA256", false)).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> service.createCryptoKey(ring, "sign-hmac", "ASYMMETRIC_SIGN", "HMAC_SHA256", false)).getGcpStatus());

        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> service.macSign(symmetricVersion, new byte[] {1})).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> service.macVerify(symmetricVersion, new byte[] {1}, new byte[32])).getGcpStatus());

        String macKey = service.createCryptoKey(ring, "mac", "MAC", "HMAC_SHA256", false).getName();
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> service.encrypt(macKey + "/cryptoKeyVersions/1", new byte[] {1}, null)).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> service.getPublicKeyVersion(macKey + "/cryptoKeyVersions/1")).getGcpStatus());
    }

    @Test
    void macOperationsNeedAnEnabledVersion() {
        service.createKeyRing(LOCATION, "kr");
        StoredCryptoKey key = service.createCryptoKey(LOCATION + "/keyRings/kr", "mac", "MAC", "HMAC_SHA256", false);
        String versionName = key.getName() + "/cryptoKeyVersions/1";
        byte[] mac = service.macSign(versionName, new byte[] {1});
        service.updateCryptoKeyVersionState(versionName, "DISABLED");

        assertEquals("FAILED_PRECONDITION", assertThrows(GcpException.class,
                () -> service.macSign(versionName, new byte[] {1})).getGcpStatus());
        assertEquals("FAILED_PRECONDITION", assertThrows(GcpException.class,
                () -> service.macVerify(versionName, new byte[] {1}, mac)).getGcpStatus());
    }

    private static PublicKey publicKeyFrom(StoredCryptoKeyVersion version, String algorithm) throws Exception {
        byte[] der = Base64.getDecoder().decode(version.getPublicKeyBase64());
        return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der));
    }

    @Test
    void aKeyStoredBeforeDestroyScheduledDurationExistedReportsTheDefault() {
        StoredCryptoKey legacy = new StoredCryptoKey("projects/p1/locations/l/keyRings/kr/cryptoKeys/old",
                "ENCRYPT_DECRYPT", "GOOGLE_SYMMETRIC_ENCRYPTION", "2026-01-01T00:00:00Z");
        assertNull(legacy.getDestroyScheduledDuration());
        assertEquals("2592000s", CloudKmsService.destroyScheduledDurationOf(legacy));
    }

    @Test
    void durationsAreWrittenAsProtoJson() {
        assertEquals("86400s", CloudKmsService.formatDuration(Duration.ofDays(1)));
        assertEquals("1.500s", CloudKmsService.formatDuration(Duration.ofMillis(1500)));
        assertNull(CloudKmsService.formatDuration(null));
    }

    @Test
    void updateMaskPathsAreReadInEitherCase() {
        assertEquals(Set.of("rotation_period", "next_rotation_time", "version_template.algorithm", "labels"),
                CloudKmsService.snakeCasePaths(List.of("rotationPeriod", " next_rotation_time",
                        "versionTemplate.algorithm", "labels", "")));
    }

    @Test
    void updateRejectsRotationOnANonSymmetricKeyWithoutTouchingIt() {
        service.createKeyRing(LOCATION, "kr");
        StoredCryptoKey key = service.createCryptoKey(LOCATION + "/keyRings/kr", "mac", "MAC", "HMAC_SHA256", false);
        CloudKmsService.CryptoKeyFields fields = new CloudKmsService.CryptoKeyFields(
                Duration.ofDays(1), Instant.parse("2027-01-01T00:00:00Z"), null, Map.of("team", "a"));

        GcpException ex = assertThrows(GcpException.class, () -> service.updateCryptoKey(key.getName(), fields, null,
                Set.of("labels", "rotation_period", "next_rotation_time")));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        StoredCryptoKey stored = service.getCryptoKey(key.getName());
        assertNull(stored.getLabels());
        assertNull(stored.getRotationPeriod());
    }

    @Test
    void aLabelsUpdateRacingAScheduleUpdateKeepsTheNewSchedule() throws Exception {
        StoredCryptoKey key = symmetricKey("racing");
        service.updateCryptoKey(key.getName(), new CloudKmsService.CryptoKeyFields(
                        Duration.ofDays(30), Instant.parse("2027-01-01T00:00:00Z"), null, null), null,
                Set.of("rotation_period", "next_rotation_time"));

        runLabelsUpdateAround(key.getName(), () -> service.updateCryptoKey(key.getName(),
                new CloudKmsService.CryptoKeyFields(
                        Duration.ofDays(60), Instant.parse("2028-01-01T00:00:00Z"), null, null), null,
                Set.of("rotation_period", "next_rotation_time")));

        StoredCryptoKey stored = service.getCryptoKey(key.getName());
        assertEquals(Map.of("team", "a"), stored.getLabels());
        assertEquals("5184000s", stored.getRotationPeriod());
        assertEquals("2028-01-01T00:00:00Z", stored.getNextRotationTime());
    }

    @Test
    void aLabelsUpdateRacingAnAlgorithmUpdateKeepsTheNewAlgorithm() throws Exception {
        service.createKeyRing(LOCATION, "kr");
        StoredCryptoKey key = service.createCryptoKey(LOCATION + "/keyRings/kr", "signer", "ASYMMETRIC_SIGN",
                "EC_SIGN_P256_SHA256", false);

        runLabelsUpdateAround(key.getName(), () -> service.updateCryptoKey(key.getName(),
                CloudKmsService.CryptoKeyFields.NONE, "RSA_SIGN_PKCS1_2048_SHA256",
                Set.of("version_template.algorithm")));

        StoredCryptoKey stored = service.getCryptoKey(key.getName());
        assertEquals(Map.of("team", "a"), stored.getLabels());
        assertEquals("RSA_SIGN_PKCS1_2048_SHA256", stored.getAlgorithm());
    }

    /**
     * Starts a labels-only update of the key, holds it after it has read the stored key and before it
     * writes, runs {@code concurrentUpdate} in that gap, then lets the labels update finish.
     */
    private void runLabelsUpdateAround(String name, Runnable concurrentUpdate) throws Exception {
        CountDownLatch labelsUpdateReadTheKey = new CountDownLatch(1);
        CountDownLatch concurrentUpdateDone = new CountDownLatch(1);
        Map<String, String> labels = new HashMap<>(Map.of("team", "a")) {
            @Override
            public boolean isEmpty() {
                labelsUpdateReadTheKey.countDown();
                try {
                    assertTrue(concurrentUpdateDone.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return super.isEmpty();
            }
        };
        CompletableFuture<StoredCryptoKey> labelsUpdate = CompletableFuture.supplyAsync(() ->
                service.updateCryptoKey(name,
                        new CloudKmsService.CryptoKeyFields(null, null, null, labels), null, Set.of("labels")));

        assertTrue(labelsUpdateReadTheKey.await(10, TimeUnit.SECONDS));
        concurrentUpdate.run();
        concurrentUpdateDone.countDown();
        labelsUpdate.get(10, TimeUnit.SECONDS);
    }

    @Test
    void updateIgnoresPathsItDoesNotSupport() {
        StoredCryptoKey key = symmetricKey("ignored");
        StoredCryptoKey updated = service.updateCryptoKey(key.getName(),
                new CloudKmsService.CryptoKeyFields(null, null, Duration.ofDays(1), null), null,
                Set.of("destroy_scheduled_duration", "purpose"));
        assertEquals("2592000s", updated.getDestroyScheduledDuration());
        assertEquals("ENCRYPT_DECRYPT", updated.getPurpose());
    }
}

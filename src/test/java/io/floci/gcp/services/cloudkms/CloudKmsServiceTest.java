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
import java.util.Base64;

import javax.crypto.Cipher;

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

    private static PublicKey publicKeyFrom(StoredCryptoKeyVersion version, String algorithm) throws Exception {
        byte[] der = Base64.getDecoder().decode(version.getPublicKeyBase64());
        return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der));
    }
}

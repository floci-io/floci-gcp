package io.floci.gcp.services.cloudkms;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKey;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKeyVersion;
import io.floci.gcp.services.cloudkms.model.StoredImportJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every RSA algorithm Cloud KMS offers outside the HSM-only raw PKCS#1 ones, generated and imported.
 * Signatures and ciphertexts are checked with the JCA, with the parameters GCP documents: PSS and
 * OAEP use the algorithm's hash for MGF1, and PSS a salt as long as that hash.
 */
class CloudKmsRsaServiceTest {

    private static final String KEY_RING = "projects/p1/locations/us-central1/keyRings/kr";
    private static final Map<Integer, KeyPair> KEY_PAIRS = new ConcurrentHashMap<>();

    private CloudKmsService service;

    @BeforeEach
    void setUp() {
        service = new CloudKmsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>());
        service.createKeyRing("projects/p1/locations/us-central1", "kr");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "RSA_SIGN_PSS_2048_SHA256", "RSA_SIGN_PSS_3072_SHA256", "RSA_SIGN_PSS_4096_SHA256",
            "RSA_SIGN_PSS_4096_SHA512", "RSA_SIGN_PKCS1_2048_SHA256", "RSA_SIGN_PKCS1_3072_SHA256",
            "RSA_SIGN_PKCS1_4096_SHA256", "RSA_SIGN_PKCS1_4096_SHA512"})
    void generatedSigningKeySignsDigestsAndDataForItsPublicKey(String algorithm) throws Exception {
        StoredCryptoKey key = service.createCryptoKey(KEY_RING, "k", "ASYMMETRIC_SIGN", algorithm, false);
        String version = key.getName() + "/cryptoKeyVersions/1";
        PublicKey publicKey = publicKey(version);
        byte[] data = "signed-by-kms".getBytes(StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance(hash(algorithm)).digest(data);

        assertEquals(bits(algorithm), ((RSAPublicKey) publicKey).getModulus().bitLength());
        assertTrue(verifies(algorithm, publicKey, data, service.asymmetricSign(version, hash(algorithm), digest, new byte[0])));
        assertTrue(verifies(algorithm, publicKey, data, service.asymmetricSign(version, null, new byte[0], data)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "RSA_DECRYPT_OAEP_2048_SHA256", "RSA_DECRYPT_OAEP_3072_SHA256", "RSA_DECRYPT_OAEP_4096_SHA256",
            "RSA_DECRYPT_OAEP_4096_SHA512", "RSA_DECRYPT_OAEP_2048_SHA1", "RSA_DECRYPT_OAEP_3072_SHA1",
            "RSA_DECRYPT_OAEP_4096_SHA1"})
    void generatedDecryptKeyDecryptsForItsPublicKey(String algorithm) throws Exception {
        StoredCryptoKey key = service.createCryptoKey(KEY_RING, "k", "ASYMMETRIC_DECRYPT", algorithm, false);
        String version = key.getName() + "/cryptoKeyVersions/1";
        PublicKey publicKey = publicKey(version);
        byte[] plaintext = "for-the-kms-key".getBytes(StandardCharsets.UTF_8);

        assertEquals(bits(algorithm), ((RSAPublicKey) publicKey).getModulus().bitLength());
        assertArrayEquals(plaintext, service.asymmetricDecrypt(version, rsaOaep(publicKey, hash(algorithm), plaintext)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "RSA_SIGN_PSS_2048_SHA256", "RSA_SIGN_PSS_3072_SHA256", "RSA_SIGN_PSS_4096_SHA256",
            "RSA_SIGN_PSS_4096_SHA512", "RSA_SIGN_PKCS1_3072_SHA256", "RSA_SIGN_PKCS1_4096_SHA256",
            "RSA_SIGN_PKCS1_4096_SHA512"})
    void importedSigningKeySignsForTheOriginalPublicKey(String algorithm) throws Exception {
        KeyPair pair = keyPair(bits(algorithm));
        StoredCryptoKeyVersion version = importKey("ASYMMETRIC_SIGN", algorithm, pair);
        byte[] data = "signed-by-imported-key".getBytes(StandardCharsets.UTF_8);

        assertEquals("ENABLED", version.getState());
        assertArrayEquals(pair.getPublic().getEncoded(), publicKey(version.getName()).getEncoded());
        assertTrue(verifies(algorithm, pair.getPublic(), data,
                service.asymmetricSign(version.getName(), null, new byte[0], data)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "RSA_DECRYPT_OAEP_3072_SHA256", "RSA_DECRYPT_OAEP_4096_SHA256", "RSA_DECRYPT_OAEP_4096_SHA512",
            "RSA_DECRYPT_OAEP_2048_SHA1", "RSA_DECRYPT_OAEP_3072_SHA1", "RSA_DECRYPT_OAEP_4096_SHA1"})
    void importedDecryptKeyDecryptsForTheOriginalPublicKey(String algorithm) throws Exception {
        KeyPair pair = keyPair(bits(algorithm));
        StoredCryptoKeyVersion version = importKey("ASYMMETRIC_DECRYPT", algorithm, pair);
        byte[] plaintext = "for-the-imported-key".getBytes(StandardCharsets.UTF_8);

        assertEquals("ENABLED", version.getState());
        assertArrayEquals(plaintext, service.asymmetricDecrypt(version.getName(),
                rsaOaep(pair.getPublic(), hash(algorithm), plaintext)));
    }

    @Test
    void importOfAKeyOfAnotherSizeFails() throws Exception {
        StoredCryptoKeyVersion version = importKey("ASYMMETRIC_SIGN", "RSA_SIGN_PSS_3072_SHA256", keyPair(2048));

        assertEquals("IMPORT_FAILED", version.getState());
        assertEquals("expected a 3072-bit RSA key, got 2048 bits", version.getImportFailureReason());
    }

    @Test
    void signRejectsADigestInAnotherHash() throws Exception {
        StoredCryptoKey key = service.createCryptoKey(KEY_RING, "k", "ASYMMETRIC_SIGN", "RSA_SIGN_PSS_4096_SHA512", false);
        byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(new byte[] {1});

        GcpException ex = assertThrows(GcpException.class,
                () -> service.asymmetricSign(key.getName() + "/cryptoKeyVersions/1", "SHA-256", sha256, new byte[0]));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void signRejectsADigestOfTheWrongLength() {
        StoredCryptoKey key = service.createCryptoKey(KEY_RING, "k", "ASYMMETRIC_SIGN", "RSA_SIGN_PSS_2048_SHA256", false);

        GcpException ex = assertThrows(GcpException.class,
                () -> service.asymmetricSign(key.getName() + "/cryptoKeyVersions/1", "SHA-256", new byte[31], new byte[0]));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void decryptAlgorithmCannotBeUsedForSigning() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.createCryptoKey(KEY_RING, "k", "ASYMMETRIC_SIGN", "RSA_DECRYPT_OAEP_2048_SHA256", false));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    private StoredCryptoKeyVersion importKey(String purpose, String algorithm, KeyPair pair) throws Exception {
        StoredImportJob job = service.createImportJob(KEY_RING, "job", "RSA_OAEP_3072_SHA256_AES_256", "SOFTWARE");
        StoredCryptoKey key = service.createCryptoKey(KEY_RING, "imported", purpose, algorithm, true,
                CloudKmsService.CryptoKeyFields.NONE, true);
        return service.importCryptoKeyVersion(key.getName(), algorithm, job.getName(),
                wrap(job, pair.getPrivate().getEncoded()));
    }

    private PublicKey publicKey(String versionName) throws Exception {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(service.getPublicKeyVersion(versionName).getPublicKeyBase64())));
    }

    private static boolean verifies(String algorithm, PublicKey publicKey, byte[] data, byte[] signature)
            throws Exception {
        String hash = hash(algorithm);
        Signature verifier;
        if (algorithm.startsWith("RSA_SIGN_PSS_")) {
            verifier = Signature.getInstance("RSASSA-PSS");
            verifier.setParameter(new PSSParameterSpec(hash, "MGF1", new MGF1ParameterSpec(hash),
                    MessageDigest.getInstance(hash).getDigestLength(), PSSParameterSpec.TRAILER_FIELD_BC));
        } else {
            verifier = Signature.getInstance(hash.replace("-", "") + "withRSA");
        }
        verifier.initVerify(publicKey);
        verifier.update(data);
        return verifier.verify(signature);
    }

    private static int bits(String algorithm) {
        return Integer.parseInt(algorithm.split("_")[3]);
    }

    private static String hash(String algorithm) {
        String suffix = algorithm.substring(algorithm.lastIndexOf('_') + 1);
        return suffix.replace("SHA", "SHA-");
    }

    private static KeyPair keyPair(int bits) {
        return KEY_PAIRS.computeIfAbsent(bits, b -> {
            try {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(b);
                return gen.generateKeyPair();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static byte[] rsaOaep(PublicKey key, String hash, byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key,
                new OAEPParameterSpec(hash, "MGF1", new MGF1ParameterSpec(hash), PSource.PSpecified.DEFAULT));
        return cipher.doFinal(data);
    }

    /** Client-side CKM_RSA_AES_KEY_WRAP for an {@code RSA_OAEP_*_SHA256_AES_256} job. */
    private static byte[] wrap(StoredImportJob job, byte[] targetKey) throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256);
        byte[] ephemeral = gen.generateKey().getEncoded();
        PublicKey wrappingKey = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(job.getPublicKeyBase64())));
        byte[] wrappedEphemeral = rsaOaep(wrappingKey, "SHA-256", ephemeral);
        Cipher kwp = Cipher.getInstance("AES/KWP/NoPadding");
        kwp.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ephemeral, "AES"));
        byte[] wrappedTarget = kwp.doFinal(targetKey);
        byte[] out = new byte[wrappedEphemeral.length + wrappedTarget.length];
        System.arraycopy(wrappedEphemeral, 0, out, 0, wrappedEphemeral.length);
        System.arraycopy(wrappedTarget, 0, out, wrappedEphemeral.length, wrappedTarget.length);
        return out;
    }
}

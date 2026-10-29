package io.floci.gcp.services.cloudkms;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKey;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKeyVersion;
import io.floci.gcp.services.cloudkms.model.StoredImportJob;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

class CloudKmsImportServiceTest {

    private static final String KEY_RING = "projects/p1/locations/us-central1/keyRings/kr";

    private InMemoryStorage<String, StoredImportJob> importJobStore;
    private CloudKmsService service;

    @BeforeEach
    void setUp() {
        importJobStore = new InMemoryStorage<>();
        service = new CloudKmsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                importJobStore);
        service.createKeyRing("projects/p1/locations/us-central1", "kr");
    }

    @Test
    void createImportJobIssuesActiveWrappingKeyOfTheMethodSize() throws Exception {
        StoredImportJob job = service.createImportJob(KEY_RING, "job", "RSA_OAEP_4096_SHA256_AES_256", "SOFTWARE");

        assertEquals(KEY_RING + "/importJobs/job", job.getName());
        assertEquals("ACTIVE", CloudKmsService.importJobState(job));
        assertTrue(Instant.parse(job.getExpireTime()).isAfter(Instant.parse(job.getCreateTime())));
        assertEquals(4096, ((RSAPublicKey) wrappingPublicKey(job)).getModulus().bitLength());
        assertEquals(1, service.listImportJobs(KEY_RING).size());
        assertEquals(job.getName(), service.getImportJob(job.getName()).getName());
    }

    @Test
    void createImportJobDuplicateThrowsAlreadyExists() {
        service.createImportJob(KEY_RING, "job", "RSA_OAEP_3072_SHA1_AES_256", "SOFTWARE");
        GcpException ex = assertThrows(GcpException.class,
                () -> service.createImportJob(KEY_RING, "job", "RSA_OAEP_3072_SHA1_AES_256", "SOFTWARE"));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
    }

    @Test
    void createImportJobRequiresMethodAndProtectionLevel() {
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> service.createImportJob(KEY_RING, "a", "IMPORT_METHOD_UNSPECIFIED", "SOFTWARE")).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> service.createImportJob(KEY_RING, "b", "RSA_OAEP_3072_SHA256", null)).getGcpStatus());
    }

    @Test
    void createImportJobInMissingKeyRingThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class, () -> service.createImportJob(
                "projects/p1/locations/us-central1/keyRings/missing", "job", "RSA_OAEP_3072_SHA256", "SOFTWARE"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void importedSymmetricKeyDecryptsWhatAnotherImportOfTheSameMaterialEncrypted() throws Exception {
        StoredImportJob job = service.createImportJob(KEY_RING, "job", "RSA_OAEP_3072_SHA1_AES_256", "SOFTWARE");
        byte[] aesKey = aes256();
        StoredCryptoKey first = importOnlyKey("first", "ENCRYPT_DECRYPT");
        StoredCryptoKey second = importOnlyKey("second", "ENCRYPT_DECRYPT");

        StoredCryptoKeyVersion version = service.importCryptoKeyVersion(first.getName(), "GOOGLE_SYMMETRIC_ENCRYPTION",
                job.getName(), wrap(job, aesKey));
        service.importCryptoKeyVersion(second.getName(), "GOOGLE_SYMMETRIC_ENCRYPTION",
                job.getName(), wrap(job, aesKey));

        assertEquals("ENABLED", version.getState());
        assertEquals(job.getName(), version.getImportJob());
        assertNotNull(version.getImportTime());
        assertNull(version.getGenerateTime());
        assertEquals(Integer.valueOf(1), service.getCryptoKey(first.getName()).getPrimaryVersion());

        byte[] plaintext = "imported".getBytes(StandardCharsets.UTF_8);
        CloudKmsService.EncryptResult enc = service.encrypt(first.getName(), plaintext, new byte[0]);
        assertArrayEquals(plaintext, service.decrypt(second.getName(), enc.ciphertext(), new byte[0]).plaintext());
    }

    @Test
    void importWithDirectRsaOaepMethodUnwrapsSymmetricKey() throws Exception {
        StoredImportJob job = service.createImportJob(KEY_RING, "job", "RSA_OAEP_3072_SHA256", "SOFTWARE");
        byte[] aesKey = aes256();
        StoredCryptoKey key = importOnlyKey("k", "ENCRYPT_DECRYPT");

        StoredCryptoKeyVersion version = service.importCryptoKeyVersion(key.getName(), "GOOGLE_SYMMETRIC_ENCRYPTION",
                job.getName(), rsaOaep(wrappingPublicKey(job), "SHA-256", MGF1ParameterSpec.SHA256, aesKey));

        assertEquals("ENABLED", version.getState());
        byte[] plaintext = "direct".getBytes(StandardCharsets.UTF_8);
        CloudKmsService.EncryptResult enc = service.encrypt(key.getName(), plaintext, new byte[0]);
        assertArrayEquals(plaintext, service.decrypt(key.getName(), enc.ciphertext(), new byte[0]).plaintext());
    }

    @Test
    void importedRsaSigningKeyVerifiesWithTheOriginalPublicKey() throws Exception {
        StoredImportJob job = service.createImportJob(KEY_RING, "job", "RSA_OAEP_4096_SHA256_AES_256", "SOFTWARE");
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        StoredCryptoKey key = importOnlyKey("rsa", "ASYMMETRIC_SIGN", "RSA_SIGN_PKCS1_2048_SHA256");

        StoredCryptoKeyVersion version = service.importCryptoKeyVersion(key.getName(), "RSA_SIGN_PKCS1_2048_SHA256",
                job.getName(), wrap(job, pair.getPrivate().getEncoded()));

        assertEquals("ENABLED", version.getState());
        assertArrayEquals(pair.getPublic().getEncoded(), Base64.getDecoder().decode(
                service.getPublicKeyVersion(version.getName()).getPublicKeyBase64()));
        assertTrue(verifies("SHA256withRSA", pair.getPublic(), version.getName()));
    }

    @Test
    void importedEcSigningKeyDerivesItsPublicKeyAndVerifies() throws Exception {
        StoredImportJob job = service.createImportJob(KEY_RING, "job", "RSA_OAEP_3072_SHA256_AES_256", "SOFTWARE");
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = gen.generateKeyPair();
        StoredCryptoKey key = importOnlyKey("ec", "ASYMMETRIC_SIGN", "EC_SIGN_P256_SHA256");

        StoredCryptoKeyVersion version = service.importCryptoKeyVersion(key.getName(), "EC_SIGN_P256_SHA256",
                job.getName(), wrap(job, pair.getPrivate().getEncoded()));

        assertEquals("ENABLED", version.getState());
        assertArrayEquals(pair.getPublic().getEncoded(), Base64.getDecoder().decode(
                service.getPublicKeyVersion(version.getName()).getPublicKeyBase64()));
        assertTrue(verifies("SHA256withECDSA", pair.getPublic(), version.getName()));
    }

    @Test
    void importedRsaDecryptKeyDecryptsForTheOriginalPublicKey() throws Exception {
        StoredImportJob job = service.createImportJob(KEY_RING, "job", "RSA_OAEP_4096_SHA1_AES_256", "SOFTWARE");
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        StoredCryptoKey key = importOnlyKey("rsadec", "ASYMMETRIC_DECRYPT", "RSA_DECRYPT_OAEP_2048_SHA256");

        StoredCryptoKeyVersion version = service.importCryptoKeyVersion(key.getName(), "RSA_DECRYPT_OAEP_2048_SHA256",
                job.getName(), wrap(job, pair.getPrivate().getEncoded()));

        byte[] plaintext = "for-the-imported-key".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = rsaOaep(pair.getPublic(), "SHA-256", MGF1ParameterSpec.SHA256, plaintext);
        assertArrayEquals(plaintext, service.asymmetricDecrypt(version.getName(), ciphertext));
    }

    @Test
    void keyWrappedForAnotherJobFailsImportWithAReason() throws Exception {
        StoredImportJob job = service.createImportJob(KEY_RING, "job", "RSA_OAEP_3072_SHA1_AES_256", "SOFTWARE");
        StoredImportJob other = service.createImportJob(KEY_RING, "other", "RSA_OAEP_3072_SHA1_AES_256", "SOFTWARE");
        StoredCryptoKey key = importOnlyKey("k", "ENCRYPT_DECRYPT");
        byte[] aesKey = aes256();

        StoredCryptoKeyVersion version = service.importCryptoKeyVersion(key.getName(), "GOOGLE_SYMMETRIC_ENCRYPTION",
                job.getName(), wrap(other, aesKey));

        assertEquals("IMPORT_FAILED", version.getState());
        assertNotNull(version.getImportFailureReason());
        assertNull(version.getKeyMaterialBase64());
        assertNull(service.getCryptoKey(key.getName()).getPrimaryVersion());
        GcpException ex = assertThrows(GcpException.class,
                () -> service.updateCryptoKeyVersionState(version.getName(), "ENABLED"));
        assertEquals("FAILED_PRECONDITION", ex.getGcpStatus());
        assertEquals("FAILED_PRECONDITION", assertThrows(GcpException.class,
                () -> service.destroyCryptoKeyVersion(version.getName())).getGcpStatus());
        assertEquals("IMPORT_FAILED", service.getCryptoKeyVersion(version.getName()).getState());
    }

    @Test
    void concurrentImportsIntoOneKeyEachGetTheirOwnVersion() throws Exception {
        StoredImportJob job = service.createImportJob(KEY_RING, "job", "RSA_OAEP_3072_SHA256", "SOFTWARE");
        StoredCryptoKey key = importOnlyKey("k", "ENCRYPT_DECRYPT");
        List<byte[]> wrappedKeys = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            wrappedKeys.add(rsaOaep(wrappingPublicKey(job), "SHA-256", MGF1ParameterSpec.SHA256, aes256()));
        }

        List<StoredCryptoKeyVersion> versions = runConcurrently(wrappedKeys.stream()
                .<Callable<StoredCryptoKeyVersion>>map(wrapped -> () -> service.importCryptoKeyVersion(
                        key.getName(), "GOOGLE_SYMMETRIC_ENCRYPTION", job.getName(), wrapped))
                .toList());

        Set<String> names = new HashSet<>();
        for (StoredCryptoKeyVersion version : versions) {
            names.add(version.getName());
        }
        assertEquals(8, names.size());
        assertEquals(8, service.listCryptoKeyVersions(key.getName()).size());
        assertEquals(9, service.getCryptoKey(key.getName()).getNextVersionNumber());
    }

    @Test
    void concurrentCreatesOfOneImportJobLetExactlyOneSucceed() throws Exception {
        List<Callable<String>> creates = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            creates.add(() -> {
                try {
                    return service.createImportJob(KEY_RING, "job", "RSA_OAEP_3072_SHA256", "SOFTWARE")
                            .getPublicKeyBase64();
                } catch (GcpException e) {
                    assertEquals("ALREADY_EXISTS", e.getGcpStatus());
                    return null;
                }
            });
        }

        List<String> publicKeys = runConcurrently(creates).stream().filter(k -> k != null).toList();

        assertEquals(1, publicKeys.size());
        assertEquals(publicKeys.get(0), service.getImportJob(KEY_RING + "/importJobs/job").getPublicKeyBase64());
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    throw new AssertionError(e.getCause());
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void everyUnwrapFailureReportsTheSameReason() throws Exception {
        StoredImportJob job = service.createImportJob(KEY_RING, "job", "RSA_OAEP_3072_SHA1_AES_256", "SOFTWARE");
        StoredImportJob other = service.createImportJob(KEY_RING, "other", "RSA_OAEP_3072_SHA1_AES_256", "SOFTWARE");
        StoredCryptoKey key = importOnlyKey("k", "ENCRYPT_DECRYPT");
        byte[] wrapped = wrap(job, aes256());
        byte[] badKwpLayer = wrapped.clone();
        badKwpLayer[badKwpLayer.length - 1] ^= 1;
        byte[] shortAesKey = rsaOaep(wrappingPublicKey(job), "SHA-1", MGF1ParameterSpec.SHA1, new byte[16]);
        byte[] badRsaLayer = wrap(other, aes256());

        String kwpReason = importFailure(key, job, badKwpLayer);
        assertEquals(kwpReason, importFailure(key, job, badRsaLayer));
        assertEquals(kwpReason, importFailure(key, job, concat(shortAesKey, new byte[40])));
        assertEquals(kwpReason, importFailure(key, job, new byte[8]));
    }

    @Test
    void ecImportRejectsAnEmbeddedPublicKeyThatDoesNotMatch() throws Exception {
        StoredImportJob job = service.createImportJob(KEY_RING, "job", "RSA_OAEP_3072_SHA256_AES_256", "SOFTWARE");
        StoredCryptoKey key = importOnlyKey("ec", "ASYMMETRIC_SIGN", "EC_SIGN_P256_SHA256");
        KeyPair pair = ecKeyPair();
        KeyPair stranger = ecKeyPair();

        StoredCryptoKeyVersion mismatched = service.importCryptoKeyVersion(key.getName(), "EC_SIGN_P256_SHA256",
                job.getName(), wrap(job, pkcs8WithPublicKey(pair, stranger)));
        StoredCryptoKeyVersion matching = service.importCryptoKeyVersion(key.getName(), "EC_SIGN_P256_SHA256",
                job.getName(), wrap(job, pkcs8WithPublicKey(pair, pair)));

        assertEquals("IMPORT_FAILED", mismatched.getState());
        assertTrue(mismatched.getImportFailureReason().contains("embedded public key"));
        assertEquals("ENABLED", matching.getState());
        assertTrue(verifies("SHA256withECDSA", pair.getPublic(), matching.getName()));
    }

    private String importFailure(StoredCryptoKey key, StoredImportJob job, byte[] wrappedKey) {
        StoredCryptoKeyVersion version = service.importCryptoKeyVersion(key.getName(), "GOOGLE_SYMMETRIC_ENCRYPTION",
                job.getName(), wrappedKey);
        assertEquals("IMPORT_FAILED", version.getState());
        return version.getImportFailureReason();
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    /** PKCS#8 for {@code pair}'s private key carrying {@code embedded}'s public key in ECPrivateKey. */
    private static byte[] pkcs8WithPublicKey(KeyPair pair, KeyPair embedded) throws Exception {
        PrivateKeyInfo info = PrivateKeyInfo.getInstance(pair.getPrivate().getEncoded());
        ECPrivateKey ecKey = ECPrivateKey.getInstance(info.parsePrivateKey());
        byte[] publicPoint = SubjectPublicKeyInfo.getInstance(embedded.getPublic().getEncoded())
                .getPublicKeyData().getOctets();
        return new PrivateKeyInfo(info.getPrivateKeyAlgorithm(),
                new ECPrivateKey(256, ecKey.getKey(), new DERBitString(publicPoint), null)).getEncoded();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void materialThatDoesNotFitTheAlgorithmFailsImport() throws Exception {
        StoredImportJob job = service.createImportJob(KEY_RING, "job", "RSA_OAEP_3072_SHA1_AES_256", "SOFTWARE");
        StoredCryptoKey key = importOnlyKey("k", "ENCRYPT_DECRYPT");

        StoredCryptoKeyVersion version = service.importCryptoKeyVersion(key.getName(), "GOOGLE_SYMMETRIC_ENCRYPTION",
                job.getName(), wrap(job, new byte[16]));

        assertEquals("IMPORT_FAILED", version.getState());
        assertTrue(version.getImportFailureReason().contains("32-byte"));
    }

    @Test
    void importRejectsMissingOrExpiredJobAndMissingAlgorithm() throws Exception {
        StoredImportJob job = service.createImportJob(KEY_RING, "job", "RSA_OAEP_3072_SHA1_AES_256", "SOFTWARE");
        StoredCryptoKey key = importOnlyKey("k", "ENCRYPT_DECRYPT");
        byte[] wrapped = wrap(job, aes256());

        assertEquals("NOT_FOUND", assertThrows(GcpException.class, () -> service.importCryptoKeyVersion(
                key.getName(), "GOOGLE_SYMMETRIC_ENCRYPTION", KEY_RING + "/importJobs/missing", wrapped)).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class, () -> service.importCryptoKeyVersion(
                key.getName(), "CRYPTO_KEY_VERSION_ALGORITHM_UNSPECIFIED", job.getName(), wrapped)).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class, () -> service.importCryptoKeyVersion(
                key.getName(), "EC_SIGN_P256_SHA256", job.getName(), wrapped)).getGcpStatus());

        job.setExpireTime(Instant.now().minusSeconds(1).toString());
        importJobStore.put(job.getName(), job);
        assertEquals("EXPIRED", CloudKmsService.importJobState(service.getImportJob(job.getName())));
        assertEquals("FAILED_PRECONDITION", assertThrows(GcpException.class, () -> service.importCryptoKeyVersion(
                key.getName(), "GOOGLE_SYMMETRIC_ENCRYPTION", job.getName(), wrapped)).getGcpStatus());
    }

    @Test
    void importOnlyKeyRejectsGeneratedVersions() {
        StoredCryptoKey key = importOnlyKey("k", "ENCRYPT_DECRYPT");
        assertTrue(key.isImportOnly());
        assertTrue(service.listCryptoKeyVersions(key.getName()).isEmpty());
        assertEquals("FAILED_PRECONDITION", assertThrows(GcpException.class,
                () -> service.createCryptoKeyVersion(key.getName())).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> service.createCryptoKey(KEY_RING, "k2", "ENCRYPT_DECRYPT", null, false, CloudKmsService.CryptoKeyFields.NONE, true)).getGcpStatus());
    }

    private StoredCryptoKey importOnlyKey(String id, String purpose) {
        return importOnlyKey(id, purpose, null);
    }

    private StoredCryptoKey importOnlyKey(String id, String purpose, String algorithm) {
        return service.createCryptoKey(KEY_RING, id, purpose, algorithm, true, CloudKmsService.CryptoKeyFields.NONE, true);
    }

    private boolean verifies(String jcaAlgorithm, PublicKey publicKey, String versionName) throws Exception {
        byte[] data = "signed-by-imported-key".getBytes(StandardCharsets.UTF_8);
        byte[] signature = service.asymmetricSign(versionName, MessageDigest.getInstance("SHA-256").digest(data));
        Signature verifier = Signature.getInstance(jcaAlgorithm);
        verifier.initVerify(publicKey);
        verifier.update(data);
        return verifier.verify(signature);
    }

    private static byte[] aes256() throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256);
        return gen.generateKey().getEncoded();
    }

    private static PublicKey wrappingPublicKey(StoredImportJob job) throws Exception {
        return KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(job.getPublicKeyBase64())));
    }

    /** Client-side CKM_RSA_AES_KEY_WRAP, as the GCP import documentation describes it. */
    private static byte[] wrap(StoredImportJob job, byte[] targetKey) throws Exception {
        boolean sha1 = job.getImportMethod().contains("_SHA1_");
        byte[] ephemeral = aes256();
        byte[] wrappedEphemeral = rsaOaep(wrappingPublicKey(job), sha1 ? "SHA-1" : "SHA-256",
                sha1 ? MGF1ParameterSpec.SHA1 : MGF1ParameterSpec.SHA256, ephemeral);
        Cipher kwp = Cipher.getInstance("AES/KWP/NoPadding");
        kwp.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ephemeral, "AES"));
        byte[] wrappedTarget = kwp.doFinal(targetKey);
        byte[] out = new byte[wrappedEphemeral.length + wrappedTarget.length];
        System.arraycopy(wrappedEphemeral, 0, out, 0, wrappedEphemeral.length);
        System.arraycopy(wrappedTarget, 0, out, wrappedEphemeral.length, wrappedTarget.length);
        return out;
    }

    private static byte[] rsaOaep(PublicKey key, String digest, MGF1ParameterSpec mgf1, byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new OAEPParameterSpec(digest, "MGF1", mgf1, PSource.PSpecified.DEFAULT));
        return cipher.doFinal(data);
    }
}

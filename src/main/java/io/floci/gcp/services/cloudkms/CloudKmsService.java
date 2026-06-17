package io.floci.gcp.services.cloudkms;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKey;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKeyVersion;
import io.floci.gcp.services.cloudkms.model.StoredKeyRing;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class CloudKmsService {

    private static final Logger LOG = Logger.getLogger(CloudKmsService.class);

    private static final String SYMMETRIC_ALGORITHM = "GOOGLE_SYMMETRIC_ENCRYPTION";
    private static final Set<String> SIGN_ALGORITHMS =
            Set.of("EC_SIGN_P256_SHA256", "RSA_SIGN_PKCS1_2048_SHA256");
    private static final String DECRYPT_ALGORITHM = "RSA_DECRYPT_OAEP_2048_SHA256";

    private final StorageBackend<String, StoredKeyRing> keyRingStore;
    private final StorageBackend<String, StoredCryptoKey> cryptoKeyStore;
    private final StorageBackend<String, StoredCryptoKeyVersion> versionStore;

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final GrpcServerManager grpcServerManager;

    @Inject
    public CloudKmsService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, GrpcServerManager grpcServerManager) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
        this.keyRingStore = storageFactory.createGlobal("cloudkms-keyrings", "cloudkms-keyrings.json",
                new TypeReference<Map<String, StoredKeyRing>>() {});
        this.cryptoKeyStore = storageFactory.createGlobal("cloudkms-cryptokeys", "cloudkms-cryptokeys.json",
                new TypeReference<Map<String, StoredCryptoKey>>() {});
        this.versionStore = storageFactory.createGlobal("cloudkms-versions", "cloudkms-versions.json",
                new TypeReference<Map<String, StoredCryptoKeyVersion>>() {});
    }

    CloudKmsService(StorageBackend<String, StoredKeyRing> keyRingStore,
            StorageBackend<String, StoredCryptoKey> cryptoKeyStore,
            StorageBackend<String, StoredCryptoKeyVersion> versionStore) {
        this.keyRingStore = keyRingStore;
        this.cryptoKeyStore = cryptoKeyStore;
        this.versionStore = versionStore;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("kms")
                .enabled(config.services().kms().enabled())
                .storageKey("kms")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(CloudKmsController.class)
                .build());
        grpcServerManager.bind(new CloudKmsController(this));
    }

    // ── KeyRings ─────────────────────────────────────────────────────────────

    public StoredKeyRing createKeyRing(String parent, String keyRingId) {
        String name = parent + "/keyRings/" + keyRingId;
        LOG.infof("createKeyRing name=%s", name);
        if (keyRingStore.get(name).isPresent()) {
            throw GcpException.alreadyExists("KeyRing already exists: " + name);
        }
        StoredKeyRing keyRing = new StoredKeyRing(name, Instant.now().toString());
        keyRingStore.put(name, keyRing);
        return keyRing;
    }

    public StoredKeyRing getKeyRing(String name) {
        return keyRingStore.get(name)
                .orElseThrow(() -> GcpException.notFound("KeyRing not found: " + name));
    }

    public List<StoredKeyRing> listKeyRings(String parent) {
        String prefix = parent + "/keyRings/";
        return keyRingStore.scan(k -> k.startsWith(prefix));
    }

    // ── CryptoKeys ───────────────────────────────────────────────────────────

    public StoredCryptoKey createCryptoKey(String parent, String cryptoKeyId, String purpose,
            String algorithm, boolean skipInitialVersionCreation) {
        getKeyRing(parent); // validates the parent key ring exists
        String name = parent + "/cryptoKeys/" + cryptoKeyId;
        LOG.infof("createCryptoKey name=%s purpose=%s", name, purpose);
        if (cryptoKeyStore.get(name).isPresent()) {
            throw GcpException.alreadyExists("CryptoKey already exists: " + name);
        }
        String effectiveAlgorithm = resolveAlgorithm(purpose, algorithm);
        StoredCryptoKey key = new StoredCryptoKey(name, purpose, effectiveAlgorithm, Instant.now().toString());
        cryptoKeyStore.put(name, key);
        if (!skipInitialVersionCreation) {
            StoredCryptoKeyVersion version = createCryptoKeyVersion(name);
            if ("ENCRYPT_DECRYPT".equals(purpose)) {
                key.setPrimaryVersion(version.getVersionNumber());
                cryptoKeyStore.put(name, key);
            }
        }
        return key;
    }

    public StoredCryptoKey getCryptoKey(String name) {
        return cryptoKeyStore.get(name)
                .orElseThrow(() -> GcpException.notFound("CryptoKey not found: " + name));
    }

    public List<StoredCryptoKey> listCryptoKeys(String parent) {
        String prefix = parent + "/cryptoKeys/";
        return cryptoKeyStore.scan(k -> k.startsWith(prefix));
    }

    public StoredCryptoKey updateCryptoKey(String name, Map<String, String> labels, String versionTemplateAlgorithm) {
        StoredCryptoKey key = getCryptoKey(name);
        if (labels != null) {
            key.setLabels(labels);
        }
        if (versionTemplateAlgorithm != null && !versionTemplateAlgorithm.isBlank()) {
            key.setAlgorithm(resolveAlgorithm(key.getPurpose(), versionTemplateAlgorithm));
        }
        cryptoKeyStore.put(name, key);
        return key;
    }

    public StoredCryptoKey updateCryptoKeyPrimaryVersion(String name, String cryptoKeyVersionId) {
        StoredCryptoKey key = getCryptoKey(name);
        String versionName = name + "/cryptoKeyVersions/" + cryptoKeyVersionId;
        StoredCryptoKeyVersion version = versionStore.get(versionName)
                .orElseThrow(() -> GcpException.notFound("CryptoKeyVersion not found: " + versionName));
        key.setPrimaryVersion(version.getVersionNumber());
        cryptoKeyStore.put(name, key);
        return key;
    }

    // ── CryptoKeyVersions ────────────────────────────────────────────────────

    public StoredCryptoKeyVersion createCryptoKeyVersion(String cryptoKeyName) {
        StoredCryptoKey key = getCryptoKey(cryptoKeyName);
        int versionNumber = key.getNextVersionNumber();
        String versionName = cryptoKeyName + "/cryptoKeyVersions/" + versionNumber;
        LOG.infof("createCryptoKeyVersion name=%s", versionName);
        StoredCryptoKeyVersion version = new StoredCryptoKeyVersion(
                versionName, versionNumber, key.getAlgorithm(), Instant.now().toString());
        KmsCrypto.GeneratedKey material = KmsCrypto.generate(key.getAlgorithm());
        version.setKeyMaterialBase64(material.keyMaterialBase64());
        version.setPublicKeyBase64(material.publicKeyBase64());
        versionStore.put(versionName, version);
        key.setNextVersionNumber(versionNumber + 1);
        cryptoKeyStore.put(cryptoKeyName, key);
        return version;
    }

    public StoredCryptoKeyVersion getCryptoKeyVersion(String name) {
        return versionStore.get(name)
                .orElseThrow(() -> GcpException.notFound("CryptoKeyVersion not found: " + name));
    }

    public java.util.Optional<StoredCryptoKeyVersion> getCryptoKeyVersionOptional(String name) {
        return versionStore.get(name);
    }

    public List<StoredCryptoKeyVersion> listCryptoKeyVersions(String parent) {
        String prefix = parent + "/cryptoKeyVersions/";
        return versionStore.scan(k -> k.startsWith(prefix));
    }

    public StoredCryptoKeyVersion updateCryptoKeyVersionState(String name, String state) {
        StoredCryptoKeyVersion version = getCryptoKeyVersion(name);
        if (!"ENABLED".equals(state) && !"DISABLED".equals(state)) {
            throw GcpException.invalidArgument("Unsupported state transition to: " + state);
        }
        version.setState(state);
        versionStore.put(name, version);
        return version;
    }

    public StoredCryptoKeyVersion destroyCryptoKeyVersion(String name) {
        StoredCryptoKeyVersion version = getCryptoKeyVersion(name);
        version.setState("DESTROY_SCHEDULED");
        version.setDestroyTime(Instant.now().toString());
        versionStore.put(name, version);
        return version;
    }

    public StoredCryptoKeyVersion restoreCryptoKeyVersion(String name) {
        StoredCryptoKeyVersion version = getCryptoKeyVersion(name);
        if (!"DESTROY_SCHEDULED".equals(version.getState())) {
            throw GcpException.failedPrecondition("CryptoKeyVersion is not scheduled for destruction: " + name);
        }
        version.setState("DISABLED");
        version.setDestroyTime(null);
        versionStore.put(name, version);
        return version;
    }

    // ── Crypto operations ────────────────────────────────────────────────────

    public record EncryptResult(String versionName, byte[] ciphertext) {}

    public EncryptResult encrypt(String name, byte[] plaintext, byte[] aad) {
        StoredCryptoKey key;
        StoredCryptoKeyVersion version;
        if (name.contains("/cryptoKeyVersions/")) {
            version = getCryptoKeyVersion(name);
            key = getCryptoKey(cryptoKeyOf(name));
        } else {
            key = getCryptoKey(name);
            if (key.getPrimaryVersion() == null) {
                throw GcpException.failedPrecondition("CryptoKey has no primary version: " + name);
            }
            version = getCryptoKeyVersion(name + "/cryptoKeyVersions/" + key.getPrimaryVersion());
        }
        requirePurpose(key, "ENCRYPT_DECRYPT");
        requireEnabled(version);
        byte[] ciphertext = KmsCrypto.encrypt(version.getVersionNumber(), version.getKeyMaterialBase64(), plaintext, aad);
        return new EncryptResult(version.getName(), ciphertext);
    }

    public record DecryptResult(byte[] plaintext, boolean usedPrimary) {}

    public DecryptResult decrypt(String cryptoKeyName, byte[] ciphertext, byte[] aad) {
        StoredCryptoKey key = getCryptoKey(cryptoKeyName);
        requirePurpose(key, "ENCRYPT_DECRYPT");
        int versionNumber = KmsCrypto.versionFromCiphertext(ciphertext);
        String versionName = cryptoKeyName + "/cryptoKeyVersions/" + versionNumber;
        StoredCryptoKeyVersion version = versionStore.get(versionName)
                .orElseThrow(() -> GcpException.invalidArgument("Decryption failed: the ciphertext is invalid"));
        byte[] plaintext = KmsCrypto.decrypt(version.getKeyMaterialBase64(), ciphertext, aad);
        boolean usedPrimary = Integer.valueOf(versionNumber).equals(key.getPrimaryVersion());
        return new DecryptResult(plaintext, usedPrimary);
    }

    public StoredCryptoKeyVersion getPublicKeyVersion(String versionName) {
        StoredCryptoKeyVersion version = getCryptoKeyVersion(versionName);
        StoredCryptoKey key = getCryptoKey(cryptoKeyOf(versionName));
        if (!"ASYMMETRIC_SIGN".equals(key.getPurpose()) && !"ASYMMETRIC_DECRYPT".equals(key.getPurpose())) {
            throw GcpException.invalidArgument("GetPublicKey is only valid for asymmetric keys: " + versionName);
        }
        requireEnabled(version);
        return version;
    }

    public byte[] asymmetricSign(String versionName, byte[] digestSha256) {
        StoredCryptoKeyVersion version = getCryptoKeyVersion(versionName);
        StoredCryptoKey key = getCryptoKey(cryptoKeyOf(versionName));
        requirePurpose(key, "ASYMMETRIC_SIGN");
        requireEnabled(version);
        return KmsCrypto.sign(version.getAlgorithm(), version.getKeyMaterialBase64(), digestSha256);
    }

    public byte[] asymmetricDecrypt(String versionName, byte[] ciphertext) {
        StoredCryptoKeyVersion version = getCryptoKeyVersion(versionName);
        StoredCryptoKey key = getCryptoKey(cryptoKeyOf(versionName));
        requirePurpose(key, "ASYMMETRIC_DECRYPT");
        requireEnabled(version);
        return KmsCrypto.asymmetricDecrypt(version.getAlgorithm(), version.getKeyMaterialBase64(), ciphertext);
    }

    public byte[] generateRandomBytes(int lengthBytes) {
        if (lengthBytes < 8 || lengthBytes > 1024) {
            throw GcpException.invalidArgument("length_bytes must be between 8 and 1024: " + lengthBytes);
        }
        return KmsCrypto.randomBytes(lengthBytes);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String resolveAlgorithm(String purpose, String algorithm) {
        boolean unspecified = algorithm == null || algorithm.isBlank()
                || "CRYPTO_KEY_VERSION_ALGORITHM_UNSPECIFIED".equals(algorithm);
        switch (purpose) {
            case "ENCRYPT_DECRYPT" -> {
                if (unspecified || SYMMETRIC_ALGORITHM.equals(algorithm)) {
                    return SYMMETRIC_ALGORITHM;
                }
                throw GcpException.invalidArgument("Algorithm " + algorithm + " is invalid for ENCRYPT_DECRYPT");
            }
            case "ASYMMETRIC_SIGN" -> {
                if (unspecified) {
                    throw GcpException.invalidArgument("Algorithm is required for ASYMMETRIC_SIGN");
                }
                if (SIGN_ALGORITHMS.contains(algorithm)) {
                    return algorithm;
                }
                throw GcpException.invalidArgument("Unsupported ASYMMETRIC_SIGN algorithm: " + algorithm);
            }
            case "ASYMMETRIC_DECRYPT" -> {
                if (DECRYPT_ALGORITHM.equals(algorithm)) {
                    return algorithm;
                }
                throw GcpException.invalidArgument("Unsupported ASYMMETRIC_DECRYPT algorithm: " + algorithm);
            }
            default -> throw GcpException.invalidArgument("Unsupported purpose: " + purpose);
        }
    }

    private static void requirePurpose(StoredCryptoKey key, String purpose) {
        if (!purpose.equals(key.getPurpose())) {
            throw GcpException.invalidArgument(
                    "CryptoKey purpose " + key.getPurpose() + " does not support this operation");
        }
    }

    private static void requireEnabled(StoredCryptoKeyVersion version) {
        if (!"ENABLED".equals(version.getState())) {
            throw GcpException.failedPrecondition(
                    "CryptoKeyVersion is not enabled (state=" + version.getState() + "): " + version.getName());
        }
    }

    private static String cryptoKeyOf(String versionName) {
        int idx = versionName.indexOf("/cryptoKeyVersions/");
        return idx > 0 ? versionName.substring(0, idx) : versionName;
    }
}

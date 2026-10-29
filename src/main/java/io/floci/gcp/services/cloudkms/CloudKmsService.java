package io.floci.gcp.services.cloudkms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.protobuf.util.Durations;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.LocationCatalog;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKey;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKeyVersion;
import io.floci.gcp.services.cloudkms.model.StoredImportJob;
import io.floci.gcp.services.cloudkms.model.StoredKeyRing;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class CloudKmsService {

    private static final Logger LOG = Logger.getLogger(CloudKmsService.class);
    private static final int LOCK_STRIPES = 256;
    private final Object[] locks = createLocks();

    private static final String SYMMETRIC_ALGORITHM = "GOOGLE_SYMMETRIC_ENCRYPTION";
    private static final Set<String> SIGN_ALGORITHMS =
            Set.of("EC_SIGN_P256_SHA256", "RSA_SIGN_PKCS1_2048_SHA256");
    private static final String DECRYPT_ALGORITHM = "RSA_DECRYPT_OAEP_2048_SHA256";
    private static final Set<String> IMPORT_PROTECTION_LEVELS = Set.of("SOFTWARE", "HSM");
    private static final Duration IMPORT_JOB_LIFETIME = Duration.ofDays(3);

    private static final Duration DEFAULT_DESTROY_SCHEDULED_DURATION = Duration.ofDays(30);
    private static final Duration MIN_ROTATION_PERIOD = Duration.ofHours(24);
    private static final Duration MAX_ROTATION_PERIOD = Duration.ofHours(876_000);

    private static final LocationCatalog.Kind[] KMS_LOCATION_KINDS = {
            LocationCatalog.Kind.REGION, LocationCatalog.Kind.GLOBAL, LocationCatalog.Kind.KMS_MULTI_REGION};

    private final StorageBackend<String, StoredKeyRing> keyRingStore;
    private final StorageBackend<String, StoredCryptoKey> cryptoKeyStore;
    private final StorageBackend<String, StoredCryptoKeyVersion> versionStore;
    private final StorageBackend<String, StoredImportJob> importJobStore;

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final GrpcServerManager grpcServerManager;
    private final LocationCatalog locations;

    @Inject
    public CloudKmsService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, GrpcServerManager grpcServerManager, LocationCatalog locations) {
        this.serviceRegistry = serviceRegistry;
        this.locations = locations;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
        this.keyRingStore = storageFactory.createGlobal("cloudkms-keyrings", "cloudkms-keyrings.json",
                new TypeReference<Map<String, StoredKeyRing>>() {});
        this.cryptoKeyStore = storageFactory.createGlobal("cloudkms-cryptokeys", "cloudkms-cryptokeys.json",
                new TypeReference<Map<String, StoredCryptoKey>>() {});
        this.versionStore = storageFactory.createGlobal("cloudkms-versions", "cloudkms-versions.json",
                new TypeReference<Map<String, StoredCryptoKeyVersion>>() {});
        this.importJobStore = storageFactory.createGlobal("cloudkms-importjobs", "cloudkms-importjobs.json",
                new TypeReference<Map<String, StoredImportJob>>() {});
    }

    CloudKmsService(StorageBackend<String, StoredKeyRing> keyRingStore,
            StorageBackend<String, StoredCryptoKey> cryptoKeyStore,
            StorageBackend<String, StoredCryptoKeyVersion> versionStore,
            StorageBackend<String, StoredImportJob> importJobStore) {
        this.keyRingStore = keyRingStore;
        this.cryptoKeyStore = cryptoKeyStore;
        this.versionStore = versionStore;
        this.importJobStore = importJobStore;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
        this.locations = LocationCatalog.lenient();
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("kms")
                .enabled(config.services().kms().enabled())
                .storageKey("kms")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(CloudKmsController.class)
                .api("cloudkms.googleapis.com", "Cloud Key Management Service (KMS) API",
                        CloudKmsHttpController.class)
                .build());
        grpcServerManager.bind(new CloudKmsController(this));
    }

    // ── KeyRings ─────────────────────────────────────────────────────────────

    public StoredKeyRing createKeyRing(String parent, String keyRingId) {
        requireKmsLocation(parent);
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
        locations.requireListLocation(LocationCatalog.locationOf(parent), KMS_LOCATION_KINDS);
        String prefix = parent + "/keyRings/";
        return keyRingStore.scan(k -> k.startsWith(prefix));
    }

    private void requireKmsLocation(String parent) {
        locations.requireParentLocation(parent, KMS_LOCATION_KINDS);
    }

    // ── CryptoKeys ───────────────────────────────────────────────────────────

    /**
     * The settable fields of a CryptoKey besides purpose and algorithm. A null field is unset;
     * for an update, only the fields named in the update paths are applied, so null clears.
     */
    public record CryptoKeyFields(Duration rotationPeriod, Instant nextRotationTime,
            Duration destroyScheduledDuration, Map<String, String> labels) {

        static final CryptoKeyFields NONE = new CryptoKeyFields(null, null, null, null);
    }

    public StoredCryptoKey createCryptoKey(String parent, String cryptoKeyId, String purpose,
            String algorithm, boolean skipInitialVersionCreation) {
        return createCryptoKey(parent, cryptoKeyId, purpose, algorithm, skipInitialVersionCreation,
                CryptoKeyFields.NONE, false);
    }

    public StoredCryptoKey createCryptoKey(String parent, String cryptoKeyId, String purpose,
            String algorithm, boolean skipInitialVersionCreation, CryptoKeyFields fields, boolean importOnly) {
        getKeyRing(parent); // validates the parent key ring exists
        if (importOnly && !skipInitialVersionCreation) {
            throw GcpException.invalidArgument(
                    "An import_only CryptoKey requires skip_initial_version_creation");
        }
        String name = parent + "/cryptoKeys/" + cryptoKeyId;
        LOG.infof("createCryptoKey name=%s purpose=%s", name, purpose);
        String effectiveAlgorithm = resolveAlgorithm(purpose, algorithm);
        validateRotationSchedule(purpose, fields.rotationPeriod(), fields.nextRotationTime());
        StoredCryptoKey key = new StoredCryptoKey(name, purpose, effectiveAlgorithm, Instant.now().toString());
        key.setImportOnly(importOnly);
        key.setRotationPeriod(formatDuration(fields.rotationPeriod()));
        key.setNextRotationTime(fields.nextRotationTime() != null ? fields.nextRotationTime().toString() : null);
        key.setDestroyScheduledDuration(formatDuration(fields.destroyScheduledDuration() != null
                ? fields.destroyScheduledDuration() : DEFAULT_DESTROY_SCHEDULED_DURATION));
        if (fields.labels() != null && !fields.labels().isEmpty()) {
            key.setLabels(new LinkedHashMap<>(fields.labels()));
        }
        synchronized (lock(name)) {
            if (cryptoKeyStore.get(name).isPresent()) {
                throw GcpException.alreadyExists("CryptoKey already exists: " + name);
            }
            cryptoKeyStore.put(name, key);
            if (!skipInitialVersionCreation) {
                StoredCryptoKeyVersion version = createCryptoKeyVersion(name);
                if ("ENCRYPT_DECRYPT".equals(purpose)) {
                    key.setPrimaryVersion(version.getVersionNumber());
                    cryptoKeyStore.put(name, key);
                }
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

    /**
     * Applies the fields named in {@code paths}, each a snake_case update mask path: {@code labels},
     * {@code rotation_period}, {@code next_rotation_time} or {@code version_template.algorithm}
     * ({@code version_template} alone counts as the algorithm). Other paths are ignored. Everything
     * is validated before the stored key is touched.
     */
    public StoredCryptoKey updateCryptoKey(String name, CryptoKeyFields fields, String versionTemplateAlgorithm,
            Set<String> paths) {
        synchronized (lock(name)) {
            return applyCryptoKeyUpdate(name, fields, versionTemplateAlgorithm, paths);
        }
    }

    private StoredCryptoKey applyCryptoKeyUpdate(String name, CryptoKeyFields fields, String versionTemplateAlgorithm,
            Set<String> paths) {
        StoredCryptoKey key = getCryptoKey(name);
        boolean setAlgorithm = (paths.contains("version_template.algorithm") || paths.contains("version_template"))
                && versionTemplateAlgorithm != null && !versionTemplateAlgorithm.isBlank();
        String algorithm = setAlgorithm
                ? resolveAlgorithm(key.getPurpose(), versionTemplateAlgorithm) : key.getAlgorithm();
        Duration rotationPeriod = paths.contains("rotation_period")
                ? fields.rotationPeriod() : parseStoredDuration(key.getRotationPeriod());
        Instant nextRotationTime = paths.contains("next_rotation_time")
                ? fields.nextRotationTime()
                : key.getNextRotationTime() != null ? Instant.parse(key.getNextRotationTime()) : null;
        validateRotationSchedule(key.getPurpose(), rotationPeriod, nextRotationTime);

        if (paths.contains("labels")) {
            key.setLabels(fields.labels() != null && !fields.labels().isEmpty()
                    ? new LinkedHashMap<>(fields.labels()) : null);
        }
        key.setAlgorithm(algorithm);
        key.setRotationPeriod(formatDuration(rotationPeriod));
        key.setNextRotationTime(nextRotationTime != null ? nextRotationTime.toString() : null);
        cryptoKeyStore.put(name, key);
        return key;
    }

    /** Converts lowerCamelCase update mask paths (REST) to the snake_case the proto uses. */
    public static Set<String> snakeCasePaths(Collection<String> paths) {
        Set<String> result = new HashSet<>();
        for (String path : paths) {
            String trimmed = path.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    /** The destroy scheduled duration a key reports, defaulting keys stored before the field existed. */
    public static String destroyScheduledDurationOf(StoredCryptoKey key) {
        return key.getDestroyScheduledDuration() != null
                ? key.getDestroyScheduledDuration() : formatDuration(DEFAULT_DESTROY_SCHEDULED_DURATION);
    }

    private static void validateRotationSchedule(String purpose, Duration rotationPeriod, Instant nextRotationTime) {
        if (rotationPeriod == null && nextRotationTime == null) {
            return;
        }
        if (!"ENCRYPT_DECRYPT".equals(purpose)) {
            throw GcpException.invalidArgument(
                    "rotation_period and next_rotation_time are only supported for ENCRYPT_DECRYPT keys");
        }
        if (rotationPeriod == null) {
            return;
        }
        if (nextRotationTime == null) {
            throw GcpException.invalidArgument("next_rotation_time must be set when rotation_period is set");
        }
        if (rotationPeriod.compareTo(MIN_ROTATION_PERIOD) < 0 || rotationPeriod.compareTo(MAX_ROTATION_PERIOD) > 0) {
            throw GcpException.invalidArgument("rotation_period must be at least 24 hours and at most 876000 hours");
        }
    }

    /** Formats a duration the way proto3 JSON writes {@code google.protobuf.Duration}, e.g. "7776000s". */
    static String formatDuration(Duration duration) {
        if (duration == null) {
            return null;
        }
        if (duration.getNano() == 0) {
            return duration.getSeconds() + "s";
        }
        return Durations.toString(Durations.fromSeconds(duration.getSeconds()).toBuilder()
                .setNanos(duration.getNano()).build());
    }

    private static Duration parseStoredDuration(String value) {
        if (value == null) {
            return null;
        }
        // Qualified: com.google.protobuf.Duration clashes with the imported java.time.Duration.
        com.google.protobuf.Duration parsed = Durations.parseUnchecked(value);
        return Duration.ofSeconds(parsed.getSeconds(), parsed.getNanos());
    }

    public StoredCryptoKey updateCryptoKeyPrimaryVersion(String name, String cryptoKeyVersionId) {
        synchronized (lock(name)) {
            StoredCryptoKey key = getCryptoKey(name);
            String versionName = name + "/cryptoKeyVersions/" + cryptoKeyVersionId;
            StoredCryptoKeyVersion version = versionStore.get(versionName)
                    .orElseThrow(() -> GcpException.notFound("CryptoKeyVersion not found: " + versionName));
            key.setPrimaryVersion(version.getVersionNumber());
            cryptoKeyStore.put(name, key);
            return key;
        }
    }

    // ── CryptoKeyVersions ────────────────────────────────────────────────────

    public StoredCryptoKeyVersion createCryptoKeyVersion(String cryptoKeyName) {
        synchronized (lock(cryptoKeyName)) {
            StoredCryptoKey key = getCryptoKey(cryptoKeyName);
            if (key.isImportOnly()) {
                throw GcpException.failedPrecondition(
                        "CryptoKey is import_only; add versions with ImportCryptoKeyVersion: " + cryptoKeyName);
            }
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
        requireKeyMaterial(version);
        if (!"ENABLED".equals(state) && !"DISABLED".equals(state)) {
            throw GcpException.invalidArgument("Unsupported state transition to: " + state);
        }
        version.setState(state);
        versionStore.put(name, version);
        return version;
    }

    public StoredCryptoKeyVersion destroyCryptoKeyVersion(String name) {
        StoredCryptoKeyVersion version = getCryptoKeyVersion(name);
        requireKeyMaterial(version);
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

    // ── ImportJobs ───────────────────────────────────────────────────────────

    public StoredImportJob createImportJob(String parent, String importJobId, String importMethod,
            String protectionLevel) {
        getKeyRing(parent);
        String name = parent + "/importJobs/" + importJobId;
        LOG.infof("createImportJob name=%s importMethod=%s", name, importMethod);
        if (importMethod == null || importMethod.isBlank() || "IMPORT_METHOD_UNSPECIFIED".equals(importMethod)) {
            throw GcpException.invalidArgument("import_method is required");
        }
        if (protectionLevel == null || !IMPORT_PROTECTION_LEVELS.contains(protectionLevel)) {
            throw GcpException.invalidArgument("Unsupported protection_level for an ImportJob: " + protectionLevel);
        }
        Instant now = Instant.now();
        StoredImportJob job = new StoredImportJob(name, importMethod, protectionLevel, now.toString(),
                now.plus(IMPORT_JOB_LIFETIME).toString());
        KmsCrypto.GeneratedKey wrappingKey = KmsCrypto.generateWrappingKey(importMethod);
        job.setPrivateKeyBase64(wrappingKey.keyMaterialBase64());
        job.setPublicKeyBase64(wrappingKey.publicKeyBase64());
        synchronized (lock(name)) {
            if (importJobStore.get(name).isPresent()) {
                throw GcpException.alreadyExists("ImportJob already exists: " + name);
            }
            importJobStore.put(name, job);
        }
        return job;
    }

    public StoredImportJob getImportJob(String name) {
        return importJobStore.get(name)
                .orElseThrow(() -> GcpException.notFound("ImportJob not found: " + name));
    }

    public List<StoredImportJob> listImportJobs(String parent) {
        getKeyRing(parent);
        String prefix = parent + "/importJobs/";
        return importJobStore.scan(k -> k.startsWith(prefix));
    }

    public static String importJobState(StoredImportJob job) {
        return Instant.now().isBefore(Instant.parse(job.getExpireTime())) ? "ACTIVE" : "EXPIRED";
    }

    /**
     * Imports wrapped key material as a new version of {@code cryptoKeyName}. Request-level problems
     * (unknown or expired job, wrong algorithm) fail the call. Material that cannot be unwrapped or
     * does not fit the algorithm yields a version in {@code IMPORT_FAILED} with an
     * {@code import_failure_reason}, as GCP reports asynchronous import failures.
     */
    public StoredCryptoKeyVersion importCryptoKeyVersion(String cryptoKeyName, String algorithm,
            String importJobName, byte[] wrappedKey) {
        StoredCryptoKey key = getCryptoKey(cryptoKeyName);
        if (algorithm == null || algorithm.isBlank() || "CRYPTO_KEY_VERSION_ALGORITHM_UNSPECIFIED".equals(algorithm)) {
            throw GcpException.invalidArgument("algorithm is required");
        }
        String effectiveAlgorithm = resolveAlgorithm(key.getPurpose(), algorithm);
        if (importJobName == null || importJobName.isBlank()) {
            throw GcpException.invalidArgument("import_job is required");
        }
        StoredImportJob job = getImportJob(importJobName);
        if (!"ACTIVE".equals(importJobState(job))) {
            throw GcpException.failedPrecondition("ImportJob is not active: " + importJobName);
        }
        if (wrappedKey == null || wrappedKey.length == 0) {
            throw GcpException.invalidArgument("wrapped_key is required");
        }

        KmsCrypto.GeneratedKey imported = null;
        String failureReason = null;
        try {
            byte[] material = KmsCrypto.unwrapImportedKey(job.getImportMethod(), job.getPrivateKeyBase64(), wrappedKey);
            imported = KmsCrypto.importKey(effectiveAlgorithm, material);
        } catch (IllegalArgumentException e) {
            LOG.debugf("importCryptoKeyVersion cryptoKey=%s failed: %s", cryptoKeyName, e.getMessage());
            failureReason = e.getMessage();
        }

        synchronized (lock(cryptoKeyName)) {
            StoredCryptoKey current = getCryptoKey(cryptoKeyName);
            int versionNumber = current.getNextVersionNumber();
            String versionName = cryptoKeyName + "/cryptoKeyVersions/" + versionNumber;
            LOG.infof("importCryptoKeyVersion name=%s importJob=%s", versionName, importJobName);
            String now = Instant.now().toString();
            StoredCryptoKeyVersion version = new StoredCryptoKeyVersion(
                    versionName, versionNumber, effectiveAlgorithm, now);
            version.setGenerateTime(null);
            version.setImportJob(importJobName);
            if (imported != null) {
                version.setKeyMaterialBase64(imported.keyMaterialBase64());
                version.setPublicKeyBase64(imported.publicKeyBase64());
                version.setImportTime(now);
            } else {
                version.setState("IMPORT_FAILED");
                version.setImportFailureReason(failureReason);
            }
            versionStore.put(versionName, version);
            current.setNextVersionNumber(versionNumber + 1);
            if ("ENCRYPT_DECRYPT".equals(current.getPurpose()) && current.getPrimaryVersion() == null
                    && "ENABLED".equals(version.getState())) {
                current.setPrimaryVersion(versionNumber);
            }
            cryptoKeyStore.put(cryptoKeyName, current);
            return version;
        }
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

    public byte[] macSign(String versionName, byte[] data) {
        StoredCryptoKeyVersion version = macVersion(versionName);
        return KmsCrypto.macSign(version.getAlgorithm(), version.getKeyMaterialBase64(), data);
    }

    public boolean macVerify(String versionName, byte[] data, byte[] mac) {
        StoredCryptoKeyVersion version = macVersion(versionName);
        return KmsCrypto.macVerify(version.getAlgorithm(), version.getKeyMaterialBase64(), data, mac);
    }

    private StoredCryptoKeyVersion macVersion(String versionName) {
        StoredCryptoKeyVersion version = getCryptoKeyVersion(versionName);
        requirePurpose(getCryptoKey(cryptoKeyOf(versionName)), "MAC");
        requireEnabled(version);
        return version;
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
            case "MAC" -> {
                if (unspecified) {
                    throw GcpException.invalidArgument("Algorithm is required for MAC");
                }
                if (KmsCrypto.HMAC_ALGORITHMS.contains(algorithm)) {
                    return algorithm;
                }
                throw GcpException.invalidArgument("Unsupported MAC algorithm: " + algorithm);
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

    /** A version whose import failed holds no key material, so no state change can make it usable. */
    private static void requireKeyMaterial(StoredCryptoKeyVersion version) {
        if (version.getKeyMaterialBase64() == null) {
            throw GcpException.failedPrecondition(
                    "CryptoKeyVersion holds no key material (state=" + version.getState() + "): " + version.getName());
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

    private Object lock(String resourceName) {
        return locks[Math.floorMod(resourceName.hashCode(), locks.length)];
    }

    private static Object[] createLocks() {
        Object[] stripes = new Object[LOCK_STRIPES];
        Arrays.setAll(stripes, ignored -> new Object());
        return stripes;
    }
}

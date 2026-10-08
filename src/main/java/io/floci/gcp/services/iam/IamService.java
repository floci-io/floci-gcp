package io.floci.gcp.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import io.floci.gcp.services.iam.model.StoredServiceAccountKey;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@ApplicationScoped
public class IamService {

    private static final Logger LOG = Logger.getLogger(IamService.class);
    private static final String WILDCARD_PROJECT = "-";
    private static final String SA_EMAIL_DOMAIN = ".iam.gserviceaccount.com";

    private final StorageBackend<String, StoredServiceAccount> saStore;
    private final StorageBackend<String, StoredServiceAccountKey> keyStore;
    private final StorageBackend<String, StoredPolicy> policyStore;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final GrpcServerManager grpcServerManager;
    private final Instance<IamAuthorizationService> authorization;
    private final Map<String, Consumer<String>> policyResolvers = new ConcurrentHashMap<>();

    @Inject
    public IamService(ServiceRegistry serviceRegistry, EmulatorConfig config, StorageFactory storageFactory,
            GrpcServerManager grpcServerManager, Instance<IamAuthorizationService> authorization) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
        this.authorization = authorization;
        this.saStore = storageFactory.createGlobal("iam-service-accounts", "iam-service-accounts.json",
                new TypeReference<Map<String, StoredServiceAccount>>() {});
        this.keyStore = storageFactory.createGlobal("iam-sa-keys", "iam-sa-keys.json",
                new TypeReference<Map<String, StoredServiceAccountKey>>() {});
        this.policyStore = storageFactory.createGlobal("iam-policies", "iam-policies.json",
                new TypeReference<Map<String, StoredPolicy>>() {});
    }

    IamService(StorageBackend<String, StoredServiceAccount> saStore,
            StorageBackend<String, StoredServiceAccountKey> keyStore,
            StorageBackend<String, StoredPolicy> policyStore) {
        this.saStore = saStore;
        this.keyStore = keyStore;
        this.policyStore = policyStore;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
        this.authorization = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("iam")
                .enabled(config.services().iam().enabled())
                .storageKey("iam")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(IamController.class, IamRolesController.class)
                .build());
        // Serves the google.iam.v1.IAMPolicy mixin for all services; not gated on
        // the iam REST toggle so Pub/Sub IAM keeps working when iam is disabled.
        grpcServerManager.bind(new IamPolicyGrpcController(this));
    }

    // ── Service Accounts ───────────────────────────────────────────────────────

    public StoredServiceAccount createServiceAccount(String project, String accountId,
            String displayName, String description) {
        String email = accountId + "@" + project + ".iam.gserviceaccount.com";
        String key = saKey(project, email);
        if (saStore.get(key).isPresent()) {
            throw GcpException.alreadyExists("Service account already exists: " + email);
        }
        String uniqueId = newUniqueId();
        String etag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        StoredServiceAccount sa = new StoredServiceAccount(
                "projects/" + project + "/serviceAccounts/" + email,
                project, uniqueId, email,
                displayName != null ? displayName : accountId,
                description,
                Instant.now().toString(),
                etag);
        saStore.put(key, sa);
        LOG.debugf("createServiceAccount project=%s email=%s", project, email);
        return sa;
    }

    public StoredServiceAccount getServiceAccount(String project, String emailOrId) {
        ServiceAccountRef ref = resolve(project, emailOrId);
        return saStore.get(saKey(ref.project(), ref.email()))
                .orElseThrow(() -> GcpException.notFound("Service account not found: " + ref.email()));
    }

    /**
     * Canonical {@code projects/{project}/serviceAccounts/{email}} resource name. The account ID,
     * unique ID and {@code -} project forms all map to it, so a policy is keyed the same way
     * whichever address the caller used.
     */
    public String serviceAccountResource(String project, String emailOrId) {
        ServiceAccountRef ref = resolve(project, emailOrId);
        String canonical = "projects/" + ref.project() + "/serviceAccounts/" + ref.email();
        String accountId = ref.email().substring(0, ref.email().indexOf('@'));
        adoptLegacyPolicy(canonical, "projects/" + ref.project() + "/serviceAccounts/" + accountId);
        if (!WILDCARD_PROJECT.equals(project)) {
            adoptLegacyPolicy(canonical, "projects/" + project + "/serviceAccounts/" + emailOrId);
        }
        return canonical;
    }

    // Policies used to be keyed by the address in the request path, usually the account ID.
    // A policy stored under such a key moves to the canonical email key on first access.
    private void adoptLegacyPolicy(String canonical, String legacy) {
        if (legacy.equals(canonical)) {
            return;
        }
        withPolicyLocks(List.of(canonical, legacy), () -> {
            if (policyStore.get(policyKey(canonical)).isEmpty()) {
                policyStore.get(policyKey(legacy)).ifPresent(policy -> {
                    policyStore.put(policyKey(canonical), policy);
                    policyStore.delete(policyKey(legacy));
                });
            }
            return null;
        });
    }

    public StoredServiceAccount updateServiceAccount(String project, String emailOrId,
            String displayName, String description) {
        LOG.debugf("updateServiceAccount project=%s id=%s", project, emailOrId);
        ServiceAccountRef ref = resolve(project, emailOrId);
        String key = saKey(ref.project(), ref.email());
        StoredServiceAccount sa = saStore.get(key)
                .orElseThrow(() -> GcpException.notFound("Service account not found: " + ref.email()));
        if (displayName != null) {
            sa.setDisplayName(displayName);
        }
        if (description != null) {
            sa.setDescription(description);
        }
        saStore.put(key, sa);
        return sa;
    }

    public List<StoredServiceAccount> listServiceAccounts(String project) {
        String prefix = "sa:" + project + ":";
        return saStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteServiceAccount(String project, String emailOrId) {
        ServiceAccountRef ref = resolve(project, emailOrId);
        String key = saKey(ref.project(), ref.email());
        saStore.get(key).orElseThrow(() -> GcpException.notFound("Service account not found: " + ref.email()));
        saStore.delete(key);
        LOG.debugf("deleteServiceAccount project=%s email=%s", ref.project(), ref.email());
    }

    // ── IAM Policies ───────────────────────────────────────────────────────────

    // GCP returns this fixed etag for a policy that has never been set.
    static final String EMPTY_POLICY_ETAG = "ACAB";

    /**
     * Registers an existence check for policy resources matching {@code pattern},
     * a slash-segmented template where {@code *} matches exactly one segment.
     * The resolver throws {@link GcpException} not-found for missing resources.
     * Resources matching no pattern skip the check, preserving permissive
     * behavior for services that have not opted in.
     */
    public void registerPolicyResourceResolver(String pattern, Consumer<String> requireExists) {
        policyResolvers.put(pattern, requireExists);
    }

    public StoredPolicy getPolicy(String resource) {
        String key = policyKey(resource);
        synchronized (policyLock(key)) {
            requireResourceExists(resource);
            return policyStore.get(key).orElseGet(IamService::emptyPolicy);
        }
    }

    public StoredPolicy setPolicy(String resource, StoredPolicy policy) {
        String key = policyKey(resource);
        // Serializes existence check + etag read-compare-write per resource, both
        // against concurrent setPolicy (two writers passing the etag check on the
        // same currentEtag would silently clobber one another) and against
        // deletePolicy (a write slipping in after resource deletion would
        // resurrect the policy when the same name is recreated).
        synchronized (policyLock(key)) {
            requireResourceExists(resource);
            String currentEtag = policyStore.get(key)
                    .map(StoredPolicy::getEtag)
                    .orElse(EMPTY_POLICY_ETAG);
            String requestEtag = policy.getEtag();
            if (requestEtag != null && !requestEtag.isEmpty() && !requestEtag.equals(currentEtag)) {
                throw GcpException.aborted(
                        "There were concurrent policy changes. Please retry the whole read-modify-write with exponential backoff. "
                                + "The request's ETag '" + requestEtag + "' did not match the current policy's ETag '" + currentEtag + "'.");
            }
            if (authorization != null) {
                authorization.get().validatePolicyWrite(resource, policy);
            }
            policy.setEtag(newEtag());
            policyStore.put(key, policy);
            return policy;
        }
    }

    public <T> T withPolicyLock(String resource, Supplier<T> action) {
        return withPolicyLocks(List.of(resource), action);
    }

    /** Acquires the policy-lock stripes in canonical order before running {@code action}. */
    public <T> T withPolicyLocks(List<String> resources, Supplier<T> action) {
        List<Object> locks = resources.stream()
                .map(IamService::policyKey)
                .map(IamService::policyLockIndex)
                .distinct()
                .sorted()
                .map(index -> POLICY_LOCKS[index])
                .toList();
        return withPolicyLocks(locks, 0, action);
    }

    private static <T> T withPolicyLocks(List<Object> locks, int index, Supplier<T> action) {
        if (index == locks.size()) {
            return action.get();
        }
        synchronized (locks.get(index)) {
            return withPolicyLocks(locks, index + 1, action);
        }
    }

    /**
     * Creates a resource and establishes its initial policy as one lifecycle
     * transition. Any policy left by an older holder of the same resource name
     * is removed before the new resource becomes observable to policy readers.
     * The resource callback controls its mutation lock and invokes the supplied
     * policy transition before publishing the completed resource. If the policy
     * durability boundary fails, the previous policy state is restored through
     * a checked durability boundary before the failure escapes.
     */
    public <T> T createResourceAndPolicy(String resource, StoredPolicy initialPolicy,
            Function<Runnable, T> createResource) {
        String key = policyKey(resource);
        synchronized (policyLock(key)) {
            Optional<StoredPolicy> previousPolicy = policyStore.get(key);
            return createResource.apply(() -> establishInitialPolicy(
                    resource, key, initialPolicy, previousPolicy));
        }
    }

    private void establishInitialPolicy(String resource, String key, StoredPolicy initialPolicy,
            Optional<StoredPolicy> previousPolicy) {
        try {
            policyStore.delete(key);
            if (initialPolicy != null) {
                setPolicy(resource, initialPolicy);
            }
            policyStore.checkpoint();
        } catch (RuntimeException | Error failure) {
            rollbackPolicyCreation(key, previousPolicy, failure);
            throw failure;
        }
    }

    private void rollbackPolicyCreation(String key, Optional<StoredPolicy> previousPolicy,
            Throwable failure) {
        try {
            if (previousPolicy.isPresent()) {
                policyStore.put(key, previousPolicy.get());
            } else {
                policyStore.delete(key);
            }
            policyStore.checkpoint();
        } catch (RuntimeException | Error rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    public void deletePolicy(String resource) {
        String key = policyKey(resource);
        synchronized (policyLock(key)) {
            policyStore.delete(key);
        }
    }

    /**
     * Runs {@code deleteResource} and removes the resource's policy under the
     * same lock that guards policy reads and writes. Owning services call this
     * from their delete paths so no policy operation can interleave between
     * resource removal and policy cleanup — a write landing in that gap (e.g.
     * on a just-recreated name) would otherwise be silently erased by the
     * trailing cleanup, and a read could see the previous name-holder's
     * bindings.
     */
    public void deleteResourceAndPolicy(String resource, Runnable deleteResource) {
        String key = policyKey(resource);
        synchronized (policyLock(key)) {
            deleteResource.run();
            policyStore.delete(key);
            policyStore.flush();
        }
    }

    /** Deletes the policy only when the owning service actually deletes the resource. */
    public boolean deleteResourceAndPolicyIf(String resource, BooleanSupplier deleteResource) {
        String key = policyKey(resource);
        synchronized (policyLock(key)) {
            if (!deleteResource.getAsBoolean()) {
                return false;
            }
            policyStore.delete(key);
            policyStore.flush();
            return true;
        }
    }

    public void deleteResourceAndPolicyDurably(String resource, Runnable deleteResource) {
        String key = policyKey(resource);
        synchronized (policyLock(key)) {
            deleteResource.run();
            policyStore.delete(key);
            policyStore.checkpoint();
        }
    }

    /** Evaluates supported resources in enforce mode; missing resources always return an empty set. */
    public List<String> testPermissions(String resource, List<String> permissions) {
        try {
            requireResourceExists(resource);
        } catch (GcpException e) {
            if (e.getHttpStatus() == 404) {
                return List.of();
            }
            throw e;
        }
        return authorization == null ? permissions : authorization.get().testPermissions(resource, permissions);
    }

    /** Reads an allow policy without authorizing the policy read itself or requiring resource existence. */
    public StoredPolicy policyForEvaluation(String resource) {
        String key = policyKey(resource);
        synchronized (policyLock(key)) {
            return policyStore.get(key).orElseGet(IamService::emptyPolicy);
        }
    }

    private void requireResourceExists(String resource) {
        for (Map.Entry<String, Consumer<String>> entry : policyResolvers.entrySet()) {
            if (matchesPattern(entry.getKey(), resource)) {
                entry.getValue().accept(resource);
                return;
            }
        }
    }

    private static boolean matchesPattern(String pattern, String resource) {
        String[] p = pattern.split("/");
        String[] r = resource.split("/");
        if (p.length != r.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (!p[i].equals("*") && !p[i].equals(r[i])) {
                return false;
            }
        }
        return true;
    }

    private static final int POLICY_LOCK_COUNT = 64;
    private static final Object[] POLICY_LOCKS = createPolicyLocks();

    private static Object[] createPolicyLocks() {
        Object[] locks = new Object[POLICY_LOCK_COUNT];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private static Object policyLock(String key) {
        return POLICY_LOCKS[policyLockIndex(key)];
    }

    private static int policyLockIndex(String key) {
        return Math.floorMod(key.hashCode(), POLICY_LOCKS.length);
    }

    private static StoredPolicy emptyPolicy() {
        StoredPolicy policy = new StoredPolicy();
        policy.setEtag(EMPTY_POLICY_ETAG);
        return policy;
    }

    private static String newEtag() {
        byte[] bytes = new byte[8];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    // ── Service Account Keys ───────────────────────────────────────────────────

    public StoredServiceAccountKey createKey(String requestProject, String emailOrId) {
        ServiceAccountRef ref = resolve(requestProject, emailOrId);
        String project = ref.project();
        String email = ref.email();
        saStore.get(saKey(project, email))
                .orElseThrow(() -> GcpException.notFound("Service account not found: " + email));
        String keyId = UUID.randomUUID().toString().replace("-", "");
        String name = "projects/" + project + "/serviceAccounts/" + email + "/keys/" + keyId;
        Instant now = Instant.now();
        Instant expiry = now.plus(3650, ChronoUnit.DAYS);

        KeyPair keyPair;
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            keyPair = gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw GcpException.internal("Key generation failed: " + e.getMessage());
        }

        String privateKeyPem = "-----BEGIN RSA PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                        .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END RSA PRIVATE KEY-----\n";
        String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                        .encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";

        Map<String, String> jsonKey = new LinkedHashMap<>();
        jsonKey.put("type", "service_account");
        jsonKey.put("project_id", project);
        jsonKey.put("private_key_id", keyId);
        jsonKey.put("private_key", privateKeyPem);
        jsonKey.put("client_email", email);
        jsonKey.put("client_id", "");
        jsonKey.put("auth_uri", "https://accounts.google.com/o/oauth2/auth");
        jsonKey.put("token_uri", "https://oauth2.googleapis.com/token");

        String jsonKeyStr;
        try {
            jsonKeyStr = new ObjectMapper().writeValueAsString(jsonKey);
        } catch (Exception e) {
            throw GcpException.internal("JSON serialization failed: " + e.getMessage());
        }

        StoredServiceAccountKey key = new StoredServiceAccountKey();
        key.setName(name);
        key.setKeyId(keyId);
        key.setPrivateKeyData(Base64.getEncoder().encodeToString(jsonKeyStr.getBytes(StandardCharsets.UTF_8)));
        key.setPublicKeyData(Base64.getEncoder().encodeToString(publicKeyPem.getBytes(StandardCharsets.UTF_8)));
        key.setValidAfterTime(now.toString());
        key.setValidBeforeTime(expiry.toString());
        keyStore.put(keyStorageKey(project, email, keyId), key);
        LOG.debugf("createKey project=%s email=%s keyId=%s", project, email, keyId);
        return key;
    }

    public StoredServiceAccountKey getKey(String project, String emailOrId, String keyId) {
        ServiceAccountRef ref = resolve(project, emailOrId);
        return keyStore.get(keyStorageKey(ref.project(), ref.email(), keyId))
                .orElseThrow(() -> GcpException.notFound("Key not found: " + keyId));
    }

    public List<StoredServiceAccountKey> listKeys(String project, String emailOrId) {
        ServiceAccountRef ref = resolve(project, emailOrId);
        String prefix = "key:" + ref.project() + ":" + ref.email() + ":";
        return keyStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteKey(String project, String emailOrId, String keyId) {
        ServiceAccountRef ref = resolve(project, emailOrId);
        String storageKey = keyStorageKey(ref.project(), ref.email(), keyId);
        keyStore.get(storageKey).orElseThrow(() -> GcpException.notFound("Key not found: " + keyId));
        keyStore.delete(storageKey);
        LOG.debugf("deleteKey project=%s email=%s keyId=%s", ref.project(), ref.email(), keyId);
    }

    public Map<String, String> signBlob(String project, String emailOrId, String bytesToSignBase64) {
        ServiceAccountRef ref = resolve(project, emailOrId);
        saStore.get(saKey(ref.project(), ref.email()))
                .orElseThrow(() -> GcpException.notFound("Service account not found: " + ref.email()));
        byte[] inputBytes = Base64.getDecoder().decode(bytesToSignBase64);
        byte[] signature;
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            signature = sha256.digest(inputBytes);
        } catch (NoSuchAlgorithmException e) {
            throw GcpException.internal("SHA-256 not available");
        }
        List<StoredServiceAccountKey> keys = listKeys(ref.project(), ref.email());
        String keyId = keys.isEmpty() ? "stub-key-id" : keys.get(0).getKeyId();
        return Map.of("keyId", keyId, "signedBlob", Base64.getEncoder().encodeToString(signature));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private record ServiceAccountRef(String project, String email) {}

    /**
     * Resolves the owning project and email of a service account. With the {@code -} project
     * wildcard a missing account is reported as PERMISSION_DENIED, as documented for
     * {@code projects/-/serviceAccounts/{EMAIL_ADDRESS|UNIQUE_ID}} in iam-v1.
     */
    private ServiceAccountRef resolve(String project, String emailOrId) {
        if (!WILDCARD_PROJECT.equals(project)) {
            if (isUniqueId(emailOrId)) {
                String prefix = "sa:" + project + ":";
                Optional<ServiceAccountRef> byUniqueId = saStore.scan(k -> k.startsWith(prefix)).stream()
                        .filter(sa -> emailOrId.equals(sa.getUniqueId()))
                        .findFirst()
                        .map(sa -> new ServiceAccountRef(project, sa.getEmail()));
                if (byUniqueId.isPresent()) {
                    return byUniqueId.get();
                }
            }
            return new ServiceAccountRef(project, resolveEmail(project, emailOrId));
        }
        String owner = projectFromEmail(emailOrId);
        if (owner != null && saStore.get(saKey(owner, emailOrId)).isPresent()) {
            return new ServiceAccountRef(owner, emailOrId);
        }
        return saStore.scan(k -> k.startsWith("sa:")).stream()
                .filter(sa -> emailOrId.equals(sa.getEmail()) || emailOrId.equals(sa.getUniqueId()))
                .findFirst()
                .map(sa -> new ServiceAccountRef(sa.getProjectId(), sa.getEmail()))
                .orElseThrow(() -> GcpException.permissionDenied(
                        "Permission denied on resource (or it may not exist): projects/-/serviceAccounts/"
                                + emailOrId));
    }

    // GCP account IDs must start with a letter, so an all-digit identifier is tried as a unique ID
    // first; accounts created here before that rule fall back to the account ID form.
    private static boolean isUniqueId(String emailOrId) {
        return !emailOrId.isEmpty() && emailOrId.chars().allMatch(Character::isDigit);
    }

    // GCP unique IDs are 21-digit numbers. Random rather than sequential, so IDs stay unique
    // across restarts with persistent storage.
    private String newUniqueId() {
        Set<String> taken = saStore.scan(k -> k.startsWith("sa:")).stream()
                .map(StoredServiceAccount::getUniqueId)
                .collect(Collectors.toSet());
        String id;
        do {
            StringBuilder digits = new StringBuilder("1");
            for (int i = 0; i < 20; i++) {
                digits.append(ThreadLocalRandom.current().nextInt(10));
            }
            id = digits.toString();
        } while (taken.contains(id));
        return id;
    }

    private static String projectFromEmail(String email) {
        int at = email.indexOf('@');
        if (at < 0 || !email.endsWith(SA_EMAIL_DOMAIN)) {
            return null;
        }
        return email.substring(at + 1, email.length() - SA_EMAIL_DOMAIN.length());
    }

    private static String resolveEmail(String project, String emailOrId) {
        return emailOrId.contains("@") ? emailOrId : emailOrId + "@" + project + ".iam.gserviceaccount.com";
    }

    private static String saKey(String project, String email) {
        return "sa:" + project + ":" + email;
    }

    private static String policyKey(String resource) {
        return "policy:" + resource;
    }

    private static String keyStorageKey(String project, String email, String keyId) {
        return "key:" + project + ":" + email + ":" + keyId;
    }
}

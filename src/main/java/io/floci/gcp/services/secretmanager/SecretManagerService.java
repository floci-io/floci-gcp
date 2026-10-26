package io.floci.gcp.services.secretmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.iam.v1.Policy;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageException;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.iam.IamPolicyCodec;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.secretmanager.model.StoredSecret;
import io.floci.gcp.services.secretmanager.model.StoredSecretVersion;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@ApplicationScoped
public class SecretManagerService {

    private static final Logger LOG = Logger.getLogger(SecretManagerService.class);
    private static final int SECRET_LOCK_STRIPES = 256;
    private final Object[] secretLocks = createSecretLocks();

    private final StorageBackend<String, StoredSecret> secretStore;
    private final StorageBackend<String, StoredSecretVersion> versionStore;
    private final StorageBackend<String, String> pendingDeletionStore;

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final GrpcServerManager grpcServerManager;
    private final IamService iamService;
    private final IamAuthorizationService authorization;
    private final SecretManagerIamAuthorizationAdapter iamAdapter;

    @Inject
    public SecretManagerService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, GrpcServerManager grpcServerManager, IamService iamService,
            IamAuthorizationService authorization, SecretManagerIamAuthorizationAdapter iamAdapter) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
        this.iamService = iamService;
        this.authorization = authorization;
        this.iamAdapter = iamAdapter;
        this.secretStore = storageFactory.createGlobal("secretmanager-secrets", "secretmanager-secrets.json",
                new TypeReference<Map<String, StoredSecret>>() {});
        this.versionStore = storageFactory.createGlobal("secretmanager-versions", "secretmanager-versions.json",
                new TypeReference<Map<String, StoredSecretVersion>>() {});
        this.pendingDeletionStore = storageFactory.createGlobal("secretmanager-deletions", "secretmanager-deletions.json",
                new TypeReference<Map<String, String>>() {});
    }

    SecretManagerService(StorageBackend<String, StoredSecret> secretStore,
            StorageBackend<String, StoredSecretVersion> versionStore,
            StorageBackend<String, String> pendingDeletionStore, IamService iamService) {
        this(secretStore, versionStore, pendingDeletionStore, iamService, null, null);
    }

    SecretManagerService(StorageBackend<String, StoredSecret> secretStore,
            StorageBackend<String, StoredSecretVersion> versionStore,
            StorageBackend<String, String> pendingDeletionStore, IamService iamService,
            IamAuthorizationService authorization, SecretManagerIamAuthorizationAdapter iamAdapter) {
        this.secretStore = secretStore;
        this.versionStore = versionStore;
        this.pendingDeletionStore = pendingDeletionStore;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
        this.iamService = iamService;
        this.authorization = authorization;
        this.iamAdapter = iamAdapter;
        registerPolicyResolver();
    }

    void onStart(@Observes StartupEvent ev) {
        resumePendingDeletions();
        serviceRegistry.register(ServiceDescriptor.builder("secretmanager")
                .enabled(config.services().secretmanager().enabled())
                .storageKey("secretmanager")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(SecretManagerController.class, SecretManagerHttpController.class,
                        SecretManagerRegionalHttpController.class)
                .api("secretmanager.googleapis.com", "Secret Manager API")
                .build());
        grpcServerManager.bind(new SecretManagerController(this));
        registerPolicyResolver();
    }

    private void registerPolicyResolver() {
        iamService.registerPolicyResourceResolver("projects/*/secrets/*", this::requireSecretExists);
    }

    // ── Secrets ────────────────────────────────────────────────────────────────

    public StoredSecret createSecret(String project, String secretId, String replicationType) {
        String name = "projects/" + project + "/secrets/" + secretId;
        LOG.infof("createSecret name=%s", name);
        synchronized (secretLock(name)) {
            return authorized("CreateSecret", "projects/" + project, name, () -> {
                try {
                    resumePendingDeletion(name);
                } catch (StorageException e) {
                    throw GcpException.unavailable("Pending secret deletion could not be recovered. Retry the request.");
                }
                if (secretStore.get(name).isPresent()) {
                    throw GcpException.alreadyExists("Secret already exists: " + name);
                }
                StoredSecret secret = new StoredSecret(name, Instant.now().toString(), replicationType);
                secretStore.put(name, secret);
                return secret;
            });
        }
    }

    public StoredSecret getSecret(String name) {
        LOG.debugf("getSecret name=%s", name);
        synchronized (secretLock(name)) {
            return authorized("GetSecret", name, () -> requireSecretExists(name));
        }
    }

    private StoredSecret requireSecretExists(String name) {
        if (pendingDeletionStore.get(name).isPresent()) {
            throw GcpException.notFound("Secret not found: " + name);
        }
        return secretStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Secret not found: " + name));
    }

    public List<StoredSecret> listSecrets(String project) {
        LOG.debugf("listSecrets project=%s", project);
        String prefix = "projects/" + project + "/secrets/";
        return authorized("ListSecrets", "projects/" + project,
                () -> secretStore.scan(k -> k.startsWith(prefix) && pendingDeletionStore.get(k).isEmpty()));
    }

    public StoredSecret updateSecret(String name) {
        LOG.debugf("updateSecret name=%s", name);
        synchronized (secretLock(name)) {
            return authorized("UpdateSecret", name, () -> requireSecretExists(name));
        }
    }

    public void deleteSecret(String name) {
        LOG.infof("deleteSecret name=%s", name);
        synchronized (secretLock(name)) {
            authorized("DeleteSecret", name, () -> {
                if (pendingDeletionStore.get(name).isPresent()) {
                    try {
                        resumePendingDeletion(name);
                    } catch (StorageException e) {
                        throw GcpException.unavailable("Pending secret deletion could not be recovered. Retry the request.");
                    }
                    return null;
                }
                if (secretStore.get(name).isEmpty()) {
                    throw GcpException.notFound("Secret not found: " + name);
                }
                try {
                    pendingDeletionStore.put(name, name);
                    pendingDeletionStore.checkpoint();
                    completeDeletion(name);
                    clearPendingDeletion(name);
                } catch (StorageException e) {
                    throw GcpException.unavailable("Secret deletion could not be made durable. Retry the request.");
                }
                return null;
            });
        }
    }

    void resumePendingDeletions() {
        pendingDeletionStore.scan(key -> true).forEach(name -> {
            try {
                resumePendingDeletion(name);
            } catch (StorageException e) {
                LOG.warnf(e, "Could not resume deletion for secret %s", name);
            }
        });
    }

    private void resumePendingDeletion(String name) {
        synchronized (secretLock(name)) {
            if (pendingDeletionStore.get(name).isEmpty()) {
                return;
            }
            completeDeletion(name);
            clearPendingDeletion(name);
        }
    }

    private void completeDeletion(String name) {
        iamService.deleteResourceAndPolicyDurably(name, () -> {
            String versionPrefix = name + "/versions/";
            versionStore.scan(k -> k.startsWith(versionPrefix))
                    .forEach(v -> versionStore.delete(v.getName()));
            secretStore.delete(name);
        });
        versionStore.checkpoint();
        secretStore.checkpoint();
    }

    private void clearPendingDeletion(String name) {
        pendingDeletionStore.delete(name);
        try {
            pendingDeletionStore.checkpoint();
        } catch (StorageException e) {
            pendingDeletionStore.put(name, name);
            throw e;
        }
    }

    public Policy getIamPolicy(String resource) {
        synchronized (secretLock(resource)) {
            return authorized("GetIamPolicy", resource, () -> {
                requireSecretExists(resource);
                return IamPolicyCodec.toProtoPolicy(iamService.getPolicy(resource));
            });
        }
    }

    public Policy setIamPolicy(String resource, Policy policy) {
        synchronized (secretLock(resource)) {
            return authorized("SetIamPolicy", resource, () -> {
                requireSecretExists(resource);
                StoredPolicy stored = IamPolicyCodec.toStoredPolicy(policy);
                return IamPolicyCodec.toProtoPolicy(iamService.setPolicy(resource, stored));
            });
        }
    }

    public List<String> testIamPermissions(String resource, List<String> permissions) {
        synchronized (secretLock(resource)) {
            return authorized("TestIamPermissions", resource, () -> iamService.testPermissions(resource, permissions));
        }
    }

    // ── Versions ───────────────────────────────────────────────────────────────

    public StoredSecretVersion addSecretVersion(String secretName, byte[] payload, Long dataCrc32c) {
        LOG.infof("addSecretVersion secret=%s", secretName);
        synchronized (secretLock(secretName)) {
            return authorized("AddSecretVersion", secretName, () -> {
                requireSecretExists(secretName);
                int versionNumber = nextVersionNumber(secretName);
                String versionName = secretName + "/versions/" + versionNumber;
                StoredSecretVersion version = new StoredSecretVersion(versionName, versionNumber,
                        Instant.now().toString(), payload, dataCrc32c);
                versionStore.put(versionName, version);
                return version;
            });
        }
    }

    public StoredSecretVersion getSecretVersion(String versionedName) {
        LOG.debugf("getSecretVersion name=%s", versionedName);
        String secretName = secretNameForVersion(versionedName);
        synchronized (secretLock(secretName)) {
            return authorized("GetSecretVersion", versionedName, () -> {
                requireSecretExists(secretName);
                return resolveVersion(versionedName);
            });
        }
    }

    public List<StoredSecretVersion> listSecretVersions(String secretName) {
        LOG.debugf("listSecretVersions secret=%s", secretName);
        synchronized (secretLock(secretName)) {
            return authorized("ListSecretVersions", secretName, () -> {
                requireSecretExists(secretName);
                String prefix = secretName + "/versions/";
                return versionStore.scan(k -> k.startsWith(prefix));
            });
        }
    }

    public StoredSecretVersion accessSecretVersion(String versionedName) {
        LOG.debugf("accessSecretVersion name=%s", versionedName);
        String secretName = secretNameForVersion(versionedName);
        synchronized (secretLock(secretName)) {
            return authorized("AccessSecretVersion", versionedName, () -> {
                requireSecretExists(secretName);
                StoredSecretVersion version = resolveVersion(versionedName);
                if ("DESTROYED".equals(version.getState()) || "DISABLED".equals(version.getState())) {
                    throw GcpException.failedPrecondition(
                            "Secret Version [" + version.getName() + "] is in " + version.getState() + " state.");
                }
                return version;
            });
        }
    }

    public StoredSecretVersion disableSecretVersion(String versionedName) {
        LOG.infof("disableSecretVersion name=%s", versionedName);
        return updateSecretVersion("DisableSecretVersion", versionedName, version -> version.setState("DISABLED"));
    }

    public StoredSecretVersion enableSecretVersion(String versionedName) {
        LOG.infof("enableSecretVersion name=%s", versionedName);
        return updateSecretVersion("EnableSecretVersion", versionedName, version -> version.setState("ENABLED"));
    }

    public StoredSecretVersion destroySecretVersion(String versionedName) {
        LOG.infof("destroySecretVersion name=%s", versionedName);
        return updateSecretVersion("DestroySecretVersion", versionedName, version -> {
            version.setState("DESTROYED");
            version.setPayload(null);
            version.setDestroyTime(Instant.now().toString());
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private int nextVersionNumber(String secretName) {
        String prefix = secretName + "/versions/";
        return versionStore.scan(k -> k.startsWith(prefix)).size() + 1;
    }

    private StoredSecretVersion resolveVersion(String versionedName) {
        if (versionedName.endsWith("/versions/latest")) {
            String secretName = versionedName.substring(0, versionedName.length() - "/versions/latest".length());
            String prefix = secretName + "/versions/";
            return versionStore.scan(k -> k.startsWith(prefix)).stream()
                    .max(Comparator.comparingInt(StoredSecretVersion::getVersionNumber))
                    .orElseThrow(() -> GcpException.notFound(
                            "Secret [" + secretName + "] not found or has no versions."));
        }
        return versionStore.get(versionedName)
                .orElseThrow(() -> GcpException.notFound("Version not found: " + versionedName));
    }

    private StoredSecretVersion updateSecretVersion(String operationName, String versionedName,
            java.util.function.Consumer<StoredSecretVersion> update) {
        String secretName = secretNameForVersion(versionedName);
        synchronized (secretLock(secretName)) {
            return authorized(operationName, versionedName, () -> {
                requireSecretExists(secretName);
                StoredSecretVersion version = resolveVersion(versionedName);
                update.accept(version);
                versionStore.put(version.getName(), version);
                return version;
            });
        }
    }

    private String secretNameForVersion(String versionedName) {
        int index = versionedName.indexOf("/versions/");
        if (index < 0) {
            throw GcpException.notFound("Version not found: " + versionedName);
        }
        return versionedName.substring(0, index);
    }

    private Object secretLock(String name) {
        return secretLocks[Math.floorMod(name.hashCode(), secretLocks.length)];
    }

    private <T> T authorized(String operationName, String target, Supplier<T> action) {
        return authorized(operationName, target, null, action);
    }

    private <T> T authorized(String operationName, String target, String additionalPolicyResource,
            Supplier<T> action) {
        if (authorization == null) {
            return action.get();
        }
        IamOperation operation = new IamOperation(operationName, target);
        IamResource resource = iamAdapter.checks(operation).stream()
                .map(check -> check.resource())
                .findFirst()
                .orElseGet(() -> iamAdapter.requireResource(target));
        Supplier<T> checked = () -> {
            authorization.authorizeCurrent(iamAdapter, operation);
            return action.get();
        };
        if (additionalPolicyResource != null) {
            IamResource secret = IamResource.projectChild("secretmanager.googleapis.com", "Secret",
                    additionalPolicyResource, additionalPolicyResource);
            return authorization.withPolicyLocks(List.of(resource, secret), checked);
        }
        return authorization.withPolicyLocks(resource, checked);
    }

    private static Object[] createSecretLocks() {
        Object[] locks = new Object[SECRET_LOCK_STRIPES];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }
}

package io.floci.gcp.services.secretmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.secretmanager.model.StoredSecret;
import io.floci.gcp.services.secretmanager.model.StoredSecretVersion;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SecretManagerService {

    private static final Logger LOG = Logger.getLogger(SecretManagerService.class);

    private final StorageBackend<String, StoredSecret> secretStore;
    private final StorageBackend<String, StoredSecretVersion> versionStore;

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final GrpcServerManager grpcServerManager;

    @Inject
    public SecretManagerService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, GrpcServerManager grpcServerManager) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
        this.secretStore = storageFactory.createGlobal("secretmanager-secrets", "secretmanager-secrets.json",
                new TypeReference<Map<String, StoredSecret>>() {});
        this.versionStore = storageFactory.createGlobal("secretmanager-versions", "secretmanager-versions.json",
                new TypeReference<Map<String, StoredSecretVersion>>() {});
    }

    SecretManagerService(StorageBackend<String, StoredSecret> secretStore,
            StorageBackend<String, StoredSecretVersion> versionStore) {
        this.secretStore = secretStore;
        this.versionStore = versionStore;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("secretmanager")
                .enabled(config.services().secretmanager().enabled())
                .storageKey("secretmanager")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(SecretManagerController.class)
                .build());
        grpcServerManager.bind(new SecretManagerController(this));
    }

    // ── Secrets ────────────────────────────────────────────────────────────────

    public StoredSecret createSecret(String project, String secretId, String replicationType) {
        String name = "projects/" + project + "/secrets/" + secretId;
        LOG.infof("createSecret name=%s", name);
        if (secretStore.get(name).isPresent()) {
            throw GcpException.alreadyExists("Secret already exists: " + name);
        }
        StoredSecret secret = new StoredSecret(name, Instant.now().toString(), replicationType);
        secretStore.put(name, secret);
        return secret;
    }

    public StoredSecret getSecret(String name) {
        LOG.debugf("getSecret name=%s", name);
        return secretStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Secret not found: " + name));
    }

    public List<StoredSecret> listSecrets(String project) {
        LOG.debugf("listSecrets project=%s", project);
        String prefix = "projects/" + project + "/secrets/";
        return secretStore.scan(k -> k.startsWith(prefix));
    }

    public StoredSecret updateSecret(String name) {
        LOG.debugf("updateSecret name=%s", name);
        return secretStore.get(name)
                .orElseThrow(() -> GcpException.notFound("Secret not found: " + name));
    }

    public void deleteSecret(String name) {
        LOG.infof("deleteSecret name=%s", name);
        if (secretStore.get(name).isEmpty()) {
            throw GcpException.notFound("Secret not found: " + name);
        }
        String versionPrefix = name + "/versions/";
        versionStore.scan(k -> k.startsWith(versionPrefix))
                .forEach(v -> versionStore.delete(v.getName()));
        secretStore.delete(name);
    }

    // ── Versions ───────────────────────────────────────────────────────────────

    public StoredSecretVersion addSecretVersion(String secretName, byte[] payload, Long dataCrc32c) {
        LOG.infof("addSecretVersion secret=%s", secretName);
        if (secretStore.get(secretName).isEmpty()) {
            throw GcpException.notFound("Secret not found: " + secretName);
        }
        int versionNumber = nextVersionNumber(secretName);
        String versionName = secretName + "/versions/" + versionNumber;
        StoredSecretVersion version = new StoredSecretVersion(versionName, versionNumber,
                Instant.now().toString(), payload, dataCrc32c);
        versionStore.put(versionName, version);
        return version;
    }

    public StoredSecretVersion getSecretVersion(String versionedName) {
        LOG.debugf("getSecretVersion name=%s", versionedName);
        return resolveVersion(versionedName);
    }

    public List<StoredSecretVersion> listSecretVersions(String secretName) {
        LOG.debugf("listSecretVersions secret=%s", secretName);
        String prefix = secretName + "/versions/";
        return versionStore.scan(k -> k.startsWith(prefix));
    }

    public StoredSecretVersion accessSecretVersion(String versionedName) {
        LOG.debugf("accessSecretVersion name=%s", versionedName);
        StoredSecretVersion version = resolveVersion(versionedName);
        if ("DESTROYED".equals(version.getState())) {
            throw GcpException.notFound("Secret version is destroyed: " + version.getName());
        }
        if ("DISABLED".equals(version.getState())) {
            throw GcpException.invalidArgument("Secret version is disabled: " + version.getName());
        }
        return version;
    }

    public StoredSecretVersion disableSecretVersion(String versionedName) {
        LOG.infof("disableSecretVersion name=%s", versionedName);
        StoredSecretVersion version = resolveVersion(versionedName);
        version.setState("DISABLED");
        versionStore.put(version.getName(), version);
        return version;
    }

    public StoredSecretVersion enableSecretVersion(String versionedName) {
        LOG.infof("enableSecretVersion name=%s", versionedName);
        StoredSecretVersion version = resolveVersion(versionedName);
        version.setState("ENABLED");
        versionStore.put(version.getName(), version);
        return version;
    }

    public StoredSecretVersion destroySecretVersion(String versionedName) {
        LOG.infof("destroySecretVersion name=%s", versionedName);
        StoredSecretVersion version = resolveVersion(versionedName);
        version.setState("DESTROYED");
        version.setPayload(null);
        version.setDestroyTime(Instant.now().toString());
        versionStore.put(version.getName(), version);
        return version;
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
                    .filter(v -> "ENABLED".equals(v.getState()))
                    .max(Comparator.comparingInt(StoredSecretVersion::getVersionNumber))
                    .orElseThrow(() -> GcpException.notFound("No enabled version for: " + secretName));
        }
        return versionStore.get(versionedName)
                .orElseThrow(() -> GcpException.notFound("Version not found: " + versionedName));
    }
}

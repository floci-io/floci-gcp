package io.floci.gcp.services.secretmanager;

import com.google.iam.v1.Binding;
import com.google.iam.v1.Policy;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.IamServices;
import io.floci.gcp.services.secretmanager.model.StoredSecret;
import io.floci.gcp.services.secretmanager.model.StoredSecretVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecretManagerServiceTest {

    private SecretManagerService service;
    private InMemoryStorage<String, StoredSecret> secretStore;
    private InMemoryStorage<String, StoredSecretVersion> versionStore;
    private InMemoryStorage<String, String> pendingDeletionStore;
    private IamService iamService;

    @BeforeEach
    void setUp() {
        secretStore = new InMemoryStorage<>();
        versionStore = new InMemoryStorage<>();
        pendingDeletionStore = new InMemoryStorage<>();
        iamService = IamServices.inMemory();
        service = new SecretManagerService(secretStore, versionStore, pendingDeletionStore, iamService);
    }

    @Test
    void createSecretStoredAndRetrievable() {
        service.createSecret("p1", "my-secret", "automatic");

        StoredSecret secret = service.getSecret("projects/p1/secrets/my-secret");
        assertEquals("projects/p1/secrets/my-secret", secret.getName());
    }

    @Test
    void createSecretDuplicateThrowsAlreadyExists() {
        service.createSecret("p1", "my-secret", "automatic");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.createSecret("p1", "my-secret", "automatic"));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
    }

    @Test
    void getSecretMissingThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.getSecret("projects/p1/secrets/missing"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void addSecretVersionCreatesEnabledVersionWithIncrementingNumber() {
        service.createSecret("p1", "s1", "automatic");

        byte[] payload = "value1".getBytes(StandardCharsets.UTF_8);
        StoredSecretVersion v1 = service.addSecretVersion("projects/p1/secrets/s1", payload, null);
        StoredSecretVersion v2 = service.addSecretVersion("projects/p1/secrets/s1", payload, null);

        assertEquals("ENABLED", v1.getState());
        assertEquals("ENABLED", v2.getState());
        assertEquals(1, v1.getVersionNumber());
        assertEquals(2, v2.getVersionNumber());
    }

    @Test
    void accessSecretVersionLatestReturnsPayload() {
        service.createSecret("p1", "s1", "automatic");
        byte[] payload = "my-password".getBytes(StandardCharsets.UTF_8);
        service.addSecretVersion("projects/p1/secrets/s1", payload, null);

        StoredSecretVersion version = service.accessSecretVersion(
                "projects/p1/secrets/s1/versions/latest");
        assertArrayEquals(payload, version.getPayload());
    }

    @Test
    void accessDisabledVersionThrowsFailedPrecondition() {
        service.createSecret("p1", "s1", "automatic");
        service.addSecretVersion("projects/p1/secrets/s1", new byte[]{1}, null);
        service.disableSecretVersion("projects/p1/secrets/s1/versions/1");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.accessSecretVersion("projects/p1/secrets/s1/versions/1"));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void destroySecretVersionClearsPayload() {
        service.createSecret("p1", "s1", "automatic");
        service.addSecretVersion("projects/p1/secrets/s1", new byte[]{1, 2, 3}, null);

        StoredSecretVersion destroyed = service.disableSecretVersion(
                "projects/p1/secrets/s1/versions/1");
        assertEquals("DISABLED", destroyed.getState());
    }

    @Test
    void enableSecretVersionAllowsAccess() {
        service.createSecret("p1", "s1", "automatic");
        byte[] payload = "re-enabled".getBytes(StandardCharsets.UTF_8);
        service.addSecretVersion("projects/p1/secrets/s1", payload, null);
        service.disableSecretVersion("projects/p1/secrets/s1/versions/1");

        service.enableSecretVersion("projects/p1/secrets/s1/versions/1");
        StoredSecretVersion version = service.accessSecretVersion(
                "projects/p1/secrets/s1/versions/1");
        assertArrayEquals(payload, version.getPayload());
    }

    @Test
    void deleteSecretCascadesVersions() {
        service.createSecret("p1", "s1", "automatic");
        service.addSecretVersion("projects/p1/secrets/s1", new byte[]{1}, null);

        service.deleteSecret("projects/p1/secrets/s1");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.getSecret("projects/p1/secrets/s1"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());

        List<StoredSecretVersion> versions = service.listSecretVersions("projects/p1/secrets/s1");
        assertTrue(versions.isEmpty());
    }

    @Test
    void listSecretsFiltersByProject() {
        service.createSecret("p1", "s1", "automatic");
        service.createSecret("p1", "s2", "automatic");

        List<StoredSecret> secrets = service.listSecrets("p1");
        assertEquals(2, secrets.size());
        assertTrue(secrets.stream().allMatch(s -> s.getName().startsWith("projects/p1")));
    }

    @Test
    void iamPolicyRoundTripsRejectsStaleEtagAndIsClearedOnDelete() {
        String resource = "projects/p1/secrets/iam-target";
        service.createSecret("p1", "iam-target", "automatic");

        Policy saved = service.setIamPolicy(resource, Policy.newBuilder()
                .addBindings(Binding.newBuilder()
                        .setRole("roles/secretmanager.secretAccessor")
                        .addMembers("user:reader@example.com"))
                .build());

        assertEquals("roles/secretmanager.secretAccessor", service.getIamPolicy(resource)
                .getBindings(0).getRole());
        assertThrows(GcpException.class, () -> service.setIamPolicy(resource, Policy.newBuilder()
                .setEtag(com.google.protobuf.ByteString.copyFromUtf8("stale"))
                .build()));
        assertEquals(saved.getEtag(), service.getIamPolicy(resource).getEtag());

        service.deleteSecret(resource);
        assertThrows(GcpException.class, () -> service.getIamPolicy(resource));

        service.createSecret("p1", "iam-target", "automatic");
        assertTrue(service.getIamPolicy(resource).getBindingsList().isEmpty());
    }

    @Test
    void resumesInterruptedDeletionBeforeRecreatingSecret() {
        String resource = "projects/p1/secrets/interrupted-delete";
        service.createSecret("p1", "interrupted-delete", "automatic");
        service.addSecretVersion(resource, new byte[]{1}, null);
        service.setIamPolicy(resource, Policy.newBuilder()
                .addBindings(Binding.newBuilder()
                        .setRole("roles/secretmanager.secretAccessor")
                        .addMembers("user:reader@example.com"))
                .build());

        // Simulate a process stopping after durable deletion intent is recorded.
        pendingDeletionStore.put(resource, resource);
        SecretManagerService restarted = new SecretManagerService(
                secretStore, versionStore, pendingDeletionStore, iamService);

        restarted.resumePendingDeletions();
        assertThrows(GcpException.class, () -> restarted.getSecret(resource));
        assertTrue(restarted.listSecretVersions(resource).isEmpty());

        restarted.createSecret("p1", "interrupted-delete", "automatic");
        assertTrue(restarted.getIamPolicy(resource).getBindingsList().isEmpty());
    }

    @Test
    void testIamPermissionsEchoesRequestedPermissionsForExistingSecret() {
        String resource = "projects/p1/secrets/permission-target";
        service.createSecret("p1", "permission-target", "automatic");

        assertEquals(List.of("secretmanager.secrets.get", "secretmanager.secrets.delete"),
                service.testIamPermissions(resource,
                        List.of("secretmanager.secrets.get", "secretmanager.secrets.delete")));
    }
}

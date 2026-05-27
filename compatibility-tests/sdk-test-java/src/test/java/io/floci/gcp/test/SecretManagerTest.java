package io.floci.gcp.test;

import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.AddSecretVersionRequest;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.DeleteSecretRequest;
import com.google.cloud.secretmanager.v1.DisableSecretVersionRequest;
import com.google.cloud.secretmanager.v1.EnableSecretVersionRequest;
import com.google.cloud.secretmanager.v1.ListSecretVersionsRequest;
import com.google.cloud.secretmanager.v1.ProjectName;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretName;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.cloud.secretmanager.v1.SecretVersion;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecretManagerTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String SECRET_ID = TestFixtures.uniqueName("test-secret");
    private static final String SECRET_PAYLOAD = "super-secret-password-123";

    private static SecretManagerServiceClient client;
    private static String secretVersionName;

    @BeforeAll
    static void setUp() throws IOException {
        client = TestFixtures.secretManagerClient();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    void createSecret() {
        ProjectName projectName = ProjectName.of(PROJECT_ID);

        Secret secret = Secret.newBuilder()
                .setReplication(Replication.newBuilder()
                        .setAutomatic(Replication.Automatic.getDefaultInstance())
                        .build())
                .build();

        CreateSecretRequest request = CreateSecretRequest.newBuilder()
                .setParent(projectName.toString())
                .setSecretId(SECRET_ID)
                .setSecret(secret)
                .build();

        Secret createdSecret = client.createSecret(request);
        assertThat(createdSecret.getName()).endsWith(SECRET_ID);
    }

    @Test
    @Order(2)
    void addSecretVersion() {
        SecretName secretName = SecretName.of(PROJECT_ID, SECRET_ID);

        SecretPayload payload = SecretPayload.newBuilder()
                .setData(ByteString.copyFromUtf8(SECRET_PAYLOAD))
                .build();

        AddSecretVersionRequest request = AddSecretVersionRequest.newBuilder()
                .setParent(secretName.toString())
                .setPayload(payload)
                .build();

        SecretVersion version = client.addSecretVersion(request);
        secretVersionName = version.getName();

        assertThat(version.getName()).contains(SECRET_ID);
        assertThat(version.getState()).isEqualTo(SecretVersion.State.ENABLED);
    }

    @Test
    @Order(3)
    void accessSecretVersionAndVerifyPayload() {
        SecretVersionName versionName = SecretVersionName.of(PROJECT_ID, SECRET_ID, "latest");
        AccessSecretVersionResponse response = client.accessSecretVersion(versionName);

        String retrievedPayload = response.getPayload().getData().toStringUtf8();
        assertThat(retrievedPayload).isEqualTo(SECRET_PAYLOAD);
    }

    @Test
    @Order(4)
    void listSecretVersions() {
        SecretName secretName = SecretName.of(PROJECT_ID, SECRET_ID);

        ListSecretVersionsRequest request = ListSecretVersionsRequest.newBuilder()
                .setParent(secretName.toString())
                .build();

        List<SecretVersion> versions = new ArrayList<>();
        client.listSecretVersions(request).iterateAll().forEach(versions::add);

        assertThat(versions).isNotEmpty();
        assertThat(versions).allMatch(v -> v.getName().contains(SECRET_ID));
    }

    @Test
    @Order(5)
    void disableSecretVersion() {
        SecretVersion disabled = client.disableSecretVersion(
                DisableSecretVersionRequest.newBuilder()
                        .setName(secretVersionName)
                        .build());

        assertThat(disabled.getState()).isEqualTo(SecretVersion.State.DISABLED);
    }

    @Test
    @Order(6)
    void enableSecretVersion() {
        SecretVersion enabled = client.enableSecretVersion(
                EnableSecretVersionRequest.newBuilder()
                        .setName(secretVersionName)
                        .build());

        assertThat(enabled.getState()).isEqualTo(SecretVersion.State.ENABLED);
    }

    @Test
    @Order(7)
    void listSecrets() {
        ProjectName projectName = ProjectName.of(PROJECT_ID);

        List<String> secretNames = new ArrayList<>();
        client.listSecrets(projectName).iterateAll()
                .forEach(s -> secretNames.add(s.getName()));

        assertThat(secretNames).anyMatch(name -> name.endsWith(SECRET_ID));
    }

    @Test
    @Order(8)
    void deleteSecret() {
        SecretName secretName = SecretName.of(PROJECT_ID, SECRET_ID);

        DeleteSecretRequest request = DeleteSecretRequest.newBuilder()
                .setName(secretName.toString())
                .build();

        client.deleteSecret(request);

        ProjectName projectName = ProjectName.of(PROJECT_ID);
        List<String> secretNames = new ArrayList<>();
        client.listSecrets(projectName).iterateAll()
                .forEach(s -> secretNames.add(s.getName()));

        assertThat(secretNames).noneMatch(name -> name.endsWith(SECRET_ID));
    }
}

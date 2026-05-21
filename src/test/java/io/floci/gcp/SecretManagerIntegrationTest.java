package io.floci.gcp;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.cloud.secretmanager.v1.*;
import com.google.protobuf.ByteString;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecretManagerIntegrationTest {

    private static final String PROJECT_ID = "test-project";
    private static final String SECRET_ID = "integration-test-secret-" + System.currentTimeMillis();
    private static final String PAYLOAD = "super-secret-value";

    private static SecretManagerServiceClient client;

    @BeforeAll
    static void setUp() throws IOException {
        // Quarkus @QuarkusTest overrides the HTTP port to 8081 (quarkus.http.test-port
        // default). gRPC shares this port (use-separate-server=false).
        // Use 127.0.0.1 to avoid IPv6 resolution of "localhost" on some JVMs.
        SecretManagerServiceSettings settings = SecretManagerServiceSettings.newBuilder()
                .setTransportChannelProvider(
                        InstantiatingGrpcChannelProvider.newBuilder()
                                .setEndpoint("127.0.0.1:8081")
                                .setChannelConfigurator(b -> b.usePlaintext())
                                .build())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build();
        client = SecretManagerServiceClient.create(settings);
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
        Secret secret = Secret.newBuilder()
                .setReplication(Replication.newBuilder()
                        .setAutomatic(Replication.Automatic.getDefaultInstance())
                        .build())
                .build();

        Secret created = client.createSecret(CreateSecretRequest.newBuilder()
                .setParent("projects/" + PROJECT_ID)
                .setSecretId(SECRET_ID)
                .setSecret(secret)
                .build());

        assertTrue(created.getName().endsWith(SECRET_ID));
    }

    @Test
    @Order(2)
    void addSecretVersion() {
        SecretVersion version = client.addSecretVersion(AddSecretVersionRequest.newBuilder()
                .setParent("projects/" + PROJECT_ID + "/secrets/" + SECRET_ID)
                .setPayload(SecretPayload.newBuilder()
                        .setData(ByteString.copyFromUtf8(PAYLOAD))
                        .build())
                .build());

        assertTrue(version.getName().contains(SECRET_ID));
        assertEquals(SecretVersion.State.ENABLED, version.getState());
    }

    @Test
    @Order(3)
    void accessSecretVersionLatest() {
        AccessSecretVersionResponse response = client.accessSecretVersion(
                SecretVersionName.of(PROJECT_ID, SECRET_ID, "latest"));

        assertEquals(PAYLOAD, response.getPayload().getData().toStringUtf8());
    }

    @Test
    @Order(4)
    void listSecretVersions() {
        List<SecretVersion> versions = new ArrayList<>();
        client.listSecretVersions(SecretName.of(PROJECT_ID, SECRET_ID))
                .iterateAll().forEach(versions::add);

        assertFalse(versions.isEmpty());
        assertTrue(versions.stream().allMatch(v -> v.getName().contains(SECRET_ID)));
    }

    @Test
    @Order(5)
    void deleteSecretAndVerifyGone() {
        client.deleteSecret(SecretName.of(PROJECT_ID, SECRET_ID));

        List<String> remaining = new ArrayList<>();
        client.listSecrets("projects/" + PROJECT_ID)
                .iterateAll().forEach(s -> remaining.add(s.getName()));

        assertTrue(remaining.stream().noneMatch(n -> n.endsWith(SECRET_ID)));
    }
}

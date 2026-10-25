package io.floci.gcp.services.secretmanager;

import com.google.cloud.secretmanager.v1.AccessSecretVersionRequest;
import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.AddSecretVersionRequest;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.DestroySecretVersionRequest;
import com.google.cloud.secretmanager.v1.DisableSecretVersionRequest;
import com.google.cloud.secretmanager.v1.GetSecretRequest;
import com.google.cloud.secretmanager.v1.GetSecretVersionRequest;
import com.google.cloud.secretmanager.v1.ListSecretVersionsRequest;
import com.google.cloud.secretmanager.v1.ListSecretsRequest;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceGrpc;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.cloud.secretmanager.v1.SecretVersion;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SecretManagerGrpcIntegrationTest {

    private static final String REGIONAL_MESSAGE =
            "Regional secrets (projects/*/locations/*) are not supported by the floci Secret Manager emulator";

    @TestHTTPResource
    URI endpoint;

    private ManagedChannel channel;
    private SecretManagerServiceGrpc.SecretManagerServiceBlockingStub stub;

    @BeforeEach
    void setUp() {
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext()
                .build();
        stub = SecretManagerServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void createWithRegionalParentIsUnimplementedAndStoresNothing() {
        String project = "grpc-regional-project";

        StatusRuntimeException ex = assertStatus(Status.Code.UNIMPLEMENTED,
                () -> stub.createSecret(createRequest("projects/" + project + "/locations/us-central1", "regional")));
        assertTrue(ex.getStatus().getDescription().contains(REGIONAL_MESSAGE));

        assertEquals(0, stub.listSecrets(ListSecretsRequest.newBuilder()
                .setParent("projects/" + project).build()).getSecretsCount());
        assertStatus(Status.Code.UNIMPLEMENTED, () -> stub.listSecrets(ListSecretsRequest.newBuilder()
                .setParent("projects/" + project + "/locations/us-central1").build()));
    }

    @Test
    void regionalSecretAndVersionNamesAreUnimplemented() {
        String secret = "projects/p/locations/us-central1/secrets/s";

        assertStatus(Status.Code.UNIMPLEMENTED,
                () -> stub.getSecret(GetSecretRequest.newBuilder().setName(secret).build()));
        assertStatus(Status.Code.UNIMPLEMENTED, () -> stub.addSecretVersion(AddSecretVersionRequest.newBuilder()
                .setParent(secret)
                .setPayload(SecretPayload.newBuilder().setData(ByteString.copyFromUtf8("v")))
                .build()));
        assertStatus(Status.Code.UNIMPLEMENTED, () -> stub.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                .setName(secret + "/versions/latest").build()));
        assertStatus(Status.Code.UNIMPLEMENTED, () -> stub.getIamPolicy(GetIamPolicyRequest.newBuilder()
                .setResource(secret).build()));
    }

    @Test
    void incompleteRegionalNamesAreInvalidArgument() {
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> stub.createSecret(createRequest("projects/p/locations", "s")));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> stub.listSecrets(ListSecretsRequest.newBuilder()
                .setParent("projects/p/locations/").build()));
        assertStatus(Status.Code.INVALID_ARGUMENT,
                () -> stub.getSecret(GetSecretRequest.newBuilder().setName("projects/p/locations/l").build()));
        assertStatus(Status.Code.INVALID_ARGUMENT,
                () -> stub.getSecret(GetSecretRequest.newBuilder().setName("projects/p/locations/l/secrets").build()));
        assertStatus(Status.Code.INVALID_ARGUMENT,
                () -> stub.getSecret(GetSecretRequest.newBuilder().setName("projects/p/locations/l/topics/t").build()));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> stub.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                .setName("projects/p/locations/l/secrets/s").build()));
    }

    @Test
    void malformedNamesAreInvalidArgument() {
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> stub.createSecret(createRequest("p", "s")));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> stub.createSecret(createRequest("projects/p/secrets", "s")));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> stub.createSecret(createRequest("projects/", "s")));
        assertStatus(Status.Code.INVALID_ARGUMENT,
                () -> stub.getSecret(GetSecretRequest.newBuilder().setName("projects/p/topics/t").build()));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> stub.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                .setName("projects/p/secrets/s").build()));
    }

    @Test
    void globalSecretFlowIsUnchanged() {
        String project = "grpc-global-project";
        String secretName = "projects/" + project + "/secrets/db-password";

        Secret created = stub.createSecret(createRequest("projects/" + project, "db-password"));
        assertEquals(secretName, created.getName());

        SecretVersion version = stub.addSecretVersion(AddSecretVersionRequest.newBuilder()
                .setParent(secretName)
                .setPayload(SecretPayload.newBuilder().setData(ByteString.copyFromUtf8("hunter2")))
                .build());
        assertEquals(secretName + "/versions/1", version.getName());

        AccessSecretVersionResponse accessed = stub.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                .setName(secretName + "/versions/latest").build());
        assertEquals("hunter2", accessed.getPayload().getData().toString(StandardCharsets.UTF_8));

        assertEquals(1, stub.listSecretVersions(ListSecretVersionsRequest.newBuilder()
                .setParent(secretName).build()).getVersionsCount());
        assertEquals(1, stub.listSecrets(ListSecretsRequest.newBuilder()
                .setParent("projects/" + project).build()).getSecretsCount());
        stub.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(secretName).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISABLED", "DESTROYED"})
    void latestAliasResolvesToNewestVersionRegardlessOfState(String state) {
        String secretId = "latest-" + UUID.randomUUID();
        String secretName = "projects/grpc-latest-project/secrets/" + secretId;
        String newest = secretName + "/versions/2";
        stub.createSecret(createRequest("projects/grpc-latest-project", secretId));
        for (String value : new String[]{"v1", "v2"}) {
            stub.addSecretVersion(AddSecretVersionRequest.newBuilder()
                    .setParent(secretName)
                    .setPayload(SecretPayload.newBuilder().setData(ByteString.copyFromUtf8(value)))
                    .build());
        }
        if ("DISABLED".equals(state)) {
            stub.disableSecretVersion(DisableSecretVersionRequest.newBuilder().setName(newest).build());
        } else {
            stub.destroySecretVersion(DestroySecretVersionRequest.newBuilder().setName(newest).build());
        }

        SecretVersion latestVersion = stub.getSecretVersion(GetSecretVersionRequest.newBuilder()
                .setName(secretName + "/versions/latest").build());
        assertEquals(newest, latestVersion.getName());
        assertEquals(SecretVersion.State.valueOf(state), latestVersion.getState());

        StatusRuntimeException direct = assertThrows(StatusRuntimeException.class,
                () -> stub.accessSecretVersion(AccessSecretVersionRequest.newBuilder().setName(newest).build()));
        StatusRuntimeException latest = assertThrows(StatusRuntimeException.class,
                () -> stub.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                        .setName(secretName + "/versions/latest").build()));
        assertEquals(direct.getStatus().getCode(), latest.getStatus().getCode());
        assertEquals(direct.getStatus().getDescription(), latest.getStatus().getDescription());
    }

    @Test
    void latestAliasOnSecretWithoutVersionsIsNotFound() {
        String secretId = "no-versions-" + UUID.randomUUID();
        String secretName = "projects/grpc-latest-project/secrets/" + secretId;
        stub.createSecret(createRequest("projects/grpc-latest-project", secretId));

        StatusRuntimeException ex = assertStatus(Status.Code.NOT_FOUND,
                () -> stub.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                        .setName(secretName + "/versions/latest").build()));
        assertEquals("Secret [" + secretName + "] not found or has no versions.", ex.getStatus().getDescription());
    }

    @Test
    void accessingDisabledOrDestroyedVersionIsFailedPrecondition() {
        String secretId = "state-" + UUID.randomUUID();
        String secretName = "projects/grpc-version-state-project/secrets/" + secretId;
        stub.createSecret(createRequest("projects/grpc-version-state-project", secretId));
        for (String value : new String[]{"v1", "v2"}) {
            stub.addSecretVersion(AddSecretVersionRequest.newBuilder()
                    .setParent(secretName)
                    .setPayload(SecretPayload.newBuilder().setData(ByteString.copyFromUtf8(value)))
                    .build());
        }
        stub.disableSecretVersion(DisableSecretVersionRequest.newBuilder().setName(secretName + "/versions/1").build());
        stub.destroySecretVersion(DestroySecretVersionRequest.newBuilder().setName(secretName + "/versions/2").build());

        StatusRuntimeException disabled = assertStatus(Status.Code.FAILED_PRECONDITION,
                () -> stub.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                        .setName(secretName + "/versions/1").build()));
        assertEquals("Secret Version [" + secretName + "/versions/1] is in DISABLED state.",
                disabled.getStatus().getDescription());

        StatusRuntimeException destroyed = assertStatus(Status.Code.FAILED_PRECONDITION,
                () -> stub.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                        .setName(secretName + "/versions/2").build()));
        assertEquals("Secret Version [" + secretName + "/versions/2] is in DESTROYED state.",
                destroyed.getStatus().getDescription());
    }

    private static CreateSecretRequest createRequest(String parent, String secretId) {
        return CreateSecretRequest.newBuilder()
                .setParent(parent)
                .setSecretId(secretId)
                .setSecret(Secret.newBuilder()
                        .setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.getDefaultInstance())))
                .build();
    }

    private static StatusRuntimeException assertStatus(Status.Code expected, Executable call) {
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class, call);
        assertEquals(expected, ex.getStatus().getCode());
        return ex;
    }
}

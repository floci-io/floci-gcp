package io.floci.gcp.services.firestore;

import com.google.firestore.v1.FirestoreGrpc;
import com.google.firestore.v1.GetDocumentRequest;
import io.floci.gcp.core.common.TokenRegistry;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(FirestoreIamEnforcementIntegrationTest.CtfIamProfile.class)
class FirestoreIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String DOCUMENT_NAME =
            "projects/" + PROJECT + "/databases/(default)/documents/ctf/doc1";
    private static final String PLAYER_EMAIL = "player@test-project.iam.gserviceaccount.com";
    private static final String PLAYER_TOKEN = "player-token";
    private static final String ROOT_TOKEN = "root-token-test";
    private static final int GRPC_PORT = 4588;

    @Inject
    TokenRegistry tokenRegistry;

    @Inject
    IamService iamService;

    private ManagedChannel channel;

    @BeforeEach
    void setUp() {
        tokenRegistry.register(PLAYER_TOKEN, PLAYER_EMAIL);
        iamService.setPolicy("projects/" + PROJECT, new StoredPolicy());
        int port = io.restassured.RestAssured.port > 0
                ? io.restassured.RestAssured.port
                : GRPC_PORT;
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void playerWithoutBindingIsDeniedGetDocument() {
        FirestoreGrpc.FirestoreBlockingStub stub = stubWithBearer(PLAYER_TOKEN);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.getDocument(GetDocumentRequest.newBuilder().setName(DOCUMENT_NAME).build()));

        assertEquals(Status.Code.PERMISSION_DENIED, ex.getStatus().getCode());
        assertTrue(ex.getStatus().getDescription() != null
                && ex.getStatus().getDescription().contains("datastore.entities.get"));
    }

    @Test
    void playerWithDatastoreViewerCanPassGetDocumentIamGate() {
        bindPlayer("roles/datastore.viewer");
        FirestoreGrpc.FirestoreBlockingStub stub = stubWithBearer(PLAYER_TOKEN);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.getDocument(GetDocumentRequest.newBuilder().setName(DOCUMENT_NAME).build()));

        assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode(),
                "viewer should pass IAM then get NOT_FOUND for missing document, got: "
                        + ex.getStatus());
    }

    @Test
    void rootTokenAlwaysPassesGetDocumentIamGate() {
        FirestoreGrpc.FirestoreBlockingStub stub = stubWithBearer(ROOT_TOKEN);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.getDocument(GetDocumentRequest.newBuilder().setName(DOCUMENT_NAME).build()));

        assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode(),
                "root should pass IAM then get NOT_FOUND for missing document, got: "
                        + ex.getStatus());
    }

    private FirestoreGrpc.FirestoreBlockingStub stubWithBearer(String token) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + token);
        return FirestoreGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private void bindPlayer(String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", role,
                "members", List.of("serviceAccount:" + PLAYER_EMAIL))));
        iamService.setPolicy("projects/" + PROJECT, policy);
    }

    public static class CtfIamProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.auth.validate-tokens", "true",
                    "floci-gcp.auth.root-service-account",
                    "operator@test-project.iam.gserviceaccount.com",
                    "floci-gcp.auth.root-access-token", ROOT_TOKEN,
                    "floci-gcp.services.iam.enforcement-enabled", "true",
                    "floci-gcp.services.iam.strict-enforcement-enabled", "true");
        }
    }
}

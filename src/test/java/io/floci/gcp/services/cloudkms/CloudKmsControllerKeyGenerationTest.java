package io.floci.gcp.services.cloudkms;

import com.google.cloud.kms.v1.CreateCryptoKeyRequest;
import com.google.cloud.kms.v1.CreateCryptoKeyVersionRequest;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.CryptoKeyVersion;
import com.google.cloud.kms.v1.CryptoKeyVersionTemplate;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKey;
import io.floci.gcp.services.cloudkms.model.StoredCryptoKeyVersion;
import io.grpc.stub.StreamObserver;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** A 4096-bit RSA key takes about a second to generate, so the gRPC handlers that make one leave the event loop. */
class CloudKmsControllerKeyGenerationTest {

    private static final String KEY_RING = "projects/p1/locations/us-central1/keyRings/kr";

    private Vertx vertx;
    private AtomicBoolean generatedOnEventLoop;
    private CloudKmsController controller;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        generatedOnEventLoop = new AtomicBoolean(true);
        CloudKmsService service = new CloudKmsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>()) {
            @Override
            public StoredCryptoKey createCryptoKey(String parent, String cryptoKeyId, String purpose,
                    String algorithm, boolean skipInitialVersionCreation, CryptoKeyFields fields, boolean importOnly) {
                generatedOnEventLoop.set(Context.isOnEventLoopThread());
                return super.createCryptoKey(parent, cryptoKeyId, purpose, algorithm, skipInitialVersionCreation,
                        fields, importOnly);
            }

            @Override
            public StoredCryptoKeyVersion createCryptoKeyVersion(String cryptoKeyName) {
                generatedOnEventLoop.set(Context.isOnEventLoopThread());
                return super.createCryptoKeyVersion(cryptoKeyName);
            }
        };
        service.createKeyRing("projects/p1/locations/us-central1", "kr");
        controller = new CloudKmsController(service);
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void createCryptoKeyGeneratesItsFirstVersionOffTheEventLoopAndAnswersOnIt() throws Exception {
        CreateCryptoKeyRequest request = CreateCryptoKeyRequest.newBuilder()
                .setParent(KEY_RING)
                .setCryptoKeyId("k")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.RSA_SIGN_PSS_4096_SHA512)))
                .build();

        Answer<CryptoKey> answer = onEventLoop(observer -> controller.createCryptoKey(request, observer));

        assertEquals(KEY_RING + "/cryptoKeys/k", answer.value().getName());
        assertEquals(CryptoKeyVersion.CryptoKeyVersionAlgorithm.RSA_SIGN_PSS_4096_SHA512,
                answer.value().getVersionTemplate().getAlgorithm());
        assertFalse(generatedOnEventLoop.get());
        assertTrue(answer.onEventLoop());
    }

    @Test
    void createCryptoKeyVersionGeneratesOffTheEventLoopAndAnswersOnIt() throws Exception {
        controller.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(KEY_RING)
                .setCryptoKeyId("k")
                .setSkipInitialVersionCreation(true)
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_DECRYPT)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.RSA_DECRYPT_OAEP_4096_SHA256)))
                .build(), new Collector<>());
        CreateCryptoKeyVersionRequest request = CreateCryptoKeyVersionRequest.newBuilder()
                .setParent(KEY_RING + "/cryptoKeys/k")
                .build();

        Answer<CryptoKeyVersion> answer = onEventLoop(observer -> controller.createCryptoKeyVersion(request, observer));

        assertEquals(KEY_RING + "/cryptoKeys/k/cryptoKeyVersions/1", answer.value().getName());
        assertFalse(generatedOnEventLoop.get());
        assertTrue(answer.onEventLoop());
    }

    private record Answer<T>(T value, boolean onEventLoop) {}

    /** Calls {@code call} on a Vert.x event loop, as gRPC does, and waits for its single answer. */
    private <T> Answer<T> onEventLoop(Consumer<StreamObserver<T>> call) throws Exception {
        Collector<T> collector = new Collector<>();
        vertx.runOnContext(ignored -> call.accept(collector));
        T value = collector.response.get(30, TimeUnit.SECONDS);
        return new Answer<>(value, collector.answeredOnEventLoop.get(1, TimeUnit.SECONDS));
    }

    private static final class Collector<T> implements StreamObserver<T> {

        private final CompletableFuture<T> response = new CompletableFuture<>();
        private final CompletableFuture<Boolean> answeredOnEventLoop = new CompletableFuture<>();

        @Override
        public void onNext(T value) {
            answeredOnEventLoop.complete(Context.isOnEventLoopThread());
            response.complete(value);
        }

        @Override
        public void onError(Throwable t) {
            response.completeExceptionally(t);
        }

        @Override
        public void onCompleted() {
        }
    }
}

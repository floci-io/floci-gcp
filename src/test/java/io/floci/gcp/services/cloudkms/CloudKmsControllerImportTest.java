package io.floci.gcp.services.cloudkms;

import com.google.cloud.kms.v1.CreateImportJobRequest;
import com.google.cloud.kms.v1.ImportJob;
import com.google.cloud.kms.v1.ProtectionLevel;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudkms.model.StoredImportJob;
import io.grpc.stub.StreamObserver;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CloudKmsControllerImportTest {

    private static final String LOCATION = "projects/p1/locations/us-central1";

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void createImportJobGeneratesTheWrappingKeyOffTheEventLoopAndAnswersOnIt() throws Exception {
        AtomicBoolean generatedOnEventLoop = new AtomicBoolean(true);
        CloudKmsService service = new CloudKmsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>()) {
            @Override
            public StoredImportJob createImportJob(String parent, String importJobId, String importMethod,
                    String protectionLevel) {
                generatedOnEventLoop.set(Context.isOnEventLoopThread());
                return super.createImportJob(parent, importJobId, importMethod, protectionLevel);
            }
        };
        service.createKeyRing(LOCATION, "kr");
        CloudKmsController controller = new CloudKmsController(service);

        CompletableFuture<Boolean> answeredOnEventLoop = new CompletableFuture<>();
        CompletableFuture<ImportJob> response = new CompletableFuture<>();
        CreateImportJobRequest request = CreateImportJobRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/kr")
                .setImportJobId("job")
                .setImportJob(ImportJob.newBuilder()
                        .setImportMethod(ImportJob.ImportMethod.RSA_OAEP_4096_SHA256_AES_256)
                        .setProtectionLevel(ProtectionLevel.SOFTWARE))
                .build();

        vertx.runOnContext(ignored -> controller.createImportJob(request, new StreamObserver<>() {
            @Override
            public void onNext(ImportJob value) {
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
        }));

        assertEquals(ImportJob.ImportJobState.ACTIVE, response.get(30, TimeUnit.SECONDS).getState());
        assertFalse(generatedOnEventLoop.get());
        assertTrue(answeredOnEventLoop.get(1, TimeUnit.SECONDS));
    }
}

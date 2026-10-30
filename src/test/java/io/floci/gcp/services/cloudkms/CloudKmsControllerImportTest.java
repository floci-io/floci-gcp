package io.floci.gcp.services.cloudkms;

import com.google.cloud.kms.v1.CreateImportJobRequest;
import com.google.cloud.kms.v1.GetImportJobRequest;
import com.google.cloud.kms.v1.ImportJob;
import com.google.cloud.kms.v1.ListImportJobsRequest;
import com.google.cloud.kms.v1.ListImportJobsResponse;
import com.google.cloud.kms.v1.ProtectionLevel;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudkms.model.StoredImportJob;
import io.grpc.stub.StreamObserver;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    @Test
    void importJobCarriesItsPublicKeyOnlyWhileActiveAndAnExpireEventTimeOnceExpired() {
        InMemoryStorage<String, StoredImportJob> importJobStore = new InMemoryStorage<>();
        CloudKmsService service = new CloudKmsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), importJobStore);
        service.createKeyRing(LOCATION, "kr");
        StoredImportJob stored = service.createImportJob(LOCATION + "/keyRings/kr", "job",
                "RSA_OAEP_3072_SHA256_AES_256", "SOFTWARE");
        CloudKmsController controller = new CloudKmsController(service);

        ImportJob active = getImportJob(controller, stored.getName());
        assertEquals(ImportJob.ImportJobState.ACTIVE, active.getState());
        assertTrue(active.getPublicKey().getPem().startsWith("-----BEGIN PUBLIC KEY-----"));
        assertFalse(active.hasExpireEventTime());

        Instant expiredAt = Instant.now().minusSeconds(60);
        stored.setExpireTime(expiredAt.toString());
        importJobStore.put(stored.getName(), stored);

        ImportJob expired = getImportJob(controller, stored.getName());
        assertEquals(ImportJob.ImportJobState.EXPIRED, expired.getState());
        assertFalse(expired.hasPublicKey());
        assertEquals(expired.getExpireTime(), expired.getExpireEventTime());
        assertEquals(expiredAt.getEpochSecond(), expired.getExpireEventTime().getSeconds());
        ImportJob listed = listImportJobs(controller, LOCATION + "/keyRings/kr").get(0);
        assertFalse(listed.hasPublicKey());
        assertEquals(expired.getExpireEventTime(), listed.getExpireEventTime());
    }

    @Test
    void restImportJobCarriesItsPublicKeyOnlyWhileActiveAndAnExpireEventTimeOnceExpired() {
        InMemoryStorage<String, StoredImportJob> importJobStore = new InMemoryStorage<>();
        CloudKmsService service = new CloudKmsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), importJobStore);
        service.createKeyRing(LOCATION, "kr");
        StoredImportJob stored = service.createImportJob(LOCATION + "/keyRings/kr", "job",
                "RSA_OAEP_3072_SHA256_AES_256", "SOFTWARE");
        CloudKmsHttpController controller = new CloudKmsHttpController();
        controller.service = service;

        Map<?, ?> active = (Map<?, ?>) controller.getImportJob("p1", "us-central1", "kr", "job").getEntity();
        assertEquals("ACTIVE", active.get("state"));
        assertTrue(active.containsKey("publicKey"));
        assertFalse(active.containsKey("expireEventTime"));

        stored.setExpireTime(Instant.now().minusSeconds(60).toString());
        importJobStore.put(stored.getName(), stored);

        Response response = controller.getImportJob("p1", "us-central1", "kr", "job");
        Map<?, ?> expired = (Map<?, ?>) response.getEntity();
        assertEquals("EXPIRED", expired.get("state"));
        assertFalse(expired.containsKey("publicKey"));
        assertEquals(stored.getExpireTime(), expired.get("expireEventTime"));
        Map<?, ?> listed = (Map<?, ?>) ((List<?>) ((Map<?, ?>) controller
                .listImportJobs("p1", "us-central1", "kr").getEntity()).get("importJobs")).get(0);
        assertFalse(listed.containsKey("publicKey"));
        assertEquals(stored.getExpireTime(), listed.get("expireEventTime"));
    }

    private static ImportJob getImportJob(CloudKmsController controller, String name) {
        CompletableFuture<ImportJob> response = new CompletableFuture<>();
        controller.getImportJob(GetImportJobRequest.newBuilder().setName(name).build(), observer(response));
        return response.join();
    }

    private static List<ImportJob> listImportJobs(CloudKmsController controller, String parent) {
        CompletableFuture<ListImportJobsResponse> response = new CompletableFuture<>();
        controller.listImportJobs(ListImportJobsRequest.newBuilder().setParent(parent).build(), observer(response));
        return response.join().getImportJobsList();
    }

    private static <T> StreamObserver<T> observer(CompletableFuture<T> response) {
        return new StreamObserver<>() {
            @Override
            public void onNext(T value) {
                response.complete(value);
            }

            @Override
            public void onError(Throwable t) {
                response.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
            }
        };
    }
}

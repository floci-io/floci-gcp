package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.floci.gcp.services.gcs.model.GcsObjectPreconditions;
import io.floci.gcp.services.gcs.model.StoredAcl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcsObjectPreconditionTest {

    private static final String BASE_URL = "http://localhost:4588";
    private static final String BUCKET = "precondition-bucket";
    private static final String OBJECT = "object";
    private static final int WRITERS = 16;

    private GcsService service;

    @BeforeEach
    void setUp() {
        service = new GcsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                "test-project");
        service.createBucket(BUCKET, "test-project", BASE_URL, Map.of());
    }

    @Test
    void atomicallyCreatesObjectWhenItDoesNotExist() throws Exception {
        GcsObjectPreconditions createOnly = new GcsObjectPreconditions(0L, null, null, null);

        int successes = runConcurrent(index -> put("value-" + index, createOnly));

        assertEquals(1, successes);
    }

    @Test
    void atomicallyReplacesMatchingGeneration() throws Exception {
        GcsObjectMeta initial = put("initial", GcsObjectPreconditions.NONE);
        long generation = Long.parseLong(initial.getGeneration());
        GcsObjectPreconditions matchesGeneration = new GcsObjectPreconditions(generation, null, null, null);

        int successes = runConcurrent(index -> put("value-" + index, matchesGeneration));

        assertEquals(1, successes);
        assertNotEquals(initial.getGeneration(), service.getObjectMeta(BUCKET, OBJECT).getGeneration());
    }

    @Test
    void atomicallyPatchesMatchingMetageneration() throws Exception {
        put("initial", GcsObjectPreconditions.NONE);
        GcsObjectPreconditions matchesMetageneration = new GcsObjectPreconditions(null, null, 1L, null);

        int successes = runConcurrent(index -> service.patchObject(
                BUCKET,
                OBJECT,
                Map.of("metadata", Map.of("writer", String.valueOf(index))),
                matchesMetageneration));

        assertEquals(1, successes);
        assertEquals("2", service.getObjectMeta(BUCKET, OBJECT).getMetageneration());
    }

    @Test
    void checksResumablePreconditionAgainWhenUploadCompletes() {
        GcsObjectPreconditions createOnly = new GcsObjectPreconditions(0L, null, null, null);
        String uploadId = service.startResumableUpload(BUCKET, OBJECT, "text/plain", GcsCustomerEncryption.none(), createOnly);
        byte[] competing = "competing".getBytes(StandardCharsets.UTF_8);
        service.putObject(BUCKET, OBJECT, "text/plain", competing, BASE_URL);

        GcpException exception = assertThrows(GcpException.class,
                () -> service.completeResumableUpload(
                        uploadId, "original".getBytes(StandardCharsets.UTF_8), BASE_URL));

        assertEquals(412, exception.getHttpStatus());
        assertArrayEquals(competing, service.getObjectData(BUCKET, OBJECT));
    }

    @Test
    void continuesGenerationSequenceAfterRestart() {
        InMemoryStorage<String, GcsBucket> bucketStore = new InMemoryStorage<>();
        InMemoryStorage<String, GcsObjectMeta> objectMetaStore = new InMemoryStorage<>();
        InMemoryStorage<String, byte[]> objectDataStore = new InMemoryStorage<>();
        InMemoryStorage<String, StoredAcl> aclStore = new InMemoryStorage<>();
        GcsService original = new GcsService(bucketStore, objectMetaStore, objectDataStore, aclStore, "test-project");
        original.createBucket(BUCKET, "test-project", BASE_URL, Map.of());
        GcsObjectMeta initial = original.putObject(
                BUCKET, OBJECT, "text/plain", new byte[0], GcsCustomerEncryption.none(), BASE_URL);
        long persistedGeneration = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1);
        initial.setGeneration(String.valueOf(persistedGeneration));

        GcsService restarted = new GcsService(bucketStore, objectMetaStore, objectDataStore, aclStore, "test-project");
        GcsObjectMeta replacement = restarted.putObject(
                BUCKET, OBJECT, "text/plain", new byte[0], GcsCustomerEncryption.none(), BASE_URL);

        assertTrue(Long.parseLong(replacement.getGeneration()) > persistedGeneration);
    }

    private GcsObjectMeta put(String value, GcsObjectPreconditions preconditions) {
        return service.putObject(
                BUCKET,
                OBJECT,
                "text/plain",
                value.getBytes(StandardCharsets.UTF_8),
                GcsCustomerEncryption.none(),
                preconditions,
                BASE_URL);
    }

    private int runConcurrent(IntConsumer operation) throws Exception {
        CountDownLatch ready = new CountDownLatch(WRITERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(WRITERS)) {
            for (int index = 0; index < WRITERS; index++) {
                int writer = index;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        operation.accept(writer);
                        return true;
                    } catch (GcpException e) {
                        assertEquals(412, e.getHttpStatus());
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            return successes;
        }
    }
}

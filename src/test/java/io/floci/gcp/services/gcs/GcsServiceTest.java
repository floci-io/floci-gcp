package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.PersistentStorage;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.gcs.model.GcsContentRange;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.floci.gcp.services.gcs.model.GcsObjectPreconditions;
import io.floci.gcp.services.gcs.model.GcsStreamingUpload;
import io.floci.gcp.services.gcs.model.StoredAcl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class GcsServiceTest {

    private static final String BASE_URL = "http://localhost:4588";
    private GcsService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new GcsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                "test-project");
    }

    @Test
    void createBucketStoredAndRetrievable() {
        service.createBucket("my-bucket", "p1", BASE_URL, Map.of());

        GcsBucket bucket = service.getBucket("my-bucket");
        assertNotNull(bucket);
        assertEquals("my-bucket", bucket.getName());
    }

    @Test
    void timestampsUseAtMostMicrosecondPrecision() {
        service.createBucket("ts-bucket", "p1", BASE_URL, Map.of());
        GcsObjectMeta meta = service.putObject("ts-bucket", "obj.txt", "text/plain",
                "x".getBytes(StandardCharsets.UTF_8), GcsCustomerEncryption.none(), BASE_URL);

        for (String ts : List.of(meta.getTimeCreated(), meta.getUpdated(),
                service.getBucket("ts-bucket").getTimeCreated())) {
            // Sub-microsecond digits make gcloud warn and truncate.
            assertEquals(0, Instant.parse(ts).getNano() % 1000,
                    "timestamp has finer-than-microsecond precision: " + ts);
        }
    }

    @Test
    void createBucketDuplicateThrowsAlreadyExists() {
        service.createBucket("my-bucket", "p1", BASE_URL, Map.of());

        GcpException ex = assertThrows(GcpException.class,
                () -> service.createBucket("my-bucket", "p1", BASE_URL, Map.of()));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
    }

    @Test
    void getBucketMissingThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.getBucket("missing-bucket"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void listBucketsFiltersByProject() {
        // "b1"/"b2" are shorter than the 3-character minimum GCS enforces.
        service.createBucket("bucket-one", "p1", BASE_URL, Map.of());
        service.createBucket("bucket-two", "p1", BASE_URL, Map.of());

        List<GcsBucket> buckets = service.listBuckets("p1");
        assertEquals(2, buckets.size());
    }

    @Test
    void putObjectStoredAndRetrievable() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);

        GcsObjectMeta meta = service.putObject("bucket", "obj.txt", "text/plain", data,
                GcsCustomerEncryption.none(), BASE_URL);

        assertNotNull(meta);
        assertEquals("obj.txt", meta.getName());
        assertEquals("text/plain", meta.getContentType());
        assertEquals(String.valueOf(data.length), meta.getSize());

        byte[] retrieved = service.getObjectData("bucket", "obj.txt", GcsCustomerEncryption.none());
        assertArrayEquals(data, retrieved);
    }

    @Test
    void getObjectForDownloadReturnsMatchingMetaAndData() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        var data = "payload".getBytes(StandardCharsets.UTF_8);
        var stored = service.putObject("bucket", "obj.txt", "text/plain", data,
                GcsCustomerEncryption.none(), BASE_URL);

        var download = service.getObjectForDownload("bucket", "obj.txt", null, GcsCustomerEncryption.none());

        assertEquals(stored.getGeneration(), download.meta().getGeneration());
        assertArrayEquals(data, download.data());
    }

    @Test
    void getObjectForDownloadWithExplicitGeneration() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        var data = "payload".getBytes(StandardCharsets.UTF_8);
        var stored = service.putObject("bucket", "obj.txt", "text/plain", data,
                GcsCustomerEncryption.none(), BASE_URL);

        var download = service.getObjectForDownload("bucket", "obj.txt", stored.getGeneration(),
                GcsCustomerEncryption.none());

        assertEquals(stored.getGeneration(), download.meta().getGeneration());
        assertArrayEquals(data, download.data());

        var ex = assertThrows(GcpException.class,
                () -> service.getObjectForDownload("bucket", "obj.txt", "999999", GcsCustomerEncryption.none()));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void copyObjectCopiesDataContentTypeAndMetadata() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        var data = "payload".getBytes(StandardCharsets.UTF_8);
        var stored = service.putObject("bucket", "src.txt", "text/plain", data,
                GcsCustomerEncryption.none(), Map.of("origname", "src.txt"), BASE_URL);
        assertNotNull(stored.getMetadata());

        var copied = service.copyObject("bucket", "src.txt", "bucket", "dst.txt", BASE_URL);

        assertEquals("text/plain", copied.getContentType());
        assertEquals(Map.of("origname", "src.txt"), copied.getMetadata());
        assertArrayEquals(data, service.getObjectData("bucket", "dst.txt", GcsCustomerEncryption.none()));
    }

    @Test
    void moveObjectMovesDataContentTypeAndMetadata() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        GcsObjectMeta source = service.putObject("bucket", "source/name", "text/plain", data,
                GcsCustomerEncryption.none(), Map.of("original", "source/name"), BASE_URL);
        GcsObjectPreconditions sourcePreconditions = new GcsObjectPreconditions(
                Long.parseLong(source.getGeneration()), null, null, null);
        GcsObjectPreconditions destinationPreconditions = new GcsObjectPreconditions(0L, null, null, null);

        GcsObjectMeta moved = service.moveObject("bucket", "source/name", "destination/name",
                sourcePreconditions, destinationPreconditions, BASE_URL);

        assertEquals("destination/name", moved.getName());
        assertEquals("text/plain", moved.getContentType());
        assertEquals(Map.of("original", "source/name"), moved.getMetadata());
        assertNotEquals(source.getGeneration(), moved.getGeneration());
        assertArrayEquals(data, service.getObjectData("bucket", "destination/name"));
        GcpException exception = assertThrows(GcpException.class,
                () -> service.getObjectMeta("bucket", "source/name"));
        assertEquals(404, exception.getHttpStatus());
    }

    @Test
    void moveObjectPreconditionFailureDoesNotChangeEitherObject() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        service.putObject("bucket", "source", "text/plain", "source".getBytes(StandardCharsets.UTF_8),
                GcsCustomerEncryption.none(), BASE_URL);
        service.putObject("bucket", "destination", "text/plain", "destination".getBytes(StandardCharsets.UTF_8),
                GcsCustomerEncryption.none(), BASE_URL);
        GcsObjectPreconditions destinationDoesNotExist = new GcsObjectPreconditions(0L, null, null, null);

        GcpException exception = assertThrows(GcpException.class,
                () -> service.moveObject("bucket", "source", "destination", GcsObjectPreconditions.NONE,
                        destinationDoesNotExist, BASE_URL));

        assertEquals(412, exception.getHttpStatus());
        assertArrayEquals("source".getBytes(StandardCharsets.UTF_8), service.getObjectData("bucket", "source"));
        assertArrayEquals("destination".getBytes(StandardCharsets.UTF_8),
                service.getObjectData("bucket", "destination"));
    }

    @Test
    void moveObjectRejectsIdenticalNames() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        service.putObject("bucket", "object", "text/plain", new byte[0], GcsCustomerEncryption.none(), BASE_URL);

        GcpException exception = assertThrows(GcpException.class,
                () -> service.moveObject("bucket", "object", "object", GcsObjectPreconditions.NONE,
                        GcsObjectPreconditions.NONE, BASE_URL));

        assertEquals(400, exception.getHttpStatus());
        assertEquals("Source and destination object names must be different.", exception.getMessage());
    }

    @Test
    void composeObjectConcatenatesSourcesAndInheritsFirstContentType() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        service.putObject("bucket", "part1.txt", "text/plain", "foo".getBytes(StandardCharsets.UTF_8),
                GcsCustomerEncryption.none(), BASE_URL);
        service.putObject("bucket", "part2.txt", "text/csv", "bar".getBytes(StandardCharsets.UTF_8),
                GcsCustomerEncryption.none(), BASE_URL);

        var composed = service.composeObject("bucket", "all.txt", List.of("part1.txt", "part2.txt"),
                null, BASE_URL);

        assertEquals("text/plain", composed.getContentType());
        assertArrayEquals("foobar".getBytes(StandardCharsets.UTF_8),
                service.getObjectData("bucket", "all.txt", GcsCustomerEncryption.none()));
    }

    @Test
    void composeObjectOmitsMd5AndAccumulatesComponentCount() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        service.putObject("bucket", "part1.txt", "text/plain", "foo".getBytes(StandardCharsets.UTF_8),
                GcsCustomerEncryption.none(), BASE_URL);
        service.putObject("bucket", "part2.txt", "text/plain", "bar".getBytes(StandardCharsets.UTF_8),
                GcsCustomerEncryption.none(), BASE_URL);

        var composed = service.composeObject("bucket", "all.txt", List.of("part1.txt", "part2.txt"),
                null, BASE_URL);

        assertNull(composed.getMd5Hash());
        assertNotNull(composed.getCrc32c());
        assertEquals(2, composed.getComponentCount());
        assertNull(service.getObjectMeta("bucket", "part1.txt").getComponentCount());

        // Composing an already composite source adds its component count.
        var recomposed = service.composeObject("bucket", "all2.txt", List.of("all.txt", "part1.txt"),
                null, BASE_URL);
        assertEquals(3, recomposed.getComponentCount());
        assertNull(service.getObjectMeta("bucket", "all.txt").getMd5Hash());
    }

    @Test
    void objectDataSurvivesPersistentStoreReload() {
        GcsService first = persistentService(tempDir);
        first.createBucket("bucket", "p1", BASE_URL, Map.of());
        byte[] data = "mounted volume smoke".getBytes(StandardCharsets.UTF_8);

        first.putObject("bucket", "mounted/smoke.txt", "text/plain", data,
                GcsCustomerEncryption.none(), BASE_URL);

        GcsService restarted = persistentService(tempDir);

        assertEquals("mounted/smoke.txt",
                restarted.getObjectMeta("bucket", "mounted/smoke.txt").getName());
        assertArrayEquals(data, restarted.getObjectData("bucket", "mounted/smoke.txt"));
    }

    @Test
    void stalePersistedObjectMetadataWithoutDataIsIgnoredAndCleaned() {
        StorageBackend<String, GcsBucket> bucketStore = new InMemoryStorage<>();
        StorageBackend<String, GcsObjectMeta> objectMetaStore = new InMemoryStorage<>();
        GcsService staleService = new GcsService(bucketStore, objectMetaStore,
                new InMemoryStorage<>(), new InMemoryStorage<>(), "test-project");
        staleService.createBucket("bucket", "p1", BASE_URL, Map.of());
        GcsObjectMeta staleMeta = new GcsObjectMeta();
        staleMeta.setBucket("bucket");
        staleMeta.setName("mounted/smoke.txt");
        staleMeta.setGeneration("1");
        objectMetaStore.put("bucket\0mounted/smoke.txt", staleMeta);

        assertTrue(staleService.listObjects("bucket").isEmpty());

        GcpException ex = assertThrows(GcpException.class,
                () -> staleService.getObjectMeta("bucket", "mounted/smoke.txt"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
        assertTrue(objectMetaStore.get("bucket\0mounted/smoke.txt").isEmpty());
    }

    @Test
    void getObjectMetaMissingThrowsNotFound() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());

        GcpException ex = assertThrows(GcpException.class,
                () -> service.getObjectMeta("bucket", "missing.txt"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void deleteObjectRemovesFromStorage() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        service.putObject("bucket", "obj.txt", "text/plain", new byte[]{1},
                GcsCustomerEncryption.none(), BASE_URL);

        assertTrue(service.deleteObject("bucket", "obj.txt"));

        GcpException ex = assertThrows(GcpException.class,
                () -> service.getObjectMeta("bucket", "obj.txt"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void listObjectsReturnsAll() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        service.putObject("bucket", "a/1.txt", "text/plain", new byte[]{1},
                GcsCustomerEncryption.none(), BASE_URL);
        service.putObject("bucket", "b/2.txt", "text/plain", new byte[]{2},
                GcsCustomerEncryption.none(), BASE_URL);

        List<GcsObjectMeta> objects = service.listObjects("bucket");
        assertEquals(2, objects.size());
    }

    @Test
    void deleteBucketRemovesBucket() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        service.deleteBucket("bucket");

        GcpException ex = assertThrows(GcpException.class,
                () -> service.getBucket("bucket"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void deleteNonEmptyBucketThrowsConflict() {
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        service.putObject("bucket", "obj.txt", "text/plain", new byte[]{1},
                GcsCustomerEncryption.none(), BASE_URL);

        GcpException ex = assertThrows(GcpException.class,
                () -> service.deleteBucket("bucket"));

        assertEquals(409, ex.getHttpStatus());
        assertEquals("conflict", ex.getReason());
        assertNotNull(service.getBucket("bucket"));
        assertArrayEquals(new byte[]{1},
                service.getObjectData("bucket", "obj.txt", GcsCustomerEncryption.none()));
    }

    @Test
    void softDeleteMarkerInLiveObjectNameDoesNotBypassNonEmptyCheck() {
        String objectName = "live\0softDeleted\0object.txt";
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        service.putObject("bucket", objectName, "text/plain", new byte[]{1},
                GcsCustomerEncryption.none(), BASE_URL);

        GcpException ex = assertThrows(GcpException.class,
                () -> service.deleteBucket("bucket"));

        assertEquals("conflict", ex.getReason());
        assertArrayEquals(new byte[]{1},
                service.getObjectData("bucket", objectName, GcsCustomerEncryption.none()));
        assertTrue(service.listSoftDeletedObjects("bucket", null).isEmpty());
    }

    @Test
    void deleteBucketWithOnlySoftDeletedObjectsPurgesTheirArchivedState() {
        service.createBucket("bucket", "p1", BASE_URL,
                Map.of("softDeletePolicy", Map.of("retentionDurationSeconds", "604800")));
        service.putObject("bucket", "obj.txt", "text/plain", new byte[]{1},
                GcsCustomerEncryption.none(), BASE_URL);
        service.deleteObject("bucket", "obj.txt");

        assertEquals(1, service.listSoftDeletedObjects("bucket", null).size());

        service.deleteBucket("bucket");
        service.createBucket("bucket", "p1", BASE_URL, Map.of());

        assertTrue(service.listSoftDeletedObjects("bucket", null).isEmpty());
    }

    @Test
    void deleteBucketWaitsForAnUploadToPublishMetadata() throws Exception {
        CountDownLatch metadataWriteStarted = new CountDownLatch(1);
        CountDownLatch allowMetadataWrite = new CountDownLatch(1);
        service = new GcsService(new InMemoryStorage<>(),
                new BlockingFirstPutStorage<>(metadataWriteStarted, allowMetadataWrite),
                new InMemoryStorage<>(), new InMemoryStorage<>(), "test-project");
        service.createBucket("bucket", "p1", BASE_URL, Map.of());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var upload = executor.submit(() -> service.putObject("bucket", "obj.txt", "text/plain",
                    new byte[]{1}, GcsCustomerEncryption.none(), BASE_URL));
            assertTrue(metadataWriteStarted.await(5, TimeUnit.SECONDS));

            var deletion = executor.submit(() -> service.deleteBucket("bucket"));
            assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS));

            allowMetadataWrite.countDown();
            upload.get(5, TimeUnit.SECONDS);
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> deletion.get(5, TimeUnit.SECONDS));
            GcpException deletionException = assertInstanceOf(GcpException.class, ex.getCause());
            assertEquals("conflict", deletionException.getReason());
            assertNotNull(service.getBucket("bucket"));
            assertArrayEquals(new byte[]{1},
                    service.getObjectData("bucket", "obj.txt", GcsCustomerEncryption.none()));
        } finally {
            allowMetadataWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void deleteBucketWaitsForACopyToPublishMetadata() throws Exception {
        CountDownLatch metadataWriteStarted = new CountDownLatch(1);
        CountDownLatch allowMetadataWrite = new CountDownLatch(1);
        var metadataStore = new ArmableBlockingPutStorage<String, GcsObjectMeta>(
                metadataWriteStarted, allowMetadataWrite);
        service = new GcsService(new InMemoryStorage<>(), metadataStore,
                new InMemoryStorage<>(), new InMemoryStorage<>(), "test-project");
        service.createBucket("source-bucket", "p1", BASE_URL, Map.of());
        service.createBucket("destination-bucket", "p1", BASE_URL, Map.of());
        service.putObject("source-bucket", "source.txt", "text/plain", new byte[]{1},
                GcsCustomerEncryption.none(), BASE_URL);
        metadataStore.blockNextPut();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var copy = executor.submit(() -> service.copyObject(
                    "source-bucket", "source.txt", "destination-bucket", "copy.txt", BASE_URL));
            assertTrue(metadataWriteStarted.await(5, TimeUnit.SECONDS));

            var deletion = executor.submit(() -> service.deleteBucket("destination-bucket"));
            assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS));

            allowMetadataWrite.countDown();
            copy.get(5, TimeUnit.SECONDS);
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> deletion.get(5, TimeUnit.SECONDS));
            GcpException deletionException = assertInstanceOf(GcpException.class, ex.getCause());
            assertEquals("conflict", deletionException.getReason());
            assertArrayEquals(new byte[]{1}, service.getObjectData(
                    "destination-bucket", "copy.txt", GcsCustomerEncryption.none()));
        } finally {
            allowMetadataWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void deleteBucketWaitsForAMoveToPublishMetadata() throws Exception {
        CountDownLatch metadataWriteStarted = new CountDownLatch(1);
        CountDownLatch allowMetadataWrite = new CountDownLatch(1);
        var metadataStore = new ArmableBlockingPutStorage<String, GcsObjectMeta>(
                metadataWriteStarted, allowMetadataWrite);
        service = new GcsService(new InMemoryStorage<>(), metadataStore,
                new InMemoryStorage<>(), new InMemoryStorage<>(), "test-project");
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        service.putObject("bucket", "source.txt", "text/plain", new byte[]{1},
                GcsCustomerEncryption.none(), BASE_URL);
        metadataStore.blockNextPut();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var move = executor.submit(() -> service.moveObject(
                    "bucket", "source.txt", "moved.txt",
                    GcsObjectPreconditions.NONE, GcsObjectPreconditions.NONE, BASE_URL));
            assertTrue(metadataWriteStarted.await(5, TimeUnit.SECONDS));

            var deletion = executor.submit(() -> service.deleteBucket("bucket"));
            assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS));

            allowMetadataWrite.countDown();
            move.get(5, TimeUnit.SECONDS);
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> deletion.get(5, TimeUnit.SECONDS));
            GcpException deletionException = assertInstanceOf(GcpException.class, ex.getCause());
            assertEquals("conflict", deletionException.getReason());
            assertArrayEquals(new byte[]{1}, service.getObjectData(
                    "bucket", "moved.txt", GcsCustomerEncryption.none()));
        } finally {
            allowMetadataWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void deleteBucketWaitsForARestoreToPublishMetadata() throws Exception {
        CountDownLatch metadataWriteStarted = new CountDownLatch(1);
        CountDownLatch allowMetadataWrite = new CountDownLatch(1);
        var metadataStore = new ArmableBlockingPutStorage<String, GcsObjectMeta>(
                metadataWriteStarted, allowMetadataWrite);
        service = new GcsService(new InMemoryStorage<>(), metadataStore,
                new InMemoryStorage<>(), new InMemoryStorage<>(), "test-project");
        service.createBucket("bucket", "p1", BASE_URL,
                Map.of("softDeletePolicy", Map.of("retentionDurationSeconds", "604800")));
        GcsObjectMeta object = service.putObject("bucket", "object.txt", "text/plain", new byte[]{1},
                GcsCustomerEncryption.none(), BASE_URL);
        service.deleteObject("bucket", "object.txt");
        metadataStore.blockNextPut();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var restore = executor.submit(() -> service.restoreObject(
                    "bucket", "object.txt", object.getGeneration()));
            assertTrue(metadataWriteStarted.await(5, TimeUnit.SECONDS));

            var deletion = executor.submit(() -> service.deleteBucket("bucket"));
            assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS));

            allowMetadataWrite.countDown();
            restore.get(5, TimeUnit.SECONDS);
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> deletion.get(5, TimeUnit.SECONDS));
            GcpException deletionException = assertInstanceOf(GcpException.class, ex.getCause());
            assertEquals("conflict", deletionException.getReason());
            assertArrayEquals(new byte[]{1}, service.getObjectData(
                    "bucket", "object.txt", GcsCustomerEncryption.none()));
        } finally {
            allowMetadataWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void deleteBucketWaitsForResumableUploadFinalization() throws Exception {
        CountDownLatch metadataWriteStarted = new CountDownLatch(1);
        CountDownLatch allowMetadataWrite = new CountDownLatch(1);
        var metadataStore = new ArmableBlockingPutStorage<String, GcsObjectMeta>(
                metadataWriteStarted, allowMetadataWrite);
        service = new GcsService(new InMemoryStorage<>(), metadataStore,
                new InMemoryStorage<>(), new InMemoryStorage<>(), "test-project");
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        String uploadId = service.startResumableUpload("bucket", "resumable.txt", "text/plain",
                GcsCustomerEncryption.none(), Map.of());
        metadataStore.blockNextPut();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var finalization = executor.submit(() -> service.applyResumableChunk(
                    uploadId, null, new byte[]{1}, BASE_URL));
            assertTrue(metadataWriteStarted.await(5, TimeUnit.SECONDS));

            var deletion = executor.submit(() -> service.deleteBucket("bucket"));
            assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS));

            allowMetadataWrite.countDown();
            finalization.get(5, TimeUnit.SECONDS);
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> deletion.get(5, TimeUnit.SECONDS));
            GcpException deletionException = assertInstanceOf(GcpException.class, ex.getCause());
            assertEquals("conflict", deletionException.getReason());
            assertArrayEquals(new byte[]{1}, service.getObjectData(
                    "bucket", "resumable.txt", GcsCustomerEncryption.none()));
        } finally {
            allowMetadataWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void deleteBucketWaitsForStreamingUploadFinalization() throws Exception {
        CountDownLatch metadataWriteStarted = new CountDownLatch(1);
        CountDownLatch allowMetadataWrite = new CountDownLatch(1);
        var metadataStore = new ArmableBlockingPutStorage<String, GcsObjectMeta>(
                metadataWriteStarted, allowMetadataWrite);
        service = new GcsService(new InMemoryStorage<>(), metadataStore,
                new InMemoryStorage<>(), new InMemoryStorage<>(), "test-project");
        service.createBucket("bucket", "p1", BASE_URL, Map.of());
        GcsObjectMeta input = new GcsObjectMeta();
        input.setBucket("bucket");
        input.setName("streaming.txt");
        input.setContentType("text/plain");
        String uploadId = service.startStreamingUpload(
                input, GcsObjectPreconditions.NONE, null, null, null);
        service.getStreamingUpload(uploadId).append(0, new byte[]{1});
        metadataStore.blockNextPut();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var finalization = executor.submit(() -> service.finalizeStreamingUpload(uploadId, BASE_URL));
            assertTrue(metadataWriteStarted.await(5, TimeUnit.SECONDS));

            var deletion = executor.submit(() -> service.deleteBucket("bucket"));
            assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS));

            allowMetadataWrite.countDown();
            finalization.get(5, TimeUnit.SECONDS);
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> deletion.get(5, TimeUnit.SECONDS));
            GcpException deletionException = assertInstanceOf(GcpException.class, ex.getCause());
            assertEquals("conflict", deletionException.getReason());
            assertArrayEquals(new byte[]{1}, service.getObjectData(
                    "bucket", "streaming.txt", GcsCustomerEncryption.none()));
        } finally {
            allowMetadataWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentOverwriteNeverMixesGenerations() throws Exception {
        service.createBucket("race-bucket", "p1", BASE_URL, Map.of());
        var payloads = Map.of(
                "a", "aaaaaaaa".getBytes(StandardCharsets.UTF_8),
                "b", "bb".getBytes(StandardCharsets.UTF_8));
        service.putObject("race-bucket", "obj.txt", "text/plain", payloads.get("a"),
                GcsCustomerEncryption.none(), Map.of("tag", "a"), BASE_URL);

        var stop = new AtomicBoolean(false);
        var writer = new Thread(() -> {
            var flip = false;
            while (!stop.get()) {
                var tag = flip ? "a" : "b";
                service.putObject("race-bucket", "obj.txt", "text/plain", payloads.get(tag),
                        GcsCustomerEncryption.none(), Map.of("tag", tag), BASE_URL);
                flip = !flip;
            }
        });
        writer.start();
        try {
            for (var i = 0; i < 2_000; i++) {
                var download = service.getObjectForDownload(
                        "race-bucket", "obj.txt", null, GcsCustomerEncryption.none());
                var expected = payloads.get(download.meta().getMetadata().get("tag"));
                assertArrayEquals(expected, download.data(),
                        "bytes belong to a different generation than the metadata");
            }
        } finally {
            stop.set(true);
            writer.join();
        }
    }

    private static final class BlockingFirstPutStorage<K, V> extends InMemoryStorage<K, V> {
        private final CountDownLatch putStarted;
        private final CountDownLatch allowPut;
        private final AtomicBoolean blockFirstPut = new AtomicBoolean(true);

        private BlockingFirstPutStorage(CountDownLatch putStarted, CountDownLatch allowPut) {
            this.putStarted = putStarted;
            this.allowPut = allowPut;
        }

        @Override
        public void put(K key, V value) {
            if (blockFirstPut.compareAndSet(true, false)) {
                putStarted.countDown();
                try {
                    if (!allowPut.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to publish object metadata");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while publishing object metadata", e);
                }
            }
            super.put(key, value);
        }
    }

    private static final class ArmableBlockingPutStorage<K, V> extends InMemoryStorage<K, V> {
        private final CountDownLatch putStarted;
        private final CountDownLatch allowPut;
        private final AtomicBoolean blockNextPut = new AtomicBoolean();

        private ArmableBlockingPutStorage(CountDownLatch putStarted, CountDownLatch allowPut) {
            this.putStarted = putStarted;
            this.allowPut = allowPut;
        }

        private void blockNextPut() {
            blockNextPut.set(true);
        }

        @Override
        public void put(K key, V value) {
            if (blockNextPut.compareAndSet(true, false)) {
                putStarted.countDown();
                try {
                    if (!allowPut.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to publish object metadata");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while publishing object metadata", e);
                }
            }
            super.put(key, value);
        }
    }

    private static GcsService persistentService(Path root) {
        return new GcsService(
                persistent(root.resolve("gcs-buckets.json"), new TypeReference<Map<String, GcsBucket>>() {}),
                persistent(root.resolve("gcs-objects.json"), new TypeReference<Map<String, GcsObjectMeta>>() {}),
                persistent(root.resolve("gcs-object-data.json"), new TypeReference<Map<String, byte[]>>() {}),
                persistent(root.resolve("gcs-acls.json"), new TypeReference<Map<String, StoredAcl>>() {}),
                "test-project");
    }

    private static <V> StorageBackend<String, V> persistent(Path path,
            TypeReference<Map<String, V>> typeReference) {
        PersistentStorage<String, V> storage = new PersistentStorage<>(path, typeReference);
        storage.load();
        return storage;
    }

    @Test
    void abandonedUploadSessionsAreEvictedOnceIdlePastTheTimeout() {
        service.createBucket("reap-bucket", "p1", BASE_URL, Map.of());

        String resumableId = service.startResumableUpload("reap-bucket", "abandoned.txt", "text/plain",
                GcsCustomerEncryption.none(), Map.of());
        GcsObjectMeta streamed = new GcsObjectMeta();
        streamed.setBucket("reap-bucket");
        streamed.setName("abandoned-stream.txt");
        String streamingId = service.startStreamingUpload(streamed, GcsObjectPreconditions.NONE,
                null, null, null);

        assertNotNull(service.findResumableUpload(resumableId));
        assertEquals(1, service.streamingUploadCount());

        long oneHour = 3_600_000L;
        // A "now" an hour past the session's last write, rather than sleeping.
        int evicted = service.evictExpiredUploadSessions(System.currentTimeMillis() + oneHour, oneHour / 2);

        assertEquals(2, evicted, "both the REST and the gRPC session should be swept together");
        assertNull(service.findResumableUpload(resumableId));
        assertEquals(0, service.streamingUploadCount());
        assertThrows(GcpException.class, () -> service.getStreamingUpload(streamingId));
    }

    @Test
    void aWriterHoldingAnEvictedStreamingSessionCannotAcknowledgeLostBytes() {
        service.createBucket("race-bucket", "p1", BASE_URL, Map.of());
        GcsObjectMeta meta = new GcsObjectMeta();
        meta.setBucket("race-bucket");
        meta.setName("racy.txt");
        String uploadId = service.startStreamingUpload(meta, GcsObjectPreconditions.NONE, null, null, null);

        // GcsGrpcController looks the session up and only then synchronizes on it, so a sweep can
        // remove the map entry in between. Hold the reference the way the controller would.
        GcsStreamingUpload stale = service.getStreamingUpload(uploadId);

        assertEquals(1, service.evictExpiredUploadSessions(
                System.currentTimeMillis() + 3_600_000L, 1_000L));

        // Writing through the stale reference must fail. Silently accepting the bytes would hand
        // the client a persisted size for data the next chunk could never find a session for.
        assertThrows(GcpException.class,
                () -> stale.append(0, "lost".getBytes(StandardCharsets.UTF_8)));
        assertThrows(GcpException.class, () -> service.finalizeStreamingUpload(uploadId, BASE_URL));
    }

    @Test
    void activeUploadSessionsSurviveTheSweep() {
        service.createBucket("keep-bucket", "p1", BASE_URL, Map.of());

        String resumableId = service.startResumableUpload("keep-bucket", "active.txt", "text/plain",
                GcsCustomerEncryption.none(), Map.of());
        GcsObjectMeta streamed = new GcsObjectMeta();
        streamed.setBucket("keep-bucket");
        streamed.setName("active-stream.txt");
        service.startStreamingUpload(streamed, GcsObjectPreconditions.NONE, null, null, null);

        // Swept immediately: nothing has been idle for an hour yet.
        assertEquals(0, service.evictExpiredUploadSessions(System.currentTimeMillis(), 3_600_000L));
        assertNotNull(service.findResumableUpload(resumableId));
        assertEquals(1, service.streamingUploadCount());
    }

    @Test
    void advancingAResumableChunkRefreshesTheIdleDeadline() {
        service.createBucket("touch-bucket", "p1", BASE_URL, Map.of());
        String uploadId = service.startResumableUpload("touch-bucket", "chunked.txt", "text/plain",
                GcsCustomerEncryption.none(), Map.of());

        long startedAt = service.findResumableUpload(uploadId).lastTouchedMillis();
        service.applyResumableChunk(uploadId, new GcsContentRange(0, 3, 8L, false),
                "abcd".getBytes(StandardCharsets.UTF_8), BASE_URL);

        assertTrue(service.findResumableUpload(uploadId).lastTouchedMillis() >= startedAt,
                "a chunk that advances the session must refresh its last-touched stamp");
        // Still present a moment later, because the chunk reset the idle clock.
        assertEquals(0, service.evictExpiredUploadSessions(System.currentTimeMillis(), 3_600_000L));
        assertNotNull(service.findResumableUpload(uploadId));
    }

    @Test
    void aNonPositiveIdleTimeoutDisablesEviction() {
        service.createBucket("disabled-bucket", "p1", BASE_URL, Map.of());
        String uploadId = service.startResumableUpload("disabled-bucket", "kept.txt", "text/plain",
                GcsCustomerEncryption.none(), Map.of());

        assertEquals(0, service.evictExpiredUploadSessions(System.currentTimeMillis() + 999_999_999L, 0));
        assertNotNull(service.findResumableUpload(uploadId));
    }

}

package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.PersistentStorage;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.floci.gcp.services.gcs.model.GcsObjectPreconditions;
import io.floci.gcp.services.gcs.model.StoredAcl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
}

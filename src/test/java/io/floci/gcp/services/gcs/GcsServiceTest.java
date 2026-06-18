package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.PersistentStorage;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.floci.gcp.services.gcs.model.StoredAcl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        service.createBucket("b1", "p1", BASE_URL, Map.of());
        service.createBucket("b2", "p1", BASE_URL, Map.of());

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

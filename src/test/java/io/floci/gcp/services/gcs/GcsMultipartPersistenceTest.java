package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.PersistentStorage;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.gcs.model.GcsMultipartUpload;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class GcsMultipartPersistenceTest {
    private static final String BASE = "http://localhost:4588";
    @TempDir Path directory;

    private GcsService reload() {
        var buckets = new PersistentStorage<String, GcsBucket>(directory.resolve("buckets.json"), new TypeReference<Map<String, GcsBucket>>() {});
        var metadata = new PersistentStorage<String, GcsObjectMeta>(directory.resolve("objects.json"), new TypeReference<Map<String, GcsObjectMeta>>() {});
        var data = new PersistentStorage<String, byte[]>(directory.resolve("data.json"), new TypeReference<Map<String, byte[]>>() {});
        var uploads = new PersistentStorage<String, GcsMultipartUpload>(directory.resolve("uploads.json"), new TypeReference<Map<String, GcsMultipartUpload>>() {});
        buckets.load(); metadata.load(); data.load(); uploads.load();
        return new GcsService(buckets, metadata, data, new InMemoryStorage<>(), uploads, "test-project");
    }

    @Test
    void reloadThenOutOfOrderPartsRemainPageableAndCompleteWithExactBytes() {
        GcsService gcs = reload();
        gcs.createBucket("restart-bucket", "test-project", BASE, Map.of());
        GcsMultipartService multipart = new GcsMultipartService(gcs);
        String id = multipart.initiate("restart-bucket", "file", "application/octet-stream", Map.of("purpose", "restart")).id;
        multipart.putPart("restart-bucket", "file", id, 3, new byte[]{3}, null);

        // Reload materializes the declared Map as a LinkedHashMap. New parts must not
        // inherit that insertion order when the XML handler constructs marker pages.
        gcs = reload(); multipart = new GcsMultipartService(gcs);
        byte[] first = new byte[5 * 1024 * 1024]; Arrays.fill(first, (byte) 1);
        byte[] second = new byte[5 * 1024 * 1024]; Arrays.fill(second, (byte) 2);
        multipart.putPart("restart-bucket", "file", id, 1, first, null);
        multipart.putPart("restart-bucket", "file", id, 2, second, null);
        gcs = reload(); multipart = new GcsMultipartService(gcs);
        assertEquals(id, multipart.list("restart-bucket", "fi").getFirst().id);

        List<Map<String, String>> receipts = new ArrayList<>();
        for (int marker = 0; marker < 3; marker++) {
            List<GcsMultipartUpload.Part> remaining = multipart.listParts("restart-bucket", "file", id, marker);
            GcsMultipartUpload.Part page = remaining.getFirst(); // max-parts=1
            assertEquals(marker + 1, page.number());
            assertEquals(3 - marker, remaining.size());
            receipts.add(Map.of("PartNumber", Integer.toString(page.number()), "ETag", page.etag()));
        }
        assertTrue(multipart.listParts("restart-bucket", "file", id, 3).isEmpty());
        multipart.complete("restart-bucket", "file", id, receipts, BASE);
        byte[] expected = new byte[first.length + second.length + 1];
        System.arraycopy(first, 0, expected, 0, first.length);
        System.arraycopy(second, 0, expected, first.length, second.length);
        expected[expected.length - 1] = 3;
        assertArrayEquals(expected, gcs.getObjectData("restart-bucket", "file"));
        assertEquals(Map.of("purpose", "restart"), gcs.getObjectMeta("restart-bucket", "file").getMetadata());
        assertTrue(new GcsMultipartService(reload()).list("restart-bucket", "").isEmpty());
    }

    @Test
    void bucketDeletionPurgesOnlyItsUploadsAcrossRecreationAndReload() {
        GcsService gcs = reload();
        gcs.createBucket("deleted-bucket", "first-project", BASE, Map.of());
        gcs.createBucket("retained-bucket", "first-project", BASE, Map.of());
        GcsMultipartService multipart = new GcsMultipartService(gcs);
        String id = multipart.initiate("deleted-bucket", "file", "text/plain", Map.of()).id;
        multipart.putPart("deleted-bucket", "file", id, 1, new byte[]{1}, null);
        String retained = multipart.initiate("retained-bucket", "file", "text/plain", Map.of()).id;
        gcs.deleteBucket("deleted-bucket");
        gcs = reload();
        gcs.createBucket("deleted-bucket", "second-project", BASE, Map.of());
        GcsMultipartService restarted = new GcsMultipartService(reload());
        assertTrue(restarted.list("deleted-bucket", "").isEmpty());
        assertEquals(retained, restarted.list("retained-bucket", "").getFirst().id);
        assertEquals("NoSuchUpload", assertThrows(GcpException.class,
                () -> restarted.get("deleted-bucket", "file", id)).getReason());
        assertThrows(GcpException.class, () -> restarted.putPart("deleted-bucket", "file", id, 1, new byte[]{2}, null));
        assertThrows(GcpException.class, () -> restarted.complete("deleted-bucket", "file", id, List.of(), BASE));
        assertThrows(GcpException.class, () -> restarted.abort("deleted-bucket", "file", id));
    }

    @Test
    void rejectedNonemptyBucketDeletionPreservesPendingUpload() {
        GcsService gcs = reload();
        gcs.createBucket("nonempty-bucket", "test-project", BASE, Map.of());
        GcsMultipartService multipart = new GcsMultipartService(gcs);
        String id = multipart.initiate("nonempty-bucket", "file", "text/plain", Map.of()).id;
        var part = multipart.putPart("nonempty-bucket", "file", id, 1, new byte[]{1}, null);
        String complete = multipart.initiate("nonempty-bucket", "existing", "text/plain", Map.of()).id;
        var existing = multipart.putPart("nonempty-bucket", "existing", complete, 1, new byte[]{2}, null);
        multipart.complete("nonempty-bucket", "existing", complete, List.of(Map.of("PartNumber", "1", "ETag", existing.etag())), BASE);
        assertFalse(gcs.deleteBucketIfEmpty("nonempty-bucket"));
        GcsMultipartService restarted = new GcsMultipartService(reload());
        assertEquals(part.etag(), restarted.listParts("nonempty-bucket", "file", id, 0).getFirst().etag());
    }

    @Test
    void deletionWaitsForInitiationThenRemovesItsUpload() throws Exception {
        CountDownLatch putting = new CountDownLatch(1), release = new CountDownLatch(1);
        var uploads = new InMemoryStorage<String, GcsMultipartUpload>() {
            @Override public void put(String key, GcsMultipartUpload value) {
                putting.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                super.put(key, value);
            }
        };
        GcsService gcs = new GcsService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), uploads, "test-project");
        gcs.createBucket("racing-bucket", "test-project", BASE, Map.of());
        GcsMultipartService multipart = new GcsMultipartService(gcs);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var initiate = executor.submit(() -> multipart.initiate("racing-bucket", "file", "text/plain", Map.of()));
            assertTrue(putting.await(5, TimeUnit.SECONDS));
            CountDownLatch deleting = new CountDownLatch(1);
            var deletion = executor.submit(() -> { deleting.countDown(); gcs.deleteBucket("racing-bucket"); });
            try {
                assertTrue(deleting.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            String id = initiate.get(5, TimeUnit.SECONDS).id;
            deletion.get(5, TimeUnit.SECONDS);
            gcs.createBucket("racing-bucket", "test-project", BASE, Map.of());
            assertTrue(multipart.list("racing-bucket", "").isEmpty());
            assertEquals("NoSuchUpload", assertThrows(GcpException.class,
                    () -> multipart.get("racing-bucket", "file", id)).getReason());
        } finally { release.countDown(); }
    }
}

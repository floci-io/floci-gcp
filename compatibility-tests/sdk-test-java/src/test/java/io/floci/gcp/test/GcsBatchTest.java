package io.floci.gcp.test;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageBatch;
import com.google.cloud.storage.StorageBatchResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GcsBatchTest {

    private static final String BUCKET_NAME = TestFixtures.uniqueName("batch-bucket");
    private static final String OBJ1 = "batch/object1.txt";
    private static final String OBJ2 = "batch/object2.txt";
    private static final String OBJ3 = "batch/object3.txt";

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
    }

    @AfterAll
    static void tearDown() throws Exception {
        for (String obj : List.of(OBJ1, OBJ2, OBJ3)) {
            try { storage.delete(BlobId.of(BUCKET_NAME, obj)); } catch (Exception ignored) {}
        }
        try { storage.get(BUCKET_NAME).delete(); } catch (Exception ignored) {}
        if (storage instanceof AutoCloseable closeable) closeable.close();
    }

    @Test
    @Order(1)
    void createBucketAndObjects() {
        storage.create(BucketInfo.of(BUCKET_NAME));

        for (String obj : List.of(OBJ1, OBJ2, OBJ3)) {
            BlobInfo info = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, obj))
                    .setContentType("text/plain")
                    .build();
            storage.create(info, ("content of " + obj).getBytes(StandardCharsets.UTF_8));
        }

        assertThat(storage.get(BUCKET_NAME)).isNotNull();
        assertThat(storage.get(BlobId.of(BUCKET_NAME, OBJ1))).isNotNull();
    }

    @Test
    @Order(2)
    void batchGetMetadata() {
        StorageBatch batch = storage.batch();

        StorageBatchResult<Blob> r1 = batch.get(BlobId.of(BUCKET_NAME, OBJ1));
        StorageBatchResult<Blob> r2 = batch.get(BlobId.of(BUCKET_NAME, OBJ2));
        StorageBatchResult<Blob> r3 = batch.get(BlobId.of(BUCKET_NAME, OBJ3));

        batch.submit();

        assertThat(r1.get()).isNotNull();
        assertThat(r1.get().getName()).isEqualTo(OBJ1);
        assertThat(r2.get()).isNotNull();
        assertThat(r2.get().getName()).isEqualTo(OBJ2);
        assertThat(r3.get()).isNotNull();
        assertThat(r3.get().getName()).isEqualTo(OBJ3);
    }

    @Test
    @Order(3)
    void batchGetNonExistentReturnsNull() {
        StorageBatch batch = storage.batch();

        StorageBatchResult<Blob> existing = batch.get(BlobId.of(BUCKET_NAME, OBJ1));
        StorageBatchResult<Blob> missing = batch.get(BlobId.of(BUCKET_NAME, "batch/does-not-exist.txt"));

        batch.submit();

        assertThat(existing.get()).isNotNull();
        assertThat(missing.get()).isNull();
    }

    @Test
    @Order(4)
    void batchDeleteObjects() {
        StorageBatch batch = storage.batch();

        List<StorageBatchResult<Boolean>> results = new ArrayList<>();
        results.add(batch.delete(BlobId.of(BUCKET_NAME, OBJ1)));
        results.add(batch.delete(BlobId.of(BUCKET_NAME, OBJ2)));
        results.add(batch.delete(BlobId.of(BUCKET_NAME, OBJ3)));

        batch.submit();

        for (StorageBatchResult<Boolean> result : results) {
            assertThat(result.get()).isTrue();
        }

        assertThat(storage.get(BlobId.of(BUCKET_NAME, OBJ1))).isNull();
        assertThat(storage.get(BlobId.of(BUCKET_NAME, OBJ2))).isNull();
        assertThat(storage.get(BlobId.of(BUCKET_NAME, OBJ3))).isNull();
    }

    @Test
    @Order(5)
    void batchDeleteNonExistentReturnsFalse() {
        StorageBatch batch = storage.batch();

        StorageBatchResult<Boolean> result = batch.delete(BlobId.of(BUCKET_NAME, "batch/ghost.txt"));

        batch.submit();

        assertThat(result.get()).isFalse();
    }
}

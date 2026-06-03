package io.floci.gcp.test;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression coverage for issue #2: preconditions ({@code ifGenerationMatch},
 * {@code ifMetagenerationMatch}, ...) were accepted but never evaluated, so
 * unsafe writes silently succeeded. Real GCS returns 412 Precondition Failed.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GcsPreconditionsTest {

    private static final String BUCKET_NAME = TestFixtures.uniqueName("precond-bucket");
    private static final String OBJECT_NAME = "obj.txt";

    private static Storage storage;
    private static long currentGeneration;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(BUCKET_NAME));
        Blob blob = storage.create(BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME)).build(),
                "v1".getBytes(StandardCharsets.UTF_8));
        currentGeneration = blob.getGeneration();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    @Order(1)
    void doesNotExistOnExistingObjectFailsWith412() {
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME)).build();
        assertThatThrownBy(() -> storage.create(info, "v2".getBytes(StandardCharsets.UTF_8),
                Storage.BlobTargetOption.doesNotExist()))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> assertThat(((StorageException) e).getCode()).isEqualTo(412));
    }

    @Test
    @Order(2)
    void staleGenerationMatchFailsWith412() {
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME)).build();

        // A matching generation precondition succeeds and bumps the generation.
        Blob updated = storage.create(info, "v3".getBytes(StandardCharsets.UTF_8),
                Storage.BlobTargetOption.generationMatch(currentGeneration));
        assertThat(updated.getGeneration()).isNotEqualTo(currentGeneration);

        // Re-using the now-stale generation must fail.
        assertThatThrownBy(() -> storage.create(info, "v4".getBytes(StandardCharsets.UTF_8),
                Storage.BlobTargetOption.generationMatch(currentGeneration)))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> assertThat(((StorageException) e).getCode()).isEqualTo(412));

        currentGeneration = updated.getGeneration();
    }

    @Test
    @Order(3)
    void wrongMetagenerationMatchFailsWith412() {
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME))
                .setContentType("application/json")
                .build();
        assertThatThrownBy(() -> storage.update(info, Storage.BlobTargetOption.metagenerationMatch(999)))
                .isInstanceOf(StorageException.class)
                .satisfies(e -> assertThat(((StorageException) e).getCode()).isEqualTo(412));
    }

    @Test
    @Order(4)
    void matchingMetagenerationSucceeds() {
        Blob blob = storage.get(BlobId.of(BUCKET_NAME, OBJECT_NAME));
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME))
                .setContentType("text/markdown")
                .build();
        Blob updated = storage.update(info,
                Storage.BlobTargetOption.metagenerationMatch(blob.getMetageneration()));
        assertThat(updated.getContentType()).isEqualTo("text/markdown");
    }
}

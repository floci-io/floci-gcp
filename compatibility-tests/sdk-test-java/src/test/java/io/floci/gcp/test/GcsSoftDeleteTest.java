package io.floci.gcp.test;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Soft delete is on by default in real GCS, with a seven-day policy on every new bucket, so code
 * written against production may rely on being able to undo a delete. A deleted object leaves the live listing, appears
 * under {@code softDeleted=true} with soft/hard delete timestamps, and can be restored by
 * generation through {@code objects.restore}.
 */
class GcsSoftDeleteTest {

    private static final String BUCKET = TestFixtures.uniqueName("soft-delete-bucket");
    private static final byte[] PAYLOAD = "recoverable".getBytes(StandardCharsets.UTF_8);

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.newBuilder(BUCKET)
                .setSoftDeletePolicy(BucketInfo.SoftDeletePolicy.newBuilder()
                        .setRetentionDuration(Duration.ofDays(7))
                        .build())
                .build());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void bucketReportsItsSoftDeletePolicy() {
        BucketInfo.SoftDeletePolicy policy = storage.get(BUCKET).getSoftDeletePolicy();
        assertThat(policy).isNotNull();
        assertThat(policy.getRetentionDuration()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void deletedObjectLeavesTheLiveListingAndCanBeRestored() {
        String name = "restore-me.txt";
        Blob created = storage.create(BlobInfo.newBuilder(BlobId.of(BUCKET, name)).build(), PAYLOAD);
        long generation = created.getGeneration();

        assertThat(storage.delete(BlobId.of(BUCKET, name))).isTrue();

        // Gone from the live view.
        assertThat(storage.get(BlobId.of(BUCKET, name))).isNull();
        assertThat(liveNames()).doesNotContain(name);

        // Still visible as soft deleted, with the retention window stamped on it.
        List<Blob> softDeleted = StreamSupport.stream(
                        storage.list(BUCKET, Storage.BlobListOption.softDeleted(true))
                                .iterateAll().spliterator(), false)
                .filter(blob -> blob.getName().equals(name))
                .toList();
        assertThat(softDeleted).hasSize(1);
        assertThat(softDeleted.get(0).getSoftDeleteTime()).isNotNull();
        assertThat(softDeleted.get(0).getHardDeleteTime()).isNotNull();

        Blob restored = storage.restore(BlobId.of(BUCKET, name, generation));
        assertThat(restored.getName()).isEqualTo(name);

        // Back in the live view, with its bytes intact.
        assertThat(liveNames()).contains(name);
        assertThat(storage.readAllBytes(BlobId.of(BUCKET, name))).isEqualTo(PAYLOAD);
    }

    @Test
    void bucketWithoutAPolicyGetsTheSevenDayDefaultAndRestores() {
        String bucket = TestFixtures.uniqueName("default-soft-delete");
        storage.create(BucketInfo.of(bucket));
        assertThat(storage.get(bucket).getSoftDeletePolicy().getRetentionDuration()).isEqualTo(Duration.ofDays(7));

        Blob created = storage.create(BlobInfo.newBuilder(BlobId.of(bucket, "kept.txt")).build(), PAYLOAD);
        assertThat(storage.delete(BlobId.of(bucket, "kept.txt"))).isTrue();
        Blob restored = storage.restore(BlobId.of(bucket, "kept.txt", created.getGeneration()));
        assertThat(restored.getName()).isEqualTo("kept.txt");
        assertThat(storage.readAllBytes(BlobId.of(bucket, "kept.txt"))).isEqualTo(PAYLOAD);
    }

    @Test
    void zeroRetentionDeletesPermanently() {
        String bucket = TestFixtures.uniqueName("no-soft-delete");
        storage.create(BucketInfo.newBuilder(bucket)
                .setSoftDeletePolicy(BucketInfo.SoftDeletePolicy.newBuilder()
                        .setRetentionDuration(Duration.ZERO)
                        .build())
                .build());
        storage.create(BlobInfo.newBuilder(BlobId.of(bucket, "gone.txt")).build(), PAYLOAD);
        assertThat(storage.delete(BlobId.of(bucket, "gone.txt"))).isTrue();

        assertThat(storage.list(bucket, Storage.BlobListOption.softDeleted(true)).iterateAll()).isEmpty();
    }

    private static List<String> liveNames() {
        return StreamSupport.stream(storage.list(BUCKET).iterateAll().spliterator(), false)
                .map(Blob::getName)
                .toList();
    }
}

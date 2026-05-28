package io.floci.gcp.test;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import static com.google.cloud.storage.Storage.BucketTargetOption.metagenerationMatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GcsHoldLockTest {

    private static final String CONTENT = "hold-lock-test-content";
    private static final byte[] BYTES = CONTENT.getBytes(StandardCharsets.UTF_8);

    private Storage storage;

    @BeforeEach
    void setUp() {
        storage = TestFixtures.storageClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (storage instanceof AutoCloseable c) c.close();
    }

    // ── temporaryHold ─────────────────────────────────────────────────────────

    @Test
    void temporaryHoldBlocksDelete() {
        String bucket = TestFixtures.uniqueName("hold-tmp");
        String obj = "obj.txt";
        try {
            storage.create(BucketInfo.of(bucket));
            Blob blob = storage.create(BlobInfo.newBuilder(bucket, obj).build(), BYTES);

            blob.toBuilder().setTemporaryHold(true).build().update();

            Blob held = storage.get(BlobId.of(bucket, obj));
            assertThat(held.getTemporaryHold()).isTrue();

            assertThatThrownBy(() -> storage.delete(BlobId.of(bucket, obj)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("temporary hold");

            held.toBuilder().setTemporaryHold(false).build().update();
            boolean deleted = storage.delete(BlobId.of(bucket, obj));
            assertThat(deleted).isTrue();
        } finally {
            cleanup(bucket, obj);
        }
    }

    @Test
    void temporaryHoldBlocksOverwrite() {
        String bucket = TestFixtures.uniqueName("hold-tmp-ow");
        String obj = "obj.txt";
        try {
            storage.create(BucketInfo.of(bucket));
            Blob blob = storage.create(BlobInfo.newBuilder(bucket, obj).build(), BYTES);
            blob.toBuilder().setTemporaryHold(true).build().update();

            assertThatThrownBy(() ->
                    storage.create(BlobInfo.newBuilder(bucket, obj).build(), "new".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("temporary hold");

            storage.get(BlobId.of(bucket, obj)).toBuilder().setTemporaryHold(false).build().update();
        } finally {
            cleanup(bucket, obj);
        }
    }

    // ── eventBasedHold ────────────────────────────────────────────────────────

    @Test
    void eventBasedHoldBlocksDelete() {
        String bucket = TestFixtures.uniqueName("hold-evt");
        String obj = "obj.txt";
        try {
            storage.create(BucketInfo.of(bucket));
            Blob blob = storage.create(BlobInfo.newBuilder(bucket, obj).build(), BYTES);

            blob.toBuilder().setEventBasedHold(true).build().update();

            Blob held = storage.get(BlobId.of(bucket, obj));
            assertThat(held.getEventBasedHold()).isTrue();

            assertThatThrownBy(() -> storage.delete(BlobId.of(bucket, obj)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("event-based hold");

            held.toBuilder().setEventBasedHold(false).build().update();
            boolean deleted = storage.delete(BlobId.of(bucket, obj));
            assertThat(deleted).isTrue();
        } finally {
            cleanup(bucket, obj);
        }
    }

    @Test
    void defaultEventBasedHoldInheritedByNewObjects() {
        String bucket = TestFixtures.uniqueName("hold-def-evt");
        String obj = "obj.txt";
        try {
            storage.create(BucketInfo.newBuilder(bucket)
                    .setDefaultEventBasedHold(true)
                    .build());

            Blob blob = storage.create(BlobInfo.newBuilder(bucket, obj).build(), BYTES);
            assertThat(blob.getEventBasedHold()).isTrue();

            assertThatThrownBy(() -> storage.delete(BlobId.of(bucket, obj)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("event-based hold");

            storage.get(BlobId.of(bucket, obj)).toBuilder().setEventBasedHold(false).build().update();
            assertThat(storage.delete(BlobId.of(bucket, obj))).isTrue();
        } finally {
            cleanup(bucket, obj);
        }
    }

    // ── Bucket retention policy ───────────────────────────────────────────────

    @Test
    void retentionPolicyBlocksDeleteBeforeExpiry() throws Exception {
        String bucket = TestFixtures.uniqueName("hold-ret");
        String obj = "obj.txt";
        try {
            // 3-second retention period
            storage.create(BucketInfo.newBuilder(bucket)
                    .setRetentionPeriod(3L)
                    .build());

            Blob blob = storage.create(BlobInfo.newBuilder(bucket, obj).build(), BYTES);
            assertThat(blob.getRetentionExpirationTime()).isNotNull();

            assertThatThrownBy(() -> storage.delete(BlobId.of(bucket, obj)))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("retention policy");

            Thread.sleep(4000);
            assertThat(storage.delete(BlobId.of(bucket, obj))).isTrue();
        } finally {
            cleanup(bucket, obj);
        }
    }

    @Test
    void lockRetentionPolicyIsReflectedOnBucket() {
        String bucket = TestFixtures.uniqueName("hold-lock");
        try {
            storage.create(BucketInfo.newBuilder(bucket)
                    .setRetentionPeriod(86400L)
                    .build());

            Bucket b = storage.get(bucket);
            Bucket locked = b.lockRetentionPolicy(metagenerationMatch());

            assertThat(locked.retentionPolicyIsLocked()).isTrue();
            assertThat(locked.getRetentionPeriod()).isEqualTo(86400L);
        } finally {
            cleanup(bucket, null);
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void cleanup(String bucket, String obj) {
        try {
            if (obj != null) {
                Blob b = storage.get(BlobId.of(bucket, obj));
                if (b != null) {
                    if (Boolean.TRUE.equals(b.getTemporaryHold())) {
                        b.toBuilder().setTemporaryHold(false).build().update();
                    }
                    if (Boolean.TRUE.equals(b.getEventBasedHold())) {
                        b.toBuilder().setEventBasedHold(false).build().update();
                    }
                    storage.delete(BlobId.of(bucket, obj));
                }
            }
        } catch (Exception ignored) {}
        try { storage.delete(bucket); } catch (Exception ignored) {}
    }
}

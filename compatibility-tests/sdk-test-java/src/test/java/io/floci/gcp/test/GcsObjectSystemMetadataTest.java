package io.floci.gcp.test;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageClass;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * System metadata set at upload time, and the storage class an object inherits from its bucket.
 *
 * <p>The metageneration assertions are the point of the first test: these fields have to land
 * with the object rather than being patched on afterwards, otherwise every upload reports a
 * spurious metageneration bump and clients that branch on it see a phantom change.
 */
class GcsObjectSystemMetadataTest {

    private static final String BUCKET = TestFixtures.uniqueName("system-metadata-bucket");
    private static final String NEARLINE_BUCKET = TestFixtures.uniqueName("nearline-bucket");

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(BUCKET));
        storage.create(BucketInfo.newBuilder(NEARLINE_BUCKET)
                .setStorageClass(StorageClass.NEARLINE)
                .build());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void systemMetadataSurvivesTheUploadWithoutAMetagenerationBump() {
        OffsetDateTime customTime = OffsetDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        Blob created = storage.create(BlobInfo.newBuilder(BlobId.of(BUCKET, "system-metadata.txt"))
                .setContentType("text/plain")
                .setContentDisposition("attachment; filename=\"report.txt\"")
                .setContentLanguage("en")
                .setCacheControl("public, max-age=3600")
                .setCustomTimeOffsetDateTime(customTime)
                .build(), "body".getBytes(StandardCharsets.UTF_8));

        assertThat(created.getContentDisposition()).isEqualTo("attachment; filename=\"report.txt\"");
        assertThat(created.getContentLanguage()).isEqualTo("en");
        assertThat(created.getCacheControl()).isEqualTo("public, max-age=3600");
        assertThat(created.getCustomTimeOffsetDateTime()).isEqualTo(customTime);
        assertThat(created.getMetageneration()).isEqualTo(1L);

        // Re-read to confirm the fields are persisted rather than echoed from the request.
        Blob reread = storage.get(BlobId.of(BUCKET, "system-metadata.txt"));
        assertThat(reread.getContentDisposition()).isEqualTo("attachment; filename=\"report.txt\"");
        assertThat(reread.getContentLanguage()).isEqualTo("en");
        assertThat(reread.getCacheControl()).isEqualTo("public, max-age=3600");
        assertThat(reread.getCustomTimeOffsetDateTime()).isEqualTo(customTime);
        assertThat(reread.getMetageneration()).isEqualTo(1L);
    }

    @Test
    void customTimeCanBeSetByPatchAfterUpload() {
        BlobId id = BlobId.of(BUCKET, "patched-custom-time.txt");
        storage.create(BlobInfo.newBuilder(id).build(), new byte[0]);

        OffsetDateTime customTime = OffsetDateTime.of(2026, 4, 2, 8, 30, 0, 0, ZoneOffset.UTC);
        Blob updated = storage.update(BlobInfo.newBuilder(id)
                .setCustomTimeOffsetDateTime(customTime)
                .build());

        assertThat(updated.getCustomTimeOffsetDateTime()).isEqualTo(customTime);
        assertThat(storage.get(id).getCustomTimeOffsetDateTime()).isEqualTo(customTime);
    }

    @Test
    void objectInheritsTheBucketDefaultStorageClass() {
        Blob inherited = storage.create(
                BlobInfo.newBuilder(BlobId.of(NEARLINE_BUCKET, "inherited.txt")).build(),
                new byte[0]);
        assertThat(inherited.getStorageClass()).isEqualTo(StorageClass.NEARLINE);

        // An explicit storage class on the upload still wins over the bucket default.
        Blob explicitClass = storage.create(
                BlobInfo.newBuilder(BlobId.of(NEARLINE_BUCKET, "explicit.txt"))
                        .setStorageClass(StorageClass.COLDLINE)
                        .build(),
                new byte[0]);
        assertThat(explicitClass.getStorageClass()).isEqualTo(StorageClass.COLDLINE);

        // A bucket left on the default still reports STANDARD.
        Blob standard = storage.create(
                BlobInfo.newBuilder(BlobId.of(BUCKET, "standard.txt")).build(), new byte[0]);
        assertThat(standard.getStorageClass()).isEqualTo(StorageClass.STANDARD);
    }

    @Test
    void zeroByteObjectRoundTrips() {
        // Directory placeholders, Spark _SUCCESS markers and .keep files are all empty objects,
        // and the Node SDK creates them through a resumable session with no bytes at all.
        BlobId id = BlobId.of(BUCKET, "empty/_SUCCESS");
        Blob created = storage.create(BlobInfo.newBuilder(id).build(), new byte[0]);

        assertThat(created.getSize()).isZero();
        assertThat(storage.readAllBytes(id)).isEmpty();
        assertThat(storage.get(id).getSize()).isZero();
    }
}

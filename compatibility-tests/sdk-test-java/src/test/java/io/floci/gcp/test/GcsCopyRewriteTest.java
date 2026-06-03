package io.floci.gcp.test;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.CopyWriter;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for issue #3: copy/rewrite dropped custom object metadata
 * on the destination. Per the rewrite docs, the destination inherits the source
 * metadata unless overridden in the request.
 */
class GcsCopyRewriteTest {

    private static final String BUCKET_NAME = TestFixtures.uniqueName("copy-bucket");

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(BUCKET_NAME));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void copyPreservesSourceMetadata() {
        BlobInfo source = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, "src"))
                .setContentType("text/plain")
                .build();
        storage.create(source, "x".getBytes(StandardCharsets.UTF_8));

        // Set custom metadata on the source (mirrors the bug report's PATCH step).
        Blob src = storage.update(BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, "src"))
                .setMetadata(Map.of("tag", "keep-me"))
                .build());
        assertThat(src.getMetadata()).containsEntry("tag", "keep-me");

        Storage.CopyRequest copyRequest = Storage.CopyRequest.newBuilder()
                .setSource(BlobId.of(BUCKET_NAME, "src"))
                .setTarget(BlobId.of(BUCKET_NAME, "dst"))
                .build();
        CopyWriter writer = storage.copy(copyRequest);
        Blob dst = writer.getResult();

        assertThat(dst.getName()).isEqualTo("dst");
        assertThat(dst.getContentType()).isEqualTo("text/plain");
        assertThat(dst.getMetadata()).containsEntry("tag", "keep-me");

        // Re-read to confirm the metadata is persisted, not just echoed.
        Blob reread = storage.get(BlobId.of(BUCKET_NAME, "dst"));
        assertThat(reread.getMetadata()).containsEntry("tag", "keep-me");
    }
}

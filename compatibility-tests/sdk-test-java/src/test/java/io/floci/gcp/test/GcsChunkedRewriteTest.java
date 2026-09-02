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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code objects.rewrite} is multi-call in real GCS: when {@code maxBytesRewrittenPerCall} is
 * below the object size the response comes back {@code done:false} with a {@code rewriteToken},
 * and the client loops until it completes. That is the path GCS takes for large or
 * class-changing copies, and a single-shot response never exercises it, so the SDK's chunking
 * loop goes untested against the emulator.
 */
class GcsChunkedRewriteTest {

    private static final String BUCKET = TestFixtures.uniqueName("chunked-rewrite-bucket");
    private static final String SOURCE = "large-source.bin";
    private static final String TARGET = "large-target.bin";
    // Three chunks' worth at one megabyte per call.
    private static final byte[] PAYLOAD = new byte[3 * 1024 * 1024];

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(BUCKET));
        for (int i = 0; i < PAYLOAD.length; i++) {
            PAYLOAD[i] = (byte) (i % 251);
        }
        storage.create(BlobInfo.newBuilder(BlobId.of(BUCKET, SOURCE)).build(), PAYLOAD);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void rewriteCompletesOverSeveralCallsAndPublishesOnlyWhenDone() {
        CopyWriter writer = storage.copy(Storage.CopyRequest.newBuilder()
                .setSource(BlobId.of(BUCKET, SOURCE))
                .setTarget(BlobId.of(BUCKET, TARGET))
                .setMegabytesCopiedPerChunk(1L)
                .build());

        // The first call is issued by copy() itself and cannot have finished a 3 MiB object.
        assertThat(writer.isDone()).isFalse();
        assertThat(writer.getBlobSize()).isEqualTo(PAYLOAD.length);
        assertThat(writer.getTotalBytesCopied()).isPositive().isLessThan(PAYLOAD.length);

        // A partially rewritten object must not be visible: GCS publishes the destination only
        // on the completing call.
        assertThat(storage.get(BlobId.of(BUCKET, TARGET))).isNull();

        int calls = 1;
        while (!writer.isDone()) {
            writer.copyChunk();
            calls++;
        }
        assertThat(calls).isGreaterThan(1);
        assertThat(writer.getTotalBytesCopied()).isEqualTo(PAYLOAD.length);

        Blob target = writer.getResult();
        assertThat(target.getName()).isEqualTo(TARGET);
        assertThat(target.getSize()).isEqualTo(PAYLOAD.length);
        assertThat(storage.readAllBytes(BlobId.of(BUCKET, TARGET))).isEqualTo(PAYLOAD);
    }

    @Test
    void rewriteWithoutChunkingStillCompletesInOneCall() {
        CopyWriter writer = storage.copy(Storage.CopyRequest.newBuilder()
                .setSource(BlobId.of(BUCKET, SOURCE))
                .setTarget(BlobId.of(BUCKET, "single-shot-target.bin"))
                .build());

        assertThat(writer.isDone()).isTrue();
        assertThat(writer.getResult().getSize()).isEqualTo(PAYLOAD.length);
    }
}

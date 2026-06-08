package io.floci.gcp.test;

import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class GcsResumableUploadTest {

    private static final String BUCKET = TestFixtures.uniqueName("resumable-sdk-bucket");
    private static final String OBJECT = "resumable.bin";
    private static final int CHUNK_SIZE = 1024 * 1024;

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(BUCKET));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void resumableUploadWritesMultipleChunks() throws Exception {
        int secondChunkSize = CHUNK_SIZE / 2;
        int totalSize = CHUNK_SIZE + secondChunkSize;

        byte[] data = new byte[totalSize];
        Arrays.fill(data, 0, CHUNK_SIZE, (byte) 'a');
        Arrays.fill(data, CHUNK_SIZE, totalSize, (byte) 'b');

        writeObject(OBJECT, data);

        Blob blob = storage.get(BlobId.of(BUCKET, OBJECT));
        assertThat(blob.getSize()).isEqualTo(totalSize);

        byte[] stored = storage.readAllBytes(BlobId.of(BUCKET, OBJECT));
        assertThat(stored).hasSize(totalSize);
        assertThat(stored[0]).isEqualTo((byte) 'a');
        assertThat(stored[CHUNK_SIZE]).isEqualTo((byte) 'b');
    }

    @Test
    void resumableUploadCompletesAtChunkBoundary() throws Exception {
        String objectName = "boundary-complete.bin";
        byte[] data = new byte[CHUNK_SIZE];
        Arrays.fill(data, (byte) 'c');

        writeObject(objectName, data);

        Blob blob = storage.get(BlobId.of(BUCKET, objectName));
        assertThat(blob.getSize()).isEqualTo(data.length);
        assertThat(storage.readAllBytes(BlobId.of(BUCKET, objectName))).containsExactly(data);
    }

    @Test
    void resumableUploadCompletesAtSecondChunkBoundary() throws Exception {
        String objectName = "second-boundary-complete.bin";
        byte[] data = new byte[CHUNK_SIZE * 2];
        Arrays.fill(data, 0, CHUNK_SIZE, (byte) 'c');
        Arrays.fill(data, CHUNK_SIZE, data.length, (byte) 'd');

        writeObject(objectName, data);

        Blob blob = storage.get(BlobId.of(BUCKET, objectName));
        assertThat(blob.getSize()).isEqualTo(data.length);

        byte[] stored = storage.readAllBytes(BlobId.of(BUCKET, objectName));
        assertThat(stored).hasSize(data.length);
        assertThat(stored[0]).isEqualTo((byte) 'c');
        assertThat(stored[CHUNK_SIZE]).isEqualTo((byte) 'd');
    }

    @Test
    void resumableUploadWritesSmallObject() throws Exception {
        String objectName = "small-object.bin";
        byte[] data = "small-object-data".getBytes(StandardCharsets.UTF_8);

        writeObject(objectName, data);

        Blob blob = storage.get(BlobId.of(BUCKET, objectName));
        assertThat(blob.getSize()).isEqualTo(data.length);
        assertThat(storage.readAllBytes(BlobId.of(BUCKET, objectName))).containsExactly(data);
    }

    private static void writeObject(String objectName, byte[] data) throws Exception {
        BlobInfo blob = BlobInfo.newBuilder(BlobId.of(BUCKET, objectName))
                .setContentType("application/octet-stream")
                .build();
        try (WriteChannel writer = storage.writer(blob)) {
            writer.setChunkSize(CHUNK_SIZE);
            writer.write(ByteBuffer.wrap(data));
        }
    }
}

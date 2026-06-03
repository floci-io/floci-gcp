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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for issue #1: object compose was broken
 * ("400 Unsupported method override: null") because the route was mapped to
 * {@code :compose} instead of {@code /compose}.
 */
class GcsComposeTest {

    private static final String BUCKET_NAME = TestFixtures.uniqueName("compose-bucket");

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
    void composeConcatenatesSourceObjects() {
        storage.create(BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, "p1")).build(),
                "hello ".getBytes(StandardCharsets.UTF_8));
        storage.create(BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, "p2")).build(),
                "world".getBytes(StandardCharsets.UTF_8));

        BlobInfo target = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, "composed"))
                .setContentType("text/plain")
                .build();
        Storage.ComposeRequest request = Storage.ComposeRequest.newBuilder()
                .addSource("p1")
                .addSource("p2")
                .setTarget(target)
                .build();

        Blob composed = storage.compose(request);

        assertThat(composed.getName()).isEqualTo("composed");
        assertThat(composed.getContentType()).isEqualTo("text/plain");

        String content = new String(storage.readAllBytes(BlobId.of(BUCKET_NAME, "composed")),
                StandardCharsets.UTF_8);
        assertThat(content).isEqualTo("hello world");
        assertThat(composed.getSize()).isEqualTo("hello world".getBytes(StandardCharsets.UTF_8).length);
    }
}

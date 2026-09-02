package io.floci.gcp.test;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decompressive transcoding: an object stored with {@code contentEncoding: gzip} is served
 * decompressed to a client that did not ask for gzip, and as stored to one that did.
 *
 * <p>Driven over raw HTTP rather than through the SDK because the assertion is about the exact
 * {@code Accept-Encoding} sent and the exact bytes returned, and an HTTP client that negotiates
 * compression on the caller's behalf would hide both. {@code java.net.http.HttpClient} adds no
 * {@code Accept-Encoding} of its own.
 */
class GcsTranscodingRawTest {

    private static final String BUCKET = TestFixtures.uniqueName("transcoding-bucket");
    private static final String OBJECT = "compressed.txt";
    private static final String TEXT = "transcoding payload ".repeat(64);
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static Storage storage;
    private static byte[] gzipped;

    @BeforeAll
    static void setUp() throws Exception {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(BUCKET));

        gzipped = gzip(TEXT.getBytes(StandardCharsets.UTF_8));
        storage.create(BlobInfo.newBuilder(BlobId.of(BUCKET, OBJECT))
                .setContentType("text/plain")
                .setContentEncoding("gzip")
                .build(), gzipped);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void clientThatDoesNotAskForGzipGetsTheDecompressedBytes() throws Exception {
        HttpResponse<byte[]> response = CLIENT.send(
                HttpRequest.newBuilder(mediaUri()).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(TEXT);
        // A transcoded response is no longer gzip on the wire, so it must not claim to be.
        assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
    }

    @Test
    void clientThatAsksForGzipGetsTheStoredBytes() throws Exception {
        HttpResponse<byte[]> response = CLIENT.send(
                HttpRequest.newBuilder(mediaUri())
                        .header("Accept-Encoding", "gzip")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(gzipped);
        assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
    }

    @Test
    void rangeIsIgnoredOnATranscodedRead() throws Exception {
        // Stored offsets do not correspond to the bytes the caller receives once the object is
        // decompressed, so real GCS ignores Range rather than serving a slice of the wrong thing.
        HttpResponse<byte[]> response = CLIENT.send(
                HttpRequest.newBuilder(mediaUri())
                        .header("Range", "bytes=0-9")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(TEXT);
    }

    private static URI mediaUri() {
        return URI.create(TestFixtures.endpoint()
                + "/storage/v1/b/" + BUCKET + "/o/" + OBJECT + "?alt=media");
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(raw);
        }
        return out.toByteArray();
    }
}

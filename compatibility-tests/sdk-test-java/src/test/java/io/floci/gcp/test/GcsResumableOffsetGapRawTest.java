package io.floci.gcp.test;

import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A chunk whose offset sits past the bytes the backend holds is a client-side data loss, not a
 * transient failure. Real GCS reports it as a 503 with a {@code text/plain} body, and the Java SDK
 * keys on exactly that shape in {@code JsonResumableSessionPutTask} to fail fast instead of
 * retrying. The SDK class is package private, so this asserts the wire shape it inspects.
 */
class GcsResumableOffsetGapRawTest {

    private static final String BUCKET = TestFixtures.uniqueName("offset-gap-bucket");
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

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
    void chunkPastTheReceivedBytesIsPlainText() throws Exception {
        URI session = URI.create(startResumableSession());

        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(session)
                        .header("Content-Type", "application/octet-stream")
                        .header("Content-Range", "bytes 4-5/6")
                        .PUT(HttpRequest.BodyPublishers.ofString("ok"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("text/plain; charset=utf-8");
        assertThat(response.headers().firstValueAsLong("Content-Length")).hasValue(138);
        // Byte for byte what live GCS returned on 2026-08-27, double space included.
        assertThat(response.body()).isEqualTo(
                "Invalid request.  According to the Content-Range header, the upload offset is "
                        + "4 byte(s), which exceeds already uploaded size of 0 byte(s).");

        String body = response.body().toLowerCase(Locale.US);
        assertThat(body).contains("content-range");
        // The SDK reads "earlier" as the rewind case, a separate scenario it does retry.
        assertThat(body).doesNotContain("earlier");
    }

    private static String startResumableSession() throws Exception {
        URI uri = URI.create(TestFixtures.endpoint()
                + "/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable&name=offset-gap.bin");
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(uri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("Location").orElseThrow();
    }
}

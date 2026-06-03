package io.floci.gcp.test;

import com.google.cloud.storage.BlobId;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for issue #14: `gcloud storage cp` sends a multipart upload
 * whose Content-Type quotes the boundary with single quotes
 * (`boundary='...'`, from Python apitools). The boundary parser only stripped
 * double quotes, so the delimiter was not found and the upload failed with
 * `400 "multipart boundary not found in body"`. Reproduced with a raw HTTP client.
 */
class GcsMultipartUploadRawTest {

    private static final String BUCKET = TestFixtures.uniqueName("multipart-bucket");
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
    void multipartUploadWithSingleQuotedBoundarySucceeds() throws Exception {
        String boundary = "===============4060149368708691179==";
        String content = "hello world";
        String body = "--" + boundary + "\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + "{\"name\":\"hello.txt\"}\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + content + "\r\n"
                + "--" + boundary + "--";

        URI uri = URI.create(TestFixtures.endpoint()
                + "/upload/storage/v1/b/" + BUCKET + "/o?uploadType=multipart");
        // Single-quoted boundary, exactly as gcloud / Python apitools sends it.
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "multipart/related; boundary='" + boundary + "'")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"name\":\"hello.txt\"");

        byte[] stored = storage.readAllBytes(BlobId.of(BUCKET, "hello.txt"));
        assertThat(new String(stored, StandardCharsets.UTF_8)).isEqualTo(content);
    }
}

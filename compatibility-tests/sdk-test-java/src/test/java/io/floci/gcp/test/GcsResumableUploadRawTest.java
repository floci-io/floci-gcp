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
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class GcsResumableUploadRawTest {

    private static final String BUCKET = TestFixtures.uniqueName("resumable-bucket");
    private static final String OBJECT = "resumable.bin";
    private static final int FIRST_CHUNK_SIZE = 16 * 1024 * 1024;
    private static final int SECOND_CHUNK_SIZE = 1024 * 1024;
    private static final int TOTAL_SIZE = FIRST_CHUNK_SIZE + SECOND_CHUNK_SIZE;
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
    void resumableUploadKeepsSessionOpenUntilFinalChunk() throws Exception {
        HttpResponse<String> init = CLIENT.send(
                HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                                + "/upload/storage/v1/b/" + BUCKET
                                + "/o?uploadType=resumable&name=" + OBJECT))
                        .header("X-Upload-Content-Type", "application/octet-stream")
                        .header("X-Upload-Content-Length", String.valueOf(TOTAL_SIZE))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(init.statusCode()).isEqualTo(200);
        URI uploadUri = URI.create(init.headers().firstValue("Location").orElseThrow());

        HttpResponse<String> emptyStatus = CLIENT.send(
                HttpRequest.newBuilder(uploadUri)
                        .header("Content-Range", "bytes */*")
                        .PUT(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(emptyStatus.statusCode()).isEqualTo(308);
        assertThat(emptyStatus.headers().firstValue("Range")).isEmpty();

        byte[] first = new byte[FIRST_CHUNK_SIZE];
        byte[] second = new byte[SECOND_CHUNK_SIZE];
        Arrays.fill(first, (byte) 'a');
        Arrays.fill(second, (byte) 'b');

        HttpResponse<String> firstChunk = CLIENT.send(
                HttpRequest.newBuilder(uploadUri)
                        .header("Content-Type", "application/octet-stream")
                        .header("Content-Range", "bytes 0-" + (FIRST_CHUNK_SIZE - 1) + "/*")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(first))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(firstChunk.statusCode()).isEqualTo(308);
        assertThat(firstChunk.headers().firstValue("Range"))
                .contains("bytes=0-" + (FIRST_CHUNK_SIZE - 1));

        HttpResponse<String> status = CLIENT.send(
                HttpRequest.newBuilder(uploadUri)
                        .header("Content-Range", "bytes */" + TOTAL_SIZE)
                        .PUT(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(status.statusCode()).isEqualTo(308);
        assertThat(status.headers().firstValue("Range"))
                .contains("bytes=0-" + (FIRST_CHUNK_SIZE - 1));

        HttpResponse<String> finalChunk = CLIENT.send(
                HttpRequest.newBuilder(uploadUri)
                        .header("Content-Type", "application/octet-stream")
                        .header("Content-Range", "bytes " + FIRST_CHUNK_SIZE + "-"
                                + (TOTAL_SIZE - 1) + "/" + TOTAL_SIZE)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(second))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(finalChunk.statusCode()).isEqualTo(200);
        assertThat(finalChunk.body()).contains("\"size\":\"" + TOTAL_SIZE + "\"");

        HttpResponse<byte[]> download = CLIENT.send(
                HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                                + "/storage/v1/b/" + BUCKET + "/o/" + OBJECT + "?alt=media"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).hasSize(TOTAL_SIZE);
        assertThat(download.body()[0]).isEqualTo((byte) 'a');
        assertThat(download.body()[FIRST_CHUNK_SIZE]).isEqualTo((byte) 'b');
    }

    @Test
    void resumableUploadAcceptsUnknownSizeFinalChunk() throws Exception {
        String objectName = "unknown-size.bin";
        byte[] data = "unknown-size-data".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> init = CLIENT.send(
                HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                                + "/upload/storage/v1/b/" + BUCKET
                                + "/o?uploadType=resumable&name=" + objectName))
                        .header("X-Upload-Content-Type", "application/octet-stream")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(init.statusCode()).isEqualTo(200);
        URI uploadUri = URI.create(init.headers().firstValue("Location").orElseThrow());

        HttpResponse<String> upload = CLIENT.send(
                HttpRequest.newBuilder(uploadUri)
                        .header("Content-Type", "application/octet-stream")
                        .header("Content-Range", "bytes 0-*/*")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(upload.statusCode()).isEqualTo(200);
        assertThat(upload.body()).contains("\"size\":\"" + data.length + "\"");

        HttpResponse<byte[]> download = CLIENT.send(
                HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                                + "/storage/v1/b/" + BUCKET + "/o/" + objectName + "?alt=media"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).containsExactly(data);
    }
}

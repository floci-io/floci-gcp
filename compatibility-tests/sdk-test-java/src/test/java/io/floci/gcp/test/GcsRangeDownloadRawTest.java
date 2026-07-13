package io.floci.gcp.test;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
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

class GcsRangeDownloadRawTest {

    private static final String BUCKET = TestFixtures.uniqueName("range-bucket");
    private static final String OBJECT = "range.txt";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(BUCKET));
        storage.create(BlobInfo.newBuilder(BlobId.of(BUCKET, OBJECT)).build(),
                "0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void mediaDownloadHonorsRangeHeader() throws Exception {
        assertRangeResponse(URI.create(TestFixtures.endpoint()
                + "/storage/v1/b/" + BUCKET + "/o/" + OBJECT + "?alt=media"));
    }

    @Test
    void mediaDownloadReturnsRangeNotSatisfiablePastEnd() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                TestFixtures.authorize(HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                                + "/storage/v1/b/" + BUCKET + "/o/" + OBJECT + "?alt=media"))
                        .header("Range", "bytes=16-20")
                        .GET())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(416);
    }

    @Test
    void downloadEndpointHonorsRangeHeader() throws Exception {
        assertRangeResponse(URI.create(TestFixtures.endpoint()
                + "/download/storage/v1/b/" + BUCKET + "/o/" + OBJECT));
    }

    @Test
    void xmlDownloadHonorsRangeHeader() throws Exception {
        assertRangeResponse(URI.create(TestFixtures.endpoint() + "/" + BUCKET + "/" + OBJECT));
    }

    private static void assertRangeResponse(URI uri) throws Exception {
        HttpResponse<String> response = CLIENT.send(
                TestFixtures.authorize(HttpRequest.newBuilder(uri)
                        .header("Range", "bytes=4-7")
                        .GET())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(206);
        assertThat(response.headers().firstValue("Content-Range"))
                .contains("bytes 4-7/16");
        assertThat(response.body()).isEqualTo("4567");
    }
}

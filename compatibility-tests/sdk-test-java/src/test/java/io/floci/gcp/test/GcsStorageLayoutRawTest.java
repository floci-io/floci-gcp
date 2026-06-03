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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for issue #14: `gcloud storage cp` aborts before uploading
 * because it first calls GET .../b/{bucket}/storageLayout, which was unimplemented
 * and returned 405 (caught by the OPTIONS catch-all) instead of a valid
 * storage#storageLayout resource. The high-level SDK does not expose this
 * endpoint, so it is exercised with a raw HTTP client.
 */
class GcsStorageLayoutRawTest {

    private static final String BUCKET = TestFixtures.uniqueName("layout-bucket");
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
    void storageLayoutReturnsValidResource() throws Exception {
        URI uri = URI.create(TestFixtures.endpoint()
                + "/storage/v1/b/" + BUCKET + "/storageLayout?alt=json");
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"kind\":\"storage#storageLayout\"")
                .contains("\"bucket\":\"" + BUCKET + "\"")
                .contains("\"hierarchicalNamespace\"")
                .contains("\"enabled\":false");
    }

    @Test
    void storageLayoutForMissingBucketReturns404() throws Exception {
        URI uri = URI.create(TestFixtures.endpoint()
                + "/storage/v1/b/" + TestFixtures.uniqueName("no-such") + "/storageLayout?alt=json");
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }
}

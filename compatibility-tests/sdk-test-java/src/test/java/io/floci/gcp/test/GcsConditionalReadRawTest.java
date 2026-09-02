package io.floci.gcp.test;

import com.google.cloud.storage.Blob;
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

/**
 * Conditional reads on {@code objects.get}. The two precondition families fail differently:
 * a {@code *Match} that does not hold is 412, while a {@code *NotMatch} that does not hold is
 * 304 Not Modified, which is the whole point of sending it, the caller already has the body
 * and wants to skip the transfer.
 *
 * <p>Raw HTTP because the assertion is on the status code itself, and the SDK collapses a 304
 * into a null or a cached value rather than surfacing it.
 */
class GcsConditionalReadRawTest {

    private static final String BUCKET = TestFixtures.uniqueName("conditional-read-bucket");
    private static final String OBJECT = "conditional.txt";
    private static final byte[] PAYLOAD = "conditional body".getBytes(StandardCharsets.UTF_8);
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static Storage storage;
    private static long generation;
    private static long metageneration;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(BUCKET));
        Blob blob = storage.create(BlobInfo.newBuilder(BlobId.of(BUCKET, OBJECT)).build(), PAYLOAD);
        generation = blob.getGeneration();
        metageneration = blob.getMetageneration();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void notMatchThatHoldsFalseReturns304() throws Exception {
        assertThat(get("ifGenerationNotMatch=" + generation)).isEqualTo(304);
        assertThat(get("ifMetagenerationNotMatch=" + metageneration)).isEqualTo(304);

        // Same on the media read, which is where skipping the body actually pays.
        assertThat(get("alt=media&ifGenerationNotMatch=" + generation)).isEqualTo(304);
    }

    @Test
    void matchThatDoesNotHoldReturns412() throws Exception {
        assertThat(get("ifGenerationMatch=" + (generation + 1))).isEqualTo(412);
        assertThat(get("ifMetagenerationMatch=" + (metageneration + 1))).isEqualTo(412);
        assertThat(get("alt=media&ifGenerationMatch=" + (generation + 1))).isEqualTo(412);
    }

    @Test
    void preconditionsThatHoldReturnTheObject() throws Exception {
        assertThat(get("ifGenerationMatch=" + generation)).isEqualTo(200);
        assertThat(get("ifMetagenerationMatch=" + metageneration)).isEqualTo(200);
        assertThat(get("ifGenerationNotMatch=" + (generation + 1))).isEqualTo(200);
        assertThat(get("alt=media&ifGenerationMatch=" + generation)).isEqualTo(200);
    }

    @Test
    void the304CarriesTheObjectValidators() throws Exception {
        // RFC 7232: a 304 generates the header fields a 200 for the same request would have
        // sent, ETag among them, so a cache-aware client keeps the validators it needs.
        URI uri = URI.create(TestFixtures.endpoint()
                + "/storage/v1/b/" + BUCKET + "/o/" + OBJECT
                + "?alt=media&ifGenerationNotMatch=" + generation);
        HttpResponse<Void> response = CLIENT.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(304);
        assertThat(response.headers().firstValue("ETag")).isPresent();
        assertThat(response.headers().firstValue("x-goog-generation"))
                .hasValue(String.valueOf(generation));
    }

    private static int get(String query) throws Exception {
        URI uri = URI.create(TestFixtures.endpoint()
                + "/storage/v1/b/" + BUCKET + "/o/" + OBJECT + "?" + query);
        HttpResponse<Void> response = CLIENT.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}

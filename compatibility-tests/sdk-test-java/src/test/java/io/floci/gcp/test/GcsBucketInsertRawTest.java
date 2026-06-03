package io.floci.gcp.test;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for issue #5: bucket creation rejected a request body that
 * did not declare {@code Content-Type: application/json} (returning 415). Real
 * GCS is lenient about the request content-type. The high-level SDK always sends
 * application/json, so this is exercised with a raw HTTP client.
 */
class GcsBucketInsertRawTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void createBucketWithoutJsonContentTypeSucceeds() throws Exception {
        String bucket = TestFixtures.uniqueName("raw-bucket");
        URI uri = URI.create(TestFixtures.endpoint() + "/storage/v1/b?project="
                + TestFixtures.projectId());

        // No Content-Type header at all (mirrors `curl -d` defaults).
        HttpRequest noContentType = HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + bucket + "\"}",
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(noContentType,
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(bucket);
    }

    @Test
    void createBucketWithJsonContentTypeStillSucceeds() throws Exception {
        String bucket = TestFixtures.uniqueName("raw-bucket-json");
        URI uri = URI.create(TestFixtures.endpoint() + "/storage/v1/b?project="
                + TestFixtures.projectId());

        HttpRequest withJson = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + bucket + "\"}",
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(withJson,
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(bucket);
    }
}

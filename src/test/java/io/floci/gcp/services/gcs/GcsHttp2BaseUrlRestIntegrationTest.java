package io.floci.gcp.services.gcs;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * URLs the emulator mints must name the address the caller actually reached, over HTTP/2 as well
 * as HTTP/1.1.
 *
 * <p>HTTP/2 carries the authority in the {@code :authority} pseudo-header and sends no
 * {@code Host} header, so reading {@code Host} yields null on every HTTP/2 request. That used to
 * fall back to {@code floci-gcp.base-url}, handing the client a resumable session
 * {@code Location} on port 4588 regardless of where it had actually connected, and since
 * floci-gcp negotiates HTTP/2 by ALPN on its single port, any client that upgrades hits it.
 * Java's {@code HttpClient} upgrades by default, which is how this surfaced.
 */
@QuarkusTest
class GcsHttp2BaseUrlRestIntegrationTest {

    private static final String BUCKET = "http2-base-url-bucket";

    @TestHTTPResource
    URL testUrl;

    private String origin() {
        return "http://" + testUrl.getHost() + ":" + testUrl.getPort();
    }

    @BeforeEach
    void ensureBucket() throws Exception {
        send(HttpClient.Version.HTTP_1_1,
                HttpRequest.newBuilder(URI.create(origin() + "/storage/v1/b?project=test-project"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + BUCKET + "\"}")));
    }

    @Test
    void resumableSessionLocationNamesTheRequestAuthorityOverHttp2() throws Exception {
        HttpResponse<String> response = startSession(HttpClient.Version.HTTP_2, "over-http2.bin");

        assertEquals(200, response.statusCode());
        assertEquals(HttpClient.Version.HTTP_2, response.version());

        String location = response.headers().firstValue("Location").orElseThrow();
        assertEquals(testUrl.getPort(), URI.create(location).getPort(),
                "session Location must name the port the client connected to, not floci-gcp.base-url");

        // And the session it names has to be the one that was just opened.
        HttpResponse<String> status = send(HttpClient.Version.HTTP_2,
                HttpRequest.newBuilder(URI.create(location))
                        .header("Content-Range", "bytes */*")
                        .PUT(HttpRequest.BodyPublishers.noBody()));
        assertEquals(308, status.statusCode());
    }

    @Test
    void resumableSessionLocationNamesTheRequestAuthorityOverHttp11() throws Exception {
        HttpResponse<String> response = startSession(HttpClient.Version.HTTP_1_1, "over-http11.bin");

        assertEquals(200, response.statusCode());
        String location = response.headers().firstValue("Location").orElseThrow();
        assertEquals(testUrl.getPort(), URI.create(location).getPort());
    }

    @Test
    void objectSelfLinkNamesTheRequestAuthorityOverHttp2() throws Exception {
        HttpResponse<String> uploaded = send(HttpClient.Version.HTTP_2,
                HttpRequest.newBuilder(URI.create(origin() + "/upload/storage/v1/b/" + BUCKET
                                + "/o?uploadType=media&name=selflink.txt"))
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString("bytes")));

        assertEquals(200, uploaded.statusCode());
        String port = ":" + testUrl.getPort();
        assertTrue(uploaded.body().contains("\"selfLink\":\"http://" + testUrl.getHost() + port),
                "selfLink must name the request authority, body was: " + uploaded.body());
        assertTrue(uploaded.body().contains("\"mediaLink\":\"http://" + testUrl.getHost() + port),
                "mediaLink must name the request authority, body was: " + uploaded.body());
    }

    private HttpResponse<String> startSession(HttpClient.Version version, String name) throws Exception {
        return send(version,
                HttpRequest.newBuilder(URI.create(origin() + "/upload/storage/v1/b/" + BUCKET
                                + "/o?uploadType=resumable&name=" + name))
                        .header("X-Upload-Content-Type", "application/octet-stream")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")));
    }

    private static HttpResponse<String> send(HttpClient.Version version, HttpRequest.Builder request)
            throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(version).build()) {
            return client.send(request.version(version).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}

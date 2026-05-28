package io.floci.gcp.test;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Cors;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GcsCorsTest {

    private static final String BUCKET_NAME = TestFixtures.uniqueName("cors-bucket");
    private static final String OBJECT_NAME = "cors/test.txt";
    private static final String ORIGIN = "http://example.com";

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
    }

    @AfterAll
    static void tearDown() throws Exception {
        try {
            storage.delete(BlobId.of(BUCKET_NAME, OBJECT_NAME));
        } catch (Exception ignored) {}
        try {
            storage.get(BUCKET_NAME).delete();
        } catch (Exception ignored) {}
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    @Order(1)
    void createBucketWithCorsConfig() {
        Cors corsRule = Cors.newBuilder()
                .setOrigins(List.of(Cors.Origin.of(ORIGIN)))
                .setMethods(List.of(HttpMethod.GET, HttpMethod.PUT))
                .setResponseHeaders(List.of("Content-Type", "Authorization"))
                .setMaxAgeSeconds(3600)
                .build();

        storage.create(BucketInfo.newBuilder(BUCKET_NAME)
                .setCors(List.of(corsRule))
                .build());

        var bucket = storage.get(BUCKET_NAME);
        assertThat(bucket.getCors()).isNotEmpty();
        assertThat(bucket.getCors().get(0).getOrigins())
                .contains(Cors.Origin.of(ORIGIN));
    }

    @Test
    @Order(2)
    void uploadObjectForCorsTest() {
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME))
                .setContentType("text/plain")
                .build();
        Blob blob = storage.create(info, "cors test content".getBytes(StandardCharsets.UTF_8));
        assertThat(blob).isNotNull();
    }

    @Test
    @Order(3)
    void getObjectResponseIncludesCorsHeader() throws Exception {
        String endpoint = TestFixtures.endpoint();
        URL url = new URL(endpoint + "/storage/v1/b/" + BUCKET_NAME + "/o/" + OBJECT_NAME);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Origin", ORIGIN);
        int status = conn.getResponseCode();
        assertThat(status).isEqualTo(200);
        String corsHeader = conn.getHeaderField("Access-Control-Allow-Origin");
        assertThat(corsHeader).isEqualTo(ORIGIN);
    }

    @Test
    @Order(4)
    void optionsPreflightReturnsCorsHeaders() throws Exception {
        String endpoint = TestFixtures.endpoint();
        URL url = new URL(endpoint + "/storage/v1/b/" + BUCKET_NAME + "/o/" + OBJECT_NAME);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("OPTIONS");
        conn.setRequestProperty("Origin", ORIGIN);
        conn.setRequestProperty("Access-Control-Request-Method", "PUT");
        conn.setRequestProperty("Access-Control-Request-Headers", "Content-Type");
        int status = conn.getResponseCode();
        assertThat(status).isEqualTo(200);
        String allowOrigin = conn.getHeaderField("Access-Control-Allow-Origin");
        assertThat(allowOrigin).isEqualTo(ORIGIN);
        String allowMethods = conn.getHeaderField("Access-Control-Allow-Methods");
        assertThat(allowMethods).isNotNull();
    }

    @Test
    @Order(5)
    void updateCorsConfigOnBucket() {
        Cors newRule = Cors.newBuilder()
                .setOrigins(List.of(Cors.Origin.of("*")))
                .setMethods(List.of(HttpMethod.GET))
                .build();

        var bucket = storage.get(BUCKET_NAME);
        bucket.toBuilder().setCors(List.of(newRule)).build().update();

        var updated = storage.get(BUCKET_NAME);
        assertThat(updated.getCors()).isNotEmpty();
        assertThat(updated.getCors().get(0).getOrigins())
                .contains(Cors.Origin.of("*"));
    }
}

package io.floci.gcp.test;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GcsListStartOffsetRawTest {

    private static final String BUCKET = TestFixtures.uniqueName("start-offset-bucket");
    private static final String PREFIX = "list-order-raw/";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(BUCKET));
        for (String name : new String[] {"C", "A", "B"}) {
            storage.create(BlobInfo.newBuilder(BlobId.of(BUCKET, PREFIX + name)).build(), new byte[0]);
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void listHonorsStartOffset() throws Exception {
        String startOffset = PREFIX + "B";
        URI uri = URI.create(TestFixtures.endpoint()
                + "/storage/v1/b/" + BUCKET + "/o?prefix=" + encode(PREFIX)
                + "&startOffset=" + encode(startOffset));
        HttpResponse<String> response = CLIENT.send(
                TestFixtures.authorize(HttpRequest.newBuilder(uri).GET()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain(PREFIX + "A");
        assertThat(response.body()).contains(PREFIX + "B", PREFIX + "C");
        assertThat(response.body().indexOf(PREFIX + "B"))
                .isLessThan(response.body().indexOf(PREFIX + "C"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

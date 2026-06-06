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
import java.security.MessageDigest;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class GcsCustomerEncryptionRawTest {

    private static final String BUCKET = TestFixtures.uniqueName("encryption-bucket");
    private static final String OBJECT = "encrypted.txt";
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(UTF_8);
    private static final String ENCODED_KEY = Base64.getEncoder().encodeToString(KEY);
    private static final String KEY_SHA256 = keySha256(KEY);
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static Storage storage;

    @BeforeAll
    static void setUp() throws Exception {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(BUCKET));
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(objectUri())
                        .header("x-goog-encryption-algorithm", "AES256")
                        .header("x-goog-encryption-key", ENCODED_KEY)
                        .header("x-goog-encryption-key-sha256", KEY_SHA256)
                        .PUT(HttpRequest.BodyPublishers.ofString("encrypted-data"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void encryptedObjectRequiresCustomerKeyForDownload() throws Exception {
        HttpResponse<String> missingKey = CLIENT.send(
                HttpRequest.newBuilder(objectUri()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(missingKey.statusCode()).isEqualTo(403);

        HttpResponse<String> withKey = CLIENT.send(
                HttpRequest.newBuilder(objectUri())
                        .header("x-goog-encryption-algorithm", "AES256")
                        .header("x-goog-encryption-key", ENCODED_KEY)
                        .header("x-goog-encryption-key-sha256", KEY_SHA256)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(withKey.statusCode()).isEqualTo(200);
        assertThat(withKey.body()).isEqualTo("encrypted-data");
    }

    private static URI objectUri() {
        return URI.create(TestFixtures.endpoint() + "/" + BUCKET + "/" + OBJECT);
    }

    private static String keySha256(byte[] key) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(key));
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

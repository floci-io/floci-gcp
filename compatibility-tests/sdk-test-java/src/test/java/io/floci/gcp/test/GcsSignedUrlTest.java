package io.floci.gcp.test;

import com.google.auth.ServiceAccountSigner;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.SignUrlOption;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GcsSignedUrlTest {

    private static final String BUCKET_NAME = TestFixtures.uniqueName("signed-url-bucket");
    private static final String OBJECT_NAME = "test/signed-object.txt";
    private static final String OBJECT_CONTENT = "content via signed URL";

    private static Storage storage;
    private static ServiceAccountSigner fakeSigner;
    private static String emulatorHost;

    @BeforeAll
    static void setUp() throws Exception {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(BUCKET_NAME));

        URI endpoint = URI.create(TestFixtures.endpoint());
        int port = endpoint.getPort() > 0 ? endpoint.getPort() : 80;
        emulatorHost = endpoint.getHost() + ":" + port;

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        java.security.PrivateKey privateKey = kp.getPrivate();

        fakeSigner = new ServiceAccountSigner() {
            @Override
            public String getAccount() {
                return "test-signer@test-project.iam.gserviceaccount.com";
            }

            @Override
            public byte[] sign(byte[] toSign) {
                try {
                    Signature sig = Signature.getInstance("SHA256withRSA");
                    sig.initSign(privateKey);
                    sig.update(toSign);
                    return sig.sign();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };

        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME))
                .setContentType("text/plain")
                .build();
        storage.create(blobInfo, OBJECT_CONTENT.getBytes(StandardCharsets.UTF_8));
    }

    @AfterAll
    static void tearDown() throws Exception {
        try {
            storage.delete(BlobId.of(BUCKET_NAME, OBJECT_NAME));
            storage.delete(BlobId.of(BUCKET_NAME, "test/signed-upload.txt"));
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
    void signedUrlDownloadReturnsObject() throws Exception {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME)).build();

        URL signed = storage.signUrl(blobInfo, 1, TimeUnit.HOURS,
                SignUrlOption.withV4Signature(),
                SignUrlOption.signWith(fakeSigner),
                SignUrlOption.withHostName(emulatorHost));

        assertThat(signed).isNotNull();
        assertThat(signed.toString()).contains(BUCKET_NAME);

        // SDK defaults to https:// — rewrite to http:// for the plaintext emulator
        URL emulatorUrl = toHttp(signed);

        HttpURLConnection conn = (HttpURLConnection) emulatorUrl.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestMethod("GET");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        try (InputStream is = conn.getInputStream()) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).isEqualTo(OBJECT_CONTENT);
        }
    }

    @Test
    @Order(2)
    void signedUrlUploadStoresObject() throws Exception {
        String uploadObjectName = "test/signed-upload.txt";
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, uploadObjectName))
                .setContentType("text/plain")
                .build();

        URL signed = storage.signUrl(blobInfo, 1, TimeUnit.HOURS,
                SignUrlOption.httpMethod(HttpMethod.PUT),
                SignUrlOption.withV4Signature(),
                SignUrlOption.signWith(fakeSigner),
                SignUrlOption.withHostName(emulatorHost));

        URL emulatorUrl = toHttp(signed);

        byte[] body = "uploaded via signed url".getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) emulatorUrl.openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/plain");
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));
        conn.getOutputStream().write(body);

        assertThat(conn.getResponseCode()).isEqualTo(200);

        com.google.cloud.storage.Blob uploaded = storage.get(BlobId.of(BUCKET_NAME, uploadObjectName));
        assertThat(uploaded).isNotNull();
        String content = new String(uploaded.getContent(), StandardCharsets.UTF_8);
        assertThat(content).isEqualTo("uploaded via signed url");
    }

    private static URL toHttp(URL url) throws Exception {
        String s = url.toString();
        if (s.startsWith("https://")) {
            s = "http://" + s.substring("https://".length());
        }
        return new URL(s);
    }
}

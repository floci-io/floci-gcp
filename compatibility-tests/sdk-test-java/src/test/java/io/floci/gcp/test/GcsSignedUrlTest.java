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
    private static final long MAX_EXPIRES_SECONDS = 604_800;
    private static final String INVALID_EXPIRES = String.valueOf(MAX_EXPIRES_SECONDS + 1);

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

        HttpURLConnection conn = openConnection(signed, "GET");
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

        byte[] body = "uploaded via signed url".getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = openConnection(signed, "PUT");
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

    @Test
    @Order(3)
    void expiredSignedUrlDownloadReturnsExpiredToken() throws Exception {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME)).build();

        URL signed = storage.signUrl(blobInfo, 1, TimeUnit.SECONDS,
                SignUrlOption.withV4Signature(),
                SignUrlOption.signWith(fakeSigner),
                SignUrlOption.withHostName(emulatorHost));

        Thread.sleep(2_000);

        HttpURLConnection conn = openConnection(signed, "GET");

        assertThat(conn.getResponseCode()).isEqualTo(400);
        try (InputStream is = conn.getErrorStream()) {
            String error = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(error).contains("<Code>ExpiredToken</Code>");
            assertThat(error).contains("<Message>The provided token has expired.</Message>");
            assertThat(error).doesNotContain("<Details>");
        }
    }

    @Test
    @Order(4)
    void expiredSignedUrlUploadReturnsExpiredToken() throws Exception {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, "test/expired-upload.txt"))
                .setContentType("text/plain")
                .build();

        URL signed = storage.signUrl(blobInfo, 1, TimeUnit.SECONDS,
                SignUrlOption.httpMethod(HttpMethod.PUT),
                SignUrlOption.withV4Signature(),
                SignUrlOption.signWith(fakeSigner),
                SignUrlOption.withHostName(emulatorHost));

        Thread.sleep(2_000);

        byte[] body = "expired upload".getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = openConnection(signed, "PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/plain");
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));
        conn.getOutputStream().write(body);

        assertThat(conn.getResponseCode()).isEqualTo(400);
        try (InputStream is = conn.getErrorStream()) {
            String error = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(error).contains("<Code>ExpiredToken</Code>");
            assertThat(error).contains("<Message>The provided token has expired.</Message>");
            assertThat(error).doesNotContain("<Details>");
        }
    }

    @Test
    @Order(5)
    void malformedSignedUrlDateReturnsMalformedSecurityHeader() throws Exception {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME)).build();

        URL signed = storage.signUrl(blobInfo, 1, TimeUnit.HOURS,
                SignUrlOption.withV4Signature(),
                SignUrlOption.signWith(fakeSigner),
                SignUrlOption.withHostName(emulatorHost));

        URL malformed = replaceQueryParameterValue(signed, "X-Goog-Date", "not-a-date");
        HttpURLConnection conn = openConnection(malformed, "GET");

        assertThat(conn.getResponseCode()).isEqualTo(400);
        try (InputStream is = conn.getErrorStream()) {
            String error = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(error).contains("<Code>MalformedSecurityHeader</Code>");
            assertThat(error).contains("<Message>Your request has a malformed header.</Message>");
            assertThat(error).contains("<ParameterName>Date</ParameterName>");
        }
    }

    @Test
    @Order(6)
    void invalidSignedUrlExpiresReturnsMalformedSecurityHeader() throws Exception {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME)).build();

        URL signed = storage.signUrl(blobInfo, 1, TimeUnit.HOURS,
                SignUrlOption.withV4Signature(),
                SignUrlOption.signWith(fakeSigner),
                SignUrlOption.withHostName(emulatorHost));

        URL invalid = replaceQueryParameterValue(signed, "X-Goog-Expires", INVALID_EXPIRES);
        HttpURLConnection conn = openConnection(invalid, "GET");

        assertThat(conn.getResponseCode()).isEqualTo(400);
        try (InputStream is = conn.getErrorStream()) {
            String error = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(error).contains("<Code>MalformedSecurityHeader</Code>");
            assertThat(error).contains("<Message>Your request has a malformed header.</Message>");
            assertThat(error).contains("<ParameterName>Expires</ParameterName>");
        }
    }

    @Test
    @Order(7)
    void lowerCaseSignedUrlParametersReturnMalformedSecurityHeader() throws Exception {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME)).build();

        URL signed = storage.signUrl(blobInfo, 1, TimeUnit.HOURS,
                SignUrlOption.withV4Signature(),
                SignUrlOption.signWith(fakeSigner),
                SignUrlOption.withHostName(emulatorHost));

        URL lowerCase = replaceQueryParameterValue(signed, "X-Goog-Expires", INVALID_EXPIRES);
        lowerCase = replaceQueryParameterName(lowerCase, "X-Goog-Date", "x-goog-date");
        lowerCase = replaceQueryParameterName(lowerCase, "X-Goog-Expires", "x-goog-expires");
        HttpURLConnection conn = openConnection(lowerCase, "GET");

        assertThat(conn.getResponseCode()).isEqualTo(400);
        try (InputStream is = conn.getErrorStream()) {
            String error = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(error).contains("<Code>MalformedSecurityHeader</Code>");
            assertThat(error).contains("<Message>Your request has a malformed header.</Message>");
            assertThat(error).contains("<ParameterName>Expires</ParameterName>");
        }
    }

    // SDK defaults to https:// — rewrite to http:// for the plaintext emulator
    private static URL toHttp(URL url) throws Exception {
        String s = url.toString();
        if (s.startsWith("https://")) {
            s = "http://" + s.substring("https://".length());
        }
        return new URL(s);
    }

    private static HttpURLConnection openConnection(URL url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) toHttp(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestMethod(method);
        return conn;
    }

    private static URL replaceQueryParameterValue(URL url, String name, String value) throws Exception {
        return new URL(url.toString().replaceFirst("([?&]" + name + "=)[^&]*", "$1" + value));
    }

    private static URL replaceQueryParameterName(URL url, String name, String replacement) throws Exception {
        return new URL(url.toString().replaceFirst("([?&])" + name + "=", "$1" + replacement + "="));
    }
}

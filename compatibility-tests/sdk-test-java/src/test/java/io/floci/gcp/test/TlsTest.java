package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.ProjectTopicName;
import com.google.pubsub.v1.Topic;
import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.ProjectName;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.cloud.secretmanager.v1.SecretVersion;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that real GCP SDK clients reach floci-gcp over TLS.
 *
 * <p>floci-gcp serves HTTP and HTTPS on the same port by inspecting the first byte of each
 * connection, so these tests use the same host and port as every other suite — only the
 * scheme and the transport credentials differ.
 *
 * <p>Trust is bootstrapped at runtime: the emulator's certificate is fetched from
 * {@code /_floci-gcp/tls-cert} over plain HTTP, so nothing is bundled with the suite and
 * certificates are never verified with checks disabled. The whole class is skipped when the
 * emulator under test is not serving TLS.
 */
class TlsTest {

    private static final String PROJECT_ID = TestFixtures.projectId();

    private static ManagedChannel tlsChannel;
    private static ManagedChannel plaintextChannel;

    @BeforeAll
    static void setUp() {
        assumeTrue(TestFixtures.tlsAvailable(),
                "TLS is not enabled on " + TestFixtures.endpoint()
                        + " — start floci-gcp with FLOCI_GCP_TLS_ENABLED=true to run these tests");
    }

    @AfterAll
    static void tearDown() throws Exception {
        shutdown(tlsChannel);
        shutdown(plaintextChannel);
    }

    private static void shutdown(ManagedChannel channel) throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * The defining property of the design: one port answers both schemes, so enabling TLS
     * never strands an existing plain-HTTP client.
     */
    @Test
    void samePortServesHttpAndHttps() throws Exception {
        HttpRequest.Builder plain = HttpRequest.newBuilder()
                .uri(URI.create(TestFixtures.endpoint() + "/health")).GET();
        HttpResponse<String> overHttp = HttpClient.newHttpClient()
                .send(plain.build(), HttpResponse.BodyHandlers.ofString());

        HttpRequest.Builder secure = HttpRequest.newBuilder()
                .uri(URI.create(TestFixtures.tlsEndpoint() + "/health")).GET();
        HttpResponse<String> overHttps = TestFixtures.tlsHttpClient()
                .send(secure.build(), HttpResponse.BodyHandlers.ofString());

        assertThat(overHttp.statusCode()).isEqualTo(200);
        assertThat(overHttps.statusCode()).isEqualTo(200);
        assertThat(overHttps.body()).contains("version");
    }

    /**
     * The certificate must be usable as a trust anchor — a client that installs it can verify
     * the connection rather than skipping verification — and must name the host the tests use.
     */
    @Test
    void certificateIsATrustAnchorForTheEndpointHost() throws Exception {
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(
                        TestFixtures.caCertPem().getBytes(StandardCharsets.UTF_8)));

        assertThat(cert.getBasicConstraints())
                .as("certificate must be a CA so clients can install it as a trust anchor")
                .isNotEqualTo(-1);

        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        assertThat(sans).as("certificate must carry SANs").isNotNull();
        List<String> names = sans.stream().map(san -> san.get(1).toString()).toList();

        String host = URI.create(TestFixtures.endpoint()).getHost();
        boolean covered = names.stream()
                .anyMatch(san -> san.equals(host) || matchesWildcard(san, host));
        assertThat(covered)
                .as("certificate SANs %s must cover the endpoint host '%s'", names, host)
                .isTrue();
    }

    /** GCS over HTTPS — the REST/JSON path, driven by the real Storage client. */
    @Test
    void gcsOverHttps() throws Exception {
        Storage storage = TestFixtures.tlsStorageClient();
        String bucketName = TestFixtures.uniqueName("tls-bucket");

        storage.create(BucketInfo.of(bucketName));
        try {
            BlobId blobId = BlobId.of(bucketName, "hello.txt");
            storage.create(BlobInfo.newBuilder(blobId).build(), "hello over tls".getBytes(StandardCharsets.UTF_8));

            byte[] content = storage.readAllBytes(blobId);
            assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("hello over tls");

            storage.delete(blobId);
        } finally {
            storage.delete(bucketName);
        }
    }

    /**
     * Pub/Sub over TLS — gRPC and REST share the port, so the proxy must hand a ClientHello
     * to the HTTPS backend and let ALPN negotiate h2 for the gRPC call underneath.
     */
    @Test
    void pubSubOverTls() throws Exception {
        tlsChannel = TestFixtures.tlsGrpcChannel();
        TransportChannelProvider provider = TestFixtures.channelProviderFor(tlsChannel);

        try (TopicAdminClient client = TopicAdminClient.create(
                TopicAdminSettings.newBuilder()
                        .setTransportChannelProvider(provider)
                        .setCredentialsProvider(NoCredentialsProvider.create())
                        .build())) {

            ProjectTopicName topicName =
                    ProjectTopicName.of(PROJECT_ID, TestFixtures.uniqueName("tls-topic"));

            Topic created = client.createTopic(topicName);
            assertThat(created.getName()).isEqualTo(topicName.toString());

            Topic fetched = client.getTopic(topicName);
            assertThat(fetched.getName()).isEqualTo(topicName.toString());

            client.deleteTopic(topicName);
        }
    }

    /** Secret Manager over TLS — a second gRPC service, exercising a payload round-trip. */
    @Test
    void secretManagerOverTls() throws Exception {
        ManagedChannel channel = TestFixtures.tlsGrpcChannel();
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create(
                SecretManagerServiceSettings.newBuilder()
                        .setTransportChannelProvider(TestFixtures.channelProviderFor(channel))
                        .setCredentialsProvider(NoCredentialsProvider.create())
                        .build())) {

            String secretId = TestFixtures.uniqueName("tls-secret");
            Secret secret = client.createSecret(
                    ProjectName.of(PROJECT_ID),
                    secretId,
                    Secret.newBuilder()
                            .setReplication(Replication.newBuilder()
                                    .setAutomatic(Replication.Automatic.getDefaultInstance())
                                    .build())
                            .build());

            SecretVersion version = client.addSecretVersion(
                    secret.getName(),
                    SecretPayload.newBuilder()
                            .setData(ByteString.copyFromUtf8("s3cret over tls"))
                            .build());

            AccessSecretVersionResponse accessed = client.accessSecretVersion(version.getName());
            assertThat(accessed.getPayload().getData().toStringUtf8()).isEqualTo("s3cret over tls");

            client.deleteSecret(secret.getName());
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Plaintext gRPC must keep working while TLS is enabled: an h2c client opens with the
     * HTTP/2 preface, which is not a ClientHello, so the proxy has to route it to the
     * plaintext backend. This is what protects every other suite in this repo.
     */
    @Test
    void plaintextGrpcStillWorksWhileTlsIsEnabled() throws Exception {
        plaintextChannel = ManagedChannelBuilder.forTarget(TestFixtures.grpcTarget())
                .usePlaintext()
                .build();

        try (TopicAdminClient client = TopicAdminClient.create(
                TopicAdminSettings.newBuilder()
                        .setTransportChannelProvider(TestFixtures.channelProviderFor(plaintextChannel))
                        .setCredentialsProvider(NoCredentialsProvider.create())
                        .build())) {

            ProjectTopicName topicName =
                    ProjectTopicName.of(PROJECT_ID, TestFixtures.uniqueName("plaintext-topic"));

            Topic created = client.createTopic(topicName);
            assertThat(created.getName()).isEqualTo(topicName.toString());

            client.deleteTopic(topicName);
        }
    }

    private static boolean matchesWildcard(String san, String host) {
        if (!san.startsWith("*.")) {
            return false;
        }
        int dot = host.indexOf('.');
        return dot > 0 && san.regionMatches(2, host, dot + 1, host.length() - dot - 1);
    }
}

package io.floci.gcp.lifecycle;

import com.google.api.Metric;
import com.google.api.MonitoredResource;
import com.google.monitoring.v3.CreateTimeSeriesRequest;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.ListTimeSeriesResponse;
import com.google.monitoring.v3.MetricServiceGrpc;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TLS listener (test-ssl-port 4599) serves the same router as the plaintext port,
 * secured with the PEM pair from src/test/resources/tls. Requests here perform full
 * certificate-chain and hostname verification against that fixture — the same checks a
 * TLS-requiring client (e.g. a Go SDK) applies.
 */
@QuarkusTest
class TlsIntegrationTest {

    private static final Path CERT = Path.of("src/test/resources/tls/cert.pem");
    private static final String TLS_BASE = "https://localhost:4599";

    @Test
    void certEndpoint_servesConfiguredCertificatePem() throws Exception {
        String pem = given()
                .when().get("/_floci-gcp/tls/cert")
                .then()
                .statusCode(200)
                .contentType("text/plain")
                .extract().asString();

        assertEquals(Files.readString(CERT), pem);
    }

    @Test
    void tlsListener_servesRestOverHttp2() throws Exception {
        HttpResponse<String> response = newClient().send(
                HttpRequest.newBuilder(URI.create(TLS_BASE + "/_floci-gcp/health")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(HttpClient.Version.HTTP_2, response.version());
        assertTrue(response.body().contains("\"services\""));
    }

    @Test
    void tlsListener_servesGcpRestApis() throws Exception {
        HttpResponse<String> response = newClient().send(
                HttpRequest.newBuilder(URI.create(TLS_BASE + "/v1/projects/tls-project/topics/tls-topic"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("projects/tls-project/topics/tls-topic"));
    }

    @Test
    void tlsListener_servesGrpc_monitoringWriteAndReadBack() throws Exception {
        ManagedChannel channel = NettyChannelBuilder.forAddress("localhost", 4599)
                .sslContext(GrpcSslContexts.forClient().trustManager(CERT.toFile()).build())
                .build();
        try {
            MetricServiceGrpc.MetricServiceBlockingStub stub = MetricServiceGrpc.newBlockingStub(channel);

            long nowSeconds = System.currentTimeMillis() / 1000;
            TimeSeries series = TimeSeries.newBuilder()
                    .setMetric(Metric.newBuilder()
                            .setType("compute.googleapis.com/instance/cpu/utilization"))
                    .setResource(MonitoredResource.newBuilder()
                            .setType("gce_instance")
                            .putLabels("project_id", "tls-project")
                            .putLabels("instance_id", "1234567890")
                            .putLabels("zone", "us-central1-a"))
                    .addPoints(Point.newBuilder()
                            .setInterval(TimeInterval.newBuilder()
                                    .setEndTime(Timestamp.newBuilder().setSeconds(nowSeconds)))
                            .setValue(TypedValue.newBuilder().setDoubleValue(0.42)))
                    .build();
            stub.createTimeSeries(CreateTimeSeriesRequest.newBuilder()
                    .setName("projects/tls-project")
                    .addTimeSeries(series)
                    .build());

            ListTimeSeriesResponse read = stub.listTimeSeries(ListTimeSeriesRequest.newBuilder()
                    .setName("projects/tls-project")
                    .setFilter("metric.type = \"compute.googleapis.com/instance/cpu/utilization\"")
                    .setInterval(TimeInterval.newBuilder()
                            .setStartTime(Timestamp.newBuilder().setSeconds(nowSeconds - 3600))
                            .setEndTime(Timestamp.newBuilder().setSeconds(nowSeconds + 60)))
                    .setView(ListTimeSeriesRequest.TimeSeriesView.FULL)
                    .build());

            assertEquals(1, read.getTimeSeriesCount());
            assertEquals(0.42, read.getTimeSeries(0).getPoints(0).getValue().getDoubleValue());
        } finally {
            channel.shutdownNow();
        }
    }

    private static HttpClient newClient() throws Exception {
        return HttpClient.newBuilder()
                .sslContext(trustFixtureCertificate())
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    private static SSLContext trustFixtureCertificate() throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        try (InputStream in = Files.newInputStream(CERT)) {
            trustStore.setCertificateEntry("fixture",
                    CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);
        return sslContext;
    }
}

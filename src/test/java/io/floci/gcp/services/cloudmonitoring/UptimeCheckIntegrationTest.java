package io.floci.gcp.services.cloudmonitoring;

import com.google.monitoring.v3.CreateUptimeCheckConfigRequest;
import com.google.monitoring.v3.GetUptimeCheckConfigRequest;
import com.google.monitoring.v3.UptimeCheckConfig;
import com.google.monitoring.v3.UptimeCheckServiceGrpc;
import com.sun.net.httpserver.HttpServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class UptimeCheckIntegrationTest {
    private static final String PARENT = "projects/test-project";

    @Inject
    UptimeCheckProber prober;
    @TestHTTPResource
    URI endpoint;

    @Test
    void restCreatesACheckThatTheProberRunsIntoUptimeSeries() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        String name = given().contentType("application/json").body(Map.of(
                        "displayName", "Integration home",
                        "monitoredResource", Map.of("type", "uptime_url", "labels", Map.of("host", "127.0.0.1")),
                        "httpCheck", Map.of("path", "/ping", "port", server.getAddress().getPort()),
                        "timeout", "10s"))
                .post("/v3/" + PARENT + "/uptimeCheckConfigs").then().statusCode(200)
                .body("period", equalTo("60s")).body("httpCheck.requestMethod", equalTo("GET"))
                .extract().path("name");
        try {
            given().get("/v3/" + PARENT + "/uptimeCheckConfigs").then().statusCode(200)
                    .body("uptimeCheckConfigs.name", hasItem(name));
            given().get("/v3/uptimeCheckIps").then().statusCode(200).body("uptimeCheckIps", not(empty()));
            given().contentType("application/json").queryParam("updateMask", "displayName")
                    .body(Map.of("displayName", "Integration renamed"))
                    .patch("/v3/" + name).then().statusCode(200).body("displayName", equalTo("Integration renamed"))
                    .body("timeout", equalTo("10s"));

            Instant now = Instant.now();
            prober.tick(now).join();

            String checkId = name.substring(name.lastIndexOf('/') + 1);
            given().queryParam("filter", "metric.type=\"monitoring.googleapis.com/uptime_check/check_passed\" AND "
                            + "metric.labels.check_id=\"" + checkId + "\"")
                    .queryParam("interval.startTime", now.minusSeconds(60).toString())
                    .queryParam("interval.endTime", now.plusSeconds(60).toString())
                    .get("/v3/" + PARENT + "/timeSeries").then().statusCode(200)
                    .body("timeSeries", hasSize(1))
                    .body("timeSeries[0].points[0].value.boolValue", equalTo(true))
                    .body("timeSeries[0].resource.type", equalTo("uptime_url"));

            ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
            try {
                var grpc = UptimeCheckServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
                UptimeCheckConfig read = grpc.getUptimeCheckConfig(GetUptimeCheckConfigRequest.newBuilder().setName(name).build());
                assertEquals("Integration renamed", read.getDisplayName());
                UptimeCheckConfig tcp = grpc.createUptimeCheckConfig(CreateUptimeCheckConfigRequest.newBuilder().setParent(PARENT)
                        .setUptimeCheckConfig(read.toBuilder().clearName().clearHttpCheck().setDisplayName("gRPC tcp")
                                .setTcpCheck(UptimeCheckConfig.TcpCheck.newBuilder().setPort(server.getAddress().getPort())))
                        .build());
                given().delete("/v3/" + tcp.getName()).then().statusCode(200);
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        } finally {
            given().delete("/v3/" + name).then().statusCode(200);
            server.stop(0);
        }
        given().get("/v3/" + name).then().statusCode(404).body("error.status", equalTo("NOT_FOUND"));
    }
}

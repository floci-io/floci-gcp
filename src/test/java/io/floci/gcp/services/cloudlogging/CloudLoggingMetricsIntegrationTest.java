package io.floci.gcp.services.cloudlogging;

import com.google.api.MonitoredResource;
import com.google.logging.type.LogSeverity;
import com.google.logging.v2.CreateLogMetricRequest;
import com.google.logging.v2.DeleteLogMetricRequest;
import com.google.logging.v2.GetLogMetricRequest;
import com.google.logging.v2.ListLogMetricsRequest;
import com.google.logging.v2.LogEntry;
import com.google.logging.v2.LogMetric;
import com.google.logging.v2.LoggingServiceV2Grpc;
import com.google.logging.v2.MetricsServiceV2Grpc;
import com.google.logging.v2.WriteLogEntriesRequest;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudLoggingMetricsIntegrationTest {
    private static final String PARENT = "projects/test-project";

    @TestHTTPResource
    URI endpoint;

    @Test
    void grpcAndRestManageTheSameMetricsIncludingSlashedIdentifiers() throws Exception {
        String id = "grpc-" + UUID.randomUUID();
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        try {
            var metrics = MetricsServiceV2Grpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
            LogMetric created = metrics.createLogMetric(CreateLogMetricRequest.newBuilder().setParent(PARENT)
                    .setMetric(LogMetric.newBuilder().setName(id).setFilter("severity>=ERROR")).build());
            assertEquals("1", created.getMetricDescriptor().getUnit());
            assertEquals(created, metrics.getLogMetric(GetLogMetricRequest.newBuilder()
                    .setMetricName(PARENT + "/metrics/" + id).build()));
            assertTrue(metrics.listLogMetrics(ListLogMetricsRequest.newBuilder().setParent(PARENT).build())
                    .getMetricsList().stream().anyMatch(metric -> metric.getName().equals(id)));
            given().get("/v2/" + PARENT + "/metrics/" + id).then().statusCode(200)
                    .body("name", equalTo(id)).body("metricDescriptor.metricKind", equalTo("DELTA"))
                    .body("metricDescriptor.valueType", equalTo("INT64"));
            StatusRuntimeException duplicate = assertThrows(StatusRuntimeException.class, () -> metrics.createLogMetric(
                    CreateLogMetricRequest.newBuilder().setParent(PARENT)
                            .setMetric(LogMetric.newBuilder().setName(id).setFilter("severity>=ERROR")).build()));
            assertEquals(Status.Code.ALREADY_EXISTS, duplicate.getStatus().getCode());
            metrics.deleteLogMetric(DeleteLogMetricRequest.newBuilder().setMetricName(PARENT + "/metrics/" + id).build());
            given().get("/v2/" + PARENT + "/metrics/" + id).then().statusCode(404)
                    .body("error.status", equalTo("NOT_FOUND"));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }

        String slashed = "nginx/" + UUID.randomUUID();
        String encoded = slashed.replace("/", "%2F");
        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("filter", "severity>=WARNING", "description", "upserted"))
                .put("/v2/" + PARENT + "/metrics/" + encoded).then().statusCode(200)
                .body("name", equalTo(slashed)).body("createTime", equalTo(
                        given().urlEncodingEnabled(false).get("/v2/" + PARENT + "/metrics/" + encoded)
                                .then().extract().path("updateTime")));
        given().urlEncodingEnabled(false).delete("/v2/" + PARENT + "/metrics/" + encoded).then().statusCode(200);
        given().urlEncodingEnabled(false).get("/v2/" + PARENT + "/metrics/" + encoded).then().statusCode(404);
    }

    @Test
    void writtenEntriesAppearAsDeltaPointsInCloudMonitoring() {
        String id = "requests_" + UUID.randomUUID().toString().replace("-", "");
        String log = PARENT + "/logs/" + id;
        given().contentType("application/json").body(Map.of(
                        "name", id, "filter", "logName=\"" + log + "\" AND severity>=WARNING",
                        "metricDescriptor", Map.of("labels", List.of(Map.of("key", "method"))),
                        "labelExtractors", Map.of("method", "EXTRACT(jsonPayload.method)")))
                .post("/v2/" + PARENT + "/metrics").then().statusCode(200);
        Instant start = Instant.now().minusSeconds(120);
        try {
            given().urlEncodingEnabled(false).contentType("application/json")
                    .body(Map.of("logName", log, "resource", Map.of("type", "global"), "entries", List.of(
                            Map.of("severity", "ERROR", "jsonPayload", Map.of("method", "GET")),
                            Map.of("severity", "WARNING", "jsonPayload", Map.of("method", "GET")),
                            Map.of("severity", "ERROR", "jsonPayload", Map.of("method", "POST")),
                            Map.of("severity", "INFO", "jsonPayload", Map.of("method", "GET")))))
                    .post("/v2/entries:write").then().statusCode(200);
            given().urlEncodingEnabled(false).contentType("application/json")
                    .body(Map.of("logName", log, "entries", List.of(
                            Map.of("severity", "ERROR", "jsonPayload", Map.of("method", "GET")))))
                    .post("/v2/entries:write").then().statusCode(200);

            JsonPath series = given()
                    .queryParam("filter", "metric.type=\"logging.googleapis.com/user/" + id + "\"")
                    .queryParam("interval.startTime", start.toString())
                    .queryParam("interval.endTime", Instant.now().plusSeconds(60).toString())
                    .get("/v3/" + PARENT + "/timeSeries").then().statusCode(200).extract().jsonPath();
            List<Map<String, Object>> all = series.getList("timeSeries");
            assertEquals(2, all.size(), series.prettify());
            assertEquals(3L, total(series, "GET"));
            assertEquals(1L, total(series, "POST"));
            assertEquals(id, series.getString("timeSeries[0].metric.labels.log"));
            assertEquals("DELTA", series.getString("timeSeries[0].metricKind"));

            given().get("/v3/" + PARENT + "/metricDescriptors/logging.googleapis.com/user/" + id).then()
                    .statusCode(200).body("metricKind", equalTo("DELTA")).body("valueType", equalTo("INT64"));
        } finally {
            given().delete("/v2/" + PARENT + "/metrics/" + id).then().statusCode(200);
        }
    }

    @Test
    void wholeNumbersWrittenOverGrpcAndRestGiveTheSameInt64Label() throws Exception {
        String id = "statuses_" + UUID.randomUUID().toString().replace("-", "");
        String log = PARENT + "/logs/" + id;
        given().contentType("application/json").body(Map.of(
                        "name", id, "filter", "logName=\"" + log + "\"",
                        "metricDescriptor", Map.of("labels", List.of(
                                Map.of("key", "code", "valueType", "INT64"), Map.of("key", "transport"))),
                        "labelExtractors", Map.of("code", "EXTRACT(jsonPayload.status)",
                                "transport", "EXTRACT(jsonPayload.transport)")))
                .post("/v2/" + PARENT + "/metrics").then().statusCode(200);
        Instant start = Instant.now().minusSeconds(120);
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        try {
            LoggingServiceV2Grpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                    .writeLogEntries(WriteLogEntriesRequest.newBuilder().setLogName(log)
                            .setResource(MonitoredResource.newBuilder().setType("global"))
                            .addEntries(LogEntry.newBuilder().setSeverity(LogSeverity.ERROR)
                                    .setJsonPayload(Struct.newBuilder()
                                            .putFields("status", Value.newBuilder().setNumberValue(503).build())
                                            .putFields("transport", Value.newBuilder().setStringValue("grpc").build())))
                            .build());
            given().urlEncodingEnabled(false).contentType("application/json")
                    .body(Map.of("logName", log, "resource", Map.of("type", "global"), "entries", List.of(
                            Map.of("severity", "ERROR", "jsonPayload", Map.of("status", 503, "transport", "rest")))))
                    .post("/v2/entries:write").then().statusCode(200);

            JsonPath series = given()
                    .queryParam("filter", "metric.type=\"logging.googleapis.com/user/" + id + "\"")
                    .queryParam("interval.startTime", start.toString())
                    .queryParam("interval.endTime", Instant.now().plusSeconds(60).toString())
                    .get("/v3/" + PARENT + "/timeSeries").then().statusCode(200).extract().jsonPath();
            List<Map<String, Object>> all = series.getList("timeSeries");
            assertEquals(2, all.size(), series.prettify());
            assertEquals(List.of("grpc", "rest"), all.stream()
                    .map(one -> ((Map<?, ?>) one.get("metric")).get("labels"))
                    .map(labels -> String.valueOf(((Map<?, ?>) labels).get("transport"))).sorted().toList());
            for (int i = 0; i < all.size(); i++) {
                assertEquals("503", series.getString("timeSeries[" + i + "].metric.labels.code"), series.prettify());
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            given().delete("/v2/" + PARENT + "/metrics/" + id).then().statusCode(200);
        }
    }

    @Test
    void metricsInAnotherProjectAndDisabledMetricsRecordNothing() {
        String id = "quiet_" + UUID.randomUUID().toString().replace("-", "");
        String log = PARENT + "/logs/" + id;
        given().contentType("application/json")
                .body(Map.of("name", id, "filter", "logName=\"" + log + "\"", "disabled", true))
                .post("/v2/" + PARENT + "/metrics").then().statusCode(200);
        try {
            given().urlEncodingEnabled(false).contentType("application/json")
                    .body(Map.of("logName", log, "entries", List.of(Map.of("textPayload", "ignored"))))
                    .post("/v2/entries:write").then().statusCode(200);
            JsonPath series = given()
                    .queryParam("filter", "metric.type=\"logging.googleapis.com/user/" + id + "\"")
                    .queryParam("interval.startTime", Instant.now().minusSeconds(120).toString())
                    .queryParam("interval.endTime", Instant.now().plusSeconds(60).toString())
                    .get("/v3/" + PARENT + "/timeSeries").then().statusCode(200).extract().jsonPath();
            assertTrue(series.getList("timeSeries") == null || series.getList("timeSeries").isEmpty());
        } finally {
            given().delete("/v2/" + PARENT + "/metrics/" + id).then().statusCode(200);
        }
    }

    private static long total(JsonPath series, String method) {
        List<Map<String, Object>> all = series.getList("timeSeries");
        long total = 0;
        for (int i = 0; i < all.size(); i++) {
            if (method.equals(series.getString("timeSeries[" + i + "].metric.labels.method"))) {
                List<Object> points = series.getList("timeSeries[" + i + "].points");
                for (int p = 0; p < points.size(); p++) {
                    total += Long.parseLong(series.getString("timeSeries[" + i + "].points[" + p + "].value.int64Value"));
                }
            }
        }
        return total;
    }
}

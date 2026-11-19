package io.floci.gcp.services.cloudlogging;

import com.google.api.Distribution;
import com.google.api.LabelDescriptor;
import com.google.api.MetricDescriptor;
import com.google.logging.v2.LogMetric;
import com.google.monitoring.v3.TimeSeries;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import io.floci.gcp.services.cloudmonitoring.CloudMonitoringService;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.DoubleStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CloudLoggingMetricServiceTest {
    private static final String PARENT = "projects/test-project";
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    private CloudMonitoringService monitoring;
    private CloudLoggingMetricService service;

    @BeforeEach
    void setUp() {
        monitoring = mock(CloudMonitoringService.class);
        service = new CloudLoggingMetricService(new InMemoryStorage<>(), monitoring, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createFillsTheDefaultCounterDescriptorAndPublishesItWithTheLogLabel() {
        LogMetric created = service.createLogMetric(PARENT, LogMetric.newBuilder().setName("errors")
                .setDescription("Errors").setFilter("severity>=ERROR").build());

        MetricDescriptor descriptor = created.getMetricDescriptor();
        assertEquals(MetricDescriptor.MetricKind.DELTA, descriptor.getMetricKind());
        assertEquals(MetricDescriptor.ValueType.INT64, descriptor.getValueType());
        assertEquals("1", descriptor.getUnit());
        assertEquals("logging.googleapis.com/user/errors", descriptor.getType());
        assertEquals(PARENT + "/metricDescriptors/logging.googleapis.com/user/errors", descriptor.getName());
        assertEquals("Errors", descriptor.getDescription());
        assertEquals(0, descriptor.getLabelsCount());
        assertTrue(created.hasCreateTime());
        assertEquals(created.getCreateTime(), created.getUpdateTime());

        ArgumentCaptor<MetricDescriptor> published = ArgumentCaptor.forClass(MetricDescriptor.class);
        verify(monitoring).createMetricDescriptor(eq(PARENT), published.capture());
        assertEquals("log", published.getValue().getLabels(0).getKey());
    }

    @Test
    void createRejectsDuplicatesBadIdentifiersAndUnreadableConfiguration() {
        LogMetric metric = LogMetric.newBuilder().setName("errors").setFilter("severity>=ERROR").build();
        service.createLogMetric(PARENT, metric);
        assertEquals(Status.Code.ALREADY_EXISTS, code(() -> service.createLogMetric(PARENT, metric)));
        assertEquals(Status.Code.INVALID_ARGUMENT,
                code(() -> service.createLogMetric(PARENT, metric.toBuilder().setName("/leading").build())));
        assertEquals(Status.Code.INVALID_ARGUMENT,
                code(() -> service.createLogMetric(PARENT, metric.toBuilder().setName("x".repeat(101)).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT,
                code(() -> service.createLogMetric(PARENT, metric.toBuilder().setName("nofilter").clearFilter().build())));
        assertEquals(Status.Code.INVALID_ARGUMENT,
                code(() -> service.createLogMetric(PARENT, metric.toBuilder().setName("regex")
                        .setFilter("textPayload=~\"a.*\"").build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> service.createLogMetric(PARENT, metric.toBuilder()
                .setName("unlabelled").setMetricDescriptor(MetricDescriptor.newBuilder()
                        .addLabels(LabelDescriptor.newBuilder().setKey("method"))).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> service.createLogMetric(PARENT, metric.toBuilder()
                .setName("histogram").setMetricDescriptor(MetricDescriptor.newBuilder()
                        .setValueType(MetricDescriptor.ValueType.DISTRIBUTION)).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> service.createLogMetric(PARENT, metric.toBuilder()
                .setName("gauge").setMetricDescriptor(MetricDescriptor.newBuilder()
                        .setMetricKind(MetricDescriptor.MetricKind.GAUGE)).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> service.createLogMetric(PARENT, metric.toBuilder()
                .setName("two-groups").setMetricDescriptor(MetricDescriptor.newBuilder()
                        .addLabels(LabelDescriptor.newBuilder().setKey("method")))
                .putLabelExtractors("method", "REGEXP_EXTRACT(textPayload, \"(a)(b)\")").build())));
    }

    @Test
    void createRejectsALabelThatWouldReplaceTheDefaultLogLabel() {
        LogMetric metric = LogMetric.newBuilder().setName("shadow").setFilter("severity>=ERROR")
                .setMetricDescriptor(MetricDescriptor.newBuilder()
                        .addLabels(LabelDescriptor.newBuilder().setKey("log")))
                .putLabelExtractors("log", "EXTRACT(jsonPayload.log)").build();

        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> service.createLogMetric(PARENT, metric)));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> service.createLogMetric(PARENT, metric.toBuilder()
                .clearMetricDescriptor().build())));
    }

    @Test
    void createCapsADistributionAtTwoHundredBucketsBeforeAnyEntryIsRecorded() {
        LogMetric metric = LogMetric.newBuilder().setName("latency").setFilter("severity>=ERROR")
                .setMetricDescriptor(MetricDescriptor.newBuilder().setValueType(MetricDescriptor.ValueType.DISTRIBUTION))
                .setValueExtractor("EXTRACT(jsonPayload.latency)").build();

        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> service.createLogMetric(PARENT, metric.toBuilder()
                .setBucketOptions(Distribution.BucketOptions.newBuilder().setLinearBuckets(
                        Distribution.BucketOptions.Linear.newBuilder().setNumFiniteBuckets(1_000_000_000).setWidth(1)))
                .build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> service.createLogMetric(PARENT, metric.toBuilder()
                .setBucketOptions(Distribution.BucketOptions.newBuilder().setExponentialBuckets(
                        Distribution.BucketOptions.Exponential.newBuilder().setNumFiniteBuckets(Integer.MAX_VALUE)
                                .setGrowthFactor(2).setScale(1)))
                .build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> service.createLogMetric(PARENT, metric.toBuilder()
                .setBucketOptions(Distribution.BucketOptions.newBuilder().setExplicitBuckets(
                        Distribution.BucketOptions.Explicit.newBuilder().addAllBounds(
                                DoubleStream.iterate(0, d -> d + 1).limit(200).boxed().toList())))
                .build())));

        service.createLogMetric(PARENT, metric.toBuilder()
                .setBucketOptions(Distribution.BucketOptions.newBuilder().setLinearBuckets(
                        Distribution.BucketOptions.Linear.newBuilder().setNumFiniteBuckets(198).setWidth(1)))
                .build());
    }

    @Test
    void updateIsAnUpsertThatKeepsCreateTimeAndFreezesKindValueTypeAndLabels() {
        String name = PARENT + "/metrics/nginx/requests";
        LogMetric created = service.updateLogMetric(name, LogMetric.newBuilder().setFilter("severity>=INFO")
                .setMetricDescriptor(MetricDescriptor.newBuilder()
                        .addLabels(LabelDescriptor.newBuilder().setKey("method")))
                .putLabelExtractors("method", "EXTRACT(jsonPayload.method)").build());
        assertEquals("nginx/requests", created.getName());

        LogMetric updated = service.updateLogMetric(name, created.toBuilder().setFilter("severity>=WARNING")
                .setMetricDescriptor(created.getMetricDescriptor().toBuilder()
                        .setLabels(0, created.getMetricDescriptor().getLabels(0).toBuilder().setDescription("HTTP method"))
                        .addLabels(LabelDescriptor.newBuilder().setKey("status")
                                .setValueType(LabelDescriptor.ValueType.INT64)))
                .putLabelExtractors("status", "EXTRACT(jsonPayload.status)").build());
        assertEquals(created.getCreateTime(), updated.getCreateTime());
        assertEquals("severity>=WARNING", service.getLogMetric(name).getFilter());

        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> service.updateLogMetric(name, updated.toBuilder()
                .setMetricDescriptor(updated.getMetricDescriptor().toBuilder()
                        .setValueType(MetricDescriptor.ValueType.DISTRIBUTION))
                .setValueExtractor("EXTRACT(jsonPayload.latency)")
                .setBucketOptions(linear()).build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> service.updateLogMetric(name, updated.toBuilder()
                .setMetricDescriptor(updated.getMetricDescriptor().toBuilder().removeLabels(0))
                .removeLabelExtractors("method").build())));
        assertEquals(Status.Code.INVALID_ARGUMENT, code(() -> service.updateLogMetric(name,
                updated.toBuilder().setName("other").build())));
    }

    @Test
    void getListAndDeleteFollowTheStoredMetrics() {
        for (String id : List.of("c", "a", "b")) {
            service.createLogMetric(PARENT, LogMetric.newBuilder().setName(id).setFilter("severity>=ERROR").build());
        }
        var first = service.listLogMetrics(PARENT, 2, null);
        assertEquals(List.of("a", "b"), first.getMetricsList().stream().map(LogMetric::getName).toList());
        assertEquals(List.of("c"), service.listLogMetrics(PARENT, 2, first.getNextPageToken())
                .getMetricsList().stream().map(LogMetric::getName).toList());
        assertTrue(service.listLogMetrics("projects/other", 0, null).getMetricsList().isEmpty());

        service.deleteLogMetric(PARENT + "/metrics/a");
        assertEquals(Status.Code.NOT_FOUND, code(() -> service.getLogMetric(PARENT + "/metrics/a")));
        assertEquals(Status.Code.NOT_FOUND, code(() -> service.deleteLogMetric(PARENT + "/metrics/a")));
    }

    @Test
    void matchingEntriesBecomeOneDeltaPointPerSeriesWithExtractedLabels() {
        service.createLogMetric(PARENT, LogMetric.newBuilder().setName("requests").setFilter("severity>=WARNING")
                .setMetricDescriptor(MetricDescriptor.newBuilder()
                        .addLabels(LabelDescriptor.newBuilder().setKey("method"))
                        .addLabels(LabelDescriptor.newBuilder().setKey("ok").setValueType(LabelDescriptor.ValueType.BOOL))
                        .addLabels(LabelDescriptor.newBuilder().setKey("code").setValueType(LabelDescriptor.ValueType.INT64)))
                .putLabelExtractors("method", "EXTRACT(jsonPayload.method)")
                .putLabelExtractors("ok", "EXTRACT(jsonPayload.ok)")
                .putLabelExtractors("code", "REGEXP_EXTRACT(textPayload, \"code=(\\\\d+)\")").build());
        service.createLogMetric(PARENT, LogMetric.newBuilder().setName("off").setFilter("severity>=DEFAULT")
                .setDisabled(true).build());

        service.record(List.of(
                entry("app", "ERROR", Map.of("method", "GET", "ok", true), null),
                entry("app", "WARNING", Map.of("method", "GET", "ok", true), null),
                entry("app", "ERROR", Map.of("method", "POST"), null),
                entry("app", "INFO", Map.of("method", "GET"), null),
                entry("other%2Flog", "ERROR", null, "code=503"),
                foreign()));

        List<TimeSeries> written = written();
        assertEquals(3, written.size());
        TimeSeries get = series(written, Map.of("log", "app", "method", "GET", "ok", "true", "code", "0"));
        assertEquals(2, get.getPoints(0).getValue().getInt64Value());
        assertEquals("logging.googleapis.com/user/requests", get.getMetric().getType());
        assertEquals(MetricDescriptor.MetricKind.DELTA, get.getMetricKind());
        assertEquals("global", get.getResource().getType());
        assertEquals(NOW.getEpochSecond(), get.getPoints(0).getInterval().getEndTime().getSeconds());
        assertEquals(NOW.minusSeconds(60).getEpochSecond(), get.getPoints(0).getInterval().getStartTime().getSeconds());
        assertEquals(1, series(written, Map.of("log", "app", "method", "POST", "ok", "false", "code", "0"))
                .getPoints(0).getValue().getInt64Value());
        assertEquals(1, series(written, Map.of("log", "other%2Flog", "method", "", "ok", "false", "code", "503"))
                .getPoints(0).getValue().getInt64Value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void wholeNumbersFromAGrpcJsonPayloadBecomeIntegerLabels() {
        service.createLogMetric(PARENT, LogMetric.newBuilder().setName("statuses").setFilter("severity>=DEFAULT")
                .setMetricDescriptor(MetricDescriptor.newBuilder()
                        .addLabels(LabelDescriptor.newBuilder().setKey("code").setValueType(LabelDescriptor.ValueType.INT64))
                        .addLabels(LabelDescriptor.newBuilder().setKey("text"))
                        .addLabels(LabelDescriptor.newBuilder().setKey("ratio"))
                        .addLabels(LabelDescriptor.newBuilder().setKey("nested"))
                        .addLabels(LabelDescriptor.newBuilder().setKey("big").setValueType(LabelDescriptor.ValueType.INT64)))
                .putLabelExtractors("code", "EXTRACT(jsonPayload.status)")
                .putLabelExtractors("text", "EXTRACT(jsonPayload.status)")
                .putLabelExtractors("ratio", "EXTRACT(jsonPayload.ratio)")
                .putLabelExtractors("nested", "EXTRACT(jsonPayload.http)")
                .putLabelExtractors("big", "EXTRACT(jsonPayload.bytes)").build());
        Map<String, Object> grpcPayload = (Map<String, Object>) Structs.toJava(Struct.newBuilder()
                .putFields("status", Value.newBuilder().setNumberValue(503).build())
                .putFields("ratio", Value.newBuilder().setNumberValue(0.5).build())
                .putFields("bytes", Value.newBuilder().setNumberValue(1e16).build())
                .putFields("http", Value.newBuilder().setStructValue(Struct.newBuilder()
                        .putFields("code", Value.newBuilder().setNumberValue(503).build())
                        .putFields("sizes", Value.newBuilder().setListValue(ListValue.newBuilder()
                                .addValues(Value.newBuilder().setNumberValue(2))
                                .addValues(Value.newBuilder().setNumberValue(2.5)))
                                .build())).build()).build());
        assertEquals(503.0, grpcPayload.get("status"));

        service.record(List.of(entry("app", "ERROR", grpcPayload, null)));

        series(written(), Map.of("log", "app", "code", "503", "text", "503", "ratio", "0.5",
                "nested", "{\"code\":503,\"sizes\":[2,2.5]}", "big", "10000000000000000"));
    }

    @Test
    void aRegexThatRecursesTooDeeplyExtractsNothingInsteadOfFailingTheWrite() {
        service.createLogMetric(PARENT, LogMetric.newBuilder().setName("letters").setFilter("severity>=DEFAULT")
                .setMetricDescriptor(MetricDescriptor.newBuilder()
                        .addLabels(LabelDescriptor.newBuilder().setKey("last")))
                .putLabelExtractors("last", "REGEXP_EXTRACT(textPayload, \"(a|b)*\")").build());

        assertDoesNotThrow(() -> service.record(List.of(
                entry("app", "INFO", null, "ab".repeat(100_000)),
                entry("app", "INFO", null, "ab"))));

        List<TimeSeries> written = written();
        assertEquals(1, series(written, Map.of("log", "app", "last", "")).getPoints(0).getValue().getInt64Value());
        assertEquals(1, series(written, Map.of("log", "app", "last", "b")).getPoints(0).getValue().getInt64Value());
    }

    @Test
    void aBacktrackingRegexExtractsNothingInsteadOfStallingTheWrite() {
        service.createLogMetric(PARENT, LogMetric.newBuilder().setName("words").setFilter("severity>=DEFAULT")
                .setMetricDescriptor(MetricDescriptor.newBuilder()
                        .addLabels(LabelDescriptor.newBuilder().setKey("word")))
                .putLabelExtractors("word", "REGEXP_EXTRACT(textPayload, \"(a+){10}$\")").build());

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> service.record(List.of(
                entry("app", "INFO", null, "a".repeat(64) + "!"),
                entry("app", "INFO", null, "a".repeat(12)))));

        List<TimeSeries> written = written();
        assertEquals(1, series(written, Map.of("log", "app", "word", "")).getPoints(0).getValue().getInt64Value());
        assertEquals(1, series(written, Map.of("log", "app", "word", "a")).getPoints(0).getValue().getInt64Value());
    }

    @Test
    void laterPointsOfASeriesStartWhereThePreviousEndedAndNeverOverlap() {
        service.createLogMetric(PARENT, LogMetric.newBuilder().setName("errors").setFilter("severity>=ERROR").build());

        service.record(List.of(entry("app", "ERROR", null, "first")));
        service.record(List.of(entry("app", "ERROR", null, "second")));

        List<TimeSeries> written = written();
        assertEquals(2, written.size());
        var first = written.get(0).getPoints(0).getInterval();
        var second = written.get(1).getPoints(0).getInterval();
        assertEquals(first.getEndTime(), second.getStartTime());
        assertTrue(second.getEndTime().getSeconds() * 1_000_000_000L + second.getEndTime().getNanos()
                > first.getEndTime().getSeconds() * 1_000_000_000L + first.getEndTime().getNanos());
    }

    @Test
    void distributionMetricsBucketExtractedValuesAndSkipUnparsableOnes() {
        service.createLogMetric(PARENT, LogMetric.newBuilder().setName("latency").setFilter("severity>=DEFAULT")
                .setMetricDescriptor(MetricDescriptor.newBuilder().setValueType(MetricDescriptor.ValueType.DISTRIBUTION))
                .setValueExtractor("EXTRACT(jsonPayload.latency)").setBucketOptions(linear()).build());

        service.record(List.of(
                entry("app", "INFO", Map.of("latency", 5), null),
                entry("app", "INFO", Map.of("latency", 15), null),
                entry("app", "INFO", Map.of("latency", "15"), null),
                entry("app", "INFO", Map.of("latency", 999), null),
                entry("app", "INFO", Map.of("latency", "slow"), null),
                entry("app", "INFO", Map.of(), null)));

        Distribution distribution = written().getFirst().getPoints(0).getValue().getDistributionValue();
        assertEquals(4, distribution.getCount());
        assertEquals((5 + 15 + 15 + 999) / 4.0, distribution.getMean(), 1e-9);
        assertEquals(List.of(0L, 1L, 2L, 0L, 1L), distribution.getBucketCountsList());
        assertEquals(linear(), distribution.getBucketOptions());
    }

    @Test
    void monitoringFailuresNeverEscapeTheLoggingWrite() {
        service.createLogMetric(PARENT, LogMetric.newBuilder().setName("errors").setFilter("severity>=ERROR").build());
        doThrow(GcpException.invalidArgument("rejected")).when(monitoring).createTimeSeries(anyString(), anyList());

        assertDoesNotThrow(() -> service.record(List.of(entry("app", "ERROR", null, "x"))));
    }

    private List<TimeSeries> written() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimeSeries>> captor = ArgumentCaptor.forClass(List.class);
        verify(monitoring, atLeastOnce()).createTimeSeries(eq(PARENT), captor.capture());
        List<TimeSeries> all = new ArrayList<>();
        captor.getAllValues().forEach(all::addAll);
        return all;
    }

    private static TimeSeries series(List<TimeSeries> written, Map<String, String> labels) {
        return written.stream().filter(ts -> ts.getMetric().getLabelsMap().equals(labels)).findFirst()
                .orElseThrow(() -> new AssertionError("no series with " + labels + " in " + written));
    }

    private static Distribution.BucketOptions linear() {
        return Distribution.BucketOptions.newBuilder().setLinearBuckets(Distribution.BucketOptions.Linear.newBuilder()
                .setNumFiniteBuckets(3).setWidth(10).setOffset(0)).build();
    }

    private static StoredLogEntry entry(String log, String severity, Map<String, Object> json, String text) {
        StoredLogEntry entry = new StoredLogEntry();
        entry.setLogName(PARENT + "/logs/" + log);
        entry.setSeverity(severity);
        entry.setResourceType("global");
        entry.setJsonPayload(json);
        entry.setTextPayload(text);
        entry.setTimestamp(NOW.toString());
        entry.setReceiveTimestamp(NOW.toString());
        return entry;
    }

    private static StoredLogEntry foreign() {
        StoredLogEntry entry = entry("app", "ERROR", Map.of("method", "GET"), null);
        entry.setLogName("projects/other-project/logs/app");
        return entry;
    }

    private static Status.Code code(Runnable call) {
        GcpException error = assertThrows(GcpException.class, call::run);
        return error.getGrpcCode();
    }
}

package io.floci.gcp.services.cloudmonitoring;

import com.google.api.LabelDescriptor;
import com.google.api.Metric;
import com.google.api.MetricDescriptor;
import com.google.api.MonitoredResource;
import com.google.api.MonitoredResourceDescriptor;
import com.google.monitoring.v3.Aggregation;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudmonitoring.model.StoredTimeSeriesPoint;
import io.floci.gcp.core.storage.StorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CloudMonitoringServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-07T12:00:00Z");
    private static final String PROJECT = "projects/p1";
    private static final String METRIC = "custom.googleapis.com/test/metric";

    private CloudMonitoringService service;
    private StorageBackend<String, StoredTimeSeriesPoint> timeSeriesStore;

    @BeforeEach
    void setUp() {
        timeSeriesStore = new InMemoryStorage<>();
        service = new CloudMonitoringService(new InMemoryStorage<>(), timeSeriesStore,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── Metric descriptors ───────────────────────────────────────────────────

    @Test
    void createDescriptorRequiresDomainPrefixedType() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.createMetricDescriptor(PROJECT, descriptor("no-domain-metric").build()));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());

        MetricDescriptor created = service.createMetricDescriptor(PROJECT, descriptor(METRIC).build());
        assertEquals(PROJECT + "/metricDescriptors/" + METRIC, created.getName());
    }

    @Test
    void boolValueTypeWithNonGaugeKindRejected() {
        MetricDescriptor descriptor = descriptor(METRIC)
                .setMetricKind(MetricDescriptor.MetricKind.CUMULATIVE)
                .setValueType(MetricDescriptor.ValueType.BOOL)
                .build();
        GcpException ex = assertThrows(GcpException.class,
                () -> service.createMetricDescriptor(PROJECT, descriptor));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void createDescriptorUpsertsAndUnionsLabels() {
        service.createMetricDescriptor(PROJECT, descriptor(METRIC)
                .addLabels(LabelDescriptor.newBuilder().setKey("a").setDescription("first"))
                .build());
        MetricDescriptor updated = service.createMetricDescriptor(PROJECT, descriptor(METRIC)
                .addLabels(LabelDescriptor.newBuilder().setKey("b").setDescription("second"))
                .build());

        assertEquals(2, updated.getLabelsCount());
        assertEquals(List.of("a", "b"), updated.getLabelsList().stream().map(LabelDescriptor::getKey).toList());

        MetricDescriptor redefined = service.createMetricDescriptor(PROJECT, descriptor(METRIC)
                .addLabels(LabelDescriptor.newBuilder().setKey("a").setDescription("redefined"))
                .build());
        assertEquals(2, redefined.getLabelsCount());
        assertEquals("redefined", redefined.getLabels(0).getDescription());
    }

    @Test
    void deleteSystemMetricRejected() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.deleteMetricDescriptor(PROJECT + "/metricDescriptors/compute.googleapis.com/instance/cpu/usage_time"));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void deleteCustomMetricCascadesPoints() {
        write(gaugePoint(METRIC, Map.of(), 1.0, NOW.minusSeconds(60)));
        assertEquals(1, timeSeriesStore.scan(k -> true).size());

        service.deleteMetricDescriptor(PROJECT + "/metricDescriptors/" + METRIC);
        assertEquals(0, timeSeriesStore.scan(k -> true).size());

        GcpException ex = assertThrows(GcpException.class,
                () -> service.deleteMetricDescriptor(PROJECT + "/metricDescriptors/" + METRIC));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    // ── CreateTimeSeries validation ──────────────────────────────────────────

    @Test
    void moreThan200TimeSeriesRejected() {
        List<TimeSeries> batch = IntStream.rangeClosed(1, 201)
                .mapToObj(i -> gaugePoint("custom.googleapis.com/m" + i, Map.of(), i, NOW.minusSeconds(60)))
                .toList();
        GcpException ex = assertThrows(GcpException.class, () -> service.createTimeSeries(PROJECT, batch));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void exactlyOnePointPerSeriesRequired() {
        TimeSeries twoPoints = gaugePoint(METRIC, Map.of(), 1.0, NOW.minusSeconds(120)).toBuilder()
                .addPoints(Point.newBuilder()
                        .setInterval(TimeInterval.newBuilder().setEndTime(ts(NOW.minusSeconds(60))))
                        .setValue(TypedValue.newBuilder().setDoubleValue(2.0)))
                .build();
        GcpException ex = assertThrows(GcpException.class,
                () -> service.createTimeSeries(PROJECT, List.of(twoPoints)));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void missingEndTimeRejected() {
        TimeSeries series = gaugePoint(METRIC, Map.of(), 1.0, NOW).toBuilder()
                .setPoints(0, Point.newBuilder().setValue(TypedValue.newBuilder().setDoubleValue(1.0)))
                .build();
        GcpException ex = assertThrows(GcpException.class,
                () -> service.createTimeSeries(PROJECT, List.of(series)));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void endTimeOutsideWriteWindowRejected() {
        GcpException past = assertThrows(GcpException.class,
                () -> write(gaugePoint(METRIC, Map.of(), 1.0, NOW.minus(java.time.Duration.ofHours(26)))));
        assertEquals("INVALID_ARGUMENT", past.getGcpStatus());

        GcpException future = assertThrows(GcpException.class,
                () -> write(gaugePoint(METRIC, Map.of(), 1.0, NOW.plus(java.time.Duration.ofMinutes(10)))));
        assertEquals("INVALID_ARGUMENT", future.getGcpStatus());
    }

    @Test
    void gaugeStartTimeMustEqualEndTime() {
        Instant end = NOW.minusSeconds(60);
        TimeSeries badStart = gaugePoint(METRIC, Map.of(), 1.0, end).toBuilder()
                .setPoints(0, Point.newBuilder()
                        .setInterval(TimeInterval.newBuilder()
                                .setStartTime(ts(end.minusSeconds(30)))
                                .setEndTime(ts(end)))
                        .setValue(TypedValue.newBuilder().setDoubleValue(1.0)))
                .build();
        GcpException ex = assertThrows(GcpException.class,
                () -> service.createTimeSeries(PROJECT, List.of(badStart)));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());

        TimeSeries okStart = gaugePoint(METRIC, Map.of(), 1.0, end).toBuilder()
                .setPoints(0, Point.newBuilder()
                        .setInterval(TimeInterval.newBuilder().setStartTime(ts(end)).setEndTime(ts(end)))
                        .setValue(TypedValue.newBuilder().setDoubleValue(1.0)))
                .build();
        assertDoesNotThrow(() -> service.createTimeSeries(PROJECT, List.of(okStart)));
    }

    @Test
    void cumulativeRequiresStartBeforeEnd() {
        Instant end = NOW.minusSeconds(60);
        GcpException sameStart = assertThrows(GcpException.class,
                () -> write(cumulativePoint(METRIC, 10, end, end)));
        assertEquals("INVALID_ARGUMENT", sameStart.getGcpStatus());

        assertDoesNotThrow(() -> write(cumulativePoint(METRIC, 10, end.minusSeconds(600), end)));
    }

    @Test
    void stalePointRejected() {
        write(gaugePoint(METRIC, Map.of("id", "a"), 1.0, NOW.minusSeconds(60)));

        GcpException older = assertThrows(GcpException.class,
                () -> write(gaugePoint(METRIC, Map.of("id", "a"), 2.0, NOW.minusSeconds(120))));
        assertEquals("INVALID_ARGUMENT", older.getGcpStatus());

        GcpException same = assertThrows(GcpException.class,
                () -> write(gaugePoint(METRIC, Map.of("id", "a"), 2.0, NOW.minusSeconds(60))));
        assertEquals("INVALID_ARGUMENT", same.getGcpStatus());

        // A different label value is an independent series
        assertDoesNotThrow(() -> write(gaugePoint(METRIC, Map.of("id", "b"), 2.0, NOW.minusSeconds(120))));
    }

    @Test
    void batchWritesNothingOnFailure() {
        TimeSeries valid = gaugePoint(METRIC, Map.of(), 1.0, NOW.minusSeconds(60));
        TimeSeries invalid = gaugePoint("custom.googleapis.com/other", Map.of(), 1.0,
                NOW.plus(java.time.Duration.ofMinutes(10)));

        assertThrows(GcpException.class, () -> service.createTimeSeries(PROJECT, List.of(valid, invalid)));

        assertEquals(0, timeSeriesStore.scan(k -> true).size());
        GcpException ex = assertThrows(GcpException.class,
                () -> service.getMetricDescriptor(PROJECT + "/metricDescriptors/" + METRIC));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void autoCreateInfersValueTypeFromPoint() {
        TimeSeries int64Series = gaugePoint(METRIC, Map.of(), 0, NOW.minusSeconds(60)).toBuilder()
                .setPoints(0, Point.newBuilder()
                        .setInterval(TimeInterval.newBuilder().setEndTime(ts(NOW.minusSeconds(60))))
                        .setValue(TypedValue.newBuilder().setInt64Value(42)))
                .build();
        service.createTimeSeries(PROJECT, List.of(int64Series));

        MetricDescriptor desc = service.getMetricDescriptor(PROJECT + "/metricDescriptors/" + METRIC);
        assertEquals(MetricDescriptor.ValueType.INT64, desc.getValueType());
        assertEquals(MetricDescriptor.MetricKind.GAUGE, desc.getMetricKind());
    }

    @Test
    void autoCreateRejectsStringValuesAndDeltaKind() {
        TimeSeries stringSeries = gaugePoint(METRIC, Map.of(), 0, NOW.minusSeconds(60)).toBuilder()
                .setPoints(0, Point.newBuilder()
                        .setInterval(TimeInterval.newBuilder().setEndTime(ts(NOW.minusSeconds(60))))
                        .setValue(TypedValue.newBuilder().setStringValue("nope")))
                .build();
        GcpException stringEx = assertThrows(GcpException.class,
                () -> service.createTimeSeries(PROJECT, List.of(stringSeries)));
        assertEquals("INVALID_ARGUMENT", stringEx.getGcpStatus());

        TimeSeries deltaSeries = cumulativePoint(METRIC, 5, NOW.minusSeconds(120), NOW.minusSeconds(60)).toBuilder()
                .setMetricKind(MetricDescriptor.MetricKind.DELTA)
                .build();
        GcpException deltaEx = assertThrows(GcpException.class,
                () -> service.createTimeSeries(PROJECT, List.of(deltaSeries)));
        assertEquals("INVALID_ARGUMENT", deltaEx.getGcpStatus());
    }

    @Test
    void autoCreateRejectsBoolValueWithCumulativeKind() {
        TimeSeries boolCumulative = cumulativePoint(METRIC, 1, NOW.minusSeconds(120), NOW.minusSeconds(60)).toBuilder()
                .setPoints(0, Point.newBuilder()
                        .setInterval(TimeInterval.newBuilder()
                                .setStartTime(ts(NOW.minusSeconds(120)))
                                .setEndTime(ts(NOW.minusSeconds(60))))
                        .setValue(TypedValue.newBuilder().setBoolValue(true)))
                .build();

        GcpException ex = assertThrows(GcpException.class,
                () -> service.createTimeSeries(PROJECT, List.of(boolCumulative)));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertThrows(GcpException.class,
                () -> service.getMetricDescriptor(PROJECT + "/metricDescriptors/" + METRIC));
    }

    // ── ListTimeSeries validation ────────────────────────────────────────────

    @Test
    void listTimeSeriesRequiresFilterWithMetricType() {
        TimeInterval interval = interval(NOW.minusSeconds(3600), NOW);

        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> list("", interval)).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> list("resource.type = \"global\"", interval)).getGcpStatus());
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> list("metric.type = starts_with(\"custom.\")", interval)).getGcpStatus());
    }

    @Test
    void listTimeSeriesRequiresEndTime() {
        GcpException ex = assertThrows(GcpException.class,
                () -> list(typeFilter(), TimeInterval.getDefaultInstance()));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void listTimeSeriesIntervalIsHalfOpen() {
        Instant t1 = NOW.minusSeconds(120);
        Instant t2 = NOW.minusSeconds(60);
        write(gaugePoint(METRIC, Map.of(), 1.0, t1));
        write(gaugePoint(METRIC, Map.of(), 2.0, t2));

        List<TimeSeries> series = list(typeFilter(), interval(t1, t2)).items();
        assertEquals(1, series.size());
        assertEquals(1, series.get(0).getPointsCount());
        assertEquals(2.0, series.get(0).getPoints(0).getValue().getDoubleValue());
    }

    @Test
    void filterSupportsStartsWithAndExtractsMetricType() {
        TimeSeriesFilter.ParsedFilter parsed = TimeSeriesFilter.parseFilter(
                "metric.type = \"" + METRIC + "\" AND metric.labels.id = starts_with(\"prod-\")");
        assertEquals(METRIC, parsed.metricTypeEquality());

        StoredTimeSeriesPoint prod = new StoredTimeSeriesPoint();
        prod.setMetricType(METRIC);
        prod.setMetricLabels(Map.of("id", "prod-1"));
        StoredTimeSeriesPoint dev = new StoredTimeSeriesPoint();
        dev.setMetricType(METRIC);
        dev.setMetricLabels(Map.of("id", "dev-1"));

        assertTrue(parsed.predicate().test(prod));
        assertFalse(parsed.predicate().test(dev));

        assertNull(TimeSeriesFilter.parseFilter("metric.type = starts_with(\"custom.\")").metricTypeEquality());
    }

    // ── Aggregation ──────────────────────────────────────────────────────────

    @Test
    void aggregationValidationRejectsBadRequests() {
        write(gaugePoint(METRIC, Map.of(), 1.0, NOW.minusSeconds(30)));
        TimeInterval interval = interval(NOW.minusSeconds(3600), NOW);

        Aggregation shortPeriod = Aggregation.newBuilder()
                .setPerSeriesAligner(Aggregation.Aligner.ALIGN_SUM)
                .setAlignmentPeriod(Duration.newBuilder().setSeconds(59))
                .build();
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> list(typeFilter(), interval, shortPeriod)).getGcpStatus());

        Aggregation reducerWithoutAligner = Aggregation.newBuilder()
                .setCrossSeriesReducer(Aggregation.Reducer.REDUCE_SUM)
                .build();
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> list(typeFilter(), interval, reducerWithoutAligner)).getGcpStatus());

        Aggregation unsupported = Aggregation.newBuilder()
                .setPerSeriesAligner(Aggregation.Aligner.ALIGN_PERCENTILE_99)
                .setAlignmentPeriod(Duration.newBuilder().setSeconds(60))
                .build();
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> list(typeFilter(), interval, unsupported)).getGcpStatus());
    }

    @Test
    void alignSumBucketsPointsOnPeriodBoundaries() {
        write(gaugePoint(METRIC, Map.of(), 4.0, NOW.minusSeconds(90)));
        write(gaugePoint(METRIC, Map.of(), 1.0, NOW.minusSeconds(30)));
        write(gaugePoint(METRIC, Map.of(), 2.0, NOW.minusSeconds(10)));

        List<TimeSeries> series = list(typeFilter(), interval(NOW.minusSeconds(3600), NOW),
                aligned(Aggregation.Aligner.ALIGN_SUM, 60)).items();

        assertEquals(1, series.size());
        List<Point> points = series.get(0).getPointsList();
        assertEquals(2, points.size());
        assertEquals(NOW, toInstant(points.get(0).getInterval().getEndTime()));
        assertEquals(3.0, points.get(0).getValue().getDoubleValue());
        assertEquals(NOW.minusSeconds(60), toInstant(points.get(1).getInterval().getEndTime()));
        assertEquals(4.0, points.get(1).getValue().getDoubleValue());
    }

    @Test
    void alignMeanAndCountChangeOutputTypes() {
        TimeSeries int1 = intGaugePoint(METRIC, 10, NOW.minusSeconds(30));
        TimeSeries int2 = intGaugePoint(METRIC, 20, NOW.minusSeconds(10));
        service.createTimeSeries(PROJECT, List.of(int1));
        service.createTimeSeries(PROJECT, List.of(int2));

        TimeSeries mean = list(typeFilter(), interval(NOW.minusSeconds(3600), NOW),
                aligned(Aggregation.Aligner.ALIGN_MEAN, 60)).items().get(0);
        assertEquals(MetricDescriptor.ValueType.DOUBLE, mean.getValueType());
        assertEquals(15.0, mean.getPoints(0).getValue().getDoubleValue());

        TimeSeries count = list(typeFilter(), interval(NOW.minusSeconds(3600), NOW),
                aligned(Aggregation.Aligner.ALIGN_COUNT, 60)).items().get(0);
        assertEquals(MetricDescriptor.ValueType.INT64, count.getValueType());
        assertEquals(2, count.getPoints(0).getValue().getInt64Value());
    }

    @Test
    void alignDeltaAndRateOnCumulative() {
        Instant start = NOW.minusSeconds(600);
        write(cumulativePoint(METRIC, 10, start, NOW.minusSeconds(90)));
        write(cumulativePoint(METRIC, 25, start, NOW.minusSeconds(30)));

        TimeSeries delta = list(typeFilter(), interval(NOW.minusSeconds(3600), NOW),
                aligned(Aggregation.Aligner.ALIGN_DELTA, 120)).items().get(0);
        assertEquals(MetricDescriptor.MetricKind.DELTA, delta.getMetricKind());
        assertEquals(15.0, delta.getPoints(0).getValue().getDoubleValue());
        assertEquals(NOW.minusSeconds(120), toInstant(delta.getPoints(0).getInterval().getStartTime()));

        TimeSeries rate = list(typeFilter(), interval(NOW.minusSeconds(3600), NOW),
                aligned(Aggregation.Aligner.ALIGN_RATE, 120)).items().get(0);
        assertEquals(MetricDescriptor.MetricKind.GAUGE, rate.getMetricKind());
        assertEquals(MetricDescriptor.ValueType.DOUBLE, rate.getValueType());
        assertEquals(15.0 / 120, rate.getPoints(0).getValue().getDoubleValue(), 1e-9);
    }

    @Test
    void alignDeltaOnGaugeRejected() {
        write(gaugePoint(METRIC, Map.of(), 1.0, NOW.minusSeconds(30)));
        GcpException ex = assertThrows(GcpException.class,
                () -> list(typeFilter(), interval(NOW.minusSeconds(3600), NOW),
                        aligned(Aggregation.Aligner.ALIGN_DELTA, 60)));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void reduceSumCollapsesSeriesAndGroupByPartitions() {
        write(gaugePoint(METRIC, Map.of(), Map.of("zone", "us-a"), 5.0, NOW.minusSeconds(30)));
        write(gaugePoint(METRIC, Map.of(), Map.of("zone", "us-b"), 7.0, NOW.minusSeconds(20)));

        Aggregation reduced = aligned(Aggregation.Aligner.ALIGN_SUM, 60).toBuilder()
                .setCrossSeriesReducer(Aggregation.Reducer.REDUCE_SUM)
                .build();
        List<TimeSeries> collapsed = list(typeFilter(), interval(NOW.minusSeconds(3600), NOW), reduced).items();
        assertEquals(1, collapsed.size());
        assertEquals(12.0, collapsed.get(0).getPoints(0).getValue().getDoubleValue());
        assertTrue(collapsed.get(0).getResource().getLabelsMap().isEmpty());

        Aggregation grouped = reduced.toBuilder().addGroupByFields("resource.label.zone").build();
        List<TimeSeries> partitioned = list(typeFilter(), interval(NOW.minusSeconds(3600), NOW), grouped).items();
        assertEquals(2, partitioned.size());
        assertEquals(Map.of("zone", "us-a"), partitioned.get(0).getResource().getLabelsMap());
        assertEquals(5.0, partitioned.get(0).getPoints(0).getValue().getDoubleValue());
        assertEquals(Map.of("zone", "us-b"), partitioned.get(1).getResource().getLabelsMap());
    }

    @Test
    void unknownGroupByFieldRejected() {
        write(gaugePoint(METRIC, Map.of(), 1.0, NOW.minusSeconds(30)));
        Aggregation bad = aligned(Aggregation.Aligner.ALIGN_SUM, 60).toBuilder()
                .setCrossSeriesReducer(Aggregation.Reducer.REDUCE_SUM)
                .addGroupByFields("bogus.field")
                .build();
        GcpException ex = assertThrows(GcpException.class,
                () -> list(typeFilter(), interval(NOW.minusSeconds(3600), NOW), bad));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    // ── Pagination ───────────────────────────────────────────────────────────

    @Test
    void listMetricDescriptorsPaginates() {
        for (int i = 1; i <= 3; i++) {
            service.createMetricDescriptor(PROJECT, descriptor("custom.googleapis.com/m" + i).build());
        }

        PageToken.Page<MetricDescriptor> first = service.listMetricDescriptors(PROJECT, null, 2, null);
        assertEquals(2, first.items().size());
        assertNotNull(first.nextPageToken());

        PageToken.Page<MetricDescriptor> second =
                service.listMetricDescriptors(PROJECT, null, 2, first.nextPageToken());
        assertEquals(1, second.items().size());
        assertNull(second.nextPageToken());
    }

    @Test
    void listTimeSeriesFullViewPaginatesByPoints() {
        for (String id : List.of("a", "b")) {
            for (int minute = 3; minute >= 1; minute--) {
                write(gaugePoint(METRIC, Map.of("id", id), minute, NOW.minusSeconds(minute * 60L)));
            }
        }

        PageToken.Page<TimeSeries> first = service.listTimeSeries(PROJECT, typeFilter(),
                interval(NOW.minusSeconds(3600), NOW), Aggregation.getDefaultInstance(), "FULL", 4, null);
        assertEquals(4, first.items().stream().mapToInt(TimeSeries::getPointsCount).sum());
        assertEquals(2, first.items().size());
        assertNotNull(first.nextPageToken());

        PageToken.Page<TimeSeries> second = service.listTimeSeries(PROJECT, typeFilter(),
                interval(NOW.minusSeconds(3600), NOW), Aggregation.getDefaultInstance(), "FULL", 4,
                first.nextPageToken());
        assertEquals(2, second.items().stream().mapToInt(TimeSeries::getPointsCount).sum());
        assertEquals(1, second.items().size());
        assertNull(second.nextPageToken());
    }

    @Test
    void listTimeSeriesHeadersViewPaginatesBySeries() {
        write(gaugePoint(METRIC, Map.of("id", "a"), 1.0, NOW.minusSeconds(60)));
        write(gaugePoint(METRIC, Map.of("id", "b"), 2.0, NOW.minusSeconds(60)));

        PageToken.Page<TimeSeries> first = service.listTimeSeries(PROJECT, typeFilter(),
                interval(NOW.minusSeconds(3600), NOW), Aggregation.getDefaultInstance(), "HEADERS", 1, null);
        assertEquals(1, first.items().size());
        assertEquals(0, first.items().get(0).getPointsCount());
        assertNotNull(first.nextPageToken());

        PageToken.Page<TimeSeries> second = service.listTimeSeries(PROJECT, typeFilter(),
                interval(NOW.minusSeconds(3600), NOW), Aggregation.getDefaultInstance(), "HEADERS", 1,
                first.nextPageToken());
        assertEquals(1, second.items().size());
        assertNull(second.nextPageToken());
    }

    @Test
    void listMonitoredResourceDescriptorsPaginates() {
        PageToken.Page<MonitoredResourceDescriptor> first =
                service.listMonitoredResourceDescriptors(PROJECT, 1, null);
        assertEquals(1, first.items().size());
        assertEquals("global", first.items().get(0).getType());
        assertNotNull(first.nextPageToken());

        PageToken.Page<MonitoredResourceDescriptor> second =
                service.listMonitoredResourceDescriptors(PROJECT, 1, first.nextPageToken());
        assertEquals("gce_instance", second.items().get(0).getType());
        assertNull(second.nextPageToken());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void write(TimeSeries series) {
        service.createTimeSeries(PROJECT, List.of(series));
    }

    private PageToken.Page<TimeSeries> list(String filter, TimeInterval interval) {
        return list(filter, interval, Aggregation.getDefaultInstance());
    }

    private PageToken.Page<TimeSeries> list(String filter, TimeInterval interval, Aggregation aggregation) {
        return service.listTimeSeries(PROJECT, filter, interval, aggregation, "FULL", 0, null);
    }

    private static String typeFilter() {
        return "metric.type = \"" + METRIC + "\"";
    }

    private static MetricDescriptor.Builder descriptor(String type) {
        return MetricDescriptor.newBuilder()
                .setType(type)
                .setMetricKind(MetricDescriptor.MetricKind.GAUGE)
                .setValueType(MetricDescriptor.ValueType.DOUBLE);
    }

    private static TimeSeries gaugePoint(String type, Map<String, String> metricLabels,
                                         double value, Instant end) {
        return gaugePoint(type, metricLabels, Map.of(), value, end);
    }

    private static TimeSeries gaugePoint(String type, Map<String, String> metricLabels,
                                         Map<String, String> resourceLabels, double value, Instant end) {
        return TimeSeries.newBuilder()
                .setMetric(Metric.newBuilder().setType(type).putAllLabels(metricLabels))
                .setResource(MonitoredResource.newBuilder().setType("global").putAllLabels(resourceLabels))
                .addPoints(Point.newBuilder()
                        .setInterval(TimeInterval.newBuilder().setEndTime(ts(end)))
                        .setValue(TypedValue.newBuilder().setDoubleValue(value)))
                .build();
    }

    private static TimeSeries intGaugePoint(String type, long value, Instant end) {
        return TimeSeries.newBuilder()
                .setMetric(Metric.newBuilder().setType(type))
                .setResource(MonitoredResource.newBuilder().setType("global"))
                .addPoints(Point.newBuilder()
                        .setInterval(TimeInterval.newBuilder().setEndTime(ts(end)))
                        .setValue(TypedValue.newBuilder().setInt64Value(value)))
                .build();
    }

    private static TimeSeries cumulativePoint(String type, double value, Instant start, Instant end) {
        return TimeSeries.newBuilder()
                .setMetric(Metric.newBuilder().setType(type))
                .setResource(MonitoredResource.newBuilder().setType("global"))
                .setMetricKind(MetricDescriptor.MetricKind.CUMULATIVE)
                .addPoints(Point.newBuilder()
                        .setInterval(TimeInterval.newBuilder().setStartTime(ts(start)).setEndTime(ts(end)))
                        .setValue(TypedValue.newBuilder().setDoubleValue(value)))
                .build();
    }

    private static Aggregation aligned(Aggregation.Aligner aligner, long periodSeconds) {
        return Aggregation.newBuilder()
                .setPerSeriesAligner(aligner)
                .setAlignmentPeriod(Duration.newBuilder().setSeconds(periodSeconds))
                .build();
    }

    private static TimeInterval interval(Instant start, Instant end) {
        return TimeInterval.newBuilder().setStartTime(ts(start)).setEndTime(ts(end)).build();
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static Instant toInstant(Timestamp ts) {
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }
}

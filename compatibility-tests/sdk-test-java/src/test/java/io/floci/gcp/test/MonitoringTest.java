package io.floci.gcp.test;

import com.google.api.LabelDescriptor;
import com.google.api.Metric;
import com.google.api.MetricDescriptor;
import com.google.api.MonitoredResource;
import com.google.api.MonitoredResourceDescriptor;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.cloud.monitoring.v3.MetricServiceClient.ListMetricDescriptorsPagedResponse;
import com.google.monitoring.v3.Aggregation;
import com.google.monitoring.v3.CreateMetricDescriptorRequest;
import com.google.monitoring.v3.CreateTimeSeriesRequest;
import com.google.monitoring.v3.ListMetricDescriptorsRequest;
import com.google.monitoring.v3.ListMonitoredResourceDescriptorsRequest;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MonitoringTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String PROJECT_NAME = "projects/" + PROJECT_ID;
    private static final String METRIC_TYPE = "custom.googleapis.com/" + TestFixtures.uniqueName("test-metric");
    private static final String METRIC_NAME = PROJECT_NAME + "/metricDescriptors/" + METRIC_TYPE;
    private static final String AGG_METRIC_TYPE = "custom.googleapis.com/" + TestFixtures.uniqueName("agg-metric");
    private static final String AGG_METRIC_NAME = PROJECT_NAME + "/metricDescriptors/" + AGG_METRIC_TYPE;

    private static MetricServiceClient client;

    @BeforeAll
    static void setUp() throws IOException {
        client = TestFixtures.monitoringClient();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    void listMonitoredResourceDescriptors() {
        List<MonitoredResourceDescriptor> list = new ArrayList<>();
        client.listMonitoredResourceDescriptors(ListMonitoredResourceDescriptorsRequest.newBuilder()
                        .setName(PROJECT_NAME)
                        .build())
                .iterateAll().forEach(list::add);

        assertThat(list).isNotEmpty();
        assertThat(list).anyMatch(d -> d.getType().equals("global"));
    }

    @Test
    @Order(2)
    void createMetricDescriptor() {
        MetricDescriptor descriptor = MetricDescriptor.newBuilder()
                .setType(METRIC_TYPE)
                .setMetricKind(MetricDescriptor.MetricKind.GAUGE)
                .setValueType(MetricDescriptor.ValueType.DOUBLE)
                .addLabels(LabelDescriptor.newBuilder()
                        .setKey("env")
                        .setValueType(LabelDescriptor.ValueType.STRING)
                        .setDescription("environment")
                        .build())
                .setDescription("Test custom metric descriptor")
                .setDisplayName("Test Metric")
                .build();

        MetricDescriptor created = client.createMetricDescriptor(CreateMetricDescriptorRequest.newBuilder()
                .setName(PROJECT_NAME)
                .setMetricDescriptor(descriptor)
                .build());

        assertThat(created.getName()).isEqualTo(METRIC_NAME);
        assertThat(created.getType()).isEqualTo(METRIC_TYPE);
        assertThat(created.getMetricKind()).isEqualTo(MetricDescriptor.MetricKind.GAUGE);
        assertThat(created.getValueType()).isEqualTo(MetricDescriptor.ValueType.DOUBLE);
    }

    @Test
    @Order(3)
    void getMetricDescriptor() {
        MetricDescriptor fetched = client.getMetricDescriptor(METRIC_NAME);
        assertThat(fetched.getType()).isEqualTo(METRIC_TYPE);
        assertThat(fetched.getMetricKind()).isEqualTo(MetricDescriptor.MetricKind.GAUGE);
        assertThat(fetched.getValueType()).isEqualTo(MetricDescriptor.ValueType.DOUBLE);
    }

    @Test
    @Order(4)
    void listMetricDescriptors() {
        List<MetricDescriptor> list = new ArrayList<>();
        client.listMetricDescriptors(ListMetricDescriptorsRequest.newBuilder()
                        .setName(PROJECT_NAME)
                        .setFilter("metric.type = starts_with(\"custom.googleapis.com\")")
                        .build())
                .iterateAll().forEach(list::add);

        assertThat(list).anyMatch(d -> d.getType().equals(METRIC_TYPE));
    }

    @Test
    @Order(5)
    void writeAndQueryTimeSeries() throws InterruptedException {
        // Prepare some points at different times
        Instant now = Instant.now();
        Instant oneMinAgo = now.minusSeconds(60);

        Point pt1 = Point.newBuilder()
                .setInterval(TimeInterval.newBuilder()
                        .setEndTime(toTimestamp(oneMinAgo))
                        .build())
                .setValue(TypedValue.newBuilder().setDoubleValue(42.5).build())
                .build();

        Point pt2 = Point.newBuilder()
                .setInterval(TimeInterval.newBuilder()
                        .setEndTime(toTimestamp(now))
                        .build())
                .setValue(TypedValue.newBuilder().setDoubleValue(99.9).build())
                .build();

        TimeSeries ts1 = TimeSeries.newBuilder()
                .setMetric(Metric.newBuilder()
                        .setType(METRIC_TYPE)
                        .putLabels("env", "production")
                        .build())
                .setResource(MonitoredResource.newBuilder()
                        .setType("global")
                        .build())
                .setMetricKind(MetricDescriptor.MetricKind.GAUGE)
                .setValueType(MetricDescriptor.ValueType.DOUBLE)
                .addPoints(pt1)
                .build();

        TimeSeries ts2 = TimeSeries.newBuilder()
                .setMetric(Metric.newBuilder()
                        .setType(METRIC_TYPE)
                        .putLabels("env", "production")
                        .build())
                .setResource(MonitoredResource.newBuilder()
                        .setType("global")
                        .build())
                .setMetricKind(MetricDescriptor.MetricKind.GAUGE)
                .setValueType(MetricDescriptor.ValueType.DOUBLE)
                .addPoints(pt2)
                .build();

        // Write points
        client.createTimeSeries(CreateTimeSeriesRequest.newBuilder()
                .setName(PROJECT_NAME)
                .addTimeSeries(ts1)
                .addTimeSeries(ts2)
                .build());

        // Wait a brief moment just in case
        Thread.sleep(100);

        // Query points
        TimeInterval interval = TimeInterval.newBuilder()
                .setStartTime(toTimestamp(now.minusSeconds(300)))
                .setEndTime(toTimestamp(now.plusSeconds(300)))
                .build();

        List<TimeSeries> result = new ArrayList<>();
        client.listTimeSeries(ListTimeSeriesRequest.newBuilder()
                        .setName(PROJECT_NAME)
                        .setFilter("metric.type = \"" + METRIC_TYPE + "\" AND metric.labels.env = \"production\"")
                        .setInterval(interval)
                        .build())
                .iterateAll().forEach(result::add);

        assertThat(result).hasSize(1);
        TimeSeries retrieved = result.get(0);
        assertThat(retrieved.getMetric().getType()).isEqualTo(METRIC_TYPE);
        assertThat(retrieved.getMetric().getLabelsMap()).containsEntry("env", "production");

        // Verify points are returned in reverse-chronological order (newest first)
        assertThat(retrieved.getPointsCount()).isEqualTo(2);
        assertThat(retrieved.getPoints(0).getValue().getDoubleValue()).isEqualTo(99.9);
        assertThat(retrieved.getPoints(1).getValue().getDoubleValue()).isEqualTo(42.5);
    }

    @Test
    @Order(6)
    void aggregatedTimeSeriesQuery() {
        Instant now = Instant.now();

        // Two series (env=a, env=b) of the same auto-created metric
        client.createTimeSeries(CreateTimeSeriesRequest.newBuilder()
                .setName(PROJECT_NAME)
                .addTimeSeries(gaugeSeries(AGG_METRIC_TYPE, "a", 5.0, now.minusSeconds(30)))
                .addTimeSeries(gaugeSeries(AGG_METRIC_TYPE, "b", 7.0, now.minusSeconds(20)))
                .build());

        TimeInterval interval = TimeInterval.newBuilder()
                .setStartTime(toTimestamp(now.minusSeconds(600)))
                .setEndTime(toTimestamp(now))
                .build();

        List<TimeSeries> reduced = new ArrayList<>();
        client.listTimeSeries(ListTimeSeriesRequest.newBuilder()
                        .setName(PROJECT_NAME)
                        .setFilter("metric.type = \"" + AGG_METRIC_TYPE + "\"")
                        .setInterval(interval)
                        .setAggregation(Aggregation.newBuilder()
                                .setAlignmentPeriod(Duration.newBuilder().setSeconds(3600))
                                .setPerSeriesAligner(Aggregation.Aligner.ALIGN_SUM)
                                .setCrossSeriesReducer(Aggregation.Reducer.REDUCE_SUM))
                        .build())
                .iterateAll().forEach(reduced::add);

        assertThat(reduced).hasSize(1);
        assertThat(reduced.get(0).getPointsCount()).isEqualTo(1);
        assertThat(reduced.get(0).getPoints(0).getValue().getDoubleValue()).isEqualTo(12.0);

        // alignment_period below the 60s minimum is rejected
        assertThatThrownBy(() -> client.listTimeSeries(ListTimeSeriesRequest.newBuilder()
                        .setName(PROJECT_NAME)
                        .setFilter("metric.type = \"" + AGG_METRIC_TYPE + "\"")
                        .setInterval(interval)
                        .setAggregation(Aggregation.newBuilder()
                                .setAlignmentPeriod(Duration.newBuilder().setSeconds(30))
                                .setPerSeriesAligner(Aggregation.Aligner.ALIGN_SUM))
                        .build())
                .iterateAll().forEach(ts -> {}))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    @Order(7)
    void listMetricDescriptorsPagination() {
        ListMetricDescriptorsPagedResponse response = client.listMetricDescriptors(
                ListMetricDescriptorsRequest.newBuilder()
                        .setName(PROJECT_NAME)
                        .setFilter("metric.type = starts_with(\"custom.googleapis.com\")")
                        .setPageSize(1)
                        .build());

        assertThat(response.getPage().getValues()).hasSize(1);
        assertThat(response.getPage().getNextPageToken()).isNotEmpty();

        // SDK auto-paging follows the token and sees both descriptors
        List<MetricDescriptor> all = new ArrayList<>();
        response.iterateAll().forEach(all::add);
        assertThat(all.stream().map(MetricDescriptor::getType))
                .contains(METRIC_TYPE, AGG_METRIC_TYPE);
    }

    @Test
    @Order(8)
    void stalePointRejected() {
        Instant stale = Instant.now().minusSeconds(600);
        assertThatThrownBy(() -> client.createTimeSeries(CreateTimeSeriesRequest.newBuilder()
                .setName(PROJECT_NAME)
                .addTimeSeries(gaugeSeries(AGG_METRIC_TYPE, "a", 1.0, stale))
                .build()))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    @Order(9)
    void deleteMetricDescriptor() {
        client.deleteMetricDescriptor(METRIC_NAME);
        client.deleteMetricDescriptor(AGG_METRIC_NAME);

        // Retrieve should now fail or not show in list
        List<MetricDescriptor> list = new ArrayList<>();
        client.listMetricDescriptors(ListMetricDescriptorsRequest.newBuilder()
                        .setName(PROJECT_NAME)
                        .setFilter("metric.type = \"" + METRIC_TYPE + "\"")
                        .build())
                .iterateAll().forEach(list::add);

        assertThat(list).noneMatch(d -> d.getType().equals(METRIC_TYPE));
    }

    private static TimeSeries gaugeSeries(String metricType, String env, double value, Instant end) {
        return TimeSeries.newBuilder()
                .setMetric(Metric.newBuilder()
                        .setType(metricType)
                        .putLabels("env", env)
                        .build())
                .setResource(MonitoredResource.newBuilder()
                        .setType("global")
                        .build())
                .addPoints(Point.newBuilder()
                        .setInterval(TimeInterval.newBuilder().setEndTime(toTimestamp(end)).build())
                        .setValue(TypedValue.newBuilder().setDoubleValue(value).build())
                        .build())
                .build();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}

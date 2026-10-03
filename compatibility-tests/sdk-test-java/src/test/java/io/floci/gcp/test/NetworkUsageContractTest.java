package io.floci.gcp.test;

import com.google.api.Metric;
import com.google.api.MetricDescriptor;
import com.google.api.MonitoredResource;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.cloud.monitoring.v3.MetricServiceSettings;
import com.google.monitoring.v3.*;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class NetworkUsageContractTest {
    @Test void syntheticCountersAreFilteredAndSummed() throws Exception {
        URI endpoint = URI.create(TestFixtures.endpoint());
        String target = System.getenv().getOrDefault("FLOCI_GCP_GRPC_ENDPOINT", endpoint.getHost() + ":" + endpoint.getPort());
        var channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        var settings = MetricServiceSettings.newBuilder().setCredentialsProvider(NoCredentialsProvider.create())
                .setTransportChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))).build();
        String project = "projects/" + TestFixtures.uniqueName("java-metrics");
        long now = Instant.now().getEpochSecond();
        try (var client = MetricServiceClient.create(settings)) {
            for (String direction : new String[]{"sent", "received"}) {
                String type = "compute.googleapis.com/instance/network/" + direction + "_bytes_count";
                client.createMetricDescriptor(project, MetricDescriptor.newBuilder().setType(type).setMetricKind(MetricDescriptor.MetricKind.DELTA).setValueType(MetricDescriptor.ValueType.INT64).setUnit("By").build());
                {
                    for (String instance : new String[]{"1001", "1002"}) {
                        var series = TimeSeries.newBuilder().setMetric(Metric.newBuilder().setType(type))
                                .setMetricKind(MetricDescriptor.MetricKind.DELTA).setValueType(MetricDescriptor.ValueType.INT64)
                                .setResource(MonitoredResource.newBuilder().setType("gce_instance").putLabels("project_id", project.substring(9)).putLabels("instance_id", instance).putLabels("zone", "us-central1-a"))
                                .addPoints(Point.newBuilder().setInterval(TimeInterval.newBuilder().setStartTime(Timestamp.newBuilder().setSeconds(now - 120)).setEndTime(Timestamp.newBuilder().setSeconds(now - 60)))
                                        .setValue(TypedValue.newBuilder().setInt64Value(instance.equals("1001") ? 100 : 9999))).build();
                        client.createTimeSeries(CreateTimeSeriesRequest.newBuilder().setName(project).addTimeSeries(series).build());
                    }
                    var request = ListTimeSeriesRequest.newBuilder().setName(project)
                            .setFilter("metric.type = \"" + type + "\" AND resource.labels.instance_id = \"1001\" AND resource.labels.zone = \"us-central1-a\"")
                            .setInterval(TimeInterval.newBuilder().setStartTime(Timestamp.newBuilder().setSeconds(now - 3600)).setEndTime(Timestamp.newBuilder().setSeconds(now)))
                            .setAggregation(Aggregation.newBuilder().setAlignmentPeriod(Duration.newBuilder().setSeconds(3600)).setPerSeriesAligner(Aggregation.Aligner.ALIGN_SUM))
                            .setView(ListTimeSeriesRequest.TimeSeriesView.FULL).setPageSize(1).build();
                    long total = 0;
                    for (var series : client.listTimeSeries(request).iterateAll()) {
                        for (var point : series.getPointsList()) { total += point.getValue().getInt64Value(); }
                    }
                    assertThat(total).isEqualTo(100);
                    assertThat(client.listTimeSeries(request.toBuilder().setName("projects/other-" + project.substring(9)).build()).iterateAll()).isEmpty();
                    assertThat(client.listTimeSeries(request.toBuilder().clearAggregation().setInterval(TimeInterval.newBuilder().setStartTime(Timestamp.newBuilder().setSeconds(now - 30)).setEndTime(Timestamp.newBuilder().setSeconds(now))).build()).iterateAll()).isEmpty();
                } // System metric fixtures live only in this disposable emulator project.
            }
        } finally { channel.shutdownNow(); }
    }
}

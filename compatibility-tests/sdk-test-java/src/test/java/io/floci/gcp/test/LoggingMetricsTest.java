package io.floci.gcp.test;

import com.google.api.LabelDescriptor;
import com.google.api.MetricDescriptor;
import com.google.api.MonitoredResource;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.logging.v2.LoggingClient;
import com.google.cloud.logging.v2.MetricsClient;
import com.google.cloud.logging.v2.MetricsSettings;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.logging.type.LogSeverity;
import com.google.logging.v2.LogEntry;
import com.google.logging.v2.LogMetric;
import com.google.logging.v2.WriteLogEntriesRequest;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingMetricsTest {
    private static final String PARENT = "projects/" + TestFixtures.projectId();

    @Test
    void metricsClientManagesACounterAndWritesCountItIntoMonitoring() throws Exception {
        String id = "java_" + TestFixtures.uniqueName("lbm").replace("-", "_");
        String log = PARENT + "/logs/" + id;
        try (MetricsClient metrics = metricsClient(); LoggingClient logging = TestFixtures.loggingClient();
                MetricServiceClient monitoring = TestFixtures.monitoringClient()) {
            LogMetric created = metrics.createLogMetric(PARENT, LogMetric.newBuilder().setName(id)
                    .setFilter("logName=\"" + log + "\" AND severity>=WARNING")
                    .setMetricDescriptor(MetricDescriptor.newBuilder()
                            .addLabels(LabelDescriptor.newBuilder().setKey("method")))
                    .putLabelExtractors("method", "EXTRACT(jsonPayload.method)").build());
            try {
                assertThat(created.getMetricDescriptor().getMetricKind()).isEqualTo(MetricDescriptor.MetricKind.DELTA);
                assertThat(created.getMetricDescriptor().getValueType()).isEqualTo(MetricDescriptor.ValueType.INT64);
                assertThat(created.getMetricDescriptor().getType()).isEqualTo("logging.googleapis.com/user/" + id);
                assertThat(created.hasCreateTime()).isTrue();
                assertThat(metrics.getLogMetric(PARENT + "/metrics/" + id)).isEqualTo(created);
                assertThat(metrics.listLogMetrics(PARENT).iterateAll()).extracting(LogMetric::getName).contains(id);
                assertThatThrownBy(() -> metrics.createLogMetric(PARENT, created))
                        .isInstanceOf(AlreadyExistsException.class);

                Instant start = Instant.now().minusSeconds(120);
                logging.writeLogEntries(WriteLogEntriesRequest.newBuilder().setLogName(log)
                        .setResource(MonitoredResource.newBuilder().setType("global"))
                        .addEntries(entry(LogSeverity.ERROR, "GET"))
                        .addEntries(entry(LogSeverity.WARNING, "GET"))
                        .addEntries(entry(LogSeverity.INFO, "GET"))
                        .addEntries(entry(LogSeverity.ERROR, "POST"))
                        .build());

                List<TimeSeries> series = new ArrayList<>();
                monitoring.listTimeSeries(ListTimeSeriesRequest.newBuilder().setName(PARENT)
                        .setFilter("metric.type=\"logging.googleapis.com/user/" + id + "\"")
                        .setInterval(TimeInterval.newBuilder().setStartTime(timestamp(start))
                                .setEndTime(timestamp(Instant.now().plusSeconds(60))))
                        .setView(ListTimeSeriesRequest.TimeSeriesView.FULL).build())
                        .iterateAll().forEach(series::add);
                assertThat(series).hasSize(2);
                assertThat(series).allSatisfy(one -> {
                    assertThat(one.getMetricKind()).isEqualTo(MetricDescriptor.MetricKind.DELTA);
                    assertThat(one.getMetric().getLabelsMap()).containsEntry("log", id);
                });
                assertThat(series).filteredOn(one -> one.getMetric().getLabelsMap().get("method").equals("GET"))
                        .singleElement().satisfies(one ->
                                assertThat(one.getPoints(0).getValue().getInt64Value()).isEqualTo(2));

                LogMetric updated = metrics.updateLogMetric(PARENT + "/metrics/" + id,
                        created.toBuilder().setDescription("updated").build());
                assertThat(updated.getCreateTime()).isEqualTo(created.getCreateTime());
                assertThat(updated.getDescription()).isEqualTo("updated");
            } finally {
                metrics.deleteLogMetric(PARENT + "/metrics/" + id);
            }
            assertThatThrownBy(() -> metrics.getLogMetric(PARENT + "/metrics/" + id))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    private static MetricsClient metricsClient() throws IOException {
        URI uri = URI.create(TestFixtures.endpoint());
        int port = uri.getPort() > 0 ? uri.getPort() : 4588;
        return MetricsClient.create(MetricsSettings.newBuilder()
                .setTransportChannelProvider(InstantiatingGrpcChannelProvider.newBuilder()
                        .setEndpoint(uri.getHost() + ":" + port)
                        .setChannelConfigurator(builder -> builder.usePlaintext()).build())
                .setCredentialsProvider(NoCredentialsProvider.create()).build());
    }

    private static LogEntry entry(LogSeverity severity, String method) {
        return LogEntry.newBuilder().setSeverity(severity).setJsonPayload(Struct.newBuilder()
                .putFields("method", Value.newBuilder().setStringValue(method).build())).build();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }
}

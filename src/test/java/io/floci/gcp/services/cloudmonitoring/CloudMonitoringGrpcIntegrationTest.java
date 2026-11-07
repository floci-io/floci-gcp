package io.floci.gcp.services.cloudmonitoring;

import com.google.api.Metric;
import com.google.api.MonitoredResource;
import com.google.monitoring.v3.CreateTimeSeriesRequest;
import com.google.monitoring.v3.CreateTimeSeriesSummary;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.MetricServiceGrpc;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudMonitoringGrpcIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @Test
    void generatedClientAcceptsOrganizationAndFolderTimeSeriesNames() throws Exception {
        var channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        try {
            var client = MetricServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
            var interval = TimeInterval.newBuilder()
                    .setEndTime(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond())).build();
            for (String name : List.of("organizations/123", "folders/456")) {
                var request = ListTimeSeriesRequest.newBuilder().setName(name)
                        .setFilter("metric.type = \"custom.googleapis.com/hierarchy/test\"")
                        .setInterval(interval).setPageSize(1).build();
                for (var view : List.of(ListTimeSeriesRequest.TimeSeriesView.FULL, ListTimeSeriesRequest.TimeSeriesView.HEADERS)) {
                    var result = client.listTimeSeries(request.toBuilder().setView(view).build());
                    assertEquals(0, result.getTimeSeriesCount());
                    assertTrue(result.getNextPageToken().isEmpty());
                }
                var error = assertThrows(StatusRuntimeException.class,
                        () -> client.listTimeSeries(request.toBuilder().clearFilter().build()));
                assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aPartialWriteCarriesTheSummaryInTheStatusDetails() throws Exception {
        var channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        try {
            var client = MetricServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
            var series = TimeSeries.newBuilder()
                    .setMetric(Metric.newBuilder().setType("custom.googleapis.com/grpc-partial/" + UUID.randomUUID()))
                    .setResource(MonitoredResource.newBuilder().setType("global"))
                    .addPoints(Point.newBuilder()
                            .setInterval(TimeInterval.newBuilder().setEndTime(
                                    Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond())))
                            .setValue(TypedValue.newBuilder().setDoubleValue(1)))
                    .build();

            var error = assertThrows(StatusRuntimeException.class, () -> client.createTimeSeries(
                    CreateTimeSeriesRequest.newBuilder().setName("projects/p-grpc-partial")
                            .addTimeSeries(series).addTimeSeries(series).build()));

            assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
            var summary = StatusProto.fromThrowable(error).getDetails(0).unpack(CreateTimeSeriesSummary.class);
            assertEquals(2, summary.getTotalPointCount());
            assertEquals(1, summary.getSuccessPointCount());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}

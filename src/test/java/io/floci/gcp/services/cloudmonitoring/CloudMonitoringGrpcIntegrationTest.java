package io.floci.gcp.services.cloudmonitoring;

import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.MetricServiceGrpc;
import com.google.monitoring.v3.TimeInterval;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
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
}

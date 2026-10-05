package io.floci.gcp.services.locations;

import com.google.cloud.location.GetLocationRequest;
import com.google.cloud.location.ListLocationsRequest;
import com.google.cloud.location.ListLocationsResponse;
import com.google.cloud.location.Location;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LocationsGrpcIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void listAndGetLocations() throws Exception {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try {
            ListLocationsResponse page = ClientCalls.blockingUnaryCall(channel, LocationsGrpcController.LIST_LOCATIONS,
                    CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                    ListLocationsRequest.newBuilder().setName("projects/loc-grpc").setPageSize(40).build());
            assertEquals(40, page.getLocationsCount());
            assertFalse(page.getNextPageToken().isEmpty());
            ListLocationsResponse rest = ClientCalls.blockingUnaryCall(channel, LocationsGrpcController.LIST_LOCATIONS,
                    CallOptions.DEFAULT, ListLocationsRequest.newBuilder().setName("projects/loc-grpc")
                            .setPageSize(40).setPageToken(page.getNextPageToken()).build());
            assertEquals(7, rest.getLocationsCount());
            assertTrue(rest.getNextPageToken().isEmpty());

            Location location = ClientCalls.blockingUnaryCall(channel, LocationsGrpcController.GET_LOCATION,
                    CallOptions.DEFAULT,
                    GetLocationRequest.newBuilder().setName("projects/loc-grpc/locations/europe-west1").build());
            assertEquals("projects/loc-grpc/locations/europe-west1", location.getName());
            assertEquals("europe-west1", location.getLocationId());
            assertEquals("europe-west1", location.getLabelsOrThrow("cloud.googleapis.com/region"));
            assertFalse(location.hasMetadata());

            Location global = ClientCalls.blockingUnaryCall(channel, LocationsGrpcController.GET_LOCATION,
                    CallOptions.DEFAULT,
                    GetLocationRequest.newBuilder().setName("projects/loc-grpc/locations/global").build());
            assertEquals("global", global.getLocationId());
            assertFalse(global.hasMetadata());

            StatusRuntimeException missing = assertThrows(StatusRuntimeException.class,
                    () -> ClientCalls.blockingUnaryCall(channel, LocationsGrpcController.GET_LOCATION, CallOptions.DEFAULT,
                            GetLocationRequest.newBuilder().setName("projects/loc-grpc/locations/mars-north1").build()));
            assertEquals(Status.Code.NOT_FOUND, missing.getStatus().getCode());

            StatusRuntimeException invalid = assertThrows(StatusRuntimeException.class,
                    () -> ClientCalls.blockingUnaryCall(channel, LocationsGrpcController.LIST_LOCATIONS, CallOptions.DEFAULT,
                            ListLocationsRequest.newBuilder().setName("folders/1").build()));
            assertEquals(Status.Code.INVALID_ARGUMENT, invalid.getStatus().getCode());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}

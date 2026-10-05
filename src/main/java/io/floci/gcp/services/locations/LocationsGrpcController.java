package io.floci.gcp.services.locations;

import com.google.cloud.location.GetLocationRequest;
import com.google.cloud.location.ListLocationsRequest;
import com.google.cloud.location.ListLocationsResponse;
import com.google.cloud.location.Location;
import io.floci.gcp.core.common.GcpGrpcController;
import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;

/**
 * {@code google.cloud.location.Locations} over gRPC. The stub artifact that ships
 * {@code LocationsGrpc} (grpc-google-common-protos) is not on the classpath, so the service
 * definition is declared by hand from the proto-google-common-protos messages, like Datastore.
 * The gRPC bridge does not expose the call authority, so the API cannot be told apart here:
 * responses list the union of locations (KMS's {@code global} and multi-regions included) and
 * carry no service-specific metadata.
 */
public class LocationsGrpcController implements BindableService {

    static final String SERVICE_NAME = "google.cloud.location.Locations";

    static final MethodDescriptor<ListLocationsRequest, ListLocationsResponse> LIST_LOCATIONS =
            MethodDescriptor.<ListLocationsRequest, ListLocationsResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(SERVICE_NAME + "/ListLocations")
                    .setRequestMarshaller(ProtoUtils.marshaller(ListLocationsRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(ListLocationsResponse.getDefaultInstance()))
                    .build();

    static final MethodDescriptor<GetLocationRequest, Location> GET_LOCATION =
            MethodDescriptor.<GetLocationRequest, Location>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(SERVICE_NAME + "/GetLocation")
                    .setRequestMarshaller(ProtoUtils.marshaller(GetLocationRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(Location.getDefaultInstance()))
                    .build();

    private final LocationsService service;

    LocationsGrpcController(LocationsService service) {
        this.service = service;
    }

    @Override
    public ServerServiceDefinition bindService() {
        return ServerServiceDefinition.builder(SERVICE_NAME)
                .addMethod(LIST_LOCATIONS, ServerCalls.asyncUnaryCall(this::listLocations))
                .addMethod(GET_LOCATION, ServerCalls.asyncUnaryCall(this::getLocation))
                .build();
    }

    void listLocations(ListLocationsRequest request, StreamObserver<ListLocationsResponse> observer) {
        try {
            observer.onNext(service.listLocationsByName(request.getName(), null,
                    request.getPageSize(), request.getPageToken()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    void getLocation(GetLocationRequest request, StreamObserver<Location> observer) {
        try {
            observer.onNext(service.getLocationByName(request.getName(), null));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }
}

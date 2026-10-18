package io.floci.gcp.services.datastore;

import com.google.rpc.Status;
import io.floci.gcp.core.common.ServiceUsageAdapter;
import jakarta.ws.rs.core.Response;

import java.util.Optional;

/**
 * The HTTP transport speaks protobuf: its clients parse an error body as a serialized
 * {@code google.rpc.Status} and report anything else as INTERNAL "Non-protobuf error".
 */
final class DatastoreServiceUsageAdapter implements ServiceUsageAdapter {

    @Override
    public Optional<Response> restDenial(RestRequest request, Status status) {
        if (request.resourceClass() != DatastoreHttpController.class) {
            return Optional.empty();
        }
        return Optional.of(Response.status(Response.Status.FORBIDDEN)
                .entity(status.toByteArray())
                .type("application/x-protobuf")
                .build());
    }
}

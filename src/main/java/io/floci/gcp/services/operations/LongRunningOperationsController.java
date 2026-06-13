package io.floci.gcp.services.operations;

import com.google.longrunning.WaitOperationRequest;
import com.google.protobuf.Empty;
import io.floci.gcp.core.common.ProtoJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v2/projects/{project}/locations/{location}/operations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class LongRunningOperationsController {

    private final LongRunningOperationsService operations;

    @Inject
    public LongRunningOperationsController(LongRunningOperationsService operations) {
        this.operations = operations;
    }

    @GET
    @Path("/{operation}")
    public Response get(@PathParam("project") String project,
                        @PathParam("location") String location,
                        @PathParam("operation") String operation) {
        String name = operationName(project, location, operation);
        return json(ProtoJson.print(operations.get(name)));
    }

    @GET
    public Response list(@PathParam("project") String project,
                         @PathParam("location") String location,
                         @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                         @QueryParam("pageToken") String pageToken) {
        String parent = parent(project, location);
        return json(ProtoJson.print(operations.list(parent, pageSize, pageToken)));
    }

    @POST
    @Path("/{operation}:wait")
    public Response wait(@PathParam("project") String project,
                         @PathParam("location") String location,
                         @PathParam("operation") String operation,
                         String body) {
        WaitOperationRequest request = ProtoJson.merge(body, WaitOperationRequest.newBuilder()).build();
        String name = operationName(project, location, operation);
        return json(ProtoJson.print(operations.wait(name, request.getTimeout())));
    }

    @DELETE
    @Path("/{operation}")
    public Response delete(@PathParam("project") String project,
                           @PathParam("location") String location,
                           @PathParam("operation") String operation) {
        operations.delete(operationName(project, location, operation));
        return json(ProtoJson.print(Empty.getDefaultInstance()));
    }

    private static Response json(String json) {
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }

    private static String parent(String project, String location) {
        return "projects/" + project + "/locations/" + location;
    }

    private static String operationName(String project, String location, String operation) {
        return parent(project, location) + "/operations/" + operation;
    }
}

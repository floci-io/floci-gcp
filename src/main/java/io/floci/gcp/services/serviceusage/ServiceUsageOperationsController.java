package io.floci.gcp.services.serviceusage;

import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/operations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ServiceUsageOperationsController {

    private final LongRunningOperationsService operations;

    @Inject
    public ServiceUsageOperationsController(LongRunningOperationsService operations) {
        this.operations = operations;
    }

    @GET
    @Path("/{operation}")
    public Response get(@PathParam("operation") String operation) {
        return json(ProtoJson.print(operations.get("operations/" + operation)));
    }

    @GET
    public Response list(@QueryParam("pageSize") @DefaultValue("0") int pageSize,
                         @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(operations.list("", pageSize, pageToken)));
    }

    private static Response json(String json) {
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }
}

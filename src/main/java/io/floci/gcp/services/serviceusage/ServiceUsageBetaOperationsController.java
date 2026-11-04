package io.floci.gcp.services.serviceusage;

import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Polls the operations {@code generateServiceIdentity} returns, under the v1beta1 prefix it uses. */
@Path("/v1beta1/operations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ServiceUsageBetaOperationsController {

    private final LongRunningOperationsService operations;

    @Inject
    public ServiceUsageBetaOperationsController(LongRunningOperationsService operations) {
        this.operations = operations;
    }

    @GET
    @Path("/{operation}")
    public Response get(@PathParam("operation") String operation) {
        return Response.ok(ProtoJson.print(operations.get("operations/" + operation)), MediaType.APPLICATION_JSON)
                .build();
    }
}

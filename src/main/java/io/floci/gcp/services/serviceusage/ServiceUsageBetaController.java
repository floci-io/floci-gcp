package io.floci.gcp.services.serviceusage;

import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The Service Usage v1beta1 methods that have no v1 equivalent. {@code generateServiceIdentity}
 * exists only in v1beta1, so it is not served under {@code /v1}, as in GCP.
 */
@Path("/v1beta1")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServiceUsageBetaController {

    private final ServiceUsageService service;
    private final LongRunningOperationsService operations;

    @Inject
    public ServiceUsageBetaController(ServiceUsageService service, LongRunningOperationsService operations) {
        this.service = service;
        this.operations = operations;
    }

    // The request has no body fields; the Java SDK posts an empty form-encoded body.
    @POST
    @Path("/projects/{project}/services/{service}:generateServiceIdentity")
    @Consumes(MediaType.WILDCARD)
    public Response generateServiceIdentity(@PathParam("project") String project,
                                            @PathParam("service") String serviceId) {
        return json(ProtoJson.print(service.generateServiceIdentity(project, serviceId)));
    }

    @GET
    @Path("/operations/{operation}")
    public Response getOperation(@PathParam("operation") String operation) {
        return json(ProtoJson.print(operations.get("operations/" + operation)));
    }

    private static Response json(String json) {
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }
}

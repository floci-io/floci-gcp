package io.floci.gcp.services.resourcemanager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** {@code cloudresourcemanager.operations.get} for project-creation operations ({@code operations/cp.*}). */
@Path("/v1/operations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ResourceManagerOperationsController {

    private final ResourceManagerService service;

    @Inject
    public ResourceManagerOperationsController(ResourceManagerService service) {
        this.service = service;
    }

    @GET
    @Path("/{operation: cp\\.[^/]+}")
    public Response getOperation(@PathParam("operation") String operation) {
        return Response.ok(service.getOperation(operation)).build();
    }
}

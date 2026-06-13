package io.floci.gcp.services.cloudsql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class CloudSqlGlobalController {

    private final CloudSqlService service;

    CloudSqlGlobalController() {
        this.service = null;
    }

    @Inject
    public CloudSqlGlobalController(CloudSqlService service) {
        this.service = service;
    }

    @GET
    @Path("/flags")
    public Response listFlags() {
        return Response.ok(service.listFlags()).build();
    }
}

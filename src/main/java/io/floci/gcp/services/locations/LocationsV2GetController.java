package io.floci.gcp.services.locations;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("/v2/projects/{project}/locations/{location}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class LocationsV2GetController {

    private final LocationsRestHandler handler;

    @Inject
    public LocationsV2GetController(LocationsRestHandler handler) {
        this.handler = handler;
    }

    @GET
    public Response getLocation(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @Context HttpHeaders headers,
                                @Context UriInfo uriInfo) {
        return handler.get(project, location, headers, uriInfo);
    }
}

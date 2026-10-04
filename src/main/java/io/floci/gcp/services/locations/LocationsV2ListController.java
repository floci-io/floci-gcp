package io.floci.gcp.services.locations;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("/v2/projects/{project}/locations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class LocationsV2ListController {

    private final LocationsRestHandler handler;

    @Inject
    public LocationsV2ListController(LocationsRestHandler handler) {
        this.handler = handler;
    }

    @GET
    public Response listLocations(@PathParam("project") String project,
                                  @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                  @QueryParam("pageToken") String pageToken,
                                  @Context HttpHeaders headers,
                                  @Context UriInfo uriInfo) {
        return handler.list(project, pageSize, pageToken, headers, uriInfo);
    }
}

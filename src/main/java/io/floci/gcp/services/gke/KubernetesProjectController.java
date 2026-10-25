package io.floci.gcp.services.gke;

import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * GKE (container.googleapis.com) project-scoped REST routes — {@code ListUsableSubnetworks} has
 * no {@code location} path segment, unlike every other GKE method, so it can't live on
 * {@link KubernetesController}'s {@code /projects/{project}/locations/{location}} base path.
 */
@Path("/container/v1/projects/{project}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class KubernetesProjectController {

    private final GkeService gkeService;

    @Inject
    public KubernetesProjectController(GkeService gkeService) {
        this.gkeService = gkeService;
    }

    @GET
    @Path("/aggregated/usableSubnetworks")
    public Response listUsableSubnetworks(
            @PathParam("project") String project) {

        return Response.ok(gkeService.listUsableSubnetworks(project)).build();
    }

    /** {@code ListLocations} exists only in {@code v1beta1}, which the routing filter maps onto this path. */
    @GET
    @Path("/locations")
    public Response listLocations(@PathParam("project") String project) {
        throw GcpException.unimplemented("ListLocations is not implemented by the emulator");
    }
}

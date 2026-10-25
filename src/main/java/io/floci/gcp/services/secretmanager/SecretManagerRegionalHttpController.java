package io.floci.gcp.services.secretmanager;

import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Regional Secret Manager REST paths (projects/{project}/locations/{location}/secrets/...).
 * Regional secrets are not emulated; every call returns the same UNIMPLEMENTED error as gRPC.
 */
@Path("/v1/projects/{project}/locations/{location}/secrets")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class SecretManagerRegionalHttpController {

    @GET
    @Path("{rest: .*}")
    public void get() {
        throw unsupported();
    }

    @POST
    @Path("{rest: .*}")
    public void post() {
        throw unsupported();
    }

    @PATCH
    @Path("{rest: .*}")
    public void patch() {
        throw unsupported();
    }

    @DELETE
    @Path("{rest: .*}")
    public void delete() {
        throw unsupported();
    }

    private static GcpException unsupported() {
        return GcpException.unimplemented(SecretManagerController.REGIONAL_UNSUPPORTED);
    }
}

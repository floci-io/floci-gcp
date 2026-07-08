package io.floci.gcp.services.firebaseauth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** Emulator-only management surface (unauthenticated, like the official Auth emulator). */
@Path("/emulator/v1")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class FirebaseAuthEmulatorController {

    private final FirebaseAuthService service;

    @Inject
    public FirebaseAuthEmulatorController(FirebaseAuthService service) {
        this.service = service;
    }

    @DELETE
    @Path("/projects/{project}/accounts")
    public Response deleteAllAccounts(@PathParam("project") String project) {
        service.deleteAllAccounts(project);
        return Response.ok(Map.of(), MediaType.APPLICATION_JSON).build();
    }
}

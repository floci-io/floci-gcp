package io.floci.gcp.services.gcs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Project-scoped Cloud Storage resources.
 *
 * <p>{@code projects.serviceAccount} returns the service account Cloud Storage uses to publish
 * on a project's behalf. Clients call it before wiring Pub/Sub notifications, to learn which
 * principal needs publish rights on the topic, {@code storage.getServiceAccount()} in the Node
 * and Python SDKs, {@code Storage#getServiceAccount} in Java. Without it that setup path fails
 * with a 404 even though notificationConfigs itself works.
 */
@ApplicationScoped
@Path("/storage/v1/projects/{project}")
@Produces(MediaType.APPLICATION_JSON)
public class GcsProjectController {

    @GET
    @Path("/serviceAccount")
    public Response getServiceAccount(@PathParam("project") String project) {
        // Real GCS derives this from an internal project number. The emulator has no such
        // number, so it mints a stable address from the project id: same project, same
        // address across restarts, which is what a test asserting on it needs.
        String email = "service-" + project + "@gs-project-accounts.iam.gserviceaccount.com";
        return Response.ok(Map.of(
                "kind", "storage#serviceAccount",
                "email_address", email)).build();
    }
}

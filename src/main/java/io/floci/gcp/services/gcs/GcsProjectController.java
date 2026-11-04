package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    private final ProjectRegistry projectRegistry;

    @Inject
    public GcsProjectController(ProjectRegistry projectRegistry) {
        this.projectRegistry = projectRegistry;
    }

    @GET
    @Path("/serviceAccount")
    public Response getServiceAccount(@PathParam("project") String project) {
        // Named by project number, as in GCP, so the address matches the Cloud Storage agent
        // Service Usage's generateServiceIdentity returns for the same project.
        String projectNumber = requireProject(project).getProjectNumber();
        String email = "service-" + projectNumber + "@gs-project-accounts.iam.gserviceaccount.com";
        return Response.ok(Map.of(
                "kind", "storage#serviceAccount",
                "email_address", email)).build();
    }

    // Cloud Storage answers a project it cannot resolve with 400 invalid, where Service Usage
    // and Resource Manager answer 403.
    private ProjectRecord requireProject(String project) {
        try {
            return projectRegistry.require(project);
        } catch (GcpException e) {
            if (!"PERMISSION_DENIED".equals(e.getGcpStatus())) {
                throw e;
            }
            throw GcpException.invalidArgument("Unknown project id: " + project);
        }
    }
}

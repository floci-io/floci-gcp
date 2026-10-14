package io.floci.gcp.services.resourcemanager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.Set;

@Path("/v1/projects/{project}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ResourceManagerController {

    private static final Set<String> CUSTOM_METHODS =
            Set.of("getIamPolicy", "setIamPolicy", "testIamPermissions", "undelete", "getAncestry");

    private final ResourceManagerService service;

    @Inject
    public ResourceManagerController(ResourceManagerService service) {
        this.service = service;
    }

    @GET
    public Response getProject(@PathParam("project") String project) {
        if (isCustomMethod(project)) {
            return Response.status(Response.Status.METHOD_NOT_ALLOWED).build();
        }
        return Response.ok(service.getProject(project)).build();
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateProject(@PathParam("project") String project, Map<String, Object> body) {
        return Response.ok(service.updateProject(project, body)).build();
    }

    @DELETE
    public Response deleteProject(@PathParam("project") String project) {
        service.deleteProject(project);
        return Response.ok(Map.of()).build();
    }

    private static boolean isCustomMethod(String project) {
        int colon = project.lastIndexOf(':');
        return colon >= 0 && CUSTOM_METHODS.contains(project.substring(colon + 1));
    }
}

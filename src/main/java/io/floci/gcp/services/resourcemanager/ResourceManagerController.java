package io.floci.gcp.services.resourcemanager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/v1/projects/{project}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ResourceManagerController {

    private final ResourceManagerService service;

    @Inject
    public ResourceManagerController(ResourceManagerService service) {
        this.service = service;
    }

    @GET
    public Response getProject(@PathParam("project") String project) {
        Map<String, Object> body = service.getProject(project);
        return Response.ok(body).build();
    }
}

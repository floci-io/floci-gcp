package io.floci.gcp.services.resourcemanager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/v1/projects")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ResourceManagerProjectsController {

    private final ResourceManagerService service;

    @Inject
    public ResourceManagerProjectsController(ResourceManagerService service) {
        this.service = service;
    }

    @POST
    public Response createProject(Map<String, Object> body) {
        return Response.ok(service.createProject(body)).build();
    }

    @GET
    public Response listProjects(@QueryParam("filter") String filter,
                                 @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                 @QueryParam("pageToken") String pageToken) {
        return Response.ok(service.listProjects(filter, pageSize, pageToken)).build();
    }

    @POST
    @Path("/{project}:undelete")
    public Response undeleteProject(@PathParam("project") String project, Map<String, Object> body) {
        service.undeleteProject(project);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/{project}:getAncestry")
    public Response getAncestry(@PathParam("project") String project, Map<String, Object> body) {
        return Response.ok(service.getAncestry(project)).build();
    }
}

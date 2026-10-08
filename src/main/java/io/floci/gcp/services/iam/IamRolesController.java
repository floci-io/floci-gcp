package io.floci.gcp.services.iam;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * IAM v1 project custom roles: {@code projects.roles.create/get/list/patch/delete/undelete}.
 * Declared with a longer path than {@link IamController}'s catch-all so role routes take precedence.
 */
@Path("/v1/projects/{project}/roles")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IamRolesController {

    private final IamRoleService service;

    @Inject
    public IamRolesController(IamRoleService service) {
        this.service = service;
    }

    @POST
    @SuppressWarnings("unchecked")
    public Response create(@PathParam("project") String project, Map<String, Object> body) {
        String roleId = body == null ? null : (String) body.get("roleId");
        Map<String, Object> role = body != null && body.get("role") instanceof Map<?, ?> nested ? (Map<String, Object>) nested : null;
        return Response.ok(service.create(project, roleId, role)).build();
    }

    @GET
    public Response list(@PathParam("project") String project,
                         @QueryParam("showDeleted") @DefaultValue("false") boolean showDeleted,
                         @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                         @QueryParam("pageToken") String pageToken) {
        return Response.ok(service.list(project, showDeleted, pageSize, pageToken)).build();
    }

    @GET
    @Path("/{role}")
    public Response get(@PathParam("project") String project, @PathParam("role") String role) {
        return Response.ok(service.get(project, role)).build();
    }

    @PATCH
    @Path("/{role}")
    public Response patch(@PathParam("project") String project, @PathParam("role") String role,
                          @QueryParam("updateMask") String updateMask, Map<String, Object> body) {
        return Response.ok(service.update(project, role, body, updateMask)).build();
    }

    @DELETE
    @Path("/{role}")
    public Response delete(@PathParam("project") String project, @PathParam("role") String role,
                           @QueryParam("etag") String etag) {
        return Response.ok(service.delete(project, role, etag)).build();
    }

    @POST
    @Path("/{role}:undelete")
    public Response undelete(@PathParam("project") String project, @PathParam("role") String role,
                             Map<String, Object> body) {
        return Response.ok(service.undelete(project, role, body == null ? null : (String) body.get("etag"))).build();
    }
}

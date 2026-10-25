package io.floci.gcp.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** IAM v1 {@code organizations.roles}. */
@Path("/v1/organizations/{organization}/roles")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IamOrganizationRolesController {

    private final IamRoleService service;
    private final ObjectMapper mapper;

    @Inject
    public IamOrganizationRolesController(IamRoleService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @POST
    public Response create(@PathParam("organization") String organization, Map<String, Object> body) {
        return IamRoleResponses.create(service, "organizations/" + organization, body);
    }

    @GET
    public Response list(@PathParam("organization") String organization, @QueryParam("view") String view,
            @QueryParam("showDeleted") @DefaultValue("false") boolean showDeleted,
            @QueryParam("pageSize") @DefaultValue("0") int pageSize, @QueryParam("pageToken") String pageToken) {
        return IamRoleResponses.list(service, "organizations/" + organization, view, showDeleted, pageSize, pageToken);
    }

    @GET
    @Path("/{role}")
    public Response get(@PathParam("organization") String organization, @PathParam("role") String role) {
        return Response.ok(service.get("organizations/" + organization + "/roles/" + role)).build();
    }

    @PATCH
    @Path("/{role}")
    public Response patch(@PathParam("organization") String organization, @PathParam("role") String role,
            @QueryParam("updateMask") String updateMask, Map<String, Object> body) {
        return Response.ok(service.update("organizations/" + organization + "/roles/" + role, body, updateMask))
                .build();
    }

    @DELETE
    @Path("/{role}")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_FORM_URLENCODED})
    public Response delete(@PathParam("organization") String organization, @PathParam("role") String role,
            @QueryParam("etag") String etag) {
        return Response.ok(service.delete("organizations/" + organization + "/roles/" + role, etag)).build();
    }

    @POST
    @Path("/{role}:undelete")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_FORM_URLENCODED})
    public Response undelete(@PathParam("organization") String organization, @PathParam("role") String role,
            String body) {
        return IamRoleResponses.undelete(service, "organizations/" + organization + "/roles/" + role, body, mapper);
    }
}

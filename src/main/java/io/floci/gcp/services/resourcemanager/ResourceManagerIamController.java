package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.services.iam.IamPolicyCodec;
import io.floci.gcp.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/v1/projects")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ResourceManagerIamController {

    private final IamService iamService;

    @Inject
    public ResourceManagerIamController(IamService iamService) {
        this.iamService = iamService;
    }

    @POST
    @Path("/{project}:getIamPolicy")
    public Response getIamPolicy(@PathParam("project") String project, Map<String, Object> body) {
        return Response.ok(iamService.getPolicy(projectName(project))).build();
    }

    @GET
    @Path("/{project}:getIamPolicy")
    public Response getIamPolicyWithGet() {
        return methodNotAllowed();
    }

    @POST
    @Path("/{project}:setIamPolicy")
    @SuppressWarnings("unchecked")
    public Response setIamPolicy(@PathParam("project") String project, Map<String, Object> body) {
        Map<String, Object> policy = body != null ? (Map<String, Object>) body.get("policy") : null;
        return Response.ok(iamService.setPolicy(projectName(project), IamPolicyCodec.fromJsonMap(policy))).build();
    }

    @GET
    @Path("/{project}:setIamPolicy")
    public Response setIamPolicyWithGet() {
        return methodNotAllowed();
    }

    @POST
    @Path("/{project}:testIamPermissions")
    @SuppressWarnings("unchecked")
    public Response testIamPermissions(@PathParam("project") String project, Map<String, Object> body) {
        List<String> permissions = body != null ? (List<String>) body.get("permissions") : List.of();
        return Response.ok(Map.of("permissions", iamService.testPermissions(
                projectName(project), permissions != null ? permissions : List.of()))).build();
    }

    @GET
    @Path("/{project}:testIamPermissions")
    public Response testIamPermissionsWithGet() {
        return methodNotAllowed();
    }

    private static Response methodNotAllowed() {
        return Response.status(Response.Status.METHOD_NOT_ALLOWED).build();
    }

    private static String projectName(String project) {
        return "projects/" + project;
    }
}

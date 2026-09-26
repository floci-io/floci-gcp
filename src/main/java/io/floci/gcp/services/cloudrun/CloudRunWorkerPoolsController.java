package io.floci.gcp.services.cloudrun;

import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import io.floci.gcp.core.common.ProtoJson;
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

/**
 * Cloud Run Admin API v2 REST JSON surface for worker pools and their revisions.
 */
@Path("/v2/projects/{project}/locations/{location}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudRunWorkerPoolsController {

    private final CloudRunWorkerPoolsService service;

    @Inject
    public CloudRunWorkerPoolsController(CloudRunWorkerPoolsService service) {
        this.service = service;
    }

    @POST
    @Path("/workerPools")
    public Response createWorkerPool(@PathParam("project") String project,
                                     @PathParam("location") String location,
                                     @QueryParam("workerPoolId") String workerPoolId,
                                     @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly,
                                     String body) {
        return json(ProtoJson.print(service.createWorkerPool(project, location, workerPoolId, body, validateOnly)));
    }

    @GET
    @Path("/workerPools")
    public Response listWorkerPools(@PathParam("project") String project,
                                    @PathParam("location") String location,
                                    @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                    @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listWorkerPools(project, location, pageSize, pageToken)));
    }

    @GET
    @Path("/workerPools/{workerPoolId}")
    public Response getWorkerPool(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @PathParam("workerPoolId") String workerPoolId) {
        return json(ProtoJson.print(service.getWorkerPool(workerPoolName(project, location, workerPoolId))));
    }

    @PATCH
    @Path("/workerPools/{workerPoolId}")
    public Response updateWorkerPool(@PathParam("project") String project,
                                     @PathParam("location") String location,
                                     @PathParam("workerPoolId") String workerPoolId,
                                     @QueryParam("updateMask") String updateMask,
                                     @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly,
                                     @QueryParam("allowMissing") @DefaultValue("false") boolean allowMissing,
                                     @QueryParam("forceNewRevision") @DefaultValue("false") boolean forceNewRevision,
                                     String body) {
        return json(ProtoJson.print(service.updateWorkerPool(workerPoolName(project, location, workerPoolId),
                body, updateMask, validateOnly, allowMissing, forceNewRevision)));
    }

    @DELETE
    @Path("/workerPools/{workerPoolId}")
    public Response deleteWorkerPool(@PathParam("project") String project,
                                     @PathParam("location") String location,
                                     @PathParam("workerPoolId") String workerPoolId,
                                     @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly) {
        return json(ProtoJson.print(service.deleteWorkerPool(
                workerPoolName(project, location, workerPoolId), validateOnly)));
    }

    @GET
    @Path("/workerPools/{workerPoolId}:getIamPolicy")
    public Response getIamPolicy(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @PathParam("workerPoolId") String workerPoolId) {
        return json(ProtoJson.print(service.getIamPolicy(workerPoolName(project, location, workerPoolId))));
    }

    @POST
    @Path("/workerPools/{workerPoolId}:setIamPolicy")
    public Response setIamPolicy(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @PathParam("workerPoolId") String workerPoolId,
                                 String body) {
        SetIamPolicyRequest request = ProtoJson.merge(body, SetIamPolicyRequest.newBuilder()).build();
        Policy policy = service.setIamPolicy(workerPoolName(project, location, workerPoolId), request.getPolicy());
        return json(ProtoJson.print(policy));
    }

    @POST
    @Path("/workerPools/{workerPoolId}:testIamPermissions")
    public Response testIamPermissions(@PathParam("project") String project,
                                       @PathParam("location") String location,
                                       @PathParam("workerPoolId") String workerPoolId,
                                       String body) {
        TestIamPermissionsRequest request = ProtoJson.merge(body, TestIamPermissionsRequest.newBuilder()).build();
        return json(ProtoJson.print(service.testIamPermissions(
                workerPoolName(project, location, workerPoolId), request.getPermissionsList())));
    }

    @GET
    @Path("/workerPools/{workerPoolId}/revisions")
    public Response listRevisions(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @PathParam("workerPoolId") String workerPoolId,
                                  @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                  @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listRevisions(
                workerPoolName(project, location, workerPoolId), pageSize, pageToken)));
    }

    @GET
    @Path("/workerPools/{workerPoolId}/revisions/{revisionId}")
    public Response getRevision(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @PathParam("workerPoolId") String workerPoolId,
                                @PathParam("revisionId") String revisionId) {
        return json(ProtoJson.print(service.getRevision(
                workerPoolName(project, location, workerPoolId) + "/revisions/" + revisionId)));
    }

    @DELETE
    @Path("/workerPools/{workerPoolId}/revisions/{revisionId}")
    public Response deleteRevision(@PathParam("project") String project,
                                   @PathParam("location") String location,
                                   @PathParam("workerPoolId") String workerPoolId,
                                   @PathParam("revisionId") String revisionId,
                                   @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly) {
        return json(ProtoJson.print(service.deleteRevision(
                workerPoolName(project, location, workerPoolId), revisionId, validateOnly)));
    }

    private static Response json(String json) {
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }

    private static String workerPoolName(String project, String location, String workerPoolId) {
        return "projects/" + project + "/locations/" + location + "/workerPools/" + workerPoolId;
    }
}

package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.StartInstanceRequest;
import com.google.cloud.run.v2.StopInstanceRequest;
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
 * Cloud Run Admin API v2 {@code projects.locations.instances} over REST JSON. The etag and showDeleted
 * parameters are accepted and ignored, as on GCP for etag.
 */
@Path("/v2/projects/{project}/locations/{location}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudRunInstancesController {

    private final CloudRunInstancesService service;

    @Inject
    public CloudRunInstancesController(CloudRunInstancesService service) {
        this.service = service;
    }

    @POST
    @Path("/instances")
    public Response createInstance(@PathParam("project") String project,
                                   @PathParam("location") String location,
                                   @QueryParam("instanceId") String instanceId,
                                   @QueryParam("instance_id") String instanceIdSnake,
                                   @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly,
                                   @QueryParam("validate_only") @DefaultValue("false") boolean validateOnlySnake,
                                   String body) {
        String id = instanceId != null && !instanceId.isBlank() ? instanceId : instanceIdSnake;
        return json(ProtoJson.print(service.createInstance(project, location, id, body,
                validateOnly || validateOnlySnake)));
    }

    @GET
    @Path("/instances")
    public Response listInstances(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                  @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listInstances(project, location, pageSize, pageToken)));
    }

    @GET
    @Path("/instances/{instanceId}")
    public Response getInstance(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @PathParam("instanceId") String instanceId) {
        return json(ProtoJson.print(service.getInstance(instanceName(project, location, instanceId))));
    }

    @PATCH
    @Path("/instances/{instanceId}")
    public Response updateInstance(@PathParam("project") String project,
                                   @PathParam("location") String location,
                                   @PathParam("instanceId") String instanceId,
                                   @QueryParam("updateMask") String updateMask,
                                   @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly,
                                   @QueryParam("allowMissing") @DefaultValue("false") boolean allowMissing,
                                   String body) {
        return json(ProtoJson.print(service.updateInstance(instanceName(project, location, instanceId), body,
                updateMask, validateOnly, allowMissing)));
    }

    @DELETE
    @Path("/instances/{instanceId}")
    public Response deleteInstance(@PathParam("project") String project,
                                   @PathParam("location") String location,
                                   @PathParam("instanceId") String instanceId,
                                   @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly) {
        return json(ProtoJson.print(service.deleteInstance(instanceName(project, location, instanceId),
                validateOnly)));
    }

    @POST
    @Path("/instances/{instanceId}:start")
    public Response startInstance(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @PathParam("instanceId") String instanceId,
                                  @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly,
                                  String body) {
        StartInstanceRequest request = ProtoJson.merge(body, StartInstanceRequest.newBuilder()).build();
        return json(ProtoJson.print(service.startInstance(instanceName(project, location, instanceId),
                validateOnly || request.getValidateOnly())));
    }

    @POST
    @Path("/instances/{instanceId}:stop")
    public Response stopInstance(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @PathParam("instanceId") String instanceId,
                                 @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly,
                                 String body) {
        StopInstanceRequest request = ProtoJson.merge(body, StopInstanceRequest.newBuilder()).build();
        return json(ProtoJson.print(service.stopInstance(instanceName(project, location, instanceId),
                validateOnly || request.getValidateOnly())));
    }

    @GET
    @Path("/instances/{instanceId}:getIamPolicy")
    public Response getIamPolicy(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @PathParam("instanceId") String instanceId) {
        return json(ProtoJson.print(service.getIamPolicy(instanceName(project, location, instanceId))));
    }

    @POST
    @Path("/instances/{instanceId}:setIamPolicy")
    public Response setIamPolicy(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @PathParam("instanceId") String instanceId,
                                 String body) {
        SetIamPolicyRequest request = ProtoJson.merge(body, SetIamPolicyRequest.newBuilder()).build();
        Policy policy = service.setIamPolicy(instanceName(project, location, instanceId), request.getPolicy());
        return json(ProtoJson.print(policy));
    }

    @POST
    @Path("/instances/{instanceId}:testIamPermissions")
    public Response testIamPermissions(@PathParam("project") String project,
                                       @PathParam("location") String location,
                                       @PathParam("instanceId") String instanceId,
                                       String body) {
        TestIamPermissionsRequest request = ProtoJson.merge(body, TestIamPermissionsRequest.newBuilder()).build();
        return json(ProtoJson.print(service.testIamPermissions(
                instanceName(project, location, instanceId), request.getPermissionsList())));
    }

    private static Response json(String json) {
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }

    private static String instanceName(String project, String location, String instanceId) {
        return "projects/" + project + "/locations/" + location + "/instances/" + instanceId;
    }
}

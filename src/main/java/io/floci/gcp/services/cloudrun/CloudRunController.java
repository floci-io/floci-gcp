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

@Path("/v2/projects/{project}/locations/{location}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudRunController {

    private final CloudRunService service;

    @Inject
    public CloudRunController(CloudRunService service) {
        this.service = service;
    }

    @POST
    @Path("/services")
    public Response createService(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @QueryParam("serviceId") String serviceId,
                                  @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly,
                                  String body) {
        return json(ProtoJson.print(service.createService(project, location, serviceId, body, validateOnly)));
    }

    @GET
    @Path("/services")
    public Response listServices(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                 @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listServices(project, location, pageSize, pageToken)));
    }

    @GET
    @Path("/services/{serviceId}")
    public Response getService(@PathParam("project") String project,
                               @PathParam("location") String location,
                               @PathParam("serviceId") String serviceId) {
        return json(ProtoJson.print(service.getService(serviceName(project, location, serviceId))));
    }

    @PATCH
    @Path("/services/{serviceId}")
    public Response updateService(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @PathParam("serviceId") String serviceId,
                                  @QueryParam("updateMask") String updateMask,
                                  @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly,
                                  String body) {
        return json(ProtoJson.print(service.updateService(
                serviceName(project, location, serviceId), body, updateMask, validateOnly)));
    }

    @DELETE
    @Path("/services/{serviceId}")
    public Response deleteService(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @PathParam("serviceId") String serviceId,
                                  @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly) {
        return json(ProtoJson.print(service.deleteService(serviceName(project, location, serviceId), validateOnly)));
    }

    @GET
    @Path("/services/{serviceId}:getIamPolicy")
    public Response getIamPolicy(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @PathParam("serviceId") String serviceId) {
        return json(ProtoJson.print(service.getIamPolicy(serviceName(project, location, serviceId))));
    }

    @POST
    @Path("/services/{serviceId}:setIamPolicy")
    public Response setIamPolicy(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @PathParam("serviceId") String serviceId,
                                 String body) {
        SetIamPolicyRequest request = ProtoJson.merge(body, SetIamPolicyRequest.newBuilder()).build();
        Policy policy = service.setIamPolicy(serviceName(project, location, serviceId), request.getPolicy());
        return json(ProtoJson.print(policy));
    }

    @POST
    @Path("/services/{serviceId}:testIamPermissions")
    public Response testIamPermissions(@PathParam("project") String project,
                                       @PathParam("location") String location,
                                       @PathParam("serviceId") String serviceId,
                                       String body) {
        TestIamPermissionsRequest request = ProtoJson.merge(body, TestIamPermissionsRequest.newBuilder()).build();
        return json(ProtoJson.print(service.testIamPermissions(request.getPermissionsList())));
    }

    @GET
    @Path("/services/{serviceId}/revisions")
    public Response listRevisions(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @PathParam("serviceId") String serviceId,
                                  @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                  @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listRevisions(
                serviceName(project, location, serviceId), pageSize, pageToken)));
    }

    @GET
    @Path("/services/{serviceId}/revisions/{revisionId}")
    public Response getRevision(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @PathParam("serviceId") String serviceId,
                                @PathParam("revisionId") String revisionId) {
        return json(ProtoJson.print(service.getRevision(
                serviceName(project, location, serviceId) + "/revisions/" + revisionId)));
    }

    private static Response json(String json) {
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }

    private static String serviceName(String project, String location, String serviceId) {
        return "projects/" + project + "/locations/" + location + "/services/" + serviceId;
    }
}

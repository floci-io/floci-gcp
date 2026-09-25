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
 * Cloud Run Admin API v2 Jobs, Executions and Tasks over REST JSON. {@code etag} and {@code showDeleted} are
 * accepted and ignored, as observed on GCP.
 */
@Path("/v2/projects/{project}/locations/{location}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudRunJobsController {

    private final CloudRunJobsService service;

    @Inject
    public CloudRunJobsController(CloudRunJobsService service) {
        this.service = service;
    }

    @POST
    @Path("/jobs")
    public Response createJob(@PathParam("project") String project,
                              @PathParam("location") String location,
                              @QueryParam("jobId") String jobId,
                              @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly,
                              String body) {
        return json(ProtoJson.print(service.createJob(project, location, jobId, body, validateOnly)));
    }

    @GET
    @Path("/jobs")
    public Response listJobs(@PathParam("project") String project,
                             @PathParam("location") String location,
                             @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                             @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listJobs(project, location, pageSize, pageToken)));
    }

    @GET
    @Path("/jobs/{jobId}")
    public Response getJob(@PathParam("project") String project,
                           @PathParam("location") String location,
                           @PathParam("jobId") String jobId) {
        return json(ProtoJson.print(service.getJob(jobName(project, location, jobId))));
    }

    @PATCH
    @Path("/jobs/{jobId}")
    public Response updateJob(@PathParam("project") String project,
                              @PathParam("location") String location,
                              @PathParam("jobId") String jobId,
                              @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly,
                              @QueryParam("allowMissing") @DefaultValue("false") boolean allowMissing,
                              String body) {
        return json(ProtoJson.print(service.updateJob(jobName(project, location, jobId), body, validateOnly,
                allowMissing)));
    }

    @DELETE
    @Path("/jobs/{jobId}")
    public Response deleteJob(@PathParam("project") String project,
                              @PathParam("location") String location,
                              @PathParam("jobId") String jobId,
                              @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly) {
        return json(ProtoJson.print(service.deleteJob(jobName(project, location, jobId), validateOnly)));
    }

    @POST
    @Path("/jobs/{jobId}:run")
    public Response runJob(@PathParam("project") String project,
                           @PathParam("location") String location,
                           @PathParam("jobId") String jobId,
                           String body) {
        return json(ProtoJson.print(service.runJob(jobName(project, location, jobId), body)));
    }

    @GET
    @Path("/jobs/{jobId}:getIamPolicy")
    public Response getIamPolicy(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @PathParam("jobId") String jobId) {
        return json(ProtoJson.print(service.getIamPolicy(jobName(project, location, jobId))));
    }

    @POST
    @Path("/jobs/{jobId}:setIamPolicy")
    public Response setIamPolicy(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @PathParam("jobId") String jobId,
                                 String body) {
        SetIamPolicyRequest request = ProtoJson.merge(body, SetIamPolicyRequest.newBuilder()).build();
        Policy policy = service.setIamPolicy(jobName(project, location, jobId), request.getPolicy());
        return json(ProtoJson.print(policy));
    }

    @POST
    @Path("/jobs/{jobId}:testIamPermissions")
    public Response testIamPermissions(@PathParam("project") String project,
                                       @PathParam("location") String location,
                                       @PathParam("jobId") String jobId,
                                       String body) {
        TestIamPermissionsRequest request = ProtoJson.merge(body, TestIamPermissionsRequest.newBuilder()).build();
        return json(ProtoJson.print(service.testIamPermissions(jobName(project, location, jobId),
                request.getPermissionsList())));
    }

    @GET
    @Path("/jobs/{jobId}/executions")
    public Response listExecutions(@PathParam("project") String project,
                                   @PathParam("location") String location,
                                   @PathParam("jobId") String jobId,
                                   @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                   @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listExecutions(jobName(project, location, jobId), pageSize,
                pageToken)));
    }

    @GET
    @Path("/jobs/{jobId}/executions/{executionId}")
    public Response getExecution(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @PathParam("jobId") String jobId,
                                 @PathParam("executionId") String executionId) {
        return json(ProtoJson.print(service.getExecution(executionName(project, location, jobId, executionId))));
    }

    @DELETE
    @Path("/jobs/{jobId}/executions/{executionId}")
    public Response deleteExecution(@PathParam("project") String project,
                                    @PathParam("location") String location,
                                    @PathParam("jobId") String jobId,
                                    @PathParam("executionId") String executionId,
                                    @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly) {
        return json(ProtoJson.print(service.deleteExecution(executionName(project, location, jobId, executionId),
                validateOnly)));
    }

    @POST
    @Path("/jobs/{jobId}/executions/{executionId}:cancel")
    public Response cancelExecution(@PathParam("project") String project,
                                    @PathParam("location") String location,
                                    @PathParam("jobId") String jobId,
                                    @PathParam("executionId") String executionId,
                                    String body) {
        return json(ProtoJson.print(service.cancelExecution(executionName(project, location, jobId, executionId),
                body)));
    }

    @GET
    @Path("/jobs/{jobId}/executions/{executionId}/tasks")
    public Response listTasks(@PathParam("project") String project,
                              @PathParam("location") String location,
                              @PathParam("jobId") String jobId,
                              @PathParam("executionId") String executionId,
                              @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                              @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listTasks(executionName(project, location, jobId, executionId),
                pageSize, pageToken)));
    }

    @GET
    @Path("/jobs/{jobId}/executions/{executionId}/tasks/{taskId}")
    public Response getTask(@PathParam("project") String project,
                            @PathParam("location") String location,
                            @PathParam("jobId") String jobId,
                            @PathParam("executionId") String executionId,
                            @PathParam("taskId") String taskId) {
        return json(ProtoJson.print(service.getTask(executionName(project, location, jobId, executionId)
                + "/tasks/" + taskId)));
    }

    private static Response json(String json) {
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }

    private static String jobName(String project, String location, String jobId) {
        return "projects/" + project + "/locations/" + location + "/jobs/" + jobId;
    }

    private static String executionName(String project, String location, String jobId, String executionId) {
        return jobName(project, location, jobId) + "/executions/" + executionId;
    }
}

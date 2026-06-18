package io.floci.gcp.services.scheduler;

import com.google.cloud.scheduler.v1.Job;
import com.google.cloud.scheduler.v1.ListJobsResponse;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.services.scheduler.model.StoredJob;
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

import java.util.Arrays;
import java.util.List;

/**
 * REST (JSON) controller for the Cloud Scheduler v1 API, mirroring the {@code google.api.http}
 * mappings in cloudscheduler.proto so gcloud / REST clients work unchanged. The gRPC path
 * ({@link SchedulerController}) is the primary transport; this reuses the same proto&lt;-&gt;model
 * mapping and {@link ProtoJson}. The {@code /jobs} paths don't collide with the other {@code /v1}
 * controllers (KMS {@code /keyRings}, Secret Manager {@code /secrets}).
 */
@Path("/v1/projects/{project}/locations/{location}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SchedulerHttpController {

    @Inject
    SchedulerService service;

    @POST
    @Path("/jobs")
    public Response createJob(@PathParam("project") String project,
            @PathParam("location") String location, String body) {
        Job job = ProtoJson.merge(body, Job.newBuilder()).build();
        StoredJob stored = service.createJob(parent(project, location), SchedulerController.fromJobProto(job));
        return json(SchedulerController.toJobProto(stored));
    }

    @GET
    @Path("/jobs")
    public Response listJobs(@PathParam("project") String project,
            @PathParam("location") String location,
            @QueryParam("pageSize") @DefaultValue("0") int pageSize,
            @QueryParam("pageToken") String pageToken) {
        List<StoredJob> all = service.listJobs(project, location);
        PageToken.Page<StoredJob> page = PageToken.paginate(all, pageSize, pageToken);
        ListJobsResponse.Builder resp = ListJobsResponse.newBuilder();
        for (StoredJob j : page.items()) {
            resp.addJobs(SchedulerController.toJobProto(j));
        }
        if (page.nextPageToken() != null) {
            resp.setNextPageToken(page.nextPageToken());
        }
        return json(resp.build());
    }

    @GET
    @Path("/jobs/{jobId}")
    public Response getJob(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("jobId") String jobId) {
        return json(SchedulerController.toJobProto(service.getJob(jobName(project, location, jobId))));
    }

    @PATCH
    @Path("/jobs/{jobId}")
    public Response updateJob(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("jobId") String jobId,
            @QueryParam("updateMask") String updateMask, String body) {
        Job job = ProtoJson.merge(body, Job.newBuilder().setName(jobName(project, location, jobId))).build();
        List<String> mask = (updateMask == null || updateMask.isBlank())
                ? List.of() : Arrays.asList(updateMask.split(","));
        return json(SchedulerController.toJobProto(service.updateJob(SchedulerController.fromJobProto(job), mask)));
    }

    @DELETE
    @Path("/jobs/{jobId}")
    public Response deleteJob(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("jobId") String jobId) {
        service.deleteJob(jobName(project, location, jobId));
        return Response.ok("{}", MediaType.APPLICATION_JSON).build();
    }

    @POST
    @Path("/jobs/{jobId}:pause")
    public Response pauseJob(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("jobId") String jobId) {
        return json(SchedulerController.toJobProto(service.pauseJob(jobName(project, location, jobId))));
    }

    @POST
    @Path("/jobs/{jobId}:resume")
    public Response resumeJob(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("jobId") String jobId) {
        return json(SchedulerController.toJobProto(service.resumeJob(jobName(project, location, jobId))));
    }

    @POST
    @Path("/jobs/{jobId}:run")
    public Response runJob(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("jobId") String jobId) {
        return json(SchedulerController.toJobProto(service.runJob(jobName(project, location, jobId))));
    }

    private static Response json(com.google.protobuf.MessageOrBuilder message) {
        return Response.ok(ProtoJson.print(message), MediaType.APPLICATION_JSON).build();
    }

    private static String parent(String project, String location) {
        return "projects/" + project + "/locations/" + location;
    }

    private static String jobName(String project, String location, String jobId) {
        return parent(project, location) + "/jobs/" + jobId;
    }
}

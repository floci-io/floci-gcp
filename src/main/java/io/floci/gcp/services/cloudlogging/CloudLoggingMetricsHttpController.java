package io.floci.gcp.services.cloudlogging;

import com.google.logging.v2.LogMetric;
import io.floci.gcp.core.common.ProtoJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Logging v2 REST {@code projects.metrics}; a metric identifier may contain {@code /}, sent as {@code %2F}. */
@Path("/v2/projects/{project}/metrics")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudLoggingMetricsHttpController {

    private final CloudLoggingMetricService service;

    @Inject
    public CloudLoggingMetricsHttpController(CloudLoggingMetricService service) {
        this.service = service;
    }

    @POST
    public Response createLogMetric(@PathParam("project") String project, String body) {
        LogMetric metric = ProtoJson.merge(body, LogMetric.newBuilder()).build();
        return json(ProtoJson.print(service.createLogMetric("projects/" + project, metric)));
    }

    @GET
    public Response listLogMetrics(@PathParam("project") String project,
            @QueryParam("pageSize") @DefaultValue("0") int pageSize, @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listLogMetrics("projects/" + project, pageSize, pageToken)));
    }

    @GET
    @Path("/{metricId: .+}")
    public Response getLogMetric(@PathParam("project") String project, @PathParam("metricId") String metricId) {
        return json(ProtoJson.print(service.getLogMetric("projects/" + project + "/metrics/" + metricId)));
    }

    @PUT
    @Path("/{metricId: .+}")
    public Response updateLogMetric(@PathParam("project") String project, @PathParam("metricId") String metricId,
            String body) {
        LogMetric metric = ProtoJson.merge(body, LogMetric.newBuilder()).build();
        return json(ProtoJson.print(service.updateLogMetric("projects/" + project + "/metrics/" + metricId, metric)));
    }

    @DELETE
    @Path("/{metricId: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteLogMetric(@PathParam("project") String project, @PathParam("metricId") String metricId) {
        service.deleteLogMetric("projects/" + project + "/metrics/" + metricId);
        return json("{}");
    }

    private static Response json(String body) {
        return Response.ok(body, MediaType.APPLICATION_JSON).build();
    }
}

package io.floci.gcp.services.cloudmonitoring;

import com.google.api.MetricDescriptor;
import com.google.api.MonitoredResourceDescriptor;
import com.google.monitoring.v3.*;
import com.google.protobuf.Empty;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProtoJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

@Path("/v3/projects/{project}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudMonitoringHttpController {

    private static final Logger LOG = Logger.getLogger(CloudMonitoringHttpController.class);

    private final CloudMonitoringService service;

    @Inject
    public CloudMonitoringHttpController(CloudMonitoringService service) {
        this.service = service;
    }

    @GET
    @Path("/monitoredResourceDescriptors")
    public Response listMonitoredResourceDescriptors(@PathParam("project") String project,
                                                     @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                                     @QueryParam("pageToken") String pageToken) {
        LOG.debugf("HTTP GET listMonitoredResourceDescriptors project=%s", project);
        PageToken.Page<MonitoredResourceDescriptor> page = service.listMonitoredResourceDescriptors(
                "projects/" + project, pageSize, pageToken);
        ListMonitoredResourceDescriptorsResponse.Builder response = ListMonitoredResourceDescriptorsResponse.newBuilder()
                .addAllResourceDescriptors(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return json(ProtoJson.print(response.build()));
    }

    @GET
    @Path("/monitoredResourceDescriptors/{resourceType:.*}")
    public Response getMonitoredResourceDescriptor(@PathParam("project") String project,
                                                   @PathParam("resourceType") String resourceType) {
        LOG.debugf("HTTP GET getMonitoredResourceDescriptor project=%s type=%s", project, resourceType);
        MonitoredResourceDescriptor desc = service.getMonitoredResourceDescriptor(
                "projects/" + project + "/monitoredResourceDescriptors/" + resourceType);
        return json(ProtoJson.print(desc));
    }

    @GET
    @Path("/metricDescriptors")
    public Response listMetricDescriptors(@PathParam("project") String project,
                                          @QueryParam("filter") String filter,
                                          @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                          @QueryParam("pageToken") String pageToken) {
        LOG.debugf("HTTP GET listMetricDescriptors project=%s filter=%s", project, filter);
        PageToken.Page<MetricDescriptor> page = service.listMetricDescriptors(
                "projects/" + project, filter, pageSize, pageToken);
        ListMetricDescriptorsResponse.Builder response = ListMetricDescriptorsResponse.newBuilder()
                .addAllMetricDescriptors(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return json(ProtoJson.print(response.build()));
    }

    @POST
    @Path("/metricDescriptors")
    public Response createMetricDescriptor(@PathParam("project") String project, String body) {
        LOG.debugf("HTTP POST createMetricDescriptor project=%s", project);
        MetricDescriptor req = ProtoJson.merge(body, MetricDescriptor.newBuilder()).build();
        MetricDescriptor created = service.createMetricDescriptor("projects/" + project, req);
        return json(ProtoJson.print(created));
    }

    @GET
    @Path("/metricDescriptors/{metricDescriptorId:.*}")
    public Response getMetricDescriptor(@PathParam("project") String project,
                                        @PathParam("metricDescriptorId") String metricDescriptorId) {
        LOG.debugf("HTTP GET getMetricDescriptor project=%s id=%s", project, metricDescriptorId);
        MetricDescriptor desc = service.getMetricDescriptor(
                "projects/" + project + "/metricDescriptors/" + metricDescriptorId);
        return json(ProtoJson.print(desc));
    }

    @DELETE
    @Path("/metricDescriptors/{metricDescriptorId:.*}")
    public Response deleteMetricDescriptor(@PathParam("project") String project,
                                           @PathParam("metricDescriptorId") String metricDescriptorId) {
        LOG.debugf("HTTP DELETE deleteMetricDescriptor project=%s id=%s", project, metricDescriptorId);
        service.deleteMetricDescriptor("projects/" + project + "/metricDescriptors/" + metricDescriptorId);
        return json(ProtoJson.print(Empty.getDefaultInstance()));
    }

    @POST
    @Path("/timeSeries")
    public Response createTimeSeries(@PathParam("project") String project, String body) {
        LOG.debugf("HTTP POST createTimeSeries project=%s", project);
        CreateTimeSeriesRequest req = ProtoJson.merge(body, CreateTimeSeriesRequest.newBuilder()).build();
        service.createTimeSeries("projects/" + project, req.getTimeSeriesList());
        return json(ProtoJson.print(Empty.getDefaultInstance()));
    }

    @GET
    @Path("/timeSeries")
    public Response listTimeSeries(@PathParam("project") String project,
                                   @QueryParam("filter") String filter,
                                   @QueryParam("interval.startTime") String intervalStartTime,
                                   @QueryParam("interval.endTime") String intervalEndTime,
                                   @QueryParam("aggregation.alignmentPeriod") String alignmentPeriod,
                                   @QueryParam("aggregation.perSeriesAligner") String perSeriesAligner,
                                   @QueryParam("aggregation.crossSeriesReducer") String crossSeriesReducer,
                                   @QueryParam("aggregation.groupByFields") List<String> groupByFields,
                                   @QueryParam("view") @DefaultValue("FULL") String view,
                                   @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                   @QueryParam("pageToken") String pageToken) {
        LOG.debugf("HTTP GET listTimeSeries project=%s filter=%s", project, filter);

        TimeInterval.Builder interval = TimeInterval.newBuilder();
        if (intervalStartTime != null && !intervalStartTime.isBlank()) {
            interval.setStartTime(fromIso(intervalStartTime));
        }
        if (intervalEndTime != null && !intervalEndTime.isBlank()) {
            interval.setEndTime(fromIso(intervalEndTime));
        }

        PageToken.Page<TimeSeries> page = service.listTimeSeries(
                "projects/" + project,
                filter,
                interval.build(),
                aggregation(alignmentPeriod, perSeriesAligner, crossSeriesReducer, groupByFields),
                view,
                pageSize,
                pageToken
        );

        ListTimeSeriesResponse.Builder response = ListTimeSeriesResponse.newBuilder()
                .addAllTimeSeries(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return json(ProtoJson.print(response.build()));
    }

    private static Aggregation aggregation(String alignmentPeriod, String perSeriesAligner,
                                           String crossSeriesReducer, List<String> groupByFields) {
        Aggregation.Builder builder = Aggregation.newBuilder();
        if (alignmentPeriod != null && !alignmentPeriod.isBlank()) {
            builder.setAlignmentPeriod(parseDuration(alignmentPeriod));
        }
        if (perSeriesAligner != null && !perSeriesAligner.isBlank()) {
            builder.setPerSeriesAligner(parseEnum(Aggregation.Aligner.class, perSeriesAligner,
                    "aggregation.perSeriesAligner"));
        }
        if (crossSeriesReducer != null && !crossSeriesReducer.isBlank()) {
            builder.setCrossSeriesReducer(parseEnum(Aggregation.Reducer.class, crossSeriesReducer,
                    "aggregation.crossSeriesReducer"));
        }
        if (groupByFields != null) {
            builder.addAllGroupByFields(groupByFields);
        }
        return builder.build();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String param) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw GcpException.invalidArgument("Invalid " + param + ": " + value);
        }
    }

    private static com.google.protobuf.Duration parseDuration(String value) {
        try {
            String seconds = value.endsWith("s") ? value.substring(0, value.length() - 1) : value;
            return com.google.protobuf.Duration.newBuilder()
                    .setSeconds(Long.parseLong(seconds))
                    .build();
        } catch (NumberFormatException e) {
            throw GcpException.invalidArgument("Invalid aggregation.alignmentPeriod: " + value);
        }
    }

    private static Response json(String json) {
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }

    private static com.google.protobuf.Timestamp fromIso(String iso) {
        try {
            java.time.Instant instant = java.time.Instant.parse(iso);
            return com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(instant.getEpochSecond())
                    .setNanos(instant.getNano())
                    .build();
        } catch (Exception e) {
            return com.google.protobuf.Timestamp.getDefaultInstance();
        }
    }
}

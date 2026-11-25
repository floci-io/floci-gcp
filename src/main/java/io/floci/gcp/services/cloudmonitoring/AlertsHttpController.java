package io.floci.gcp.services.cloudmonitoring;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Monitoring v3 REST {@code projects.alerts}, which has no gRPC counterpart in the published protos. */
@Path("/v3/projects/{project}/alerts")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class AlertsHttpController {

    private final AlertService alerts;

    @Inject
    public AlertsHttpController(AlertService alerts) {
        this.alerts = alerts;
    }

    @GET
    public Response listAlerts(@PathParam("project") String project, @QueryParam("filter") String filter,
            @QueryParam("orderBy") String orderBy, @QueryParam("pageSize") @DefaultValue("0") int pageSize,
            @QueryParam("pageToken") String pageToken) {
        return Response.ok(alerts.listAlerts("projects/" + project, filter, orderBy, pageSize, pageToken)).build();
    }

    @GET
    @Path("/{alert}")
    public Response getAlert(@PathParam("project") String project, @PathParam("alert") String alert) {
        return Response.ok(alerts.getAlert("projects/" + project + "/alerts/" + alert)).build();
    }
}

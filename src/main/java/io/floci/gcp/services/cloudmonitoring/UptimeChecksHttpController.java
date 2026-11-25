package io.floci.gcp.services.cloudmonitoring;

import com.google.monitoring.v3.UptimeCheckConfig;
import com.google.protobuf.util.FieldMaskUtil;
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

/** Monitoring v3 REST {@code projects.uptimeCheckConfigs}. */
@Path("/v3/projects/{project}/uptimeCheckConfigs")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UptimeChecksHttpController {

    private final UptimeCheckService checks;

    @Inject
    public UptimeChecksHttpController(UptimeCheckService checks) {
        this.checks = checks;
    }

    @GET
    public Response listUptimeCheckConfigs(@PathParam("project") String project,
            @QueryParam("pageSize") @DefaultValue("0") int pageSize, @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(checks.list("projects/" + project, pageSize, pageToken)));
    }

    @POST
    public Response createUptimeCheckConfig(@PathParam("project") String project, String body) {
        UptimeCheckConfig check = ProtoJson.merge(body, UptimeCheckConfig.newBuilder()).build();
        return json(ProtoJson.print(checks.create("projects/" + project, check)));
    }

    @GET
    @Path("/{check}")
    public Response getUptimeCheckConfig(@PathParam("project") String project, @PathParam("check") String check) {
        return json(ProtoJson.print(checks.get(name(project, check))));
    }

    @PATCH
    @Path("/{check}")
    public Response updateUptimeCheckConfig(@PathParam("project") String project, @PathParam("check") String check,
            @QueryParam("updateMask") String updateMask, String body) {
        UptimeCheckConfig requested = ProtoJson.merge(body, UptimeCheckConfig.newBuilder()).setName(name(project, check)).build();
        return json(ProtoJson.print(checks.update(requested,
                FieldMaskUtil.fromJsonString(updateMask == null ? "" : updateMask))));
    }

    @DELETE
    @Path("/{check}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteUptimeCheckConfig(@PathParam("project") String project, @PathParam("check") String check) {
        checks.delete(name(project, check));
        return json("{}");
    }

    private static String name(String project, String check) {
        return "projects/" + project + "/uptimeCheckConfigs/" + check;
    }

    private static Response json(String body) {
        return Response.ok(body, MediaType.APPLICATION_JSON).build();
    }
}

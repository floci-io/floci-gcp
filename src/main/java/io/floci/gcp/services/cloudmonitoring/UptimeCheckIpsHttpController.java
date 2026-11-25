package io.floci.gcp.services.cloudmonitoring;

import io.floci.gcp.core.common.ProtoJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Monitoring v3 REST {@code uptimeCheckIps.list}. */
@Path("/v3/uptimeCheckIps")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class UptimeCheckIpsHttpController {

    private final UptimeCheckService checks;

    @Inject
    public UptimeCheckIpsHttpController(UptimeCheckService checks) {
        this.checks = checks;
    }

    @GET
    public Response listUptimeCheckIps(@QueryParam("pageSize") @DefaultValue("0") int pageSize,
            @QueryParam("pageToken") String pageToken) {
        return Response.ok(ProtoJson.print(checks.listIps(pageSize, pageToken)), MediaType.APPLICATION_JSON).build();
    }
}

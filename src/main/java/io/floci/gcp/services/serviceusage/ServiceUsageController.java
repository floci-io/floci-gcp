package io.floci.gcp.services.serviceusage;

import io.floci.gcp.core.common.ProtoJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/v1/projects/{project}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServiceUsageController {

    private final ServiceUsageService service;

    @Inject
    public ServiceUsageController(ServiceUsageService service) {
        this.service = service;
    }

    @POST
    @Path("/services/{service}:enable")
    public Response enable(@PathParam("project") String project,
                           @PathParam("service") String serviceId,
                           String body) {
        return json(ProtoJson.print(service.enable(project, serviceId)));
    }

    @POST
    @Path("/services/{service}:disable")
    public Response disable(@PathParam("project") String project,
                            @PathParam("service") String serviceId,
                            String body) {
        return json(ProtoJson.print(service.disable(project, serviceId)));
    }

    @GET
    @Path("/services/{service}")
    public Response get(@PathParam("project") String project,
                        @PathParam("service") String serviceId) {
        return json(ProtoJson.print(service.get(project, serviceId)));
    }

    @GET
    @Path("/services")
    public Response list(@PathParam("project") String project,
                         @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                         @QueryParam("pageToken") String pageToken,
                         @QueryParam("filter") String filter) {
        return json(ProtoJson.print(service.list(project, pageSize, pageToken, filter)));
    }

    @POST
    @Path("/services:batchEnable")
    public Response batchEnable(@PathParam("project") String project, String body) {
        return json(ProtoJson.print(service.batchEnable(project, body)));
    }

    @GET
    @Path("/services:batchGet")
    public Response batchGet(@PathParam("project") String project,
                             @QueryParam("names") List<String> names) {
        return json(ProtoJson.print(service.batchGet(project, names)));
    }

    private static Response json(String json) {
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }
}

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

/**
 * The Service Usage v1beta1 {@code services} methods. They share state with the v1 methods.
 * {@code generateServiceIdentity} exists only in v1beta1, so it is not served under {@code /v1},
 * as in GCP, and {@code batchGet} exists only in v1, so it is not served here.
 * Unlike v1, enable, disable and batchEnable complete with a {@code google.protobuf.Empty} response.
 *
 * <p>The class path stops at {@code /v1beta1/projects/{project}} rather than claiming all of
 * {@code /v1beta1}, so a Cloud Storage bucket named {@code v1beta1} stays reachable over the
 * XML API.
 */
@Path("/v1beta1/projects/{project}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServiceUsageBetaController {

    private final ServiceUsageService service;

    @Inject
    public ServiceUsageBetaController(ServiceUsageService service) {
        this.service = service;
    }

    @POST
    @Path("/services/{service}:enable")
    public Response enable(@PathParam("project") String project,
                           @PathParam("service") String serviceId) {
        return json(ProtoJson.print(service.enableBeta(project, serviceId)));
    }

    @POST
    @Path("/services/{service}:disable")
    public Response disable(@PathParam("project") String project,
                            @PathParam("service") String serviceId) {
        return json(ProtoJson.print(service.disableBeta(project, serviceId)));
    }

    @GET
    @Path("/services/{service}")
    public Response get(@PathParam("project") String project,
                        @PathParam("service") String serviceId) {
        return json(ProtoJson.print(service.getBeta(project, serviceId)));
    }

    @GET
    @Path("/services")
    public Response list(@PathParam("project") String project,
                         @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                         @QueryParam("pageToken") String pageToken,
                         @QueryParam("filter") String filter) {
        return json(ProtoJson.print(service.listBeta(project, pageSize, pageToken, filter)));
    }

    @POST
    @Path("/services:batchEnable")
    public Response batchEnable(@PathParam("project") String project, String body) {
        return json(ProtoJson.print(service.batchEnableBeta(project, body)));
    }

    // The request has no body fields; the Java SDK posts an empty form-encoded body.
    @POST
    @Path("/services/{service}:generateServiceIdentity")
    @Consumes(MediaType.WILDCARD)
    public Response generateServiceIdentity(@PathParam("project") String project,
                                            @PathParam("service") String serviceId) {
        return json(ProtoJson.print(service.generateServiceIdentity(project, serviceId)));
    }

    private static Response json(String json) {
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }
}

package io.floci.gcp.services.cloudbilling;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** Cloud Billing v1 REST: {@code projects.getBillingInfo} and {@code projects.updateBillingInfo}. */
@Path("/v1/projects/{project}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudBillingController {

    private final CloudBillingService service;

    @Inject
    public CloudBillingController(CloudBillingService service) {
        this.service = service;
    }

    @GET
    @Path("/billingInfo")
    public Response getBillingInfo(@PathParam("project") String project) {
        return Response.ok(service.getBillingInfo(project)).build();
    }

    @PUT
    @Path("/billingInfo")
    public Response updateBillingInfo(@PathParam("project") String project, Map<String, Object> body) {
        return Response.ok(service.updateBillingInfo(project, body)).build();
    }
}

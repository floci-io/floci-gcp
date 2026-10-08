package io.floci.gcp.services.cloudbilling;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** Cloud Billing v1 REST: {@code billingAccounts.get}, {@code billingAccounts.list} and {@code billingAccounts.projects.list}. */
@Path("/v1/billingAccounts")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudBillingAccountsController {

    private final CloudBillingService service;

    @Inject
    public CloudBillingAccountsController(CloudBillingService service) {
        this.service = service;
    }

    @GET
    public Response list(@QueryParam("pageSize") @DefaultValue("0") int pageSize,
                         @QueryParam("pageToken") String pageToken) {
        return Response.ok(service.listBillingAccounts(pageSize, pageToken)).build();
    }

    @GET
    @Path("/{account}")
    public Response get(@PathParam("account") String account) {
        return Response.ok(service.getBillingAccount(account)).build();
    }

    @GET
    @Path("/{account}/projects")
    public Response listProjects(@PathParam("account") String account,
                                 @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                 @QueryParam("pageToken") String pageToken) {
        return Response.ok(service.listProjects(account, pageSize, pageToken)).build();
    }
}

package io.floci.gcp.services.cloudbilling;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
import java.util.Map;

@Path("/v1/billingAccounts")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class CloudBillingAccountsController {

    private final CloudBillingService service;

    @Inject
    public CloudBillingAccountsController(CloudBillingService service) {
        this.service = service;
    }

    @GET
    public Response listBillingAccounts(@QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                        @QueryParam("pageToken") String pageToken) {
        return Response.ok(service.listBillingAccounts(pageSize, pageToken)).build();
    }

    @GET
    @Path("/{account}")
    public Response getBillingAccount(@PathParam("account") String account) {
        return Response.ok(service.getBillingAccount(account)).build();
    }

    @GET
    @Path("/{account}/projects")
    public Response listProjectBillingInfo(@PathParam("account") String account,
                                           @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                           @QueryParam("pageToken") String pageToken) {
        return Response.ok(service.listProjectBillingInfo(account, pageSize, pageToken)).build();
    }

    @POST
    @Path("/{account}:testIamPermissions")
    @SuppressWarnings("unchecked")
    public Response testIamPermissions(Map<String, Object> body) {
        Object permissions = body != null ? body.get("permissions") : null;
        return Response.ok(service.testIamPermissions(
                permissions instanceof List<?> list ? (List<String>) list : List.of())).build();
    }
}

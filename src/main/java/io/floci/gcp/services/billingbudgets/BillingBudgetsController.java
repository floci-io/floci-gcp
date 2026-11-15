package io.floci.gcp.services.billingbudgets;

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

import java.util.Map;

@Path("/v1/billingAccounts/{account}/budgets")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BillingBudgetsController {

    private final BillingBudgetsService service;

    @Inject
    public BillingBudgetsController(BillingBudgetsService service) {
        this.service = service;
    }

    @POST
    public Response createBudget(@PathParam("account") String account, Map<String, Object> body) {
        return Response.ok(service.createBudget(account, body)).build();
    }

    @GET
    public Response listBudgets(@PathParam("account") String account,
                                @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                @QueryParam("pageToken") String pageToken,
                                @QueryParam("scope") String scope) {
        return Response.ok(service.listBudgets(account, pageSize, pageToken, scope)).build();
    }

    @GET
    @Path("/{budget}")
    public Response getBudget(@PathParam("account") String account, @PathParam("budget") String budget) {
        return Response.ok(service.getBudget(account, budget)).build();
    }

    @PATCH
    @Path("/{budget}")
    public Response updateBudget(@PathParam("account") String account, @PathParam("budget") String budget,
                                 @QueryParam("updateMask") String updateMask, Map<String, Object> body) {
        return Response.ok(service.updateBudget(account, budget, updateMask, body)).build();
    }

    @DELETE
    @Path("/{budget}")
    public Response deleteBudget(@PathParam("account") String account, @PathParam("budget") String budget) {
        return Response.ok(service.deleteBudget(account, budget)).build();
    }
}

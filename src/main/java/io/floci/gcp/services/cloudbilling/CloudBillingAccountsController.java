package io.floci.gcp.services.cloudbilling;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
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

    @POST
    @Path("/{account}:testIamPermissions")
    @SuppressWarnings("unchecked")
    public Response testIamPermissions(Map<String, Object> body) {
        Object permissions = body != null ? body.get("permissions") : null;
        return Response.ok(service.testIamPermissions(
                permissions instanceof List<?> list ? (List<String>) list : List.of())).build();
    }
}

package io.floci.gcp.services.iamcredentials;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/v1/projects/-/serviceAccounts")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IamCredentialsController {

    private final IamCredentialsService service;

    @Inject
    public IamCredentialsController(IamCredentialsService service) {
        this.service = service;
    }

    @POST
    @Path("/{serviceAccount}:generateAccessToken")
    public Response generateAccessToken(@PathParam("serviceAccount") String serviceAccount,
            Map<String, Object> body) {
        List<?> scopes = body != null && body.get("scope") instanceof List<?> scopeList
                ? scopeList
                : null;
        IamCredentialsService.GeneratedAccessToken token =
                service.generateAccessToken(serviceAccount, scopes, body != null ? body.get("lifetime") : null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accessToken", token.accessToken());
        response.put("expireTime", token.expireTime().toString());
        return Response.ok(response).build();
    }
}

package io.floci.gcp.services.iamcredentials;

import io.floci.gcp.services.iam.IamService;
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

@Path("/v1/projects")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IamCredentialsController {

    private final IamCredentialsService service;
    private final IamService iamService;

    @Inject
    public IamCredentialsController(IamCredentialsService service, IamService iamService) {
        this.service = service;
        this.iamService = iamService;
    }

    @POST
    @Path("/-/serviceAccounts/{serviceAccount}:generateAccessToken")
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

    @POST
    @Path("/-/serviceAccounts/{serviceAccount}:generateIdToken")
    public Response generateIdToken(@PathParam("serviceAccount") String serviceAccount, Map<String, Object> body) {
        Object audience = body != null ? body.get("audience") : null;
        boolean includeEmail = body != null && Boolean.TRUE.equals(body.get("includeEmail"));
        return Response.ok(service.generateIdToken(serviceAccount, audience instanceof String s ? s : null,
                includeEmail)).build();
    }

    /**
     * iamcredentials {@code signBlob} and IAM v1 {@code serviceAccounts.signBlob} share this path when
     * the project is {@code -}. The request body tells them apart: iamcredentials sends {@code payload},
     * IAM v1 sends {@code bytesToSign}.
     */
    @POST
    @Path("/-/serviceAccounts/{serviceAccount}:signBlob")
    public Response signBlob(@PathParam("serviceAccount") String serviceAccount, Map<String, Object> body) {
        if (body != null && !body.containsKey("payload") && body.get("bytesToSign") instanceof String bytesToSign) {
            return Response.ok(iamService.signBlob("-", serviceAccount, bytesToSign)).build();
        }
        Object payload = body != null ? body.get("payload") : null;
        return Response.ok(service.signBlob(serviceAccount, payload instanceof String s ? s : null)).build();
    }

    @POST
    @Path("/-/serviceAccounts/{serviceAccount}:signJwt")
    public Response signJwt(@PathParam("serviceAccount") String serviceAccount, Map<String, Object> body) {
        Object payload = body != null ? body.get("payload") : null;
        return Response.ok(service.signJwt(serviceAccount, payload instanceof String s ? s : null)).build();
    }
}

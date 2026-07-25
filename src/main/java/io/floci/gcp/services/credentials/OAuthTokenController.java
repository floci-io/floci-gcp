package io.floci.gcp.services.credentials;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
@Path("/token")
@Produces(MediaType.APPLICATION_JSON)
public class OAuthTokenController {

    private static final String JWT_BEARER_GRANT_TYPE =
            "urn:ietf:params:oauth:grant-type:jwt-bearer";

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response exchangeServiceAccountJwt(
            @FormParam("grant_type") String grantType,
            @FormParam("assertion") String assertion) {
        if (!JWT_BEARER_GRANT_TYPE.equals(grantType)) {
            return oauthError("unsupported_grant_type", "Unsupported grant type");
        }
        if (assertion == null || assertion.isBlank()) {
            return oauthError("invalid_grant", "Invalid JWT bearer token request");
        }

        return Response.ok(Map.of(
                "access_token", "floci-gcp-" + UUID.randomUUID(),
                "token_type", "Bearer",
                "expires_in", 3600))
                .build();
    }

    private static Response oauthError(String error, String description) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("error_description", description);
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(body)
                .build();
    }
}

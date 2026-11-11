package io.floci.gcp.services.credentials;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.Map;

@ApplicationScoped
@Path("/token")
@Produces(MediaType.APPLICATION_JSON)
public class OAuthTokenController {

    private final OAuthTokenService tokens;

    @Inject
    public OAuthTokenController(OAuthTokenService tokens) {
        this.tokens = tokens;
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response exchangeServiceAccountJwt(
            @FormParam("grant_type") String grantType,
            @FormParam("assertion") String assertion,
            @Context UriInfo uri) {
        return tokens.exchange(grantType, assertion, requestUrl(uri));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response exchangeServiceAccountJwtJson(Map<String, Object> body, @Context UriInfo uri) {
        return exchangeJson(tokens, body, uri);
    }

    static Response exchangeJson(OAuthTokenService tokens, Map<String, Object> body, UriInfo uri) {
        Map<String, Object> safe = body == null ? Map.of() : body;
        return tokens.exchange(stringOrNull(safe.get("grant_type")), stringOrNull(safe.get("assertion")),
                requestUrl(uri));
    }

    static String requestUrl(UriInfo uri) {
        return uri.getAbsolutePath().toString();
    }

    private static String stringOrNull(Object value) {
        return value instanceof String s ? s : null;
    }
}

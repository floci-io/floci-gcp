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

/**
 * The legacy {@code https://www.googleapis.com/oauth2/v4/token} endpoint, still served by Google
 * and still used by clients that predate {@code oauth2.googleapis.com/token}. Same exchange as
 * {@link OAuthTokenController}.
 */
@ApplicationScoped
@Path("/oauth2/v4/token")
@Produces(MediaType.APPLICATION_JSON)
public class OAuthTokenV4Controller {

    private final OAuthTokenService tokens;

    @Inject
    public OAuthTokenV4Controller(OAuthTokenService tokens) {
        this.tokens = tokens;
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response exchangeServiceAccountJwt(
            @FormParam("grant_type") String grantType,
            @FormParam("assertion") String assertion,
            @Context UriInfo uri) {
        return tokens.exchange(grantType, assertion, OAuthTokenController.requestUrl(uri));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response exchangeServiceAccountJwtJson(Map<String, Object> body, @Context UriInfo uri) {
        return OAuthTokenController.exchangeJson(tokens, body, uri);
    }
}

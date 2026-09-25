package io.floci.gcp.services.credentials;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response exchangeServiceAccountJwt(
            @FormParam("grant_type") String grantType,
            @FormParam("assertion") String assertion) {
        return OAuthTokenController.exchange(grantType, assertion);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response exchangeServiceAccountJwtJson(Map<String, Object> body) {
        return OAuthTokenController.exchangeJson(body);
    }
}

package io.floci.gcp.services.firebaseauth;

import io.floci.gcp.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** Secure Token refresh endpoint; accepts both form-urlencoded (SDK default) and JSON. */
@Path("/securetoken.googleapis.com/v1")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class SecureTokenController {

    private final FirebaseAuthService service;
    private final EmulatorConfig config;

    @Inject
    public SecureTokenController(FirebaseAuthService service, EmulatorConfig config) {
        this.service = service;
        this.config = config;
    }

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response tokenForm(@FormParam("grant_type") String grantType,
                              @FormParam("grantType") String grantTypeCamel,
                              @FormParam("refresh_token") String refreshToken,
                              @FormParam("refreshToken") String refreshTokenCamel) {
        return json(service.grantToken(config.defaultProjectId(),
                first(grantType, grantTypeCamel), first(refreshToken, refreshTokenCamel)));
    }

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tokenJson(Map<String, Object> body) {
        Map<String, Object> safe = body == null ? Map.of() : body;
        return json(service.grantToken(config.defaultProjectId(),
                first(str(safe.get("grant_type")), str(safe.get("grantType"))),
                first(str(safe.get("refresh_token")), str(safe.get("refreshToken")))));
    }

    private static String first(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Response json(Object entity) {
        return Response.ok(entity, MediaType.APPLICATION_JSON).build();
    }
}

package io.floci.gcp.services.firebaseauth;

import io.floci.gcp.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Identity Toolkit v1 REST surface, mounted at the same paths the Firebase Auth
 * emulator serves: the API hostname is a path prefix (SDKs with
 * FIREBASE_AUTH_EMULATOR_HOST prepend it), bare /v1 is intentionally not served.
 */
@Path("/identitytoolkit.googleapis.com/v1")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FirebaseAuthController {

    private final FirebaseAuthService service;
    private final EmulatorConfig config;

    @Inject
    public FirebaseAuthController(FirebaseAuthService service, EmulatorConfig config) {
        this.service = service;
        this.config = config;
    }

    // ── client endpoints (project inferred from API key → default project) ────

    @POST
    @Path("/accounts:signUp")
    public Response signUp(@HeaderParam("Authorization") String authorization,
                           @QueryParam("key") String apiKey,
                           @HeaderParam("x-goog-api-key") String apiKeyHeader,
                           Map<String, Object> body) {
        boolean privileged = requireClientOrPrivileged(authorization, apiKey, apiKeyHeader);
        return json(service.signUp(defaultProject(), safe(body), privileged));
    }

    @POST
    @Path("/accounts:signInWithPassword")
    public Response signInWithPassword(@HeaderParam("Authorization") String authorization,
                                       @QueryParam("key") String apiKey,
                                       @HeaderParam("x-goog-api-key") String apiKeyHeader,
                                       Map<String, Object> body) {
        requireClientOrPrivileged(authorization, apiKey, apiKeyHeader);
        return json(service.signInWithPassword(defaultProject(), safe(body)));
    }

    @POST
    @Path("/accounts:signInWithCustomToken")
    public Response signInWithCustomToken(@HeaderParam("Authorization") String authorization,
                                          @QueryParam("key") String apiKey,
                                          @HeaderParam("x-goog-api-key") String apiKeyHeader,
                                          Map<String, Object> body) {
        requireClientOrPrivileged(authorization, apiKey, apiKeyHeader);
        return json(service.signInWithCustomToken(defaultProject(), safe(body)));
    }

    @POST
    @Path("/accounts:lookup")
    public Response lookup(@HeaderParam("Authorization") String authorization,
                           @QueryParam("key") String apiKey,
                           @HeaderParam("x-goog-api-key") String apiKeyHeader,
                           Map<String, Object> body) {
        boolean privileged = requireClientOrPrivileged(authorization, apiKey, apiKeyHeader);
        return json(service.lookup(defaultProject(), safe(body), privileged));
    }

    @POST
    @Path("/accounts:update")
    public Response update(@HeaderParam("Authorization") String authorization,
                           @QueryParam("key") String apiKey,
                           @HeaderParam("x-goog-api-key") String apiKeyHeader,
                           Map<String, Object> body) {
        boolean privileged = requireClientOrPrivileged(authorization, apiKey, apiKeyHeader);
        return json(service.update(defaultProject(), safe(body), privileged));
    }

    @POST
    @Path("/accounts:delete")
    public Response delete(@HeaderParam("Authorization") String authorization,
                           @QueryParam("key") String apiKey,
                           @HeaderParam("x-goog-api-key") String apiKeyHeader,
                           Map<String, Object> body) {
        boolean privileged = requireClientOrPrivileged(authorization, apiKey, apiKeyHeader);
        return json(service.delete(defaultProject(), safe(body), privileged));
    }

    // ── admin endpoints (firebase-admin SDK, project in path) ─────────────────

    @POST
    @Path("/projects/{project}/accounts")
    public Response adminSignUp(@HeaderParam("Authorization") String authorization,
                                @PathParam("project") String project,
                                Map<String, Object> body) {
        requirePrivileged(authorization);
        return json(service.signUp(project, safe(body), true));
    }

    @POST
    @Path("/projects/{project}/accounts:lookup")
    public Response adminLookup(@HeaderParam("Authorization") String authorization,
                                @PathParam("project") String project,
                                Map<String, Object> body) {
        requirePrivileged(authorization);
        return json(service.lookup(project, safe(body), true));
    }

    @POST
    @Path("/projects/{project}/accounts:update")
    public Response adminUpdate(@HeaderParam("Authorization") String authorization,
                                @PathParam("project") String project,
                                Map<String, Object> body) {
        requirePrivileged(authorization);
        return json(service.update(project, safe(body), true));
    }

    @POST
    @Path("/projects/{project}/accounts:delete")
    public Response adminDelete(@HeaderParam("Authorization") String authorization,
                                @PathParam("project") String project,
                                Map<String, Object> body) {
        requirePrivileged(authorization);
        return json(service.delete(project, safe(body), true));
    }

    @GET
    @Path("/projects/{project}/accounts:batchGet")
    public Response batchGet(@HeaderParam("Authorization") String authorization,
                             @PathParam("project") String project,
                             @QueryParam("maxResults") @DefaultValue("0") int maxResults,
                             @QueryParam("nextPageToken") String nextPageToken) {
        requirePrivileged(authorization);
        return json(service.batchGet(project, maxResults, nextPageToken));
    }

    @POST
    @Path("/projects/{project}/accounts:batchDelete")
    public Response batchDelete(@HeaderParam("Authorization") String authorization,
                                @PathParam("project") String project,
                                Map<String, Object> body) {
        requirePrivileged(authorization);
        return json(service.batchDelete(project, safe(body)));
    }

    // ── auth model (matches the Auth emulator) ────────────────────────────────

    static boolean isPrivileged(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "bearer ", 0, 7)) {
            return false;
        }
        String token = authorization.substring(7).trim();
        if ("owner".equalsIgnoreCase(token) || token.startsWith("ya29.")) {
            return true;
        }
        throw FirebaseAuthException.unauthenticated(
                "Request had invalid authentication credentials. Expected OAuth 2 access token, "
                        + "login cookie or other valid authentication credential.");
    }

    static void requirePrivileged(String authorization) {
        if (authorization == null || !isPrivileged(authorization)) {
            throw FirebaseAuthException.unauthenticated(
                    "Request is missing required authentication credential. Expected OAuth 2 "
                            + "access token, login cookie or other valid authentication credential.");
        }
    }

    private static boolean requireClientOrPrivileged(String authorization, String apiKey, String apiKeyHeader) {
        if (authorization != null && isPrivileged(authorization)) {
            return true;
        }
        boolean hasKey = (apiKey != null && !apiKey.isEmpty())
                || (apiKeyHeader != null && !apiKeyHeader.isEmpty());
        if (!hasKey) {
            throw FirebaseAuthException.permissionDenied("The request is missing a valid API key.");
        }
        return false;
    }

    private String defaultProject() {
        return config.defaultProjectId();
    }

    private static Map<String, Object> safe(Map<String, Object> body) {
        return body == null ? Map.of() : body;
    }

    private static Response json(Object entity) {
        return Response.ok(entity, MediaType.APPLICATION_JSON).build();
    }
}

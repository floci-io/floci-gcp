package io.floci.gcp.services.firebaseauth;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Provider
public class FirebaseAuthExceptionMapper implements ExceptionMapper<FirebaseAuthException> {

    @Override
    public Response toResponse(FirebaseAuthException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", e.getHttpCode());
        error.put("message", e.getMessage());
        error.put("errors", List.of(Map.of(
                "message", e.getMessage(),
                "domain", "global",
                "reason", "invalid")));
        if (e.getStatus() != null) {
            error.put("status", e.getStatus());
        }
        return Response.status(e.getHttpCode())
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", error))
                .build();
    }
}

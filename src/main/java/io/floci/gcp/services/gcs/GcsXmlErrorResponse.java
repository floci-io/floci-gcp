package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.XmlBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Builds Cloud Storage XML API error responses. */
final class GcsXmlErrorResponse {

    private GcsXmlErrorResponse() {
    }

    static boolean handlesAuthentication(GcpException error) {
        return error.getHttpStatus() == 401 || error.getHttpStatus() == 403;
    }

    static Response authentication(GcpException error, String authorization) {
        boolean credentialProvided = authorization != null && !authorization.isBlank();
        int status = error.getHttpStatus() == 403 && !credentialProvided ? 401 : error.getHttpStatus();
        String code = switch (status) {
            case 401 -> credentialProvided ? "InvalidAuthentication" : "AuthenticationRequired";
            case 403 -> "AccessDenied";
            default -> throw new IllegalArgumentException("Not an authentication error: " + status);
        };
        return of(status, code, error.getMessage());
    }

    static Response of(int status, String code, String message) {
        return of(status, code, message, null, null);
    }

    static Response of(int status, String code, String message, String details, String parameterName) {
        String body = new XmlBuilder()
                .start("Error")
                .elem("Code", code)
                .elem("Message", message)
                .elem("Details", details)
                .elem("ParameterName", parameterName)
                .end("Error")
                .build();
        return Response.status(status).type(MediaType.APPLICATION_XML).entity(body).build();
    }
}

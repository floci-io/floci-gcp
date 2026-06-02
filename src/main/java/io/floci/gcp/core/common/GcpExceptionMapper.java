package io.floci.gcp.core.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

/**
 * Maps {@link GcpException} to the canonical GCP REST error shape:
 * <pre>
 * {"error": {"code": 404, "message": "...", "status": "NOT_FOUND",
 *            "errors": [{"message": "...", "domain": "global", "reason": "notFound"}]}}
 * </pre>
 * The nested {@code errors[]} array (with {@code domain} and {@code reason}) mirrors the
 * legacy Google JSON API error format that some SDK retry/handling logic inspects.
 */
@Provider
public class GcpExceptionMapper implements ExceptionMapper<GcpException> {

    @Override
    public Response toResponse(GcpException ex) {
        return Response.status(ex.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorWrapper(
                        ErrorDetail.of(ex.getHttpStatus(), ex.getMessage(), ex.getGcpStatus())))
                .build();
    }

    /** Legacy Google JSON API {@code reason} code for a canonical GCP status. */
    private static String reasonFor(String gcpStatus) {
        return switch (gcpStatus) {
            case "NOT_FOUND" -> "notFound";
            case "ALREADY_EXISTS" -> "alreadyExists";
            case "INVALID_ARGUMENT" -> "invalid";
            case "FAILED_PRECONDITION" -> "failedPrecondition";
            case "CONDITION_NOT_MET" -> "conditionNotMet";
            case "PERMISSION_DENIED" -> "forbidden";
            case "RESOURCE_EXHAUSTED" -> "rateLimitExceeded";
            case "UNIMPLEMENTED" -> "notImplemented";
            case "INTERNAL" -> "internalError";
            default -> "backendError";
        };
    }

    @RegisterForReflection
    public record ErrorWrapper(@JsonProperty("error") ErrorDetail error) {}

    @RegisterForReflection
    public record ErrorDetail(@JsonProperty("code") int code,
                              @JsonProperty("message") String message,
                              @JsonProperty("status") String status,
                              @JsonProperty("errors") List<ErrorItem> errors) {

        /** Builds a detail with the matching legacy {@code errors[]} entry derived from {@code status}. */
        public static ErrorDetail of(int code, String message, String status) {
            return new ErrorDetail(code, message, status,
                    List.of(new ErrorItem(message, "global", reasonFor(status))));
        }
    }

    @RegisterForReflection
    public record ErrorItem(@JsonProperty("message") String message,
                            @JsonProperty("domain") String domain,
                            @JsonProperty("reason") String reason) {}
}

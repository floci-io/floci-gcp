package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.XmlBuilder;
import io.floci.gcp.services.credentials.CredentialAuthenticationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Set;

/** Builds Cloud Storage XML API error responses. */
final class GcsXmlErrorResponse {

    private static final Logger LOG = Logger.getLogger(GcsXmlErrorResponse.class);
    private static final Set<String> MULTIPART_CODES = Set.of(
            "NoSuchUpload", "BadDigest", "MalformedXML", "InvalidPart", "InvalidPartOrder", "InvalidArgument");

    private GcsXmlErrorResponse() {
    }

    static boolean handlesAuthentication(GcpException error) {
        return error.getHttpStatus() == 401 || error.getHttpStatus() == 403;
    }

    static Response authentication(GcpException error) {
        if (error.getHttpStatus() == 403) {
            return of(403, "AccessDenied", "Access denied.", error.getMessage(), null);
        }
        boolean expired = error instanceof CredentialAuthenticationException credential
                && credential.failure() == CredentialAuthenticationException.Failure.EXPIRED;
        String code = expired ? "InvalidAuthentication" : "AuthenticationRequired";
        String message = expired ? "Invalid authentication." : "Authentication required.";
        Response response = of(401, code, message, error.getMessage(), null);
        return Response.fromResponse(response)
                .header("WWW-Authenticate", "Bearer realm=\"https://accounts.google.com/\", error=\"invalid_token\"")
                .build();
    }

    static Response from(Exception failure) {
        if (failure instanceof GcsXmlException web) {
            // Signed URL and authorization errors already carry their transport response.
            return web.getResponse();
        }
        if (failure instanceof WebApplicationException web && web.getResponse().getStatus() < 500) {
            return of(web.getResponse().getStatus(), "InvalidArgument", "Invalid argument.");
        }
        if (!(failure instanceof GcpException error)) {
            return internal(failure);
        }
        if (error instanceof GcsResourceException resource) {
            return switch (resource.failure()) {
                case BUCKET_NOT_FOUND -> of(404, "NoSuchBucket", "The specified bucket does not exist.");
                case OBJECT_NOT_FOUND -> of(404, "NoSuchKey", "The specified key does not exist.");
                case ENCRYPTION_KEY_MISSING -> of(400, "ResourceIsEncryptedWithCustomerEncryptionKey",
                        "The resource is encrypted with a customer-supplied encryption key.");
                case ENCRYPTION_KEY_INCORRECT -> of(400, "CustomerEncryptionKeyIsIncorrect",
                        "The provided encryption key is incorrect.");
                case OBJECT_HELD -> of(403, "ObjectUnderActiveHold", "The object is under an active hold.");
                case RETENTION_POLICY -> of(403, "RetentionPolicyNotMet", "The object has not met its retention period.");
            };
        }
        if (handlesAuthentication(error)) {
            return authentication(error);
        }
        // The multipart service already identifies these failures; JSON reasons are otherwise unrelated.
        if (MULTIPART_CODES.contains(error.getReason() == null ? "" : error.getReason())) {
            return of(error.getHttpStatus(), error.getReason(), error.getMessage());
        }
        return switch (error.getHttpStatus()) {
            case 400 -> of(400, "InvalidArgument", "Invalid argument.", error.getMessage(), null);
            case 409 -> of(409, "OperationAborted", "A conflicting operation is in progress.");
            case 412 -> of(412, "PreconditionFailed", "At least one precondition did not hold.");
            case 416 -> of(416, "InvalidRange", "The requested range cannot be satisfied.");
            case 429 -> of(429, "SlowDown", "Please reduce your request rate.");
            case 501 -> of(400, "NotImplemented", "The requested functionality is not implemented.");
            case 503 -> of(503, "SlowDown", "The service is temporarily unavailable.");
            default -> internal(error);
        };
    }

    private static Response internal(Exception failure) {
        LOG.errorf(failure, "GCS XML operation failed");
        return of(500, "InternalError", "We encountered an internal error. Please try again.");
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

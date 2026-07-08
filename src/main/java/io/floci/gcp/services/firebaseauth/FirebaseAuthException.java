package io.floci.gcp.services.firebaseauth;

/**
 * Firebase Auth wire errors. The Identity Toolkit error body differs from the generic
 * GCP JSON error ({@code GcpExceptionMapper}): the machine-readable code IS the message
 * (optionally with a "CODE : detail" suffix), the errors[] reason is always "invalid",
 * and plain 400 asserts carry no status field — matching the official Auth emulator.
 */
public class FirebaseAuthException extends RuntimeException {

    private final int httpCode;
    private final String status;

    private FirebaseAuthException(int httpCode, String status, String message) {
        super(message);
        this.httpCode = httpCode;
        this.status = status;
    }

    public int getHttpCode() {
        return httpCode;
    }

    public String getStatus() {
        return status;
    }

    /** Mirrors the emulator's assert() → BadRequestError: HTTP 400, no status field. */
    public static FirebaseAuthException badRequest(String message) {
        return new FirebaseAuthException(400, null, message);
    }

    public static FirebaseAuthException permissionDenied(String message) {
        return new FirebaseAuthException(403, "PERMISSION_DENIED", message);
    }

    public static FirebaseAuthException unauthenticated(String message) {
        return new FirebaseAuthException(401, "UNAUTHENTICATED", message);
    }

    public static void check(boolean condition, String message) {
        if (!condition) {
            throw badRequest(message);
        }
    }
}

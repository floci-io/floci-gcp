package io.floci.gcp.core.common;

import io.grpc.Status;

/**
 * Domain exception for floci-gcp services. Carries an HTTP status, a GCP status string
 * (e.g. "NOT_FOUND"), and a gRPC {@link Status.Code} for dual REST/gRPC error mapping.
 */
public class GcpException extends RuntimeException {

    private final int httpStatus;
    private final String gcpStatus;
    private final Status.Code grpcCode;

    private GcpException(int httpStatus, String gcpStatus, Status.Code grpcCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.gcpStatus = gcpStatus;
        this.grpcCode = grpcCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getGcpStatus() {
        return gcpStatus;
    }

    public Status.Code getGrpcCode() {
        return grpcCode;
    }

    public static GcpException notFound(String message) {
        return new GcpException(404, "NOT_FOUND", Status.Code.NOT_FOUND, message);
    }

    public static GcpException alreadyExists(String message) {
        return new GcpException(409, "ALREADY_EXISTS", Status.Code.ALREADY_EXISTS, message);
    }

    public static GcpException invalidArgument(String message) {
        return new GcpException(400, "INVALID_ARGUMENT", Status.Code.INVALID_ARGUMENT, message);
    }

    public static GcpException outOfRange(String message) {
        return new GcpException(416, "OUT_OF_RANGE", Status.Code.OUT_OF_RANGE, message);
    }

    public static GcpException failedPrecondition(String message) {
        return new GcpException(400, "FAILED_PRECONDITION", Status.Code.FAILED_PRECONDITION, message);
    }

    public static GcpException permissionDenied(String message) {
        return new GcpException(403, "PERMISSION_DENIED", Status.Code.PERMISSION_DENIED, message);
    }

    public static GcpException resourceExhausted(String message) {
        return new GcpException(429, "RESOURCE_EXHAUSTED", Status.Code.RESOURCE_EXHAUSTED, message);
    }

    public static GcpException internal(String message) {
        return new GcpException(500, "INTERNAL", Status.Code.INTERNAL, message);
    }

    public static GcpException badGateway(String message) {
        return new GcpException(502, "UNAVAILABLE", Status.Code.UNAVAILABLE, message);
    }

    public static GcpException conditionNotMet(String message) {
        return new GcpException(412, "CONDITION_NOT_MET", Status.Code.FAILED_PRECONDITION, message);
    }

    public static GcpException unimplemented(String message) {
        return new GcpException(501, "UNIMPLEMENTED", Status.Code.UNIMPLEMENTED, message);
    }

    public static GcpException unavailable(String message) {
        return new GcpException(503, "UNAVAILABLE", Status.Code.UNAVAILABLE, message);
    }

    public static GcpException deadlineExceeded(String message) {
        return new GcpException(504, "DEADLINE_EXCEEDED", Status.Code.DEADLINE_EXCEEDED, message);
    }
}

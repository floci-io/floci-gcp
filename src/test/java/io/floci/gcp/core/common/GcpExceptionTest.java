package io.floci.gcp.core.common;

import io.grpc.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GcpExceptionTest {

    @Test
    void notFound() {
        GcpException ex = GcpException.notFound("thing not found");
        assertEquals(404, ex.getHttpStatus());
        assertEquals("NOT_FOUND", ex.getGcpStatus());
        assertEquals(Status.Code.NOT_FOUND, ex.getGrpcCode());
        assertEquals("thing not found", ex.getMessage());
    }

    @Test
    void alreadyExists() {
        GcpException ex = GcpException.alreadyExists("already there");
        assertEquals(409, ex.getHttpStatus());
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
        assertEquals(Status.Code.ALREADY_EXISTS, ex.getGrpcCode());
    }

    @Test
    void invalidArgument() {
        GcpException ex = GcpException.invalidArgument("bad input");
        assertEquals(400, ex.getHttpStatus());
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getGrpcCode());
    }

    @Test
    void failedPrecondition() {
        GcpException ex = GcpException.failedPrecondition("precondition failed");
        assertEquals(400, ex.getHttpStatus());
        assertEquals("FAILED_PRECONDITION", ex.getGcpStatus());
        assertEquals(Status.Code.FAILED_PRECONDITION, ex.getGrpcCode());
    }

    @Test
    void permissionDenied() {
        GcpException ex = GcpException.permissionDenied("forbidden");
        assertEquals(403, ex.getHttpStatus());
        assertEquals("PERMISSION_DENIED", ex.getGcpStatus());
        assertEquals(Status.Code.PERMISSION_DENIED, ex.getGrpcCode());
    }

    @Test
    void unauthenticated() {
        GcpException ex = GcpException.unauthenticated("no credentials");
        assertEquals(401, ex.getHttpStatus());
        assertEquals("UNAUTHENTICATED", ex.getGcpStatus());
        assertEquals(Status.Code.UNAUTHENTICATED, ex.getGrpcCode());
        assertEquals("no credentials", ex.getMessage());
    }

    @Test
    void resourceExhausted() {
        GcpException ex = GcpException.resourceExhausted("quota exceeded");
        assertEquals(429, ex.getHttpStatus());
        assertEquals("RESOURCE_EXHAUSTED", ex.getGcpStatus());
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, ex.getGrpcCode());
    }

    @Test
    void internal() {
        GcpException ex = GcpException.internal("server error");
        assertEquals(500, ex.getHttpStatus());
        assertEquals("INTERNAL", ex.getGcpStatus());
        assertEquals(Status.Code.INTERNAL, ex.getGrpcCode());
    }

    @Test
    void unimplemented() {
        GcpException ex = GcpException.unimplemented("not yet");
        assertEquals(501, ex.getHttpStatus());
        assertEquals("UNIMPLEMENTED", ex.getGcpStatus());
        assertEquals(Status.Code.UNIMPLEMENTED, ex.getGrpcCode());
    }

    @Test
    void isRuntimeException() {
        assertInstanceOf(RuntimeException.class, GcpException.notFound("x"));
    }
}

package io.floci.gcp.core.common;

import com.google.rpc.ErrorInfo;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GcpExceptionMapperTest {

    @Test
    void permissionDetailUsesGoogleRestAndGrpcShapes() throws Exception {
        String message = "Permission 'logging.sinks.get' denied on resource "
                + "'//logging.googleapis.com/projects/p/sinks/one' (or it may not exist).";
        GcpException error = GcpException.permissionDenied(message).withErrorInfo("IAM_PERMISSION_DENIED",
                "iam.googleapis.com", Map.of("permission", "logging.sinks.get",
                        "resource", "projects/p/sinks/one"));
        GcpExceptionMapper.ErrorWrapper wrapper = (GcpExceptionMapper.ErrorWrapper)
                new GcpExceptionMapper().toResponse(error).getEntity();
        assertEquals(message, wrapper.error().message());
        assertEquals("forbidden", wrapper.error().errors().getFirst().reason());
        assertEquals("IAM_PERMISSION_DENIED", wrapper.error().details().getFirst().get("reason"));
        assertEquals(Map.of("permission", "logging.sinks.get", "resource", "projects/p/sinks/one"),
                wrapper.error().details().getFirst().get("metadata"));

        StatusRuntimeException grpc = GcpGrpcController.grpcException(error);
        com.google.rpc.Status status = StatusProto.fromThrowable(grpc);
        assertNotNull(status);
        assertEquals(7, status.getCode());
        assertEquals(message, status.getMessage());
        ErrorInfo info = status.getDetails(0).unpack(ErrorInfo.class);
        assertEquals("IAM_PERMISSION_DENIED", info.getReason());
        assertEquals("iam.googleapis.com", info.getDomain());
        assertEquals("projects/p/sinks/one", info.getMetadataOrThrow("resource"));
    }

    @Test
    void errorDetailCarriesAllFields() {
        var detail = GcpExceptionMapper.ErrorDetail.of(404, "not found", "NOT_FOUND");
        assertEquals(404, detail.code());
        assertEquals("not found", detail.message());
        assertEquals("NOT_FOUND", detail.status());
    }

    @Test
    void errorWrapperWrapsDetail() {
        var detail = GcpExceptionMapper.ErrorDetail.of(409, "exists", "ALREADY_EXISTS");
        var wrapper = new GcpExceptionMapper.ErrorWrapper(detail);
        assertSame(detail, wrapper.error());
    }

    @Test
    void mapperProducesCorrectDetailForNotFound() {
        GcpException ex = GcpException.notFound("bucket missing");
        var detail = GcpExceptionMapper.ErrorDetail.of(ex.getHttpStatus(), ex.getMessage(), ex.getGcpStatus());
        assertEquals(404, detail.code());
        assertEquals("bucket missing", detail.message());
        assertEquals("NOT_FOUND", detail.status());
    }

    @Test
    void mapperProducesCorrectDetailForAlreadyExists() {
        GcpException ex = GcpException.alreadyExists("bucket exists");
        var detail = GcpExceptionMapper.ErrorDetail.of(ex.getHttpStatus(), ex.getMessage(), ex.getGcpStatus());
        assertEquals(409, detail.code());
        assertEquals("ALREADY_EXISTS", detail.status());
    }

    @Test
    void errorDetailIncludesLegacyErrorsArray() {
        var detail = GcpExceptionMapper.ErrorDetail.of(404, "bucket missing", "NOT_FOUND");
        assertEquals(1, detail.errors().size());
        var item = detail.errors().get(0);
        assertEquals("bucket missing", item.message());
        assertEquals("global", item.domain());
        assertEquals("notFound", item.reason());
    }

    @Test
    void explicitReasonOverridesDerivedReason() {
        GcpException ex = GcpException.invalidArgument("bad sql").withReason("invalidQuery");
        var response = new GcpExceptionMapper().toResponse(ex);
        var wrapper = (GcpExceptionMapper.ErrorWrapper) response.getEntity();

        assertEquals(400, wrapper.error().code());
        assertEquals("INVALID_ARGUMENT", wrapper.error().status());
        assertEquals("invalidQuery", wrapper.error().errors().get(0).reason());
    }

    @Test
    void mapperDerivesReasonWhenNoneSet() {
        GcpException ex = GcpException.alreadyExists("exists");
        var response = new GcpExceptionMapper().toResponse(ex);
        var wrapper = (GcpExceptionMapper.ErrorWrapper) response.getEntity();

        assertEquals("alreadyExists", wrapper.error().errors().get(0).reason());
        assertNull(ex.getReason());
    }

    @Test
    void withReasonPreservesStatusAndMessage() {
        GcpException ex = GcpException.alreadyExists("Already Exists: Dataset p:d").withReason("duplicate");
        assertEquals(409, ex.getHttpStatus());
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
        assertEquals("Already Exists: Dataset p:d", ex.getMessage());
        assertEquals("duplicate", ex.getReason());
    }

    @Test
    void plainTextErrorsCarryTheBareMessage() {
        GcpException ex = GcpException.unavailable("upload offset is 4 byte(s)").asPlainText();
        var response = new GcpExceptionMapper().toResponse(ex);

        assertEquals(503, response.getStatus());
        // Spelled out the way live GCS sends it, rather than however MediaType would render it.
        assertEquals("text/plain; charset=utf-8", response.getHeaderString("Content-Type"));
        assertEquals("upload offset is 4 byte(s)", response.getEntity());
    }

    @Test
    void plainTextFlagSurvivesWithReason() {
        GcpException ex = GcpException.unavailable("gap").asPlainText().withReason("backendError");
        assertTrue(ex.isPlainText());
        assertEquals("backendError", ex.getReason());
    }

    @Test
    void errorsAreJsonUnlessFlagged() {
        assertFalse(GcpException.unavailable("gap").isPlainText());
    }

    @Test
    void reasonIsDerivedFromStatus() {
        assertEquals("alreadyExists",
                GcpExceptionMapper.ErrorDetail.of(409, "x", "ALREADY_EXISTS").errors().get(0).reason());
        assertEquals("conditionNotMet",
                GcpExceptionMapper.ErrorDetail.of(412, "x", "CONDITION_NOT_MET").errors().get(0).reason());
		assertEquals("authError",
				GcpExceptionMapper.ErrorDetail.of(401, "x", "UNAUTHENTICATED").errors().get(0).reason());
        assertEquals("forbidden",
                GcpExceptionMapper.ErrorDetail.of(403, "x", "PERMISSION_DENIED").errors().get(0).reason());
        assertEquals("backendError",
                GcpExceptionMapper.ErrorDetail.of(503, "x", "UNAVAILABLE").errors().get(0).reason());
        assertEquals("aborted",
                GcpExceptionMapper.ErrorDetail.of(409, "x", "ABORTED").errors().get(0).reason());
    }
}

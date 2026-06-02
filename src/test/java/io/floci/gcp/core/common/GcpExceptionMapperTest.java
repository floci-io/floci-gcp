package io.floci.gcp.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GcpExceptionMapperTest {

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
    void reasonIsDerivedFromStatus() {
        assertEquals("alreadyExists",
                GcpExceptionMapper.ErrorDetail.of(409, "x", "ALREADY_EXISTS").errors().get(0).reason());
        assertEquals("conditionNotMet",
                GcpExceptionMapper.ErrorDetail.of(412, "x", "CONDITION_NOT_MET").errors().get(0).reason());
        assertEquals("forbidden",
                GcpExceptionMapper.ErrorDetail.of(403, "x", "PERMISSION_DENIED").errors().get(0).reason());
        assertEquals("backendError",
                GcpExceptionMapper.ErrorDetail.of(503, "x", "UNAVAILABLE").errors().get(0).reason());
    }
}

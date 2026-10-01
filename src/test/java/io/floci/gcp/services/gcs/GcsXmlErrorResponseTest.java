package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcsXmlErrorResponseTest {

    @Test
    void anonymousPermissionDenialRemainsAccessDenied() {
        Response response = GcsXmlErrorResponse.authentication(
                GcpException.permissionDenied("Access denied"));

        assertEquals(403, response.getStatus());
        assertEquals(MediaType.APPLICATION_XML_TYPE, response.getMediaType());
        assertTrue(response.getEntity().toString().contains("<Code>AccessDenied</Code>"));
    }

    @Test
    void unknownCredentialRequiresAuthentication() {
        Response response = GcsXmlErrorResponse.authentication(
                GcpException.unauthenticated("Unknown Floci credential token"));

        assertEquals(401, response.getStatus());
        assertTrue(response.getEntity().toString().contains("<Code>AuthenticationRequired</Code>"));
    }

    @Test
    void expiredCredentialIsInvalidAuthentication() {
        Response response = GcsXmlErrorResponse.authentication(
                GcpException.unauthenticated("Expired Floci credential token"));

        assertEquals(401, response.getStatus());
        assertTrue(response.getEntity().toString().contains("<Code>InvalidAuthentication</Code>"));
    }

    @Test
    void optionalErrorDetailsAreRendered() {
        Response response = GcsXmlErrorResponse.of(
                400, "MalformedSecurityHeader", "Malformed header", "Invalid date", "Date");
        String body = response.getEntity().toString();

        assertTrue(body.contains("<Details>Invalid date</Details>"));
        assertTrue(body.contains("<ParameterName>Date</ParameterName>"));
    }

    @Test
    void absentOptionalErrorDetailsAreOmitted() {
        Response response = GcsXmlErrorResponse.of(400, "InvalidArgument", "Invalid argument");
        String body = response.getEntity().toString();

        assertFalse(body.contains("<Details>"));
        assertFalse(body.contains("<ParameterName>"));
    }
}

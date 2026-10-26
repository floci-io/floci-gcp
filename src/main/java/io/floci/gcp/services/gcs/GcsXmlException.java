package io.floci.gcp.services.gcs;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/** A response already rendered by the XML transport, including signed URL details. */
final class GcsXmlException extends WebApplicationException {

    GcsXmlException(Response response) {
        super(response);
    }
}

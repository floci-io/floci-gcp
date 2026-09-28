package io.floci.gcp.core.common;

import io.floci.gcp.core.storage.StorageException;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/** Maps unexpected storage failures to a sanitized GCP REST error response. */
@Provider
public class StorageExceptionMapper implements ExceptionMapper<StorageException> {

    private static final Logger LOG = Logger.getLogger(StorageExceptionMapper.class);
    private static final String CLIENT_MESSAGE = "Internal server error.";

    private final GcpExceptionMapper gcpExceptionMapper;

    @Inject
    public StorageExceptionMapper(GcpExceptionMapper gcpExceptionMapper) {
        this.gcpExceptionMapper = gcpExceptionMapper;
    }

    @Override
    public Response toResponse(StorageException exception) {
        LOG.errorv(exception, "Storage operation failed");
        return gcpExceptionMapper.toResponse(GcpException.internal(CLIENT_MESSAGE));
    }
}

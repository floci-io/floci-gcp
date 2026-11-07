package io.floci.gcp.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * The bytes of a REST request's body, read once however many filters ask for them and left in
 * place for the handler. Each filter decides for itself what a body that is not JSON means.
 */
public final class RequestBody {

    private static final String PROPERTY = RequestBody.class.getName();
    private static final byte[] EMPTY = new byte[0];

    private RequestBody() {}

    public static byte[] of(ContainerRequestContext context) throws IOException {
        if (context.getProperty(PROPERTY) instanceof byte[] buffered) {
            return buffered;
        }
        byte[] bytes = EMPTY;
        if (context.hasEntity()) {
            bytes = context.getEntityStream().readAllBytes();
            context.setEntityStream(new ByteArrayInputStream(bytes));
        }
        context.setProperty(PROPERTY, bytes);
        return bytes;
    }
}

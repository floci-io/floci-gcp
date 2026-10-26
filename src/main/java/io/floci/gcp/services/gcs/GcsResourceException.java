package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;

/** Domain failure context; the original JSON and gRPC contracts remain on the base exception. */
final class GcsResourceException extends GcpException {

    enum Failure { BUCKET_NOT_FOUND, OBJECT_NOT_FOUND, ENCRYPTION_KEY_MISSING, ENCRYPTION_KEY_INCORRECT,
        OBJECT_HELD, RETENTION_POLICY }

    private final Failure failure;

    GcsResourceException(Failure failure, GcpException error) {
        super(error);
        this.failure = failure;
    }

    Failure failure() {
        return failure;
    }
}

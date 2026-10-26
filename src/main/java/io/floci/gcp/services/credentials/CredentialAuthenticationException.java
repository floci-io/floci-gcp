package io.floci.gcp.services.credentials;

import io.floci.gcp.core.common.GcpException;

/** Credential lookup outcome, independent of the HTTP transport's error representation. */
public final class CredentialAuthenticationException extends GcpException {

    public enum Failure { UNKNOWN, EXPIRED }

    private final Failure failure;

    public CredentialAuthenticationException(Failure failure, String message) {
        super(GcpException.unauthenticated(message));
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
    }
}

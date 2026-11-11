package io.floci.gcp.services.credentials;

import io.floci.gcp.core.common.GcpException;

/** Credential lookup outcome, independent of the HTTP transport's error representation. */
public final class CredentialAuthenticationException extends GcpException {

    public enum Failure { UNKNOWN, EXPIRED, ACCOUNT_STATE_INVALID }

    /**
     * What Google answers for a token whose account is disabled or deleted, the ErrorInfo reason
     * ACCOUNT_STATE_INVALID of google/api/error_reason.proto ("the account associated with the provided
     * access token is in an invalid state, such as disabled or deleted").
     */
    public static final String ACCOUNT_STATE_INVALID_MESSAGE = "Request had invalid authentication credentials. "
            + "Expected OAuth 2 access token, login cookie or other valid authentication credential. "
            + "See https://developers.google.com/identity/sign-in/web/devconsole-project.";

    private final Failure failure;

    public CredentialAuthenticationException(Failure failure, String message) {
        super(GcpException.unauthenticated(message));
        this.failure = failure;
    }

    public static CredentialAuthenticationException accountStateInvalid() {
        return new CredentialAuthenticationException(Failure.ACCOUNT_STATE_INVALID, ACCOUNT_STATE_INVALID_MESSAGE);
    }

    public Failure failure() {
        return failure;
    }
}

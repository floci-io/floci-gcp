package io.floci.gcp.services.iam;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.services.credentials.CredentialAuthenticationException;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import io.floci.gcp.services.iam.authorization.IamIdentityKind;
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Optional;

/** Resolves only identities represented by valid Floci-issued credential tokens. */
@ApplicationScoped
public class IamPrincipalResolver {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SERVICE_ACCOUNT_RESOURCE_MARKER = "/serviceAccounts/";

    static final String DISABLED_ACCOUNT_MESSAGE = "The service account of this credential is disabled";

    private final CredentialTokenService tokenService;
    private final EmulatorConfig config;
    private final Instance<IamService> accounts;

    @Inject
    public IamPrincipalResolver(CredentialTokenService tokenService, EmulatorConfig config,
            Instance<IamService> accounts) {
        this.tokenService = tokenService;
        this.config = config;
        this.accounts = accounts;
    }

    IamPrincipalResolver(CredentialTokenService tokenService) {
        this(tokenService, null, null);
    }

    public Resolution resolve(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return Resolution.anonymous();
        }
        Optional<String> bearerToken = bearerToken(authorization);
        if (bearerToken.isEmpty()) {
            return Resolution.external();
        }
        Optional<StoredCredentialToken> token = tokenService.lookupBearerToken(bearerToken.get());
        if (token.isEmpty()) {
            return Resolution.external();
        }
        if (token.get().getTokenKind() == StoredCredentialToken.TokenKind.DOWNSCOPED) {
            Resolution downscoped = Resolution.downscopedToken(token.get().getPrincipal());
            requireEnabledAccount(downscoped.principal());
            return downscoped;
        }
        IamPrincipal principal = IamPrincipal.serviceAccount(normalizeServiceAccount(token.get().getPrincipal()));
        requireEnabledAccount(principal);
        return Resolution.authenticated(principal);
    }

    /**
     * IAM {@code serviceAccounts.disable}: "Existing access tokens for the service account are rejected", and
     * accepted again once the account is re-enabled. Only enforce mode evaluates identities.
     */
    private void requireEnabledAccount(IamPrincipal principal) {
        if (config == null || accounts == null || !principal.isAuthenticated()
                || config.services().iam().authorizationMode() != EmulatorConfig.IamAuthorizationMode.ENFORCE) {
            return;
        }
        String email = principal.member().substring("serviceAccount:".length());
        if (accounts.get().findServiceAccount(email).map(StoredServiceAccount::isDisabled).orElse(false)) {
            throw new CredentialAuthenticationException(
                    CredentialAuthenticationException.Failure.ACCOUNT_STATE_INVALID, DISABLED_ACCOUNT_MESSAGE);
        }
    }

    private static Optional<String> bearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private static String normalizeServiceAccount(String principal) {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("Floci impersonated token has no service account principal");
        }
        if (principal.startsWith("serviceAccount:")) {
            return principal.substring("serviceAccount:".length());
        }
        int resourceMarker = principal.indexOf(SERVICE_ACCOUNT_RESOURCE_MARKER);
        return resourceMarker >= 0 ? principal.substring(resourceMarker + SERVICE_ACCOUNT_RESOURCE_MARKER.length())
                : principal;
    }

    public record Resolution(IamPrincipal principal, IamIdentityKind kind) {

        public boolean downscoped() {
            return kind == IamIdentityKind.FLOCI_DOWNSCOPED;
        }

        private static Resolution anonymous() {
            return new Resolution(IamPrincipal.anonymous(), IamIdentityKind.ANONYMOUS);
        }

        private static Resolution external() {
            return new Resolution(IamPrincipal.anonymous(), IamIdentityKind.EXTERNAL);
        }

        private static Resolution downscopedToken(String principal) {
            if (principal == null || principal.isBlank()) {
                return new Resolution(IamPrincipal.anonymous(), IamIdentityKind.FLOCI_DOWNSCOPED);
            }
            return new Resolution(IamPrincipal.serviceAccount(normalizeServiceAccount(principal)),
                    IamIdentityKind.FLOCI_DOWNSCOPED);
        }

        private static Resolution authenticated(IamPrincipal principal) {
            return new Resolution(principal, IamIdentityKind.FLOCI_SERVICE_ACCOUNT);
        }
    }
}

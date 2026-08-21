package io.floci.gcp.services.iam;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/** Resolves only identities represented by valid Floci-issued credential tokens. */
@ApplicationScoped
public class IamPrincipalResolver {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SERVICE_ACCOUNT_RESOURCE_MARKER = "/serviceAccounts/";

    private final CredentialTokenService tokenService;

    @Inject
    public IamPrincipalResolver(CredentialTokenService tokenService) {
        this.tokenService = tokenService;
    }

    public Resolution resolve(String authorization) {
        Optional<String> bearerToken = bearerToken(authorization);
        if (bearerToken.isEmpty()) {
            return Resolution.anonymous();
        }
        Optional<StoredCredentialToken> token = tokenService.lookupBearerToken(bearerToken.get());
        if (token.isEmpty()) {
            return Resolution.anonymous();
        }
        if (token.get().getTokenKind() == StoredCredentialToken.TokenKind.DOWNSCOPED) {
			return Resolution.downscopedToken(token.get().getPrincipal());
        }
        return Resolution.authenticated(IamPrincipal.serviceAccount(normalizeServiceAccount(token.get().getPrincipal())));
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

    public record Resolution(IamPrincipal principal, boolean downscoped) {

        private static Resolution anonymous() {
            return new Resolution(IamPrincipal.anonymous(), false);
        }

		private static Resolution downscopedToken(String principal) {
			if (principal == null || principal.isBlank()) {
				return new Resolution(IamPrincipal.anonymous(), true);
			}
			return new Resolution(IamPrincipal.serviceAccount(normalizeServiceAccount(principal)), true);
		}

        private static Resolution authenticated(IamPrincipal principal) {
            return new Resolution(principal, false);
        }
    }
}

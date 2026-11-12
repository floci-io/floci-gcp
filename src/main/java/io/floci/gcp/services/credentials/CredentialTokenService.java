package io.floci.gcp.services.credentials;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CredentialTokenService {

    public static final String FLOCI_TOKEN_PREFIX = "floci-gcp-";
    public static final String IMPERSONATED_TOKEN_PREFIX = "floci-gcp-impersonated-";
    public static final String DOWNSCOPED_TOKEN_PREFIX = "floci-gcp-downscoped-";
    public static final String EXPIRED_TOKEN_MESSAGE = "Expired Floci credential token";
    public static final long DEFAULT_LIFETIME_SECONDS = 3600;

    private final StorageBackend<String, StoredCredentialToken> tokenStore;
    private final Clock clock;
    /**
     * Held while a token's account or source token is checked and the token stored, and while an account's
     * tokens are revoked, so a token minted while its account is deleted is either refused or revoked.
     */
    private final Object issuanceLock = new Object();

    @Inject
    public CredentialTokenService(StorageFactory storageFactory) {
        this(storageFactory.createGlobal("credential-tokens", "credential-tokens.json",
                new TypeReference<Map<String, StoredCredentialToken>>() {}),
                Clock.systemUTC());
    }

    public CredentialTokenService(StorageBackend<String, StoredCredentialToken> tokenStore, Clock clock) {
        this.tokenStore = tokenStore;
        this.clock = clock;
    }

    public StoredCredentialToken mintImpersonatedToken(String principal, Instant expireTime) {
        return mintImpersonatedToken(principal, expireTime, () -> { });
    }

    /**
     * Like {@link #mintImpersonatedToken(String, Instant)}, with {@code requireAccount} run under the lock
     * {@link #revokeServiceAccountTokens} takes: it throws when the account may not get a token, and an
     * account deleted after it passes has its revocation see the token.
     */
    public StoredCredentialToken mintImpersonatedToken(String principal, Instant expireTime,
            Runnable requireAccount) {
        if (principal == null || principal.isBlank()) {
            throw GcpException.invalidArgument("service account is required");
        }
        if (expireTime == null || !expireTime.isAfter(clock.instant())) {
            throw GcpException.invalidArgument("expireTime must be in the future");
        }

        String tokenValue = IMPERSONATED_TOKEN_PREFIX + UUID.randomUUID();
        StoredCredentialToken token = new StoredCredentialToken(
                tokenValue,
                StoredCredentialToken.TokenKind.IMPERSONATED,
                expireTime,
                null,
                principal,
                List.of());
        synchronized (issuanceLock) {
            requireAccount.run();
            tokenStore.put(tokenValue, token);
        }
        return token;
    }

    /**
     * Mints a token constrained by the requested Credential Access Boundary.
     *
     * <p>Floci-issued downscoped tokens cannot be exchanged again because this
     * service does not intersect the existing and requested boundaries. Replacing
     * the existing boundary could broaden the token's authority.</p>
     */
    public MintedDownscopedToken mintDownscopedToken(String sourceToken,
            List<CredentialAccessBoundaryRule> gcsRules) {
        if (sourceToken == null || sourceToken.isBlank()) {
            throw GcpException.invalidArgument("subject_token is required");
        }
        if (gcsRules == null || gcsRules.isEmpty()) {
            throw GcpException.invalidArgument("credential access boundary rules are required");
        }

        Instant now = clock.instant();
        StoredCredentialToken token;
        Instant expireTime;
        // The source is read and the child stored under the lock revocation takes: a child stored first is
        // revoked with its source, and a source revoked first is refused.
        synchronized (issuanceLock) {
            Optional<StoredCredentialToken> storedSource = lookupBearerToken(sourceToken, now);
            if (storedSource.isPresent()
                    && storedSource.get().getTokenKind() == StoredCredentialToken.TokenKind.DOWNSCOPED) {
                throw GcpException.invalidArgument("A downscoped token cannot be used as a subject token");
            }
            expireTime = storedSource
                    .map(StoredCredentialToken::getExpireTime)
                    .orElseGet(() -> now.plusSeconds(DEFAULT_LIFETIME_SECONDS));
            String principal = storedSource
                    .map(StoredCredentialToken::getPrincipal)
                    .orElse(null);
            if (storedSource.isPresent() && (principal == null || principal.isBlank())) {
                throw GcpException.invalidArgument("Floci impersonated subject token has no principal");
            }

            String tokenValue = DOWNSCOPED_TOKEN_PREFIX + UUID.randomUUID();
            token = new StoredCredentialToken(
                    tokenValue,
                    StoredCredentialToken.TokenKind.DOWNSCOPED,
                    expireTime,
                    null,
                    principal,
                    gcsRules);
            tokenStore.put(tokenValue, token);
        }
        return new MintedDownscopedToken(token, Duration.between(now, expireTime).toSeconds());
    }

    public Optional<StoredCredentialToken> lookupBearerToken(String bearerToken) {
        return lookupBearerToken(bearerToken, clock.instant());
    }

    private Optional<StoredCredentialToken> lookupBearerToken(String bearerToken, Instant now) {
        if (bearerToken == null || bearerToken.isBlank()
                || (!bearerToken.startsWith(IMPERSONATED_TOKEN_PREFIX)
                        && !bearerToken.startsWith(DOWNSCOPED_TOKEN_PREFIX))) {
            return Optional.empty();
        }

        StoredCredentialToken token = tokenStore.get(bearerToken)
                .orElseThrow(() -> new CredentialAuthenticationException(
                        CredentialAuthenticationException.Failure.UNKNOWN, "Unknown Floci credential token"));
        if (token.isRevoked()) {
            throw CredentialAuthenticationException.accountStateInvalid();
        }
        if (token.getExpireTime() == null || !token.getExpireTime().isAfter(now)) {
            // Keep the record so retries report the same expired-credential outcome.
            throw new CredentialAuthenticationException(
                    CredentialAuthenticationException.Failure.EXPIRED, EXPIRED_TOKEN_MESSAGE);
        }
        return Optional.of(token);
    }

    /**
     * Revokes every token, downscoped ones included, issued for the service account with this email,
     * whichever form its principal was recorded in. Revoked tokens are kept, so that they keep being
     * refused as the token of a deleted account rather than as an unknown token.
     */
    public void revokeServiceAccountTokens(String email) {
        synchronized (issuanceLock) {
            List<StoredCredentialToken> revoked = tokenStore.scan(key -> true).stream()
                    .filter(token -> token.getPrincipal() != null && (token.getPrincipal().equals(email)
                            || token.getPrincipal().equals("serviceAccount:" + email)
                            || token.getPrincipal().endsWith("/serviceAccounts/" + email)))
                    .toList();
            for (StoredCredentialToken token : revoked) {
                token.setRevoked(true);
                tokenStore.put(token.getTokenValue(), token);
            }
        }
    }

    public void clear() {
        tokenStore.clear();
    }

    public record MintedDownscopedToken(StoredCredentialToken token, long expiresInSeconds) {
    }
}

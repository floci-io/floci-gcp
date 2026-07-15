package io.floci.gcp.services.credentials;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
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
	public static final long DEFAULT_LIFETIME_SECONDS = 3600;

	private final StorageBackend<String, StoredCredentialToken> tokenStore;
	private final Clock clock;

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
		tokenStore.put(tokenValue, token);
		return token;
	}

	public StoredCredentialToken mintDownscopedToken(String sourceToken,
			List<CredentialAccessBoundaryRule> gcsRules) {
		if (sourceToken == null || sourceToken.isBlank()) {
			throw GcpException.invalidArgument("subject_token is required");
		}
		if (gcsRules == null || gcsRules.isEmpty()) {
			throw GcpException.invalidArgument("credential access boundary rules are required");
		}

		String tokenValue = DOWNSCOPED_TOKEN_PREFIX + UUID.randomUUID();
		StoredCredentialToken token = new StoredCredentialToken(
				tokenValue,
				StoredCredentialToken.TokenKind.DOWNSCOPED,
				clock.instant().plusSeconds(DEFAULT_LIFETIME_SECONDS),
				null,
				null,
				gcsRules);
		tokenStore.put(tokenValue, token);
		return token;
	}

	public Optional<StoredCredentialToken> lookupBearerToken(String bearerToken) {
		if (bearerToken == null || bearerToken.isBlank() || !bearerToken.startsWith(FLOCI_TOKEN_PREFIX)) {
			return Optional.empty();
		}

		StoredCredentialToken token = tokenStore.get(bearerToken)
				.orElseThrow(() -> GcpException.unauthenticated("Unknown Floci credential token"));
		if (token.getExpireTime() == null || !token.getExpireTime().isAfter(clock.instant())) {
			tokenStore.delete(bearerToken);
			throw GcpException.unauthenticated("Expired Floci credential token");
		}
		return Optional.of(token);
	}

	public void clear() {
		tokenStore.clear();
	}
}

package io.floci.gcp.services.credentials;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialTokenServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
	private final InMemoryStorage<String, StoredCredentialToken> store = new InMemoryStorage<>();
	private final CredentialTokenService service =
			new CredentialTokenService(store, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void storesAndRetrievesDownscopedToken() {
		StoredCredentialToken token = service.mintDownscopedToken("source-token", rules());

		Optional<StoredCredentialToken> resolved = service.lookupBearerToken(token.getTokenValue());

		assertTrue(resolved.isPresent());
		assertEquals(StoredCredentialToken.TokenKind.DOWNSCOPED, resolved.get().getTokenKind());
		assertEquals(NOW.plusSeconds(CredentialTokenService.DEFAULT_LIFETIME_SECONDS),
				resolved.get().getExpireTime());
		assertNull(resolved.get().getSourceToken());
		assertEquals("bucket", resolved.get().getGcsRules().getFirst().getBucket());
	}

	@Test
	void storesAndRetrievesImpersonatedTokenWithoutAccessRules() {
		StoredCredentialToken token = service.mintImpersonatedToken(
				"test@test-project.iam.gserviceaccount.com", NOW.plusSeconds(1200));

		Optional<StoredCredentialToken> resolved = service.lookupBearerToken(token.getTokenValue());

		assertTrue(resolved.isPresent());
		assertEquals(StoredCredentialToken.TokenKind.IMPERSONATED, resolved.get().getTokenKind());
		assertEquals(NOW.plusSeconds(1200), resolved.get().getExpireTime());
		assertEquals("test@test-project.iam.gserviceaccount.com", resolved.get().getPrincipal());
		assertTrue(resolved.get().getGcsRules().isEmpty());
	}

	@Test
	void expiredTokenIsRejectedAndRemoved() {
		StoredCredentialToken expired = new StoredCredentialToken(
				CredentialTokenService.DOWNSCOPED_TOKEN_PREFIX + "expired",
				StoredCredentialToken.TokenKind.DOWNSCOPED,
				NOW.minusSeconds(1),
				"source-token",
				null,
				rules());
		store.put(expired.getTokenValue(), expired);

		GcpException ex = assertThrows(GcpException.class,
				() -> service.lookupBearerToken(expired.getTokenValue()));

		assertEquals("UNAUTHENTICATED", ex.getGcpStatus());
		assertFalse(store.get(expired.getTokenValue()).isPresent());
	}

	@Test
	void unknownFlociTokenIsRejected() {
		GcpException ex = assertThrows(GcpException.class,
				() -> service.lookupBearerToken(CredentialTokenService.DOWNSCOPED_TOKEN_PREFIX + "missing"));

		assertEquals("UNAUTHENTICATED", ex.getGcpStatus());
	}

	@Test
	void nonFlociTokenReturnsEmptyLookupForLaterBypassContract() {
		assertTrue(service.lookupBearerToken("external-token").isEmpty());
		assertTrue(service.lookupBearerToken(null).isEmpty());
	}

	private static List<CredentialAccessBoundaryRule> rules() {
		return List.of(new CredentialAccessBoundaryRule(
				"bucket",
				"data/",
				List.of(CredentialAccessBoundaryParser.LEGACY_OBJECT_READER)));
	}
}

package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.credentials.CredentialAccessBoundaryRule;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.GcsAuthorizationService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcsAuthorizationTest {

	private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
	private static final String READER = "inRole:roles/storage.legacyObjectReader";
	private static final String VIEWER = "inRole:roles/storage.objectViewer";
	private static final String WRITER = "inRole:roles/storage.legacyBucketWriter";

	private final InMemoryStorage<String, StoredCredentialToken> store = new InMemoryStorage<>();
	private final CredentialTokenService tokenService =
			new CredentialTokenService(store, Clock.fixed(NOW, ZoneOffset.UTC));
	private final GcsAuthorizationService authorizationService = new GcsAuthorizationService(tokenService);

	@Test
	void missingAndStaticBearerTokensBypassAuthorization() {
		assertTrue(authorizationService.isBypassed(null));
		assertTrue(authorizationService.isBypassed("Bearer external-token"));

		assertDoesNotThrow(() -> authorizationService.requireObjectRead(null, "bucket", "outside/file.txt"));
		assertDoesNotThrow(() -> authorizationService.requireObjectWrite(
				"Bearer external-token", "bucket", "outside/file.txt"));
	}

	@Test
	void storedImpersonatedTokenBypassesDownscopedCabChecks() {
		String token = tokenService.mintImpersonatedToken(
				"test@test-project.iam.gserviceaccount.com", NOW.plusSeconds(1200)).getTokenValue();

		assertDoesNotThrow(() -> authorizationService.requireObjectWrite(
				bearer(token), "bucket", "outside/file.txt"));
	}

	@Test
	void unknownFlociTokenIsUnauthenticated() {
		GcpException ex = assertThrows(GcpException.class,
				() -> authorizationService.requireObjectRead(
						"Bearer floci-gcp-downscoped-missing", "bucket", "allowed/file.txt"));

		assertEquals("UNAUTHENTICATED", ex.getGcpStatus());
	}

	@Test
	void expiredFlociTokenIsUnauthenticated() {
		StoredCredentialToken expired = new StoredCredentialToken(
				CredentialTokenService.DOWNSCOPED_TOKEN_PREFIX + "expired",
				StoredCredentialToken.TokenKind.DOWNSCOPED,
				NOW.minusSeconds(1),
				"source-token",
				null,
				List.of(rule("bucket", "allowed/", READER)));
		store.put(expired.getTokenValue(), expired);

		GcpException ex = assertThrows(GcpException.class,
				() -> authorizationService.requireObjectRead(
						"Bearer " + expired.getTokenValue(), "bucket", "allowed/file.txt"));

		assertEquals("UNAUTHENTICATED", ex.getGcpStatus());
		assertTrue(store.get(expired.getTokenValue()).isEmpty());
	}

	@Test
	void allowedPrefixAndPermissionsSucceed() {
		String token = mint("bucket", "allowed/", READER, VIEWER, WRITER);

		assertDoesNotThrow(() -> authorizationService.requireObjectRead(
				bearer(token), "bucket", "allowed/file.txt"));
		assertDoesNotThrow(() -> authorizationService.requireObjectList(
				bearer(token), "bucket", "allowed/nested/"));
		assertDoesNotThrow(() -> authorizationService.requireObjectWrite(
				bearer(token), "bucket", "allowed/file.txt"));
		assertDoesNotThrow(() -> authorizationService.requireObjectDelete(
				bearer(token), "bucket", "allowed/file.txt"));
	}

	@Test
	void siblingPrefixAndNoPrefixListAreForbidden() {
		String token = mint("bucket", "allowed/", READER, VIEWER, WRITER);

		assertPermissionDenied(() -> authorizationService.requireObjectRead(
				bearer(token), "bucket", "allowed_sibling/file.txt"));
		assertPermissionDenied(() -> authorizationService.requireObjectWrite(
				bearer(token), "bucket", "allowed_sibling/file.txt"));
		assertPermissionDenied(() -> authorizationService.requireObjectList(
				bearer(token), "bucket", "allowed_sibling/"));
		assertPermissionDenied(() -> authorizationService.requireObjectList(
				bearer(token), "bucket", null));
	}

	@Test
	void adminSurfaceRejectsDownscopedToken() {
		String token = mint("bucket", "allowed/", READER, VIEWER, WRITER);

		assertPermissionDenied(() -> authorizationService.rejectDownscopedToken(bearer(token)));
		assertDoesNotThrow(() -> authorizationService.rejectDownscopedToken("Bearer external-token"));
	}

	private String mint(String bucket, String prefix, String... permissions) {
		return tokenService.mintDownscopedToken("source-token",
				List.of(new CredentialAccessBoundaryRule(bucket, prefix, List.of(permissions))))
				.getTokenValue();
	}

	private static CredentialAccessBoundaryRule rule(String bucket, String prefix, String... permissions) {
		return new CredentialAccessBoundaryRule(bucket, prefix, List.of(permissions));
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private static void assertPermissionDenied(ThrowingRunnable runnable) {
		GcpException ex = assertThrows(GcpException.class, runnable::run);
		assertEquals("PERMISSION_DENIED", ex.getGcpStatus());
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run();
	}
}

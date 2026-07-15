package io.floci.gcp.services.iamcredentials;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamCredentialsServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
	private static final String SERVICE_ACCOUNT = "test@test-project.iam.gserviceaccount.com";

	private final InMemoryStorage<String, StoredCredentialToken> tokenStore = new InMemoryStorage<>();
	private final CredentialTokenService tokenService =
			new CredentialTokenService(tokenStore, Clock.fixed(NOW, ZoneOffset.UTC));
	private final IamCredentialsService service =
			new IamCredentialsService(tokenService, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void generatesTokenAndExpiryForNormalRequest() {
        IamCredentialsService.GeneratedAccessToken token = service.generateAccessToken(
                SERVICE_ACCOUNT,
                List.of(IamCredentialsService.DEVSTORAGE_READ_WRITE_SCOPE),
                "1200s");

		assertTrue(token.accessToken().startsWith(IamCredentialsService.TOKEN_PREFIX));
		assertEquals(NOW.plusSeconds(1200), token.expireTime());
		Optional<StoredCredentialToken> stored = tokenService.lookupBearerToken(token.accessToken());
		assertTrue(stored.isPresent());
		assertEquals(StoredCredentialToken.TokenKind.IMPERSONATED, stored.get().getTokenKind());
		assertEquals(SERVICE_ACCOUNT, stored.get().getPrincipal());
	}

    @Test
    void defaultsLifetimeWhenAbsent() {
        IamCredentialsService.GeneratedAccessToken token = service.generateAccessToken(
                SERVICE_ACCOUNT,
                List.of(IamCredentialsService.DEVSTORAGE_READ_WRITE_SCOPE),
                null);

        assertEquals(NOW.plusSeconds(IamCredentialsService.DEFAULT_LIFETIME_SECONDS), token.expireTime());
    }

    @Test
    void capsLifetimeAtOneHour() {
        IamCredentialsService.GeneratedAccessToken token = service.generateAccessToken(
                SERVICE_ACCOUNT,
                List.of(IamCredentialsService.DEVSTORAGE_READ_WRITE_SCOPE),
                "7200s");

        assertEquals(NOW.plusSeconds(IamCredentialsService.MAX_LIFETIME_SECONDS), token.expireTime());
    }

    @Test
    void acceptsArbitraryServiceAccountWithoutPreCreation() {
        IamCredentialsService.GeneratedAccessToken token = service.generateAccessToken(
                "not-created@example.iam.gserviceaccount.com",
                List.of(IamCredentialsService.DEVSTORAGE_READ_WRITE_SCOPE),
                "60s");

        assertTrue(token.accessToken().startsWith(IamCredentialsService.TOKEN_PREFIX));
    }

    @Test
    void acceptsCloudPlatformScope() {
        IamCredentialsService.GeneratedAccessToken token = service.generateAccessToken(
                SERVICE_ACCOUNT,
                List.of(IamCredentialsService.CLOUD_PLATFORM_SCOPE),
                "60s");

        assertTrue(token.accessToken().startsWith(IamCredentialsService.TOKEN_PREFIX));
    }

    @Test
    void rejectsBlankServiceAccount() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.generateAccessToken(
                        " ",
                        List.of(IamCredentialsService.DEVSTORAGE_READ_WRITE_SCOPE),
                        "60s"));

        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void rejectsMissingScope() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.generateAccessToken(SERVICE_ACCOUNT, null, "60s"));

        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void rejectsEmptyScope() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.generateAccessToken(SERVICE_ACCOUNT, List.of(), "60s"));

        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void rejectsUnsupportedScope() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.generateAccessToken(
                        SERVICE_ACCOUNT,
                        List.of("https://www.googleapis.com/auth/userinfo.email"),
                        "60s"));

        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void rejectsMalformedLifetime() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.generateAccessToken(
                        SERVICE_ACCOUNT,
                        List.of(IamCredentialsService.DEVSTORAGE_READ_WRITE_SCOPE),
                        "one-hour"));

        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void rejectsZeroLifetime() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.generateAccessToken(
                        SERVICE_ACCOUNT,
                        List.of(IamCredentialsService.DEVSTORAGE_READ_WRITE_SCOPE),
                        "0s"));

        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void rejectsNegativeLifetime() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.generateAccessToken(
                        SERVICE_ACCOUNT,
                        List.of(IamCredentialsService.DEVSTORAGE_READ_WRITE_SCOPE),
                        "-1s"));

        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }
}

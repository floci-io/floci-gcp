package io.floci.gcp.services.sts;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.credentials.CredentialAccessBoundaryParser;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import io.floci.gcp.services.credentials.CredentialTokenService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StsServiceTest {

	private final InMemoryStorage<String, StoredCredentialToken> store = new InMemoryStorage<>();
	private final CredentialTokenService tokenService =
			new CredentialTokenService(store, Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC));
	private final StsService service = new StsService(
			new CredentialAccessBoundaryParser(new ObjectMapper()),
			tokenService);

	@Test
	void exchangesSupportedCabForStoredDownscopedToken() {
		Map<String, Object> response = validExchange();

		String accessToken = (String) response.get("access_token");
		assertTrue(accessToken.startsWith(CredentialTokenService.DOWNSCOPED_TOKEN_PREFIX));
		assertEquals(StsService.ACCESS_TOKEN_TYPE, response.get("issued_token_type"));
		assertEquals(StsService.BEARER_TOKEN_TYPE, response.get("token_type"));
		assertEquals(CredentialTokenService.DEFAULT_LIFETIME_SECONDS, response.get("expires_in"));
		assertTrue(store.get(accessToken).isPresent());
	}

	@Test
	void rejectsWrongGrantTypeAsInvalidRequest() {
		StsException ex = assertThrows(StsException.class,
				() -> service.exchangeToken("bad", StsService.ACCESS_TOKEN_TYPE, "source-token",
						StsService.ACCESS_TOKEN_TYPE, cab()));

		assertEquals("invalid_request", ex.error());
	}

	@Test
	void rejectsUnsupportedCabAsInvalidGrant() {
		StsException ex = assertThrows(StsException.class,
				() -> service.exchangeToken(StsService.TOKEN_EXCHANGE_GRANT_TYPE, StsService.ACCESS_TOKEN_TYPE,
						"source-token", StsService.ACCESS_TOKEN_TYPE, cab().replace(
								"inRole:roles/storage.legacyObjectReader", "inRole:roles/storage.admin")));

		assertEquals("invalid_grant", ex.error());
	}

	private Map<String, Object> validExchange() {
		return service.exchangeToken(
				StsService.TOKEN_EXCHANGE_GRANT_TYPE,
				StsService.ACCESS_TOKEN_TYPE,
				"source-token",
				StsService.ACCESS_TOKEN_TYPE,
				cab());
	}

	static String cab() {
		return """
				{
				  "accessBoundary": {
					"accessBoundaryRules": [
					  {
						"availableResource": "//storage.googleapis.com/projects/_/buckets/bucket",
						"availablePermissions": ["%s"],
						"availabilityCondition": {
						  "expression": "resource.name.startsWith('projects/_/buckets/bucket/objects/data/')"
						}
					  }
					]
				  }
				}
				""".formatted("inRole:roles/storage.legacyObjectReader");
	}
}

package io.floci.gcp.services.iamcredentials;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.SigningKeyFixtures;
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import io.floci.gcp.services.iam.model.StoredSigningKey;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    private static final String SIGNER = "signer@p1.iam.gserviceaccount.com";

    private final InMemoryStorage<String, StoredSigningKey> signingKeys = new InMemoryStorage<>();
    private final IamService iam = SigningKeyFixtures.iamService(signingKeys);
    private final IamCredentialsService signingService =
            new IamCredentialsService(tokenService, iam, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void signBlobSignsThePayloadWithTheAccountsKey() throws Exception {
        iam.createServiceAccount("p1", "signer", "", "");
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);

        Map<String, String> response = signingService.signBlob(SIGNER, Base64.getEncoder().encodeToString(data));

        assertEquals(List.of("keyId", "signedBlob"), List.copyOf(response.keySet()));
        StoredSigningKey key = signingKeys.scan(k -> true).get(0);
        assertEquals(key.getKeyId(), response.get("keyId"));
        assertTrue(SigningKeyFixtures.verifies(SigningKeyFixtures.publicKey(key), data,
                Base64.getDecoder().decode(response.get("signedBlob"))));
    }

    @Test
    void signBlobRequiresABase64Payload() {
        iam.createServiceAccount("p1", "signer", "", "");

        assertInvalidArgument(() -> signingService.signBlob(SIGNER, null));
        assertInvalidArgument(() -> signingService.signBlob(SIGNER, "not base64!"));
    }

    @Test
    void signJwtReturnsAnRs256JwtNamingItsKey() throws Exception {
        iam.createServiceAccount("p1", "signer", "", "");
        String claims = "{\"sub\":\"user\",\"exp\":" + NOW.plusSeconds(600).getEpochSecond() + "}";

        Map<String, String> response = signingService.signJwt(SIGNER, claims);

        String[] parts = response.get("signedJwt").split("\\.");
        assertEquals(3, parts.length);
        JsonNode header = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertEquals("RS256", header.get("alg").asText());
        assertEquals("JWT", header.get("typ").asText());
        assertEquals(response.get("keyId"), header.get("kid").asText());
        assertEquals(claims, new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        StoredSigningKey key = signingKeys.scan(k -> true).get(0);
        assertEquals(key.getKeyId(), response.get("keyId"));
        assertTrue(SigningKeyFixtures.verifies(SigningKeyFixtures.publicKey(key),
                (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII),
                Base64.getUrlDecoder().decode(parts[2])));
    }

    @Test
    void signJwtRejectsAnExpiryInThePastBeyondTwelveHoursOrNotAnInteger() {
        iam.createServiceAccount("p1", "signer", "", "");
        long now = NOW.getEpochSecond();

        assertInvalidArgument(() -> signingService.signJwt(SIGNER, "{\"exp\":" + (now - 1) + "}"));
        assertInvalidArgument(() -> signingService.signJwt(SIGNER, "{\"exp\":" + (now + 12 * 3600 + 1) + "}"));
        assertInvalidArgument(() -> signingService.signJwt(SIGNER, "{\"exp\":\"soon\"}"));
        assertInvalidArgument(() -> signingService.signJwt(SIGNER, "{\"exp\":" + now + ".5}"));
        signingService.signJwt(SIGNER, "{\"exp\":" + (now + 12 * 3600) + "}");
    }

    @Test
    void signJwtRequiresAJsonObjectOfClaims() {
        iam.createServiceAccount("p1", "signer", "", "");

        assertInvalidArgument(() -> signingService.signJwt(SIGNER, null));
        assertInvalidArgument(() -> signingService.signJwt(SIGNER, "not json"));
        assertInvalidArgument(() -> signingService.signJwt(SIGNER, "[1,2]"));
    }

    @Test
    void generateIdTokenSignsItsClaimsWithTheAccountsKeyAndOneHourExpiry() throws Exception {
        iam.createServiceAccount("p1", "signer", "", "");
        String uniqueId = iam.getServiceAccount("p1", SIGNER).getUniqueId();

        Map<String, String> response = signingService.generateIdToken(SIGNER, "https://foo.bar", false);

        assertEquals(List.of("token"), List.copyOf(response.keySet()));
        String[] parts = response.get("token").split("\\.");
        assertEquals(3, parts.length);
        StoredSigningKey key = signingKeys.scan(k -> true).get(0);
        ObjectMapper json = new ObjectMapper();
        assertEquals(key.getKeyId(), json.readTree(Base64.getUrlDecoder().decode(parts[0])).get("kid").asText());
        assertTrue(SigningKeyFixtures.verifies(SigningKeyFixtures.publicKey(key),
                (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII),
                Base64.getUrlDecoder().decode(parts[2])));
        assertEquals(json.createObjectNode()
                        .put("aud", "https://foo.bar")
                        .put("azp", uniqueId)
                        .put("exp", NOW.getEpochSecond() + 3600)
                        .put("iat", NOW.getEpochSecond())
                        .put("iss", "https://accounts.google.com")
                        .put("sub", uniqueId)
                        .toString(),
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }

    @Test
    void generateIdTokenAddsEmailClaimsOnlyWhenAsked() throws Exception {
        iam.createServiceAccount("p1", "signer", "", "");

        String token = signingService.generateIdToken(SIGNER, "https://foo.bar", true).get("token");

        JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertEquals(SIGNER, claims.get("email").asText());
        assertTrue(claims.get("email_verified").asBoolean());
    }

    @Test
    void generateIdTokenClaimsNameTheAccountWhoseKeySignsWhenTheAccountIsReplaced() throws Exception {
        Runnable[] onSecondRead = new Runnable[1];
        int[] reads = new int[1];
        InMemoryStorage<String, StoredSigningKey> keys = new InMemoryStorage<>();
        IamService racing = SigningKeyFixtures.iamService(new InMemoryStorage<>() {
            @Override
            public Optional<StoredServiceAccount> get(String key) {
                Optional<StoredServiceAccount> read = super.get(key);
                // Resolving the address reads first; the read after it is the one claims come from.
                if (onSecondRead[0] != null && ++reads[0] == 2) {
                    onSecondRead[0].run();
                }
                return read;
            }
        }, keys);
        IamCredentialsService credentials = new IamCredentialsService(tokenService, racing, Clock.fixed(NOW, ZoneOffset.UTC));
        racing.createServiceAccount("p1", "signer", "", "");
        String firstId = racing.getServiceAccount("p1", SIGNER).getUniqueId();
        String firstKey = racing.signingKeyId("p1", SIGNER);
        // Another request replaces the account right after a read. Inside the lock deletion takes it
        // has to wait, so give it a moment rather than waiting for it.
        Thread replacing = new Thread(() -> {
            racing.deleteServiceAccount("p1", SIGNER);
            racing.createServiceAccount("p1", "signer", "", "");
        });
        onSecondRead[0] = () -> {
            replacing.start();
            try {
                replacing.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        String[] parts = credentials.generateIdToken(SIGNER, "https://foo.bar", false).get("token").split("\\.");
        replacing.join(5000);

        ObjectMapper json = new ObjectMapper();
        String kid = json.readTree(Base64.getUrlDecoder().decode(parts[0])).get("kid").asText();
        String sub = json.readTree(Base64.getUrlDecoder().decode(parts[1])).get("sub").asText();
        String secondId = racing.getServiceAccount("p1", SIGNER).getUniqueId();
        assertNotEquals(firstId, secondId);
        assertEquals(kid.equals(firstKey) ? firstId : secondId, sub);
    }

    @Test
    void generateIdTokenRequiresAnAudience() {
        iam.createServiceAccount("p1", "signer", "", "");

        assertInvalidArgument(() -> signingService.generateIdToken(SIGNER, null, false));
        assertInvalidArgument(() -> signingService.generateIdToken(SIGNER, "", false));
    }

    private static void assertInvalidArgument(org.junit.jupiter.api.function.Executable call) {
        GcpException e = assertThrows(GcpException.class, call);
        assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, e.getGrpcCode());
    }

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

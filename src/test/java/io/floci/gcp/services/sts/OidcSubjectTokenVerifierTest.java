package io.floci.gcp.services.sts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OidcSubjectTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");
    private static final String ISSUER = "https://token.actions.githubusercontent.com";
    private static final String PROVIDER_NAME =
            "projects/123456789/locations/global/workloadIdentityPools/gh-pool/providers/gh-provider";

    private final ObjectMapper mapper = new ObjectMapper();
    private final OidcSubjectTokenVerifier verifier =
            new OidcSubjectTokenVerifier(mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void verifiesValidRs256JwtWithAllowedAudience() {
        KeyPair kp = StsFederationTestFixtures.generateRsaKeyPair();
        String jwks = StsFederationTestFixtures.rsaJwksJson("rsa-1", (RSAPublicKey) kp.getPublic());
        ObjectNode provider = oidcProvider(ISSUER, List.of("https://custom.aud"), jwks);

        String jwt = StsFederationTestFixtures.signJwt("RS256", "rsa-1",
                StsFederationTestFixtures.oidcClaims(ISSUER, "https://custom.aud", "user-42",
                        NOW, 600, Map.of("repository", "my-org/my-repo")),
                kp.getPrivate());

        Map<String, Object> assertion = verifier.verify(jwt, provider, PROVIDER_NAME);
        assertEquals("user-42", assertion.get("sub"));
        assertEquals("my-org/my-repo", assertion.get("repository"));
    }

    @Test
    void verifiesValidEs256JwtWithDefaultProviderAudience() {
        KeyPair kp = StsFederationTestFixtures.generateEcP256KeyPair();
        String jwks = StsFederationTestFixtures.ecJwksJson("ec-1", (ECPublicKey) kp.getPublic());
        ObjectNode provider = oidcProvider(ISSUER, List.of(), jwks);

        String jwt = StsFederationTestFixtures.signJwt("ES256", "ec-1",
                StsFederationTestFixtures.oidcClaims(ISSUER, "https://iam.googleapis.com/" + PROVIDER_NAME,
                        "ec-user", NOW, 600, Map.of()),
                kp.getPrivate());

        Map<String, Object> assertion = verifier.verify(jwt, provider, PROVIDER_NAME);
        assertEquals("ec-user", assertion.get("sub"));
    }

    @Test
    void rejectsInvalidIssuerAudienceSignatureExpiredAndMissingJwks() {
        KeyPair kp = StsFederationTestFixtures.generateRsaKeyPair();
        KeyPair wrongKp = StsFederationTestFixtures.generateRsaKeyPair();
        String jwks = StsFederationTestFixtures.rsaJwksJson("rsa-1", (RSAPublicKey) kp.getPublic());
        ObjectNode provider = oidcProvider(ISSUER, List.of("https://expected.aud"), jwks);

        // Wrong issuer
        String wrongIss = StsFederationTestFixtures.signJwt("RS256", "rsa-1",
                StsFederationTestFixtures.oidcClaims("https://evil.example", "https://expected.aud",
                        "u", NOW, 600, Map.of()), kp.getPrivate());
        assertInvalidGrant(() -> verifier.verify(wrongIss, provider, PROVIDER_NAME));

        // Wrong audience
        String wrongAud = StsFederationTestFixtures.signJwt("RS256", "rsa-1",
                StsFederationTestFixtures.oidcClaims(ISSUER, "https://wrong.aud",
                        "u", NOW, 600, Map.of()), kp.getPrivate());
        assertInvalidGrant(() -> verifier.verify(wrongAud, provider, PROVIDER_NAME));

        // Signed with wrong key
        String badSig = StsFederationTestFixtures.signJwt("RS256", "rsa-1",
                StsFederationTestFixtures.oidcClaims(ISSUER, "https://expected.aud",
                        "u", NOW, 600, Map.of()), wrongKp.getPrivate());
        assertInvalidGrant(() -> verifier.verify(badSig, provider, PROVIDER_NAME));

        // Unknown kid
        String unknownKid = StsFederationTestFixtures.signJwt("RS256", "other-kid",
                StsFederationTestFixtures.oidcClaims(ISSUER, "https://expected.aud",
                        "u", NOW, 600, Map.of()), kp.getPrivate());
        assertInvalidGrant(() -> verifier.verify(unknownKid, provider, PROVIDER_NAME));

        // Expired token
        String expired = StsFederationTestFixtures.signJwt("RS256", "rsa-1",
                StsFederationTestFixtures.oidcClaims(ISSUER, "https://expected.aud",
                        "u", NOW.minusSeconds(1200), 600, Map.of()), kp.getPrivate());
        assertInvalidGrant(() -> verifier.verify(expired, provider, PROVIDER_NAME));

        // alg=none unsigned token
        String algNoneHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claimsPart = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"iss\":\"" + ISSUER + "\",\"aud\":\"https://expected.aud\",\"sub\":\"u\",\"exp\":"
                        + (NOW.getEpochSecond() + 600) + "}").getBytes(StandardCharsets.UTF_8));
        assertInvalidGrant(() -> verifier.verify(algNoneHeader + "." + claimsPart + ".", provider, PROVIDER_NAME));

        // Missing jwksJson
        ObjectNode noJwksProvider = oidcProvider(ISSUER, List.of("https://expected.aud"), "");
        String validJwt = StsFederationTestFixtures.signJwt("RS256", "rsa-1",
                StsFederationTestFixtures.oidcClaims(ISSUER, "https://expected.aud",
                        "u", NOW, 600, Map.of()), kp.getPrivate());
        assertInvalidGrant(() -> verifier.verify(validJwt, noJwksProvider, PROVIDER_NAME));
    }

    @Test
    void rejectsEcCurveAndJwkAlgMismatches() {
        KeyPair ec256 = StsFederationTestFixtures.generateEcP256KeyPair();
        String ecJwks = StsFederationTestFixtures.ecJwksJson("ec-1", (ECPublicKey) ec256.getPublic());
        ObjectNode ecProvider = oidcProvider(ISSUER, List.of("https://expected.aud"), ecJwks);

        // ES512 header signed with a P-256 key must be rejected per RFC 7518
        String mismatchedCurveJwt = StsFederationTestFixtures.signJwt("ES512", "ec-1",
                StsFederationTestFixtures.oidcClaims(ISSUER, "https://expected.aud",
                        "u", NOW, 600, Map.of()), ec256.getPrivate());
        assertInvalidGrant(() -> verifier.verify(mismatchedCurveJwt, ecProvider, PROVIDER_NAME));

        // RSA JWK restricting alg to RS256 must reject RS384 token
        KeyPair rsa = StsFederationTestFixtures.generateRsaKeyPair();
        String rsaJwks = StsFederationTestFixtures.rsaJwksJson("rsa-1", (RSAPublicKey) rsa.getPublic());
        ObjectNode rsaProvider = oidcProvider(ISSUER, List.of("https://expected.aud"), rsaJwks);
        String rs384Jwt = StsFederationTestFixtures.signJwt("RS384", "rsa-1",
                StsFederationTestFixtures.oidcClaims(ISSUER, "https://expected.aud",
                        "u", NOW, 600, Map.of()), rsa.getPrivate());
        assertInvalidGrant(() -> verifier.verify(rs384Jwt, rsaProvider, PROVIDER_NAME));
    }

    private ObjectNode oidcProvider(String issuer, List<String> allowedAudiences, String jwksJson) {
        ObjectNode provider = mapper.createObjectNode();
        ObjectNode oidc = provider.putObject("oidc");
        oidc.put("issuerUri", issuer);
        if (!allowedAudiences.isEmpty()) {
            ArrayNode auds = oidc.putArray("allowedAudiences");
            allowedAudiences.forEach(auds::add);
        }
        if (jwksJson != null && !jwksJson.isBlank()) {
            oidc.put("jwksJson", jwksJson);
        }
        return provider;
    }

    private static void assertInvalidGrant(Runnable action) {
        StsException ex = assertThrows(StsException.class, action::run);
        assertEquals("invalid_grant", ex.error());
    }
}

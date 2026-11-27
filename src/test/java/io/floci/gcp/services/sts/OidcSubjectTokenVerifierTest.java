package io.floci.gcp.services.sts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

        // Missing jwksJson with non-HTTP issuer cannot discover JWKS
        String nonHttpIssuer = "urn:example:issuer";
        ObjectNode noJwksProvider = oidcProvider(nonHttpIssuer, List.of("https://expected.aud"), "");
        String validJwt = StsFederationTestFixtures.signJwt("RS256", "rsa-1",
                StsFederationTestFixtures.oidcClaims(nonHttpIssuer, "https://expected.aud",
                        "u", NOW, 600, Map.of()), kp.getPrivate());
        assertInvalidGrant(() -> verifier.verify(validJwt, noJwksProvider, PROVIDER_NAME));
    }

    @Test
    void verifiesJwtViaOidcDiscoveryAndRefreshesOnKeyRotation() throws IOException {
        KeyPair firstKey = StsFederationTestFixtures.generateRsaKeyPair();
        KeyPair rotatedKey = StsFederationTestFixtures.generateRsaKeyPair();
        AtomicReference<String> currentJwks = new AtomicReference<>(
                StsFederationTestFixtures.rsaJwksJson("key-1", (RSAPublicKey) firstKey.getPublic()));
        AtomicInteger jwksFetches = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String issuer = "http://127.0.0.1:" + server.getAddress().getPort();
        String discoveryJson = "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer + "/jwks\"}";
        server.createContext("/.well-known/openid-configuration", exchange -> {
            byte[] body = discoveryJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/jwks", exchange -> {
            jwksFetches.incrementAndGet();
            byte[] body = currentJwks.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            AtomicReference<Instant> currentTime = new AtomicReference<>(NOW);
            Clock mutableClock = new Clock() {
                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return currentTime.get();
                }
            };
            OidcSubjectTokenVerifier discoveryVerifier =
                    new OidcSubjectTokenVerifier(mapper, mutableClock);
            ObjectNode provider = oidcProvider(issuer, List.of("https://expected.aud"), "");

            String jwt1 = StsFederationTestFixtures.signJwt("RS256", "key-1",
                    StsFederationTestFixtures.oidcClaims(issuer, "https://expected.aud",
                            "alice", NOW, 600, Map.of()), firstKey.getPrivate());
            assertEquals("alice", discoveryVerifier.verify(jwt1, provider, PROVIDER_NAME).get("sub"));
            assertEquals("alice", discoveryVerifier.verify(jwt1, provider, PROVIDER_NAME).get("sub"));
            assertEquals(1, jwksFetches.get(), "second verification should use cached JWKS");

            // Bad signature within refresh cooldown must not trigger another outbound JWKS fetch
            String badJwt = StsFederationTestFixtures.signJwt("RS256", "key-1",
                    StsFederationTestFixtures.oidcClaims(issuer, "https://expected.aud",
                            "mallory", NOW, 600, Map.of()), rotatedKey.getPrivate());
            assertInvalidGrant(() -> discoveryVerifier.verify(badJwt, provider, PROVIDER_NAME));
            assertEquals(1, jwksFetches.get(), "bad token within cooldown should not refetch JWKS");

            // After cooldown elapses, rotate JWKS to key-2; verifier should refresh once and accept key-2
            currentTime.set(NOW.plus(OidcSubjectTokenVerifier.JWKS_REFRESH_COOLDOWN).plusSeconds(1));
            currentJwks.set(StsFederationTestFixtures.rsaJwksJson("key-2", (RSAPublicKey) rotatedKey.getPublic()));
            String jwt2 = StsFederationTestFixtures.signJwt("RS256", "key-2",
                    StsFederationTestFixtures.oidcClaims(issuer, "https://expected.aud",
                            "bob", NOW, 600, Map.of()), rotatedKey.getPrivate());
            assertEquals("bob", discoveryVerifier.verify(jwt2, provider, PROVIDER_NAME).get("sub"));
            assertEquals(2, jwksFetches.get(), "key rotation after cooldown should trigger one JWKS refresh");

            // Discovery issuer mismatch must be rejected
            String mismatchedIssuer = issuer + "/other";
            ObjectNode mismatchedProvider = oidcProvider(mismatchedIssuer, List.of("https://expected.aud"), "");
            String mismatchedJwt = StsFederationTestFixtures.signJwt("RS256", "key-2",
                    StsFederationTestFixtures.oidcClaims(mismatchedIssuer, "https://expected.aud",
                            "bob", NOW, 600, Map.of()), rotatedKey.getPrivate());
            assertInvalidGrant(() -> discoveryVerifier.verify(mismatchedJwt, mismatchedProvider, PROVIDER_NAME));

            // Link-local metadata issuer must be rejected before connecting
            String linkLocalIssuer = "http://169.254.169.254";
            ObjectNode linkLocalProvider = oidcProvider(linkLocalIssuer, List.of("https://expected.aud"), "");
            String linkLocalJwt = StsFederationTestFixtures.signJwt("RS256", "key-2",
                    StsFederationTestFixtures.oidcClaims(linkLocalIssuer, "https://expected.aud",
                            "bob", NOW, 600, Map.of()), rotatedKey.getPrivate());
            assertInvalidGrant(() -> discoveryVerifier.verify(linkLocalJwt, linkLocalProvider, PROVIDER_NAME));
        } finally {
            server.stop(0);
        }
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

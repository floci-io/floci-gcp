package io.floci.gcp.services.credentials;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.iam.model.StoredServiceAccountKey;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * The JWT bearer grant of https://developers.google.com/identity/protocols/oauth2/service-account,
 * verified in IAM enforce mode against the account's user-managed keys.
 */
@QuarkusTest
@TestProfile(OAuthTokenEnforceRestIntegrationTest.EnforceProfile.class)
class OAuthTokenEnforceRestIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUDIENCE = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    @Inject
    IamService iam;

    private String project;
    private String email;

    @BeforeEach
    void setUp() {
        project = "oauth-" + UUID.randomUUID().toString().substring(0, 8);
        email = iam.createServiceAccount(project, "app", null, null).getEmail();
    }

    @Test
    void jwtSignedWithTheAccountsKeyGetsATokenThatActsAsTheAccount() throws Exception {
        KeyFile key = createKey(email);
        String token = exchange("/token", assertion(key, claims(key.email(), AUDIENCE, 0, 3600), "RS256", key.keyId()))
                .then().statusCode(200)
                .body("token_type", equalTo("Bearer"))
                .body("expires_in", equalTo(3600))
                .body("access_token", startsWith(CredentialTokenService.FLOCI_TOKEN_PREFIX))
                .extract().path("access_token");

        String accountPath = "/v1/projects/" + project + "/serviceAccounts/" + email;
        given().header("Authorization", "Bearer " + token).when().get(accountPath)
                .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
        grant("projects/" + project, "roles/iam.serviceAccountAdmin", email);
        given().header("Authorization", "Bearer " + token).when().get(accountPath)
                .then().statusCode(200).body("email", equalTo(email));
    }

    @Test
    void jwtSignedWithADeletedKeyIsAnInvalidGrant() throws Exception {
        KeyFile key = createKey(email);
        String assertion = assertion(key, claims(key.email(), AUDIENCE, 0, 3600), "RS256", key.keyId());
        exchange("/token", assertion).then().statusCode(200);

        iam.deleteKey(project, email, key.keyId());

        invalidGrant(exchange("/token", assertion), OAuthTokenService.INVALID_SIGNATURE);
    }

    @Test
    void jwtOfADeletedAccountIsAnInvalidGrant() throws Exception {
        KeyFile key = createKey(email);
        iam.deleteServiceAccount(project, email);

        invalidGrant(exchange("/token", assertion(key, claims(key.email(), AUDIENCE, 0, 3600), "RS256", key.keyId())),
                OAuthTokenService.INVALID_SIGNATURE);
    }

    @Test
    void jwtSignedWithAnotherAccountsKeyIsAnInvalidGrant() throws Exception {
        String other = iam.createServiceAccount(project, "other", null, null).getEmail();
        KeyFile otherKey = createKey(other);

        invalidGrant(exchange("/token", assertion(otherKey, claims(email, AUDIENCE, 0, 3600), "RS256",
                otherKey.keyId())), OAuthTokenService.INVALID_SIGNATURE);
    }

    @Test
    void anIncorrectKeyIdFallsBackToTheAccountsOtherKeys() throws Exception {
        KeyFile key = createKey(email);
        createKey(email);

        exchange("/token", assertion(key, claims(key.email(), AUDIENCE, 0, 3600), "RS256", "not-a-key-id"))
                .then().statusCode(200);
    }

    @Test
    void malformedOrUnsignedAssertionsAreInvalidGrants() throws Exception {
        KeyFile key = createKey(email);
        String signed = assertion(key, claims(key.email(), AUDIENCE, 0, 3600), "RS256", key.keyId());
        String unsigned = signed.substring(0, signed.lastIndexOf('.') + 1);

        invalidGrant(exchange("/token", "header.payload.signature"), OAuthTokenService.INVALID_SIGNATURE);
        invalidGrant(exchange("/token", unsigned), OAuthTokenService.INVALID_SIGNATURE);
        invalidGrant(exchange("/token", assertion(key, claims(key.email(), AUDIENCE, 0, 3600), "none", key.keyId())),
                OAuthTokenService.INVALID_SIGNATURE);
    }

    @Test
    void assertionTimesOutsideTheDocumentedWindowAreInvalidGrants() throws Exception {
        KeyFile key = createKey(email);

        for (long[] window : new long[][] {{0, 66 * 60}, {0, -1}, {-7200, -3600}}) {
            invalidGrant(exchange("/token", assertion(key, claims(key.email(), AUDIENCE, window[0], window[1]),
                    "RS256", key.keyId())), OAuthTokenService.INVALID_TIMEFRAME);
        }
    }

    @Test
    void audienceMustNameTheTokenEndpoint() throws Exception {
        KeyFile key = createKey(email);

        invalidGrant(exchange("/token", assertion(key, claims(key.email(), "https://example.com/token", 0, 3600),
                "RS256", key.keyId())), "Invalid JWT Signature.");
        String emulatorAudience = RestAssured.baseURI + ":" + RestAssured.port + "/token";
        exchange("/token", assertion(key, claims(key.email(), emulatorAudience, 0, 3600), "RS256", key.keyId()))
                .then().statusCode(200);
    }

    @Test
    void aMissingOrEmptyScopeIsAnInvalidScope() throws Exception {
        KeyFile key = createKey(email);
        Map<String, Object> missing = claims(key.email(), AUDIENCE, 0, 3600);
        missing.remove("scope");
        Map<String, Object> empty = claims(key.email(), AUDIENCE, 0, 3600);
        empty.put("scope", "");
        Map<String, Object> blank = claims(key.email(), AUDIENCE, 0, 3600);
        blank.put("scope", "   ");

        for (Map<String, Object> claims : List.of(missing, empty, blank)) {
            exchange("/token", assertion(key, claims, "RS256", key.keyId())).then().statusCode(400)
                    .body("error", equalTo("invalid_scope"))
                    .body("error_description", equalTo("Invalid OAuth scope or ID token audience provided."));
        }
    }

    @Test
    void scopesOfGoogleApisAreAcceptedEvenWhenFlociDoesNotEmulateTheApi() throws Exception {
        KeyFile key = createKey(email);

        for (String scope : List.of("https://www.googleapis.com/auth/gmail.readonly",
                SCOPE + "  https://www.googleapis.com/auth/admin.directory.user openid email profile")) {
            Map<String, Object> claims = claims(key.email(), AUDIENCE, 0, 3600);
            claims.put("scope", scope);
            exchange("/token", assertion(key, claims, "RS256", key.keyId())).then().statusCode(200);
        }
    }

    @Test
    void anUnknownScopeIsAnInvalidScopeAloneOrAmongValidOnes() throws Exception {
        KeyFile key = createKey(email);

        for (String scope : List.of("https://www.googleapis.com/auth/not-a-google-scope",
                SCOPE + " https://www.googleapis.com/auth/not-a-google-scope", "cloud-platform")) {
            Map<String, Object> claims = claims(key.email(), AUDIENCE, 0, 3600);
            claims.put("scope", scope);
            exchange("/token", assertion(key, claims, "RS256", key.keyId())).then().statusCode(400)
                    .body("error", equalTo("invalid_scope"))
                    .body("error_description", equalTo("Invalid OAuth scope or ID token audience provided."));
        }
    }

    @Test
    void legacyV4EndpointAndJsonBodiesAreVerifiedToo() throws Exception {
        KeyFile key = createKey(email);
        String assertion = assertion(key, claims(key.email(), AUDIENCE, 0, 3600), "RS256", key.keyId());

        exchange("/oauth2/v4/token", assertion).then().statusCode(200);
        given().contentType("application/json")
                .body(Map.of("grant_type", OAuthTokenService.JWT_BEARER_GRANT_TYPE, "assertion", assertion))
                .when().post("/token")
                .then().statusCode(200);
        invalidGrant(exchange("/oauth2/v4/token", "header.payload.signature"), OAuthTokenService.INVALID_SIGNATURE);
        invalidGrant(given().contentType("application/json")
                .body(Map.of("grant_type", OAuthTokenService.JWT_BEARER_GRANT_TYPE,
                        "assertion", "header.payload.signature"))
                .when().post("/oauth2/v4/token"), OAuthTokenService.INVALID_SIGNATURE);
    }

    private KeyFile createKey(String account) throws Exception {
        StoredServiceAccountKey key = iam.createKey(project, account);
        JsonNode file = MAPPER.readTree(Base64.getDecoder().decode(key.getPrivateKeyData()));
        String pem = file.get("private_key").asText()
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        return new KeyFile(file.get("client_email").asText(), key.getKeyId(), privateKey);
    }

    private static Map<String, Object> claims(String issuer, String audience, long iatOffset, long expOffset) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("scope", SCOPE);
        claims.put("aud", audience);
        claims.put("iat", now + iatOffset);
        claims.put("exp", now + expOffset);
        return claims;
    }

    private static String assertion(KeyFile key, Map<String, Object> claims, String algorithm, String keyId)
            throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", algorithm);
        header.put("typ", "JWT");
        header.put("kid", keyId);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String input = encoder.encodeToString(MAPPER.writeValueAsBytes(header)) + "."
                + encoder.encodeToString(MAPPER.writeValueAsBytes(claims));
        return input + "." + encoder.encodeToString(sign(key.privateKey(), input));
    }

    private static byte[] sign(PrivateKey key, String input) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return signer.sign();
    }

    private static Response exchange(String path, String assertion) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", OAuthTokenService.JWT_BEARER_GRANT_TYPE)
                .formParam("assertion", assertion)
                .when().post(path);
    }

    private static void invalidGrant(Response response, String description) {
        response.then().statusCode(400)
                .body("error", equalTo("invalid_grant"))
                .body("error_description", equalTo(description));
    }

    private void grant(String resource, String role, String member) {
        StoredPolicy policy = new StoredPolicy();
        policy.setVersion(1);
        policy.setBindings(List.of(Map.of("role", role, "members", List.of("serviceAccount:" + member))));
        iam.setPolicy(resource, policy);
    }

    private record KeyFile(String email, String keyId, PrivateKey privateKey) {
    }

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }
}

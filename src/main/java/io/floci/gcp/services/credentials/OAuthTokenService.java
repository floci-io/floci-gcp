package io.floci.gcp.services.credentials;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The OAuth 2.0 token endpoint's JWT bearer grant, as described in
 * https://developers.google.com/identity/protocols/oauth2/service-account. In IAM enforce mode the
 * assertion is verified against the service account's user-managed keys and the token acts as that
 * account; otherwise any assertion gets an unrecognized token, as before.
 */
@ApplicationScoped
public class OAuthTokenService {

    static final String JWT_BEARER_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    static final String INVALID_SIGNATURE = "Invalid JWT Signature.";
    static final String INVALID_TIMEFRAME =
            "Invalid JWT: Token must be a short-lived token (60 minutes) and in a reasonable timeframe.";
    static final String INVALID_SCOPE = "Invalid OAuth scope or ID token audience provided.";
    /** Google documents that it refuses an {@code exp} more than 65 minutes after {@code iat}. */
    static final long MAX_ASSERTION_LIFETIME_SECONDS = 65 * 60;
    static final long TOKEN_LIFETIME_SECONDS = 3600;
    private static final Set<String> GOOGLE_AUDIENCES = Set.of(
            "https://oauth2.googleapis.com/token", "https://www.googleapis.com/oauth2/v4/token");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EmulatorConfig config;
    private final CredentialTokenService tokens;
    private final Instance<IamService> accounts;
    private final Clock clock;

    @Inject
    public OAuthTokenService(EmulatorConfig config, CredentialTokenService tokens, Instance<IamService> accounts) {
        this.config = config;
        this.tokens = tokens;
        this.accounts = accounts;
        this.clock = Clock.systemUTC();
    }

    /** {@code requestUrl} is where the client sent the assertion, which it may name as the audience. */
    public Response exchange(String grantType, String assertion, String requestUrl) {
        if (!JWT_BEARER_GRANT_TYPE.equals(grantType)) {
            return oauthError("unsupported_grant_type", "Unsupported grant type");
        }
        if (assertion == null || assertion.isBlank()) {
            return oauthError("invalid_grant", "Invalid JWT bearer token request");
        }
        if (config.services().iam().authorizationMode() != EmulatorConfig.IamAuthorizationMode.ENFORCE) {
            return tokenResponse(CredentialTokenService.FLOCI_TOKEN_PREFIX + UUID.randomUUID(), TOKEN_LIFETIME_SECONDS);
        }
        return verifiedExchange(assertion, requestUrl);
    }

    private Response verifiedExchange(String assertion, String requestUrl) {
        String[] parts = assertion.split("\\.", -1);
        if (parts.length != 3) {
            return oauthError("invalid_grant", INVALID_SIGNATURE);
        }
        JsonNode header;
        JsonNode claims;
        byte[] signature;
        try {
            header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException | IOException e) {
            // Google lists an assertion that is not Base64url-encoded JSON under the same error.
            return oauthError("invalid_grant", INVALID_SIGNATURE);
        }
        if (header == null || !header.isObject() || claims == null || !claims.isObject()) {
            return oauthError("invalid_grant", INVALID_SIGNATURE);
        }
        String email = claims.path("iss").asText(null);
        Map<String, PublicKey> keys = email == null ? Map.of()
                : accounts.get().userManagedPublicKeys(email, clock.instant());
        byte[] signedInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        if (!"RS256".equals(header.path("alg").asText(null))
                || !verifies(keys, header.path("kid").asText(null), signedInput, signature)) {
            return oauthError("invalid_grant", INVALID_SIGNATURE);
        }
        String audience = claims.path("aud").asText(null);
        if (audience == null || !(GOOGLE_AUDIENCES.contains(audience) || audience.equals(requestUrl))) {
            return oauthError("invalid_grant", INVALID_SIGNATURE);
        }
        JsonNode issuedAt = claims.get("iat");
        JsonNode expiresAt = claims.get("exp");
        if (issuedAt == null || !issuedAt.canConvertToLong() || expiresAt == null || !expiresAt.canConvertToLong()) {
            return oauthError("invalid_grant", INVALID_TIMEFRAME);
        }
        long iat = issuedAt.asLong();
        long exp = expiresAt.asLong();
        if (exp < iat || exp - iat > MAX_ASSERTION_LIFETIME_SECONDS || exp <= clock.instant().getEpochSecond()) {
            return oauthError("invalid_grant", INVALID_TIMEFRAME);
        }
        // Google answers invalid_scope when "No scopes were requested (empty list of scopes)"; scope names are
        // not checked against Google's catalog.
        JsonNode scope = claims.get("scope");
        if (scope == null || !scope.isTextual() || scope.asText().isBlank()) {
            return oauthError("invalid_scope", INVALID_SCOPE);
        }
        StoredCredentialToken token = tokens.mintImpersonatedToken(email,
                clock.instant().plusSeconds(TOKEN_LIFETIME_SECONDS));
        return tokenResponse(token.getTokenValue(), TOKEN_LIFETIME_SECONDS);
    }

    /** Google: "If an incorrect Key ID is specified, all keys associated with the service account are tried." */
    private static boolean verifies(Map<String, PublicKey> keys, String keyId, byte[] signedInput, byte[] signature) {
        List<PublicKey> candidates = new ArrayList<>();
        if (keyId != null && keys.containsKey(keyId)) {
            candidates.add(keys.get(keyId));
        }
        candidates.addAll(keys.values());
        for (PublicKey key : candidates) {
            try {
                Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(key);
                verifier.update(signedInput);
                if (verifier.verify(signature)) {
                    return true;
                }
            } catch (GeneralSecurityException e) {
                // A signature that does not parse for this key verifies nothing; the next key is tried.
                continue;
            }
        }
        return false;
    }

    private static Response tokenResponse(String accessToken, long expiresIn) {
        return Response.ok(Map.of(
                "access_token", accessToken,
                "token_type", "Bearer",
                "expires_in", expiresIn))
                .build();
    }

    private static Response oauthError(String error, String description) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("error_description", description);
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(body)
                .build();
    }
}

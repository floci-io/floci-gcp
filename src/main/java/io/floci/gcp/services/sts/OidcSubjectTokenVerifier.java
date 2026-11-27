package io.floci.gcp.services.sts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies external OIDC/JWT subject tokens ({@code urn:ietf:params:oauth:token-type:jwt} and
 * {@code urn:ietf:params:oauth:token-type:id_token}) against a Workload Identity Pool OIDC
 * provider's issuer, allowed audiences, timeframe, and inline {@code oidc.jwksJson} or OIDC
 * discovery ({@code /.well-known/openid-configuration} -> {@code jwks_uri}).
 */
@ApplicationScoped
public class OidcSubjectTokenVerifier {

    static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration JWKS_CACHE_TTL = Duration.ofMinutes(5);
    static final Duration JWKS_REFRESH_COOLDOWN = Duration.ofSeconds(30);

    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, CachedJwks> discoveredJwksCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> refreshLocks = new ConcurrentHashMap<>();

    @Inject
    public OidcSubjectTokenVerifier(ObjectMapper mapper) {
        this(mapper, Clock.systemUTC(), defaultHttpClient());
    }

    OidcSubjectTokenVerifier(ObjectMapper mapper, Clock clock) {
        this(mapper, clock, defaultHttpClient());
    }

    OidcSubjectTokenVerifier(ObjectMapper mapper, Clock clock, HttpClient httpClient) {
        this.mapper = mapper;
        this.clock = clock;
        this.httpClient = httpClient;
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Map<String, Object> verify(String subjectToken, JsonNode provider, String canonicalProviderName) {
        JsonNode oidc = provider.get("oidc");
        if (oidc == null || !oidc.isObject()) {
            throw new StsException("invalid_grant",
                    "The target Workload Identity Pool Provider is not configured for OIDC.");
        }

        String[] parts = subjectToken.split("\\.", -1);
        if (parts.length != 3) {
            throw new StsException("invalid_grant", "Malformed JWT subject_token.");
        }

        JsonNode header;
        JsonNode claims;
        byte[] signature;
        try {
            header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException | IOException e) {
            throw new StsException("invalid_grant", "Malformed JWT subject_token.");
        }
        if (header == null || !header.isObject() || claims == null || !claims.isObject()) {
            throw new StsException("invalid_grant", "Malformed JWT subject_token.");
        }

        String issuerUri = oidc.path("issuerUri").asText("");
        verifyIssuer(claims, issuerUri);
        verifyAudience(claims, oidc.get("allowedAudiences"), canonicalProviderName);
        verifyTimeframe(claims);
        verifySignature(header, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII),
                signature, issuerUri, oidc.path("jwksJson").asText(""));

        return toAssertionMap(claims);
    }

    private static void verifyIssuer(JsonNode claims, String expectedIssuer) {
        String actualIssuer = claims.path("iss").isTextual() ? claims.get("iss").asText() : null;
        if (actualIssuer == null || actualIssuer.isBlank() || !actualIssuer.equals(expectedIssuer)) {
            throw new StsException("invalid_grant", "The JWT issuer does not match the provider issuerUri.");
        }
    }

    private static void verifyAudience(JsonNode claims, JsonNode allowedAudiencesNode,
            String canonicalProviderName) {
        List<String> tokenAudiences = extractAudiences(claims.get("aud"));
        if (tokenAudiences.isEmpty()) {
            throw new StsException("invalid_grant", "The JWT aud claim is missing.");
        }

        Set<String> allowed = new LinkedHashSet<>();
        if (allowedAudiencesNode != null && allowedAudiencesNode.isArray() && !allowedAudiencesNode.isEmpty()) {
            for (JsonNode entry : allowedAudiencesNode) {
                if (entry.isTextual() && !entry.asText().isBlank()) {
                    allowed.add(entry.asText());
                }
            }
        } else {
            allowed.add("https://iam.googleapis.com/" + canonicalProviderName);
            allowed.add("//iam.googleapis.com/" + canonicalProviderName);
        }

        for (String aud : tokenAudiences) {
            if (allowed.contains(aud)) {
                return;
            }
        }
        throw new StsException("invalid_grant",
                "The JWT audience is not in the provider's allowed audiences.");
    }

    private static List<String> extractAudiences(JsonNode audNode) {
        if (audNode == null || audNode.isNull()) {
            return List.of();
        }
        if (audNode.isTextual()) {
            return audNode.asText().isBlank() ? List.of() : List.of(audNode.asText());
        }
        if (audNode.isArray()) {
            List<String> result = new ArrayList<>();
            for (JsonNode element : audNode) {
                if (element.isTextual() && !element.asText().isBlank()) {
                    result.add(element.asText());
                }
            }
            return result;
        }
        return List.of();
    }

    private void verifyTimeframe(JsonNode claims) {
        long now = clock.instant().getEpochSecond();
        JsonNode expNode = claims.get("exp");
        if (expNode == null || !expNode.canConvertToLong()) {
            throw new StsException("invalid_grant", "The JWT exp claim is missing or invalid.");
        }
        long exp = expNode.asLong();
        if (exp <= now) {
            throw new StsException("invalid_grant", "The JWT subject_token is expired.");
        }
        JsonNode nbfNode = claims.get("nbf");
        if (nbfNode != null && !nbfNode.isNull()) {
            if (!nbfNode.canConvertToLong() || nbfNode.asLong() > now + MAX_CLOCK_SKEW_SECONDS) {
                throw new StsException("invalid_grant", "The JWT subject_token is not yet valid.");
            }
        }
        JsonNode iatNode = claims.get("iat");
        if (iatNode != null && !iatNode.isNull()) {
            if (!iatNode.canConvertToLong() || iatNode.asLong() > now + MAX_CLOCK_SKEW_SECONDS) {
                throw new StsException("invalid_grant", "The JWT iat claim is invalid.");
            }
        }
    }

    private void verifySignature(JsonNode header, byte[] signedInput, byte[] signature,
            String issuerUri, String jwksJson) {
        String alg = header.path("alg").asText("");
        String jcaAlg = jcaAlgorithm(alg);
        if (jcaAlg == null) {
            throw new StsException("invalid_grant", "Unsupported JWT signing algorithm: " + alg);
        }
        String kid = header.path("kid").isTextual() ? header.get("kid").asText() : null;

        if (jwksJson != null && !jwksJson.isBlank()) {
            List<JwkKey> keys = parseJwks(jwksJson);
            if (verifyWithKeys(keys, alg, jcaAlg, kid, signedInput, signature)) {
                return;
            }
            throw new StsException("invalid_grant", "Invalid JWT signature.");
        }

        CachedJwks initial = resolveDiscoveredJwks(issuerUri, false);
        if (verifyWithKeys(initial.keys(), alg, jcaAlg, kid, signedInput, signature)) {
            return;
        }
        // Retry once with a fresh discovery fetch if the cached JWKS is past cooldown and may have rotated.
        if (canRefresh(initial)) {
            CachedJwks refreshed = resolveDiscoveredJwks(issuerUri, true);
            if (refreshed != initial
                    && verifyWithKeys(refreshed.keys(), alg, jcaAlg, kid, signedInput, signature)) {
                return;
            }
        }
        throw new StsException("invalid_grant", "Invalid JWT signature.");
    }

    private static boolean verifyWithKeys(List<JwkKey> keys, String alg, String jcaAlg, String kid,
            byte[] signedInput, byte[] signature) {
        String requiredKty = alg.startsWith("RS") ? "RSA" : "EC";
        String requiredCrv = expectedEcCurve(alg);

        List<JwkKey> candidates = new ArrayList<>();
        for (JwkKey key : keys) {
            if (!requiredKty.equals(key.kty())) {
                continue;
            }
            if (key.alg() != null && !key.alg().isBlank() && !alg.equals(key.alg())) {
                continue;
            }
            if (requiredCrv != null && !requiredCrv.equals(key.crv())) {
                continue;
            }
            if (kid != null && !kid.isBlank()) {
                if (kid.equals(key.kid())) {
                    candidates.add(key);
                }
            } else {
                candidates.add(key);
            }
        }

        for (JwkKey candidate : candidates) {
            try {
                Signature verifier = Signature.getInstance(jcaAlg);
                verifier.initVerify(candidate.publicKey());
                verifier.update(signedInput);
                if (verifier.verify(signature)) {
                    return true;
                }
            } catch (GeneralSecurityException expected) {
                // Signature does not verify with this candidate key; try remaining keys.
                continue;
            }
        }
        return false;
    }

    private boolean canRefresh(CachedJwks cached) {
        return !clock.instant().isBefore(cached.fetchedAt().plus(JWKS_REFRESH_COOLDOWN));
    }

    private CachedJwks resolveDiscoveredJwks(String issuerUri, boolean forceRefresh) {
        Instant now = clock.instant();
        CachedJwks cached = discoveredJwksCache.get(issuerUri);
        if (cached != null && cached.expiresAt().isAfter(now)
                && (!forceRefresh || !canRefresh(cached))) {
            return cached;
        }
        Object lock = refreshLocks.computeIfAbsent(issuerUri, key -> new Object());
        synchronized (lock) {
            now = clock.instant();
            cached = discoveredJwksCache.get(issuerUri);
            if (cached != null && cached.expiresAt().isAfter(now)
                    && (!forceRefresh || !canRefresh(cached))) {
                return cached;
            }
            URI discoveryUri = buildDiscoveryUri(issuerUri);
            String discoveryBody = fetchHttpGet(discoveryUri, "OIDC discovery document");
            JsonNode discovery;
            try {
                discovery = mapper.readTree(discoveryBody);
            } catch (IOException e) {
                throw new StsException("invalid_grant", "Invalid OIDC discovery document JSON.");
            }
            if (discovery == null || !discovery.isObject()) {
                throw new StsException("invalid_grant", "Invalid OIDC discovery document JSON.");
            }
            String discoveredIssuer = discovery.path("issuer").asText("");
            if (!discoveredIssuer.isBlank() && !discoveredIssuer.equals(issuerUri)) {
                throw new StsException("invalid_grant",
                        "OIDC discovery issuer does not match provider issuerUri.");
            }
            String jwksUriText = discovery.path("jwks_uri").asText("");
            if (jwksUriText.isBlank()) {
                throw new StsException("invalid_grant",
                        "OIDC discovery document is missing jwks_uri.");
            }
            boolean requireHttps = "https".equalsIgnoreCase(discoveryUri.getScheme());
            URI jwksUri = parseDiscoveryUri(jwksUriText, "OIDC jwks_uri", requireHttps);
            String jwksBody = fetchHttpGet(jwksUri, "OIDC JWKS");
            List<JwkKey> keys = parseJwks(jwksBody);
            CachedJwks updated = new CachedJwks(List.copyOf(keys), now, now.plus(JWKS_CACHE_TTL));
            discoveredJwksCache.put(issuerUri, updated);
            return updated;
        }
    }

    private static URI buildDiscoveryUri(String issuerUri) {
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new StsException("invalid_grant",
                    "OIDC provider has no jwksJson or discoverable issuerUri configured.");
        }
        String base = issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
        return parseDiscoveryUri(base + "/.well-known/openid-configuration", "OIDC discovery URI", false);
    }

    private static URI parseDiscoveryUri(String rawUri, String label, boolean requireHttps) {
        URI uri;
        try {
            uri = URI.create(rawUri);
        } catch (IllegalArgumentException e) {
            throw new StsException("invalid_grant", "Invalid " + label + ".");
        }
        String scheme = uri.getScheme();
        boolean allowedScheme = requireHttps
                ? "https".equalsIgnoreCase(scheme)
                : ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        if (!allowedScheme || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new StsException("invalid_grant", "Invalid " + label + ".");
        }
        validateHostAddress(uri.getHost(), label);
        return uri;
    }

    private static void validateHostAddress(String host, String label) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLinkLocalAddress()
                        || address.isAnyLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new StsException("invalid_grant", "Disallowed host for " + label + ".");
                }
            }
        } catch (UnknownHostException e) {
            throw new StsException("invalid_grant", "Failed to resolve host for " + label + ".");
        }
    }

    private String fetchHttpGet(URI uri, String label) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StsException("invalid_grant",
                        "Failed to fetch " + label + " (HTTP " + response.statusCode() + ").");
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StsException("invalid_grant", "Interrupted while fetching " + label + ".");
        } catch (IOException e) {
            throw new StsException("invalid_grant", "Failed to fetch " + label + ": " + e.getMessage());
        }
    }

    private List<JwkKey> parseJwks(String jwksJson) {
        JsonNode root;
        try {
            root = mapper.readTree(jwksJson);
        } catch (IOException e) {
            throw new StsException("invalid_grant", "Invalid OIDC provider jwksJson.");
        }
        JsonNode keysNode = root.get("keys");
        if (keysNode == null || !keysNode.isArray()) {
            throw new StsException("invalid_grant", "OIDC provider jwksJson has no keys array.");
        }
        List<JwkKey> result = new ArrayList<>();
        for (JsonNode jwk : keysNode) {
            if (!jwk.isObject()) {
                continue;
            }
            String use = jwk.path("use").asText("");
            if (!use.isBlank() && !"sig".equals(use)) {
                continue;
            }
            String kty = jwk.path("kty").asText("");
            String kid = jwk.path("kid").isTextual() ? jwk.get("kid").asText() : null;
            String jwkAlg = jwk.path("alg").isTextual() ? jwk.get("alg").asText() : null;
            String crv = jwk.path("crv").isTextual() ? jwk.get("crv").asText() : null;
            PublicKey key = parsePublicKey(kty, jwk);
            if (key != null) {
                result.add(new JwkKey(kty, kid, jwkAlg, crv, key));
            }
        }
        return result;
    }

    private static PublicKey parsePublicKey(String kty, JsonNode jwk) {
        try {
            if ("RSA".equals(kty)) {
                String n = jwk.path("n").asText("");
                String e = jwk.path("e").asText("");
                if (n.isBlank() || e.isBlank()) {
                    return null;
                }
                BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(n));
                BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(e));
                return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
            }
            if ("EC".equals(kty)) {
                String crv = jwk.path("crv").asText("");
                String x = jwk.path("x").asText("");
                String y = jwk.path("y").asText("");
                String stdCurve = ecCurveName(crv);
                if (stdCurve == null || x.isBlank() || y.isBlank()) {
                    return null;
                }
                BigInteger xCoord = new BigInteger(1, Base64.getUrlDecoder().decode(x));
                BigInteger yCoord = new BigInteger(1, Base64.getUrlDecoder().decode(y));
                AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
                params.init(new ECGenParameterSpec(stdCurve));
                ECParameterSpec ecSpec = params.getParameterSpec(ECParameterSpec.class);
                ECPoint point = new ECPoint(xCoord, yCoord);
                return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, ecSpec));
            }
            return null;
        } catch (GeneralSecurityException | IllegalArgumentException expected) {
            // Skip malformed JWK entries so other valid keys in the JWKS remain usable.
            return null;
        }
    }

    private static String jcaAlgorithm(String alg) {
        return switch (alg) {
            case "RS256" -> "SHA256withRSA";
            case "RS384" -> "SHA384withRSA";
            case "RS512" -> "SHA512withRSA";
            case "ES256" -> "SHA256withECDSAinP1363Format";
            case "ES384" -> "SHA384withECDSAinP1363Format";
            case "ES512" -> "SHA512withECDSAinP1363Format";
            default -> null;
        };
    }

    private static String expectedEcCurve(String alg) {
        return switch (alg) {
            case "ES256" -> "P-256";
            case "ES384" -> "P-384";
            case "ES512" -> "P-521";
            default -> null;
        };
    }

    private static String ecCurveName(String crv) {
        return switch (crv) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            case "P-521" -> "secp521r1";
            default -> null;
        };
    }

    private static Map<String, Object> toAssertionMap(JsonNode claims) {
        Map<String, Object> map = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = claims.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            map.put(entry.getKey(), toJavaValue(entry.getValue()));
        }
        return map;
    }

    private static Object toJavaValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isArray()) {
            List<Object> items = new ArrayList<>(node.size());
            for (JsonNode child : node) {
                items.add(toJavaValue(child));
            }
            return items;
        }
        if (node.isObject()) {
            return toAssertionMap(node);
        }
        return node.asText();
    }

    private record JwkKey(String kty, String kid, String alg, String crv, PublicKey publicKey) {
    }

    private record CachedJwks(List<JwkKey> keys, Instant fetchedAt, Instant expiresAt) {
    }
}

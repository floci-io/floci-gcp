package io.floci.gcp.services.sts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies external AWS {@code urn:ietf:params:aws:token-type:aws4_request} subject tokens
 * containing a serialized AWS STS {@code GetCallerIdentity} SigV4 request.
 *
 * <p>Because floci-gcp runs locally without calling live AWS STS ({@code sts.amazonaws.com}),
 * cryptographic SigV4 HMAC verification against AWS IAM is simulated: the emulator validates the
 * serialized request shape, HTTPS STS {@code GetCallerIdentity} URL, host match, {@code x-amz-date}
 * freshness window, {@code x-goog-cloud-target-resource} binding, SigV4 {@code Authorization}
 * header format and required signed headers, and AWS account ID match against
 * {@code provider.aws.accountId}.</p>
 */
@ApplicationScoped
public class AwsSubjectTokenVerifier {

    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(15);

    private static final DateTimeFormatter AMZ_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern AWS_STS_HOST =
            Pattern.compile("^sts(?:-fips)?(?:\\.([a-z0-9-]+))?\\.amazonaws\\.com(?:\\.cn)?$");
    private static final Pattern SIGV4_AUTH = Pattern.compile(
            "^AWS4-HMAC-SHA256\\s+"
                    + "Credential=([^/\\s,]+)/(\\d{8})/([^/\\s,]+)/([^/\\s,]+)/([^/\\s,]+),\\s*"
                    + "SignedHeaders=([^\\s,]+),\\s*"
                    + "Signature=([0-9a-f]{64})$");
    private static final Pattern AWS_ARN = Pattern.compile(
            "^arn:aws(?:-[a-z0-9-]+)?:(sts|iam)::(\\d{12}):(.+)$");

    private final ObjectMapper mapper;
    private final Clock clock;

    @Inject
    public AwsSubjectTokenVerifier(ObjectMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    AwsSubjectTokenVerifier(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public Map<String, Object> verify(String subjectToken, JsonNode provider,
            String canonicalProviderName, String requestedAudience) {
        JsonNode aws = provider.get("aws");
        if (aws == null || !aws.isObject()) {
            throw new StsException("invalid_grant",
                    "The target Workload Identity Pool Provider is not configured for AWS.");
        }
        String expectedAccountId = aws.path("accountId").asText("");
        if (!expectedAccountId.matches("\\d{12}")) {
            throw new StsException("invalid_grant", "AWS provider has an invalid accountId.");
        }

        JsonNode request = parseTokenJson(subjectToken);
        String method = request.path("method").asText("");
        if (!"POST".equalsIgnoreCase(method)) {
            throw new StsException("invalid_grant",
                    "AWS subject_token request method must be POST.");
        }

        URI uri = parseAndValidateUrl(request.path("url").asText(""));
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        Matcher hostMatcher = AWS_STS_HOST.matcher(host);
        if (!hostMatcher.matches()) {
            throw new StsException("invalid_grant",
                    "AWS subject_token URL must target an AWS STS endpoint.");
        }
        String hostRegion = hostMatcher.group(1);

        Map<String, String> headers = extractHeaders(request.get("headers"));
        String hostHeader = headers.get("host");
        if (hostHeader == null || !hostHeader.equalsIgnoreCase(host)) {
            throw new StsException("invalid_grant",
                    "AWS subject_token host header does not match URL host.");
        }

        String amzDate = headers.get("x-amz-date");
        Instant requestTime = parseAmzDate(amzDate);
        Duration skew = Duration.between(requestTime, clock.instant()).abs();
        if (skew.compareTo(MAX_CLOCK_SKEW) > 0) {
            throw new StsException("invalid_grant",
                    "AWS subject_token x-amz-date is outside the allowed clock skew window.");
        }

        String targetResource = headers.get("x-goog-cloud-target-resource");
        verifyTargetResource(targetResource, canonicalProviderName, requestedAudience);

        String authHeader = headers.get("authorization");
        if (authHeader == null || authHeader.isBlank()) {
            throw new StsException("invalid_grant",
                    "AWS subject_token is missing the Authorization header.");
        }
        Matcher authMatcher = SIGV4_AUTH.matcher(authHeader.trim());
        if (!authMatcher.matches()) {
            throw new StsException("invalid_grant",
                    "AWS subject_token Authorization header is not a valid AWS4-HMAC-SHA256 signature.");
        }
        String accessKeyId = authMatcher.group(1);
        String credentialDate = authMatcher.group(2);
        String credentialRegion = authMatcher.group(3);
        String credentialService = authMatcher.group(4);
        String credentialTerminator = authMatcher.group(5);
        String signedHeadersRaw = authMatcher.group(6);

        if (!credentialDate.equals(amzDate.substring(0, 8))) {
            throw new StsException("invalid_grant",
                    "AWS subject_token Credential date does not match x-amz-date.");
        }
        if (!"sts".equals(credentialService) || !"aws4_request".equals(credentialTerminator)) {
            throw new StsException("invalid_grant",
                    "AWS subject_token Credential scope must target sts/aws4_request.");
        }
        if (hostRegion != null && !hostRegion.equals(credentialRegion)) {
            throw new StsException("invalid_grant",
                    "AWS subject_token Credential region does not match STS endpoint host.");
        }

        Set<String> signedHeaders = new HashSet<>();
        for (String h : signedHeadersRaw.split(";")) {
            if (!h.isBlank()) {
                signedHeaders.add(h.toLowerCase(Locale.ROOT));
            }
        }
        if (!signedHeaders.contains("host")
                || !signedHeaders.contains("x-amz-date")
                || !signedHeaders.contains("x-goog-cloud-target-resource")) {
            throw new StsException("invalid_grant",
                    "AWS subject_token SignedHeaders must include host, x-amz-date, and x-goog-cloud-target-resource.");
        }
        String sessionToken = headers.get("x-amz-security-token");
        if (sessionToken != null && !sessionToken.isBlank()
                && !signedHeaders.contains("x-amz-security-token")) {
            throw new StsException("invalid_grant",
                    "AWS subject_token SignedHeaders must include x-amz-security-token when present.");
        }

        AwsCallerIdentity identity = resolveSimulatedIdentity(accessKeyId, sessionToken, expectedAccountId);
        if (!expectedAccountId.equals(identity.account())) {
            throw new StsException("invalid_grant",
                    "AWS caller account does not match the provider's configured accountId.");
        }

        Map<String, Object> assertion = new LinkedHashMap<>();
        assertion.put("arn", identity.arn());
        assertion.put("account", identity.account());
        assertion.put("userid", identity.userId());
        return assertion;
    }

    private JsonNode parseTokenJson(String subjectToken) {
        String raw = subjectToken.trim();
        if (!raw.startsWith("{")) {
            try {
                raw = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new StsException("invalid_grant", "Malformed URL-encoded AWS subject_token.");
            }
        }
        try {
            JsonNode node = mapper.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new StsException("invalid_grant", "AWS subject_token must be a JSON object.");
            }
            return node;
        } catch (IOException e) {
            throw new StsException("invalid_grant", "Malformed JSON in AWS subject_token.");
        }
    }

    private static URI parseAndValidateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new StsException("invalid_grant", "AWS subject_token url is required.");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new StsException("invalid_grant", "AWS subject_token url is invalid.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new StsException("invalid_grant", "AWS subject_token url must use https.");
        }
        Map<String, String> query = parseQuery(uri.getRawQuery());
        if (!"GetCallerIdentity".equals(query.get("Action"))
                || !"2011-06-15".equals(query.get("Version"))) {
            throw new StsException("invalid_grant",
                    "AWS subject_token url must invoke Action=GetCallerIdentity&Version=2011-06-15.");
        }
        return uri;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, val);
            }
        }
        return params;
    }

    private static Map<String, String> extractHeaders(JsonNode headersNode) {
        if (headersNode == null || !headersNode.isArray()) {
            throw new StsException("invalid_grant", "AWS subject_token headers must be an array.");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (JsonNode entry : headersNode) {
            if (!entry.isObject()) {
                continue;
            }
            String key = entry.path("key").asText("");
            String value = entry.path("value").asText("");
            if (!key.isBlank()) {
                headers.put(key.toLowerCase(Locale.ROOT), value);
            }
        }
        return headers;
    }

    private static Instant parseAmzDate(String amzDate) {
        if (amzDate == null || amzDate.isBlank()) {
            throw new StsException("invalid_grant", "AWS subject_token is missing x-amz-date header.");
        }
        try {
            return Instant.from(AMZ_DATE_FORMAT.parse(amzDate));
        } catch (DateTimeParseException e) {
            throw new StsException("invalid_grant", "AWS subject_token x-amz-date is invalid.");
        }
    }

    private static void verifyTargetResource(String targetResource, String canonicalProviderName,
            String requestedAudience) {
        if (targetResource == null || targetResource.isBlank()) {
            throw new StsException("invalid_grant",
                    "AWS subject_token is missing x-goog-cloud-target-resource header.");
        }
        String canonicalAudience = "//iam.googleapis.com/" + canonicalProviderName;
        String httpsAudience = "https://iam.googleapis.com/" + canonicalProviderName;
        if (!targetResource.equals(canonicalAudience)
                && !targetResource.equals(httpsAudience)
                && !targetResource.equals(requestedAudience)) {
            throw new StsException("invalid_grant",
                    "AWS subject_token x-goog-cloud-target-resource does not match audience.");
        }
    }

    /**
     * Resolves the simulated AWS {@code GetCallerIdentity} response locally. If
     * {@code x-amz-security-token} is an AWS ARN ({@code arn:aws:sts::<account>:assumed-role/...}
     * or {@code arn:aws:iam::<account>:...}), its account and ARN are used directly (allowing
     * tests to supply any role/session ARN or a mismatched account ID). Otherwise a deterministic
     * identity under {@code expectedAccountId} is synthesized from {@code accessKeyId}.
     */
    private static AwsCallerIdentity resolveSimulatedIdentity(String accessKeyId,
            String sessionToken, String expectedAccountId) {
        if (sessionToken != null && !sessionToken.isBlank()) {
            Matcher arnMatcher = AWS_ARN.matcher(sessionToken.trim());
            if (arnMatcher.matches()) {
                String account = arnMatcher.group(2);
                String resource = arnMatcher.group(3);
                String userId = accessKeyId;
                if (resource.startsWith("assumed-role/")) {
                    int lastSlash = resource.lastIndexOf('/');
                    if (lastSlash > "assumed-role/".length()) {
                        userId = accessKeyId + ":" + resource.substring(lastSlash + 1);
                    }
                }
                return new AwsCallerIdentity(account, sessionToken.trim(), userId);
            }
            if (sessionToken.trim().matches("\\d{12}")) {
                String account = sessionToken.trim();
                String arn = "arn:aws:sts::" + account + ":assumed-role/" + accessKeyId + "/" + accessKeyId;
                return new AwsCallerIdentity(account, arn, accessKeyId + ":" + accessKeyId);
            }
            String arn = "arn:aws:sts::" + expectedAccountId + ":assumed-role/" + accessKeyId + "/" + accessKeyId;
            return new AwsCallerIdentity(expectedAccountId, arn, accessKeyId + ":" + accessKeyId);
        }
        String arn = "arn:aws:iam::" + expectedAccountId + ":user/" + accessKeyId;
        return new AwsCallerIdentity(expectedAccountId, arn, accessKeyId);
    }

    private record AwsCallerIdentity(String account, String arn, String userId) {
    }
}

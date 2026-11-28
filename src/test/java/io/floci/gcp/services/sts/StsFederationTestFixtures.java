package io.floci.gcp.services.sts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared test fixtures for generating RSA/EC JWKS, signed OIDC JWTs, and serialized AWS SigV4
 * {@code GetCallerIdentity} tokens.
 */
final class StsFederationTestFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String VALID_HEX_SIGNATURE =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final DateTimeFormatter AMZ_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private StsFederationTestFixtures() {
    }

    static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static KeyPair generateEcP256KeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String rsaJwksJson(String kid, RSAPublicKey publicKey) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode keys = root.putArray("keys");
        ObjectNode jwk = keys.addObject();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", kid);
        jwk.put("n", base64UrlUnsigned(publicKey.getModulus()));
        jwk.put("e", base64UrlUnsigned(publicKey.getPublicExponent()));
        return root.toString();
    }

    static String ecJwksJson(String kid, ECPublicKey publicKey) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode keys = root.putArray("keys");
        ObjectNode jwk = keys.addObject();
        jwk.put("kty", "EC");
        jwk.put("use", "sig");
        jwk.put("crv", "P-256");
        jwk.put("alg", "ES256");
        jwk.put("kid", kid);
        jwk.put("x", base64UrlFixedLength(publicKey.getW().getAffineX(), 32));
        jwk.put("y", base64UrlFixedLength(publicKey.getW().getAffineY(), 32));
        return root.toString();
    }

    static String signJwt(String alg, String kid, Map<String, Object> claims, PrivateKey privateKey) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", alg);
            header.put("typ", "JWT");
            if (kid != null) {
                header.put("kid", kid);
            }
            String headerPart = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MAPPER.writeValueAsBytes(header));
            String claimsPart = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MAPPER.writeValueAsBytes(claims));
            byte[] signingInput = (headerPart + "." + claimsPart).getBytes(StandardCharsets.US_ASCII);
            String jcaAlg = switch (alg) {
                case "RS256" -> "SHA256withRSA";
                case "RS384" -> "SHA384withRSA";
                case "RS512" -> "SHA512withRSA";
                case "ES256" -> "SHA256withECDSAinP1363Format";
                case "ES384" -> "SHA384withECDSAinP1363Format";
                case "ES512" -> "SHA512withECDSAinP1363Format";
                default -> throw new IllegalArgumentException("Unsupported alg: " + alg);
            };
            Signature signer = Signature.getInstance(jcaAlg);
            signer.initSign(privateKey);
            signer.update(signingInput);
            String sigPart = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
            return headerPart + "." + claimsPart + "." + sigPart;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String awsSubjectTokenJson(String region, String accessKeyId, Instant timestamp,
            String targetResource, String sessionToken) {
        String host = region == null ? "sts.amazonaws.com" : "sts." + region + ".amazonaws.com";
        String credRegion = region == null ? "us-east-1" : region;
        String amzDate = AMZ_DATE_FORMAT.format(timestamp);
        String dateStamp = amzDate.substring(0, 8);
        String signedHeaders = sessionToken == null
                ? "host;x-amz-date;x-goog-cloud-target-resource"
                : "host;x-amz-date;x-amz-security-token;x-goog-cloud-target-resource";
        String authHeader = "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + dateStamp + "/"
                + credRegion + "/sts/aws4_request, SignedHeaders=" + signedHeaders
                + ", Signature=" + VALID_HEX_SIGNATURE;

        ObjectNode request = MAPPER.createObjectNode();
        request.put("url", "https://" + host + "/?Action=GetCallerIdentity&Version=2011-06-15");
        request.put("method", "POST");
        ArrayNode headers = request.putArray("headers");
        addHeader(headers, "host", host);
        addHeader(headers, "x-amz-date", amzDate);
        if (sessionToken != null) {
            addHeader(headers, "x-amz-security-token", sessionToken);
        }
        addHeader(headers, "x-goog-cloud-target-resource", targetResource);
        addHeader(headers, "Authorization", authHeader);
        return request.toString();
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void addHeader(ArrayNode headers, String key, String value) {
        ObjectNode h = headers.addObject();
        h.put("key", key);
        h.put("value", value);
    }

    private static String base64UrlUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String base64UrlFixedLength(BigInteger value, int length) {
        byte[] bytes = value.toByteArray();
        byte[] out = new byte[length];
        int srcPos = Math.max(0, bytes.length - length);
        int copyLen = Math.min(bytes.length, length);
        int destPos = length - copyLen;
        System.arraycopy(bytes, srcPos, out, destPos, copyLen);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    static Map<String, Object> oidcClaims(String issuer, Object audience, String subject,
            Instant now, long lifetimeSeconds, Map<String, Object> extra) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", audience);
        claims.put("sub", subject);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.getEpochSecond() + lifetimeSeconds);
        if (extra != null) {
            claims.putAll(extra);
        }
        return claims;
    }

    static List<String> listOf(String... values) {
        return List.of(values);
    }
}

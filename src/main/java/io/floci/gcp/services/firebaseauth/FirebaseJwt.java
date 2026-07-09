package io.floci.gcp.services.firebaseauth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Unsigned ("alg":"none") JWTs in the exact shape the Firebase Auth emulator mints:
 * base64url(header) + "." + base64url(payload) + "." with an empty signature segment.
 */
final class FirebaseJwt {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private FirebaseJwt() {}

    static String sign(Map<String, Object> payload) {
        return encode(Map.of("alg", "none", "typ", "JWT")) + "." + encode(payload) + ".";
    }

    /** Decodes the payload of any JWT (signed or not) without verifying the signature. */
    static Map<String, Object> decodePayload(String jwt) {
        String[] parts = jwt.split("\\.", -1);
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(parts[1]);
            return MAPPER.readValue(json, MAP);
        } catch (Exception e) {
            return null;
        }
    }

    static Map<String, Object> decodeHeader(String jwt) {
        String[] parts = jwt.split("\\.", -1);
        if (parts.length < 2) {
            return null;
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(parts[0]);
            return MAPPER.readValue(json, MAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String encode(Map<String, Object> value) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MAPPER.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode JWT segment", e);
        }
    }
}

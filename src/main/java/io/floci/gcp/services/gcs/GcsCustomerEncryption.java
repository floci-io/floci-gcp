package io.floci.gcp.services.gcs;

import jakarta.ws.rs.core.HttpHeaders;

import java.util.Map;

final class GcsCustomerEncryption {

    private static final String ALGORITHM_HEADER = "x-goog-encryption-algorithm";
    private static final String KEY_SHA256_HEADER = "x-goog-encryption-key-sha256";
    private static final String ALGORITHM = "AES256";
    private static final GcsCustomerEncryption NONE = new GcsCustomerEncryption(null, null);

    private final String algorithm;
    private final String keySha256;

    private GcsCustomerEncryption(String algorithm, String keySha256) {
        this.algorithm = algorithm;
        this.keySha256 = keySha256;
    }

    static GcsCustomerEncryption none() {
        return NONE;
    }

    static GcsCustomerEncryption fromHeaders(HttpHeaders headers) {
        String keySha256 = keySha256(headers);
        if (keySha256 == null) {
            return none();
        }
        String algorithm = headers.getHeaderString(ALGORITHM_HEADER);
        if (algorithm == null || algorithm.isBlank()) {
            algorithm = ALGORITHM;
        }
        return new GcsCustomerEncryption(algorithm, keySha256);
    }

    static GcsCustomerEncryption fromKeySha256(String keySha256) {
        if (keySha256 == null || keySha256.isBlank()) {
            return none();
        }
        return new GcsCustomerEncryption(ALGORITHM, keySha256);
    }

    static GcsCustomerEncryption fromMetadata(Map<String, String> metadata) {
        if (metadata == null) {
            return none();
        }
        return new GcsCustomerEncryption(metadata.get("encryptionAlgorithm"), metadata.get("keySha256"));
    }

    Map<String, String> metadata() {
        if (keySha256 == null) {
            return null;
        }
        return Map.of(
                "encryptionAlgorithm", algorithm,
                "keySha256", keySha256);
    }

    String keySha256() {
        return keySha256;
    }

    private static String keySha256(HttpHeaders headers) {
        return headers.getHeaderString(KEY_SHA256_HEADER);
    }
}

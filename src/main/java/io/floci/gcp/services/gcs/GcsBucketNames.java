package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;

import java.util.Locale;

/**
 * Bucket-name validation, per the Cloud Storage naming rules.
 *
 * <p>Worth enforcing in an emulator rather than waving through: bucket names are usually baked
 * into configuration, so a name that is accepted locally and rejected by GCS fails at deploy
 * rather than during development, the most expensive place to find it.
 *
 * <p>Only the rules that hold for every bucket are checked. Names between 64 and 222 characters
 * are legal in GCS only when dot-separated into labels of at most 63, which is handled below.
 */
final class GcsBucketNames {

    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 63;
    private static final int MAX_DOTTED_LENGTH = 222;

    private GcsBucketNames() {
    }

    static void validate(String name) {
        if (name == null || name.isEmpty()) {
            throw invalid("Bucket names must not be empty.");
        }
        if (!name.equals(name.toLowerCase(Locale.ROOT))) {
            throw invalid("Bucket names must be lowercase: " + name);
        }
        if (name.length() < MIN_LENGTH) {
            throw invalid("Bucket names must be at least " + MIN_LENGTH + " characters: " + name);
        }

        boolean dotted = name.indexOf('.') >= 0;
        int limit = dotted ? MAX_DOTTED_LENGTH : MAX_LENGTH;
        if (name.length() > limit) {
            throw invalid("Bucket names must be at most " + limit + " characters: " + name);
        }

        if (!isAlphanumeric(name.charAt(0)) || !isAlphanumeric(name.charAt(name.length() - 1))) {
            throw invalid("Bucket names must start and end with a letter or number: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!isAlphanumeric(c) && c != '-' && c != '_' && c != '.') {
                throw invalid("Bucket names may only contain letters, numbers, '-', '_' and '.': " + name);
            }
        }
        if (name.contains("..")) {
            throw invalid("Bucket names must not contain consecutive dots: " + name);
        }
        if (dotted) {
            for (String label : name.split("\\.", -1)) {
                if (label.isEmpty() || label.length() > MAX_LENGTH) {
                    throw invalid("Each dot-separated part must be 1-" + MAX_LENGTH + " characters: " + name);
                }
            }
        }
        if (looksLikeIpv4(name)) {
            throw invalid("Bucket names must not be formatted as an IP address: " + name);
        }
        // "goog" and anything close to "google" are reserved.
        String flattened = name.replace("-", "").replace("_", "").replace(".", "");
        if (flattened.startsWith("goog") || flattened.contains("google")) {
            throw invalid("Bucket names must not begin with 'goog' or contain 'google': " + name);
        }
    }

    private static boolean isAlphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean looksLikeIpv4(String name) {
        String[] parts = name.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') {
                    return false;
                }
            }
        }
        return true;
    }

    private static GcpException invalid(String message) {
        return GcpException.invalidArgument(message).withReason("invalid");
    }
}

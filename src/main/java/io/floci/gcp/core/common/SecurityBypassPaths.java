package io.floci.gcp.core.common;

/**
 * Shared path classification for auth-related and CTF hardening filters.
 */
public final class SecurityBypassPaths {

    private SecurityBypassPaths() {
    }

    public static boolean isPrefixedInternalPath(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isEmpty()) {
            return false;
        }
        String normalized = normalizedPath.startsWith("/") ? normalizedPath : "/" + normalizedPath;
        return normalized.startsWith("/_floci-gcp/")
                || "/_floci-gcp".equals(normalized);
    }

    public static boolean isHealthPath(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isEmpty()) {
            return false;
        }
        String normalized = normalizedPath.startsWith("/") ? normalizedPath : "/" + normalizedPath;
        return "/health".equals(normalized);
    }

    /**
     * k3s TokenReview webhook: authenticates {@code spec.token} in the body, not HTTP Bearer.
     * Must stay reachable when validate-tokens / IAM enforcement / hide-internal are on.
     */
    public static boolean isGkeTokenWebhookPath(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isEmpty()) {
            return false;
        }
        String normalized = normalizedPath.startsWith("/") ? normalizedPath : "/" + normalizedPath;
        return "/_floci-gcp/gke/token-webhook".equals(normalized);
    }

    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }
}

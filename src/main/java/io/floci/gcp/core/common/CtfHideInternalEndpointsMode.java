package io.floci.gcp.core.common;

/**
 * Parsed value of {@code floci-gcp.ctf.hide-internal-endpoints} /
 * {@code FLOCI_GCP_CTF_HIDE_INTERNAL_ENDPOINTS}.
 */
public enum CtfHideInternalEndpointsMode {

    /** Internal endpoints are reachable (set hide-internal-endpoints to false). */
    OFF,

    /** Hide {@code /_floci-gcp/*} and related operator routes. */
    PREFIXED,

    /** Also hide {@code /health}. */
    ALL;

    public static CtfHideInternalEndpointsMode parse(String raw) {
        if (raw == null || raw.isBlank() || "false".equalsIgnoreCase(raw.trim())) {
            return OFF;
        }
        return switch (raw.trim().toLowerCase()) {
            case "true" -> PREFIXED;
            case "all" -> ALL;
            default -> throw new IllegalArgumentException(
                    "Invalid floci-gcp.ctf.hide-internal-endpoints: " + raw
                            + " (expected false, true, or all)");
        };
    }

    public boolean hidesAnything() {
        return this != OFF;
    }

    public boolean isPathHidden(String path) {
        if (!hidesAnything()) {
            return false;
        }
        String normalized = SecurityBypassPaths.normalizePath(path);
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        // k3s must POST TokenReviews here even when other _floci-gcp routes are hidden.
        if (SecurityBypassPaths.isGkeTokenWebhookPath(normalized)) {
            return false;
        }
        boolean prefixed = SecurityBypassPaths.isPrefixedInternalPath(normalized);
        return switch (this) {
            case OFF -> false;
            case PREFIXED -> prefixed;
            case ALL -> prefixed || SecurityBypassPaths.isHealthPath(normalized);
        };
    }
}

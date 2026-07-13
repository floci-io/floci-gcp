package io.floci.gcp.core.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Prevents user-controlled container environment from injecting GCP or Floci
 * operator credentials into child containers.
 */
public final class ContainerEnvHardening {

    private static final Set<String> BLOCKED_KEYS = Set.of(
            "GOOGLE_APPLICATION_CREDENTIALS",
            "GOOGLE_API_KEY",
            "GOOGLE_OAUTH_ACCESS_TOKEN",
            "GOOGLE_CLOUD_PROJECT",
            "GCLOUD_PROJECT",
            "GCLOUD_ACCESS_TOKEN",
            "CLOUDSDK_AUTH_ACCESS_TOKEN",
            "CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE",
            "CLOUDSDK_CORE_PROJECT",
            "FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT",
            "FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN");

    private ContainerEnvHardening() {
    }

    public static boolean isBlocked(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String upper = key.trim().toUpperCase(Locale.ROOT);
        if (BLOCKED_KEYS.contains(upper)) {
            return true;
        }
        return upper.startsWith("FLOCI_GCP_AUTH_")
                || upper.startsWith("CLOUDSDK_AUTH_")
                || upper.startsWith("CLOUDSDK_API_ENDPOINT_OVERRIDES_")
                || upper.startsWith("CLOUDSDK_CORE_")
                || upper.startsWith("GOOGLE_APPLICATION_");
    }

    public static void removeBlockedKeys(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return;
        }
        env.keySet().removeIf(ContainerEnvHardening::isBlocked);
    }

    public static void putIfAllowed(Map<String, String> env, String key, String value) {
        if (!isBlocked(key)) {
            env.put(key, value);
        }
    }

    public static void putAllIfAllowed(Map<String, String> env, Map<String, String> additions) {
        if (additions == null) {
            return;
        }
        additions.forEach((k, v) -> putIfAllowed(env, k, v));
    }

    public static List<String> filterEnvList(List<String> envVars) {
        if (envVars == null || envVars.isEmpty()) {
            return List.of();
        }
        List<String> filtered = new ArrayList<>(envVars.size());
        for (String entry : envVars) {
            if (entry == null) {
                continue;
            }
            int eq = entry.indexOf('=');
            String key = eq >= 0 ? entry.substring(0, eq) : entry;
            if (!isBlocked(key)) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    /**
     * Merges user-supplied entries into {@code target} and strips blocked keys
     * so operator credential keys cannot be injected from the user map.
     */
    public static void mergeUserEnvThenOperatorCredentials(Map<String, String> target,
                                                           Map<String, String> userSupplied) {
        putAllIfAllowed(target, userSupplied);
        removeBlockedKeys(target);
    }

    public static Map<String, String> mergeUserEnvListToMap(
            Map<String, String> taskDefEnv,
            Map<String, String> overrideEnv) {
        Map<String, String> merged = new LinkedHashMap<>();
        putAllIfAllowed(merged, taskDefEnv);
        putAllIfAllowed(merged, overrideEnv);
        removeBlockedKeys(merged);
        return merged;
    }
}

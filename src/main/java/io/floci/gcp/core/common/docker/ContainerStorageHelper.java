package io.floci.gcp.core.common.docker;

import io.floci.gcp.config.EmulatorConfig;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Central helper for Docker resource naming, labelling and sidecar volume management
 * (Kafka/Redpanda, CloudSQL, …).
 *
 * <p>Naming convention (shared across the Floci emulators): every emulator-created container
 * and volume is named {@code floci-gcp-[<namespace>-]<service>-<id>} and labelled so it is
 * attributable to exactly one emulator by name and by label. The prefix is owned here —
 * call sites pass bare {@code <service>-<id>} tokens, never the prefix.</p>
 *
 * <p>Storage modes:</p>
 * <ul>
 *   <li>Named-volume (default) — Floci manages per-resource Docker named volumes.
 *       Active when {@code FLOCI_GCP_STORAGE_HOST_PERSISTENT_PATH} is not set to an
 *       absolute path.</li>
 *   <li>Host-path (legacy) — active when {@code host-persistent-path} is set to an absolute
 *       path; callers use bind-mounts to the specified directory instead.</li>
 * </ul>
 */
public final class ContainerStorageHelper {

    private static final Logger LOG = Logger.getLogger(ContainerStorageHelper.class);

    static final String CLOUD = "gcp";
    static final String CONTAINER_PREFIX = "floci-" + CLOUD + "-";
    static final String LEGACY_PREFIX = "floci-";

    public static final String CLOUD_LABEL = "io.floci";
    public static final String SERVICE_LABEL = "io.floci.service";
    public static final String RESOURCE_ID_LABEL = "io.floci.resource-id";
    public static final String PROJECT_LABEL = "io.floci.project";
    public static final String LOCATION_LABEL = "io.floci.location";

    /**
     * Legacy alias of each resource-identity label, keyed by the new label. Call sites set only the
     * new keys; {@link #withLegacyAliases} stamps the legacy key next to each one with the same value,
     * and readers go through {@link #labelValue}. This is the only place a legacy key is spelled.
     */
    public static final Map<String, String> LEGACY_LABEL_ALIASES = legacyLabelAliases();

    private ContainerStorageHelper() {}

    private static Map<String, String> legacyLabelAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put(SERVICE_LABEL, "floci_service");
        aliases.put(RESOURCE_ID_LABEL, "floci_resource");
        aliases.put(PROJECT_LABEL, "floci_project");
        aliases.put(LOCATION_LABEL, "floci_location");
        return Collections.unmodifiableMap(aliases);
    }

    /**
     * Canonical container/volume name for a resource. Uses {@code volumeId} when set;
     * falls back to {@code fallbackId} (the resource name) for resources created before
     * volume ids were introduced.
     */
    public static String resourceName(EmulatorConfig config, String service, String volumeId, String fallbackId) {
        return dockerName(config, service + "-" + (volumeId != null ? volumeId : fallbackId));
    }

    /**
     * Prefixes {@code baseName} with {@code floci-gcp-} and the configured resource namespace.
     * Accepts already-prefixed names (current or legacy {@code floci-} prefix) and normalises
     * them, so the namespace always lands between the cloud token and the service token.
     */
    public static String dockerName(EmulatorConfig config, String baseName) {
        String base = stripPrefix(baseName);
        String namespace = resourceNamespace(config);
        if (namespace.isBlank()) {
            return CONTAINER_PREFIX + base;
        }
        return CONTAINER_PREFIX + namespace + "-" + base;
    }

    /**
     * Labels applied to every emulator-created container and volume:
     * {@code floci=true} (umbrella across all Floci emulators),
     * {@code floci_emulator=floci-gcp} (per-emulator discriminator), and
     * {@code floci_namespace} when a resource namespace is configured.
     */
    public static Map<String, String> defaultLabels(EmulatorConfig config) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("floci", "true");
        labels.put("floci_emulator", "floci-" + CLOUD);
        String namespace = resourceNamespace(config);
        if (!namespace.isBlank()) {
            labels.put("floci_namespace", namespace);
        }
        return labels;
    }

    /**
     * Labels tying a container to the emulated GCP resource it backs: {@code io.floci=gcp},
     * {@code io.floci.service}, {@code io.floci.resource-id} (the resource short name a gcloud user
     * passes), {@code io.floci.project} and {@code io.floci.location}. Merged into a spec's own labels
     * (never into {@link #defaultLabels}). A blank or null value omits that key, e.g. the shared
     * BigQuery SQL engine container has no per-resource id.
     */
    public static Map<String, String> resourceIdentityLabels(
            String service, String resourceId, String project, String location) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(CLOUD_LABEL, CLOUD);
        putIfNotBlank(labels, SERVICE_LABEL, service);
        putIfNotBlank(labels, RESOURCE_ID_LABEL, resourceId);
        putIfNotBlank(labels, PROJECT_LABEL, project);
        putIfNotBlank(labels, LOCATION_LABEL, location);
        return labels;
    }

    /**
     * Returns a copy of {@code labels} with the legacy alias of every resource-identity label present,
     * carrying the same value, so the two keys can never drift on a container this emulator creates.
     */
    public static Map<String, String> withLegacyAliases(Map<String, String> labels) {
        Map<String, String> aliased = new LinkedHashMap<>(labels);
        for (Map.Entry<String, String> alias : LEGACY_LABEL_ALIASES.entrySet()) {
            String value = labels.get(alias.getKey());
            if (value != null) {
                aliased.put(alias.getValue(), value);
            }
        }
        return aliased;
    }

    /**
     * Reads a resource-identity label: the new key's value, or its legacy alias's when the new key is
     * absent (containers created before the {@code io.floci.*} keys). Null when neither is set.
     */
    public static String labelValue(Map<String, String> labels, String newKey) {
        if (labels == null) {
            return null;
        }
        String value = labels.get(newKey);
        if (value != null) {
            return value;
        }
        String legacyKey = LEGACY_LABEL_ALIASES.get(newKey);
        return legacyKey == null ? null : labels.get(legacyKey);
    }

    /**
     * The same Docker label filter spelled with legacy keys, or empty when {@code filter} uses no
     * aliased key. A Docker label filter is an AND, so a reader that must also find containers carrying
     * only the legacy keys runs the query once per key set and merges the results by container id.
     */
    public static Map<String, String> legacyLabelFilter(Map<String, String> filter) {
        Map<String, String> legacy = new LinkedHashMap<>();
        boolean aliased = false;
        for (Map.Entry<String, String> entry : filter.entrySet()) {
            String legacyKey = LEGACY_LABEL_ALIASES.get(entry.getKey());
            if (legacyKey != null) {
                aliased = true;
                legacy.put(legacyKey, entry.getValue());
            } else {
                legacy.put(entry.getKey(), entry.getValue());
            }
        }
        return aliased ? legacy : Map.of();
    }

    /**
     * New keys whose legacy alias is also present with a different value. Containers this emulator
     * creates always agree, so a mismatch means something outside Floci relabelled the container and
     * destructive paths must leave it alone.
     */
    public static List<String> conflictingAliases(Map<String, String> labels) {
        List<String> conflicts = new ArrayList<>();
        if (labels == null) {
            return conflicts;
        }
        for (Map.Entry<String, String> alias : LEGACY_LABEL_ALIASES.entrySet()) {
            String value = labels.get(alias.getKey());
            String legacyValue = labels.get(alias.getValue());
            if (value != null && legacyValue != null && !Objects.equals(value, legacyValue)) {
                conflicts.add(alias.getKey());
            }
        }
        return conflicts;
    }

    private static void putIfNotBlank(Map<String, String> labels, String key, String value) {
        if (value != null && !value.isBlank()) {
            labels.put(key, value);
        }
    }

    /**
     * Host data directory for a resource in host-path mode: the configured
     * {@code host-persistent-path} plus the resource namespace (when configured),
     * service token and resource id.
     */
    public static Path hostResourcePath(EmulatorConfig config, String service, String resourceId) {
        String namespace = resourceNamespace(config);
        Path base = Path.of(config.storage().hostPersistentPath());
        if (namespace.isBlank()) {
            return base.resolve(service).resolve(resourceId);
        }
        return base.resolve(namespace).resolve(service).resolve(resourceId);
    }

    private static String stripPrefix(String baseName) {
        if (baseName.startsWith(CONTAINER_PREFIX)) {
            return baseName.substring(CONTAINER_PREFIX.length());
        }
        if (baseName.startsWith(LEGACY_PREFIX)) {
            return baseName.substring(LEGACY_PREFIX.length());
        }
        return baseName;
    }

    private static String resourceNamespace(EmulatorConfig config) {
        if (config == null || config.docker() == null || config.docker().resourceNamespace() == null) {
            return "";
        }
        return sanitizeNamePart(config.docker().resourceNamespace().orElse(""));
    }

    private static String sanitizeNamePart(String value) {
        String cleaned = value.trim().replaceAll("[^A-Za-z0-9_.-]+", "-");
        while (cleaned.startsWith("-")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("-")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.equals(".") || cleaned.equals("..")) {
            return "";
        }
        return cleaned;
    }

    /**
     * Returns {@code true} when named-volume mode is active.
     * Returns {@code false} only when {@code FLOCI_GCP_STORAGE_HOST_PERSISTENT_PATH} is set to
     * an absolute path, indicating the caller should use a host bind-mount instead.
     * Volume names and relative paths are not supported in {@code host-persistent-path} —
     * they are treated as named-volume mode.
     */
    public static boolean isNamedVolumeMode(EmulatorConfig config) {
        return !config.storage().hostPersistentPath().startsWith("/");
    }

    /**
     * Ensures the named volume exists and mounts it to {@code internalMount} in the container.
     * Must only be called when {@link #isNamedVolumeMode} returns {@code true}.
     *
     * <p>Prefer the {@code volumeName} overload when the resource has a persisted volume name —
     * recomputing the name from config would orphan data after a prefix or namespace change.</p>
     */
    public static void applyStorage(
            ContainerBuilder.Builder builder,
            ContainerLifecycleManager lifecycleManager,
            EmulatorConfig config,
            String service,
            String volumeId,
            String fallbackId,
            String internalMount) {
        applyStorage(builder, lifecycleManager, resourceName(config, service, volumeId, fallbackId), internalMount);
    }

    /**
     * Ensures the named volume exists and mounts it to {@code internalMount} in the container.
     * Must only be called when {@link #isNamedVolumeMode} returns {@code true}.
     */
    public static void applyStorage(
            ContainerBuilder.Builder builder,
            ContainerLifecycleManager lifecycleManager,
            String volumeName,
            String internalMount) {
        lifecycleManager.ensureVolume(volumeName);
        builder.withNamedVolume(volumeName, internalMount);
    }

    /**
     * Removes the named volume on resource delete, honouring the configured prune policy.
     *
     * <ul>
     *   <li>In {@code memory} storage mode: always removes (data cannot survive a restart anyway).</li>
     *   <li>In persistent modes: removes only when {@code prune-volumes-on-delete: true}.</li>
     * </ul>
     */
    public static void removeStorage(
            EmulatorConfig config,
            ContainerLifecycleManager lifecycleManager,
            String volumeName) {
        boolean isMemory = "memory".equals(config.storage().mode());
        if (isMemory || config.storage().pruneVolumesOnDelete()) {
            lifecycleManager.removeVolume(volumeName);
        } else {
            LOG.infov("Retained Docker volume {0}. Remove manually: docker volume rm {0}", volumeName);
        }
    }

    /**
     * Ensures the host data directory exists for host-path mode (absolute paths only).
     * Called by managers in their legacy host-path code paths.
     */
    public static void ensureHostDir(String hostDataPath) {
        try {
            Files.createDirectories(Path.of(hostDataPath));
        } catch (IOException e) {
            LOG.errorv("Failed to create data directory {0}: {1}", hostDataPath, e.getMessage());
        }
    }
}

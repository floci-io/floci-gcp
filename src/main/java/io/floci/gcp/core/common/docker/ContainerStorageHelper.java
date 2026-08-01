package io.floci.gcp.core.common.docker;

import io.floci.gcp.config.EmulatorConfig;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

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

    private ContainerStorageHelper() {}

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

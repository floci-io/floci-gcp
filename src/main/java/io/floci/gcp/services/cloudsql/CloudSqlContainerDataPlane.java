package io.floci.gcp.services.cloudsql;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ExecResult;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Docker half of a Cloud SQL data plane, shared by every engine: one container per
 * instance, a named volume (or host bind) for its data directory, a readiness wait, and the
 * {@code ipAddresses} / {@code flociDataPlane} metadata the control plane serves back. Engines
 * supply the image, port, environment, data directory and readiness probe, and implement the
 * database and user operations with their own client.
 */
abstract class CloudSqlContainerDataPlane implements CloudSqlDataPlane {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Volume-name prefix used before names were persisted. Pinned for backfill only:
     * pre-upgrade volumes must keep resolving to this exact prefix regardless of what the
     * live naming helper produces.
     */
    private static final String LEGACY_VOLUME_PREFIX = "floci-gcp-cloudsql-";

    protected final ContainerBuilder containerBuilder;
    protected final ContainerLifecycleManager lifecycleManager;
    protected final ContainerDetector containerDetector;
    protected final EmulatorConfig config;
    private final Logger log;

    /**
     * For the ArC client proxy only: a normal-scoped bean needs a no-args constructor, and ArC
     * can synthesise one on the subclass only if its superclass already has one.
     */
    protected CloudSqlContainerDataPlane() {
        this(null, null, null, null, Logger.getLogger(CloudSqlContainerDataPlane.class));
    }

    protected CloudSqlContainerDataPlane(ContainerBuilder containerBuilder,
                                         ContainerLifecycleManager lifecycleManager,
                                         ContainerDetector containerDetector,
                                         EmulatorConfig config,
                                         Logger log) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.config = config;
        this.log = log;
    }

    // ── Engine hooks ──────────────────────────────────────────────────────────

    /** Value stored under {@code flociDataPlane.engine}. */
    protected abstract String engineName();

    /** Human name for log and error text ({@code PostgreSQL}, {@code MySQL}). */
    protected abstract String displayName();

    /** The port the server listens on inside the container. */
    protected abstract int port();

    /** Image for a {@code databaseVersion}; {@code 400} for one this engine does not serve. */
    protected abstract String imageFor(String databaseVersion);

    /** Environment the image needs to initialise (admin credentials, default database). */
    protected abstract Map<String, String> containerEnv();

    /** Data directory to mount the instance volume on, for a {@code databaseVersion}. */
    protected abstract String dataMountPath(String databaseVersion);

    /** A command whose zero exit means the server accepts TCP connections. */
    protected abstract List<String> readinessCommand();

    // ── Container lifecycle ───────────────────────────────────────────────────

    @Override
    public Map<String, Object> startInstance(String project, String instance, Map<String, Object> metadata) {
        Map<String, Object> updated = copy(metadata);
        String databaseVersion = stringValue(updated.get("databaseVersion"));
        String image = imageFor(databaseVersion);
        boolean newVolume = stringValue(dataPlane(updated).get("volumeId")) == null;
        String volumeId = volumeId(updated, project, instance);
        String containerName = containerName(project, instance);
        String fallbackId = fallbackId(project, instance);
        String volumeName = resolveVolumeName(updated, volumeId, fallbackId, newVolume);

        log.infov("Starting Cloud SQL {0} instance {1}:{2} using image {3}", displayName(), project, instance, image);
        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "cloudsql", instance, project, stringValue(updated.get("region"))));
        for (Map.Entry<String, String> env : containerEnv().entrySet()) {
            specBuilder = specBuilder.withEnv(env.getKey(), env.getValue());
        }
        specBuilder = specBuilder
                .withLogRotation()
                .withDockerNetwork(config.services().dockerNetwork());

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(port());
        } else {
            specBuilder.withExposedPort(port());
        }

        String internalMountPath = dataMountPath(databaseVersion);
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.applyStorage(specBuilder, lifecycleManager, volumeName, internalMountPath);
        } else {
            String hostDataPath = Path.of(config.storage().hostPersistentPath(), "cloudsql",
                    sanitize(project), sanitize(instance)).toAbsolutePath().toString();
            ContainerStorageHelper.ensureHostDir(hostDataPath);
            specBuilder.withBind(hostDataPath, internalMountPath);
        }

        ContainerSpec spec = specBuilder.build();
        String containerId = null;
        try {
            containerId = lifecycleManager.create(spec);
            ContainerInfo info = lifecycleManager.startCreated(containerId, spec);
            EndpointInfo endpoint = info.getEndpoint(port());
            awaitReady(info.containerId(), Duration.ofSeconds(config.services().cloudsql().startupTimeoutSeconds()));
            applyEndpoint(updated, image, volumeId, volumeName, info.containerId(), endpoint);
            return updated;
        } catch (RuntimeException e) {
            if (containerId != null) {
                lifecycleManager.stopAndRemove(containerId, null);
            }
            if (newVolume && ContainerStorageHelper.isNamedVolumeMode(config)) {
                lifecycleManager.removeVolume(volumeName);
            }
            throw e;
        }
    }

    @Override
    public Map<String, Object> ensureInstance(String project, String instance, Map<String, Object> metadata) {
        Map<String, Object> dataPlane = dataPlane(metadata);
        String containerId = stringValue(dataPlane.get("containerId"));
        if (containerId != null && lifecycleManager.isContainerRunning(containerId)) {
            EndpointInfo endpoint = lifecycleManager.resolveEndpoint(containerId, port());
            Map<String, Object> updated = copy(metadata);
            String volumeId = volumeId(updated, project, instance);
            applyEndpoint(updated, stringValue(dataPlane.get("image")), volumeId,
                    resolveVolumeName(updated, volumeId, fallbackId(project, instance), false),
                    containerId, endpoint);
            return updated;
        }
        return startInstance(project, instance, metadata);
    }

    @Override
    public void stopInstance(String project, String instance, Map<String, Object> metadata, boolean removeStorage) {
        String containerId = stringValue(dataPlane(metadata).get("containerId"));
        if (containerId != null) {
            lifecycleManager.stopAndRemove(containerId, null);
        } else {
            lifecycleManager.removeIfExists(containerName(project, instance));
        }
        if (removeStorage) {
            String volumeId = stringValue(dataPlane(metadata).get("volumeId"));
            ContainerStorageHelper.removeStorage(config, lifecycleManager,
                    resolveVolumeName(metadata, volumeId, fallbackId(project, instance), false));
        }
    }

    private void awaitReady(String containerId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = "no response";
        while (System.nanoTime() < deadline) {
            ExecResult result = lifecycleManager.exec(containerId, List.of(), readinessCommand());
            if (result.exitCode() == 0) {
                return;
            }
            last = errorOf(result);
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw GcpException.unavailable("Interrupted while waiting for " + displayName() + " startup");
            }
        }
        throw GcpException.unavailable(displayName() + " data plane did not become ready: " + last);
    }

    // ── Helpers for engines ───────────────────────────────────────────────────

    protected String requireContainerId(Map<String, Object> instanceMetadata) {
        String containerId = stringValue(dataPlane(instanceMetadata).get("containerId"));
        if (containerId == null || containerId.isBlank()) {
            throw GcpException.failedPrecondition("Cloud SQL instance has no running " + displayName() + " container");
        }
        return containerId;
    }

    protected static String errorOf(ExecResult result) {
        String message = result.stderr().isBlank() ? result.stdout() : result.stderr();
        return message.strip().replace('\n', ' ');
    }

    private void applyEndpoint(Map<String, Object> metadata, String image, String volumeId,
                               String volumeName, String containerId, EndpointInfo endpoint) {
        metadata.put("ipAddresses", List.of(mapOf(
                "type", "PRIMARY",
                "ipAddress", endpoint.host(),
                "port", endpoint.port())));
        metadata.put("flociDataPlane", mapOf(
                "engine", engineName(),
                "image", image,
                "containerId", containerId,
                "host", endpoint.host(),
                "port", endpoint.port(),
                "volumeId", volumeId,
                "volumeName", volumeName,
                "status", "RUNNING"));
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> dataPlane(Map<String, Object> metadata) {
        Object value = metadata.get("flociDataPlane");
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    /**
     * Resolves the Docker volume name for an instance. Pre-upgrade instances persisted only a
     * {@code volumeId}, so their data lives under the pinned legacy name; the live helper must
     * never be used to backfill them, since a future prefix or namespace change would orphan the data.
     */
    private String resolveVolumeName(Map<String, Object> metadata, String volumeId,
                                     String fallbackId, boolean newVolume) {
        String persisted = stringValue(dataPlane(metadata).get("volumeName"));
        if (persisted != null && !persisted.isBlank()) {
            return persisted;
        }
        if (newVolume) {
            return ContainerStorageHelper.resourceName(config, "cloudsql", volumeId, fallbackId);
        }
        return LEGACY_VOLUME_PREFIX + (volumeId != null ? volumeId : fallbackId);
    }

    private String volumeId(Map<String, Object> metadata, String project, String instance) {
        String existing = stringValue(dataPlane(metadata).get("volumeId"));
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return sanitize(project + "-" + instance) + "-" + String.format("%06x", RANDOM.nextInt(0xFFFFFF));
    }

    private String containerName(String project, String instance) {
        return ContainerStorageHelper.dockerName(config, "cloudsql-" + fallbackId(project, instance));
    }

    private String fallbackId(String project, String instance) {
        return sanitize(project + "-" + instance);
    }

    private static String sanitize(String value) {
        String sanitized = value.toLowerCase().replaceAll("[^a-z0-9_.-]", "-");
        sanitized = sanitized.replaceAll("^-+", "").replaceAll("-+$", "");
        return sanitized.isBlank() ? "default" : sanitized;
    }

    protected static Map<String, Object> copy(Map<String, Object> value) {
        return new LinkedHashMap<>(value);
    }

    protected static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    protected static Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}

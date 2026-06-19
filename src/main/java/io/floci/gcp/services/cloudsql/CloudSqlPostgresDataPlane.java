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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CloudSqlPostgresDataPlane implements CloudSqlDataPlane {

    private static final Logger LOG = Logger.getLogger(CloudSqlPostgresDataPlane.class);
    private static final int POSTGRES_PORT = 5432;
    private static final String ADMIN_USER = "postgres";
    private static final String ADMIN_PASSWORD = "postgres";
    private static final String POSTGRES_DATA_PARENT = "/var/lib/postgresql";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;

    @Inject
    public CloudSqlPostgresDataPlane(ContainerBuilder containerBuilder,
                                     ContainerLifecycleManager lifecycleManager,
                                     ContainerDetector containerDetector,
                                     EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.config = config;
    }

    @Override
    public Map<String, Object> startInstance(String project, String instance, Map<String, Object> metadata) {
        Map<String, Object> updated = copy(metadata);
        String image = imageFor(stringValue(updated.get("databaseVersion")));
        boolean newVolume = stringValue(dataPlane(updated).get("volumeId")) == null;
        String volumeId = volumeId(updated, project, instance);
        String containerName = containerName(project, instance);
        String fallbackId = fallbackId(project, instance);

        LOG.infov("Starting Cloud SQL PostgreSQL instance {0}:{1} using image {2}", project, instance, image);
        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv("POSTGRES_USER", ADMIN_USER)
                .withEnv("POSTGRES_PASSWORD", ADMIN_PASSWORD)
                .withEnv("POSTGRES_DB", "postgres")
                .withLogRotation()
                .withDockerNetwork(config.services().dockerNetwork());

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(POSTGRES_PORT);
        } else {
            specBuilder.withExposedPort(POSTGRES_PORT);
        }

        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.applyStorage(specBuilder, lifecycleManager,
                    "cloudsql", volumeId, fallbackId, POSTGRES_DATA_PARENT);
        } else {
            String hostDataPath = Path.of(config.storage().hostPersistentPath(), "cloudsql",
                    sanitize(project), sanitize(instance)).toAbsolutePath().toString();
            ContainerStorageHelper.ensureHostDir(hostDataPath);
            specBuilder.withBind(hostDataPath, POSTGRES_DATA_PARENT);
        }

        ContainerSpec spec = specBuilder.build();
        String containerId = null;
        try {
            containerId = lifecycleManager.create(spec);
            ContainerInfo info = lifecycleManager.startCreated(containerId, spec);
            EndpointInfo endpoint = info.getEndpoint(POSTGRES_PORT);
            awaitReady(info.containerId(), Duration.ofSeconds(config.services().cloudsql().startupTimeoutSeconds()));
            applyEndpoint(updated, image, volumeId, info.containerId(), endpoint);
            return updated;
        } catch (RuntimeException e) {
            if (containerId != null) {
                lifecycleManager.stopAndRemove(containerId, null);
            }
            if (newVolume && ContainerStorageHelper.isNamedVolumeMode(config)) {
                lifecycleManager.removeVolume(ContainerStorageHelper.resourceName("cloudsql", volumeId, fallbackId));
            }
            throw e;
        }
    }

    @Override
    public Map<String, Object> ensureInstance(String project, String instance, Map<String, Object> metadata) {
        Map<String, Object> dataPlane = dataPlane(metadata);
        String containerId = stringValue(dataPlane.get("containerId"));
        if (containerId != null && lifecycleManager.isContainerRunning(containerId)) {
            EndpointInfo endpoint = lifecycleManager.resolveEndpoint(containerId, POSTGRES_PORT);
            Map<String, Object> updated = copy(metadata);
            applyEndpoint(updated, stringValue(dataPlane.get("image")),
                    volumeId(updated, project, instance), containerId, endpoint);
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
                    "cloudsql", volumeId, fallbackId(project, instance));
        }
    }

    @Override
    public void createDatabase(Map<String, Object> instanceMetadata, String database) {
        if ("postgres".equals(database)) {
            return;
        }
        if (databaseExists(instanceMetadata, database)) {
            return;
        }
        runSql(instanceMetadata, "postgres", "CREATE DATABASE " + quoteIdentifier(database),
                "Could not create PostgreSQL database " + database);
    }

    @Override
    public void deleteDatabase(Map<String, Object> instanceMetadata, String database) {
        if ("postgres".equals(database)) {
            return;
        }
        runSql(instanceMetadata, "postgres", "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                        + "WHERE datname = " + quoteLiteral(database) + " AND pid <> pg_backend_pid()",
                "Could not delete PostgreSQL database " + database);
        runSql(instanceMetadata, "postgres", "DROP DATABASE IF EXISTS " + quoteIdentifier(database),
                "Could not delete PostgreSQL database " + database);
    }

    @Override
    public void createOrUpdateUser(Map<String, Object> instanceMetadata, String user, String password) {
        String secret = password == null || password.isBlank() ? ADMIN_PASSWORD : password;
        String verb = roleExists(instanceMetadata, user) ? "ALTER ROLE " : "CREATE ROLE ";
        runSql(instanceMetadata, "postgres",
                verb + quoteIdentifier(user) + " WITH LOGIN PASSWORD " + quoteLiteral(secret),
                "Could not create PostgreSQL user " + user);
    }

    @Override
    public void deleteUser(Map<String, Object> instanceMetadata, String user, Iterable<String> databases) {
        for (String database : databases) {
            ExecResult result = psql(instanceMetadata, database, "DROP OWNED BY " + quoteIdentifier(user));
            if (result.exitCode() != 0) {
                LOG.debugv("Could not drop objects owned by {0} in database {1}: {2}",
                        user, database, errorOf(result));
            }
        }
        runSql(instanceMetadata, "postgres", "DROP ROLE IF EXISTS " + quoteIdentifier(user),
                "Could not delete PostgreSQL user " + user);
    }

    @Override
    public void grantDatabaseAccess(Map<String, Object> instanceMetadata, String database, String user) {
        runSql(instanceMetadata, "postgres",
                "GRANT CONNECT, CREATE ON DATABASE " + quoteIdentifier(database) + " TO " + quoteIdentifier(user),
                "Could not grant PostgreSQL database access");
        runSql(instanceMetadata, database,
                "GRANT USAGE, CREATE ON SCHEMA public TO " + quoteIdentifier(user),
                "Could not grant PostgreSQL schema access");
    }

    private void awaitReady(String containerId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = "no response";
        while (System.nanoTime() < deadline) {
            ExecResult result = lifecycleManager.exec(containerId, List.of(),
                    List.of("pg_isready", "-h", "127.0.0.1", "-p", String.valueOf(POSTGRES_PORT),
                            "-U", ADMIN_USER, "-d", "postgres"));
            if (result.exitCode() == 0) {
                return;
            }
            last = errorOf(result);
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw GcpException.unavailable("Interrupted while waiting for PostgreSQL startup");
            }
        }
        throw GcpException.unavailable("PostgreSQL data plane did not become ready: " + last);
    }

    /** Runs a SQL statement via {@code psql} inside the instance container; throws on failure. */
    private void runSql(Map<String, Object> instanceMetadata, String database, String sql, String errorMessage) {
        ExecResult result = psql(instanceMetadata, database, sql);
        if (result.exitCode() != 0) {
            throw GcpException.unavailable(errorMessage + ": " + errorOf(result));
        }
    }

    /** Runs a scalar query via {@code psql}; returns the trimmed output (empty when no rows match). */
    private String querySql(Map<String, Object> instanceMetadata, String database, String sql) {
        ExecResult result = psql(instanceMetadata, database, sql);
        if (result.exitCode() != 0) {
            throw GcpException.unavailable("PostgreSQL query failed: " + errorOf(result));
        }
        return result.stdout().strip();
    }

    private ExecResult psql(Map<String, Object> instanceMetadata, String database, String sql) {
        return lifecycleManager.exec(requireContainerId(instanceMetadata),
                List.of("PGPASSWORD=" + ADMIN_PASSWORD),
                List.of("psql", "-h", "127.0.0.1", "-p", String.valueOf(POSTGRES_PORT),
                        "-U", ADMIN_USER, "-d", database, "-v", "ON_ERROR_STOP=1", "-tAqc", sql));
    }

    private String requireContainerId(Map<String, Object> instanceMetadata) {
        String containerId = stringValue(dataPlane(instanceMetadata).get("containerId"));
        if (containerId == null || containerId.isBlank()) {
            throw GcpException.failedPrecondition("Cloud SQL instance has no running PostgreSQL container");
        }
        return containerId;
    }

    private static String errorOf(ExecResult result) {
        String message = result.stderr().isBlank() ? result.stdout() : result.stderr();
        return message.strip().replace('\n', ' ');
    }

    private boolean databaseExists(Map<String, Object> instanceMetadata, String database) {
        return !querySql(instanceMetadata, "postgres",
                "SELECT 1 FROM pg_database WHERE datname = " + quoteLiteral(database)).isEmpty();
    }

    private boolean roleExists(Map<String, Object> instanceMetadata, String user) {
        return !querySql(instanceMetadata, "postgres",
                "SELECT 1 FROM pg_roles WHERE rolname = " + quoteLiteral(user)).isEmpty();
    }

    private void applyEndpoint(Map<String, Object> metadata, String image, String volumeId,
                               String containerId, EndpointInfo endpoint) {
        metadata.put("ipAddresses", List.of(mapOf(
                "type", "PRIMARY",
                "ipAddress", endpoint.host(),
                "port", endpoint.port())));
        metadata.put("flociDataPlane", mapOf(
                "engine", "postgres",
                "image", image,
                "containerId", containerId,
                "host", endpoint.host(),
                "port", endpoint.port(),
                "volumeId", volumeId,
                "status", "RUNNING"));
    }

    private String imageFor(String databaseVersion) {
        return switch (databaseVersion) {
            case "POSTGRES_15" -> config.services().cloudsql().postgres15Image();
            case "POSTGRES_16" -> config.services().cloudsql().postgres16Image();
            case "POSTGRES_17" -> config.services().cloudsql().postgres17Image();
            case "POSTGRES_18" -> config.services().cloudsql().postgres18Image();
            default -> throw GcpException.invalidArgument("Unsupported PostgreSQL databaseVersion: " + databaseVersion);
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataPlane(Map<String, Object> metadata) {
        Object value = metadata.get("flociDataPlane");
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String volumeId(Map<String, Object> metadata, String project, String instance) {
        String existing = stringValue(dataPlane(metadata).get("volumeId"));
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return sanitize(project + "-" + instance) + "-" + String.format("%06x", RANDOM.nextInt(0xFFFFFF));
    }

    private String containerName(String project, String instance) {
        return "floci-cloudsql-" + fallbackId(project, instance);
    }

    private String fallbackId(String project, String instance) {
        return sanitize(project + "-" + instance);
    }

    private static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String sanitize(String value) {
        String sanitized = value.toLowerCase().replaceAll("[^a-z0-9_.-]", "-");
        sanitized = sanitized.replaceAll("^-+", "").replaceAll("-+$", "");
        return sanitized.isBlank() ? "default" : sanitized;
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        return new LinkedHashMap<>(value);
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}

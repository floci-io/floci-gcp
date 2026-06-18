package io.floci.gcp.services.cloudsql;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
            awaitReady(endpoint, Duration.ofSeconds(config.services().cloudsql().startupTimeoutSeconds()));
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
        try (Connection connection = connect(instanceMetadata, "postgres")) {
            if (databaseExists(connection, database)) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + quoteIdentifier(database));
            }
        } catch (SQLException e) {
            throw GcpException.unavailable("Could not create PostgreSQL database " + database + ": " + e.getMessage());
        }
    }

    @Override
    public void deleteDatabase(Map<String, Object> instanceMetadata, String database) {
        if ("postgres".equals(database)) {
            return;
        }
        try (Connection connection = connect(instanceMetadata, "postgres");
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    + "WHERE datname = " + quoteLiteral(database) + " AND pid <> pg_backend_pid()");
            statement.execute("DROP DATABASE IF EXISTS " + quoteIdentifier(database));
        } catch (SQLException e) {
            throw GcpException.unavailable("Could not delete PostgreSQL database " + database + ": " + e.getMessage());
        }
    }

    @Override
    public void createOrUpdateUser(Map<String, Object> instanceMetadata, String user, String password) {
        try (Connection connection = connect(instanceMetadata, "postgres");
             Statement statement = connection.createStatement()) {
            String secret = password == null || password.isBlank() ? ADMIN_PASSWORD : password;
            if (roleExists(connection, user)) {
                statement.execute("ALTER ROLE " + quoteIdentifier(user)
                        + " WITH LOGIN PASSWORD " + quoteLiteral(secret));
            } else {
                statement.execute("CREATE ROLE " + quoteIdentifier(user)
                        + " WITH LOGIN PASSWORD " + quoteLiteral(secret));
            }
        } catch (SQLException e) {
            throw GcpException.unavailable("Could not create PostgreSQL user " + user + ": " + e.getMessage());
        }
    }

    @Override
    public void deleteUser(Map<String, Object> instanceMetadata, String user, Iterable<String> databases) {
        try {
            for (String database : databases) {
                try (Connection connection = connect(instanceMetadata, database);
                     Statement statement = connection.createStatement()) {
                    statement.execute("DROP OWNED BY " + quoteIdentifier(user));
                } catch (SQLException e) {
                    LOG.debugv("Could not drop objects owned by {0} in database {1}: {2}",
                            user, database, e.getMessage());
                }
            }
            try (Connection connection = connect(instanceMetadata, "postgres");
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP ROLE IF EXISTS " + quoteIdentifier(user));
            }
        } catch (SQLException e) {
            throw GcpException.unavailable("Could not delete PostgreSQL user " + user + ": " + e.getMessage());
        }
    }

    @Override
    public void grantDatabaseAccess(Map<String, Object> instanceMetadata, String database, String user) {
        try (Connection postgres = connect(instanceMetadata, "postgres");
             Statement statement = postgres.createStatement()) {
            statement.execute("GRANT CONNECT, CREATE ON DATABASE " + quoteIdentifier(database)
                    + " TO " + quoteIdentifier(user));
        } catch (SQLException e) {
            throw GcpException.unavailable("Could not grant PostgreSQL database access: " + e.getMessage());
        }

        try (Connection databaseConnection = connect(instanceMetadata, database);
             Statement statement = databaseConnection.createStatement()) {
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + quoteIdentifier(user));
        } catch (SQLException e) {
            throw GcpException.unavailable("Could not grant PostgreSQL schema access: " + e.getMessage());
        }
    }

    private void awaitReady(EndpointInfo endpoint, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try (Connection ignored = connect(endpoint, "postgres")) {
                return;
            } catch (SQLException e) {
                last = e;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw GcpException.unavailable("Interrupted while waiting for PostgreSQL startup");
                }
            }
        }
        String message = last == null ? "unknown error" : last.getMessage();
        throw GcpException.unavailable("PostgreSQL data plane did not become ready: " + message);
    }

    private Connection connect(Map<String, Object> instanceMetadata, String database) throws SQLException {
        Map<String, Object> dataPlane = dataPlane(instanceMetadata);
        String host = stringValue(dataPlane.get("host"));
        Object port = dataPlane.get("port");
        if (host == null || port == null) {
            throw GcpException.failedPrecondition("Cloud SQL instance has no running PostgreSQL endpoint");
        }
        return connect(new EndpointInfo(host, Integer.parseInt(port.toString())), database);
    }

    private Connection connect(EndpointInfo endpoint, String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(endpoint, database), ADMIN_USER, ADMIN_PASSWORD);
    }

    private String jdbcUrl(EndpointInfo endpoint, String database) {
        return "jdbc:postgresql://" + endpoint.host() + ":" + endpoint.port() + "/" + database;
    }

    private boolean databaseExists(Connection connection, String database) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            statement.setString(1, database);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean roleExists(Connection connection, String user) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
            statement.setString(1, user);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
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

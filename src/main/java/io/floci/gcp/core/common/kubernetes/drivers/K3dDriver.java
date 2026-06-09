package io.floci.gcp.core.common.kubernetes.drivers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.floci.gcp.core.common.kubernetes.Cluster;
import io.floci.gcp.core.common.kubernetes.ClusterStatus;
import io.floci.gcp.core.common.kubernetes.exceptions.ClusterCommandException;
import io.floci.gcp.core.common.kubernetes.exceptions.ClusterNotFoundException;
import io.floci.gcp.core.common.kubernetes.metadata.ClusterMetadata;
import io.floci.gcp.core.common.kubernetes.drivers.ClusterDriverType;

@ApplicationScoped
public class K3dDriver implements ClusterDriver {

    private static final Logger LOG = Logger.getLogger(K3dDriver.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(30);

    private static final int MAX_OUTPUT_SIZE = 1024 * 1024; // 1MB

    private static final Pattern VALID_CLUSTER_NAME =
            Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private static final ExecutorService IO_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "k3d-io-worker");
        t.setDaemon(true);
        return t;
    });

    public record ClusterConnectionInfo(String endpoint, String caData) {}

    @Override
    public Cluster create(String name) {
        validateClusterName(name);
        ensureDockerInstalled();
        ensureK3dInstalled();

        try {
            List<String> command = List.of("k3d", "cluster", "create", name);
            CommandResult result = executeCommand(command, DEFAULT_TIMEOUT);

            LOG.debugf("k3d cluster create stdout:%n%s", result.stdout());

            if (!result.stderr().isBlank()) {
                LOG.warnf("k3d cluster create stderr:%n%s", result.stderr());
            }

            if (result.exitCode() != 0) {
                logCommandFailure("create", name, result);
                throw new ClusterCommandException("Failed to create cluster: " + name);
            }

            return get(name);

        } catch (Exception e) {
            LOG.errorf(e, "Failed creating cluster %s", name);
            throw e instanceof ClusterCommandException ce ? ce :
                    new ClusterCommandException("Failed creating cluster: " + name, e);
        }
    }

    @Override
    public Cluster get(String name) {
        validateClusterName(name);

        if (!exists(name)) {
            throw new ClusterNotFoundException(name);
        }

        return new Cluster(name, name, ClusterStatus.RUNNING, ClusterDriverType.K3D);
    }

    @Override
    public boolean exists(String name) {
        validateClusterName(name);
        ensureK3dInstalled();
        return executeSimpleCommand("k3d", "cluster", "get", name) == 0;
    }

    @Override
    public void delete(String name) {
        validateClusterName(name);
        ensureK3dInstalled();

        int result = executeSimpleCommand("k3d", "cluster", "delete", name);
        if (result != 0) {
            throw new ClusterCommandException("Failed deleting cluster: " + name);
        }
    }

    @Override
    public List<Cluster> list() {
        ensureK3dInstalled();

        try {
            List<String> command = List.of("k3d", "cluster", "list", "-o", "json");
            CommandResult result = executeCommand(command, SHORT_TIMEOUT);

            if (result.exitCode() != 0) {
                logCommandFailure("list", null, result);
                throw new ClusterCommandException("Failed to list clusters");
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(result.stdout());

            List<Cluster> clusters = new ArrayList<>();

            if (root.isArray()) {
                for (JsonNode node : root) {
                    String name = node.path("name").asText(null);
                    if (name != null && !name.isBlank()) {
                        clusters.add(new Cluster(name, name, ClusterStatus.RUNNING, ClusterDriverType.K3D));
                    }
                }
            }
            return clusters;

        } catch (Exception e) {
            LOG.error("Failed listing clusters", e);
            throw new ClusterCommandException("Failed listing clusters", e);
        }
    }

    public String kubeConfig(String name) {
        validateClusterName(name);
        ensureK3dInstalled();

        try {
            List<String> command = List.of("k3d", "kubeconfig", "get", name);
            CommandResult result = executeCommand(command, SHORT_TIMEOUT);

            if (result.exitCode() != 0) {
                logCommandFailure("kubeconfig get", name, result);
                throw new ClusterCommandException("Failed to fetch kubeconfig for cluster: " + name);
            }

            return result.stdout();

        } catch (Exception e) {
            throw e instanceof ClusterCommandException ce ? ce :
                    new ClusterCommandException("Failed fetching kubeconfig for " + name, e);
        }
    }

    public ClusterConnectionInfo connectionInfo(String name) {
        validateClusterName(name);
        ensureK3dInstalled();

        try {
            String kubeconfigYaml = kubeConfig(name);

            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            JsonNode root = yamlMapper.readTree(kubeconfigYaml);

            JsonNode clusters = root.path("clusters");
            if (!clusters.isArray() || clusters.isEmpty()) {
                throw new ClusterCommandException("Invalid kubeconfig format for cluster: " + name);
            }

            JsonNode clusterNode = clusters.get(0).path("cluster");
            if (clusterNode.isMissingNode()) {
                throw new ClusterCommandException("Invalid kubeconfig format for cluster: " + name);
            }

            String server = clusterNode.path("server").asText("");
            String caData = clusterNode.path("certificate-authority-data").asText("");

            if (server.isBlank() || caData.isBlank()) {
                throw new ClusterCommandException("Missing server or CA data in kubeconfig for cluster: " + name);
            }

            // Normalize endpoint
            server = server.replace("https://", "");
            if (server.startsWith("0.0.0.0:")) {
                server = "127.0.0.1:" + server.substring("0.0.0.0:".length());
            }

            return new ClusterConnectionInfo(server, caData);

        } catch (Exception e) {
            throw e instanceof ClusterCommandException ce ? ce :
                    new ClusterCommandException("Failed parsing kubeconfig for " + name, e);
        }
    }

    public ClusterMetadata metadata(String clusterName, String location) {
        ClusterConnectionInfo conn = connectionInfo(clusterName);

        List<Map<String, Object>> nodePools = List.of(
                Map.of("name", "default-pool", "status", "RUNNING")
        );

        return new ClusterMetadata(
                location,
                conn.endpoint(),
                conn.caData(),
                "v1.33.1-k3s1",           // TODO: make dynamic
                nodePools,
                "default",                // VPC
                "default",                // Subnet
                Map.of()                  // labels
        );
    }

    // ==================== Validation & Helpers ====================

    private void validateClusterName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Cluster name cannot be null or blank");
        }
        if (!VALID_CLUSTER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid cluster name: '" + name +
                            "'. Must be lowercase alphanumeric with optional hyphens (1-63 chars).");
        }
    }

    private void ensureDockerInstalled() {
        if (executeSimpleCommand("docker", "version", "--format", "{{.Server.Version}}") != 0) {
            throw new ClusterCommandException("Docker is not installed or not accessible");
        }
    }

    private void ensureK3dInstalled() {
        if (executeSimpleCommand("k3d", "version") == 0) {
            return;
        }

        throw new ClusterCommandException("""
                k3d not found.

                Install it first:

                macOS:
                  brew install k3d

                Linux:
                  wget -q -O - https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh | bash

                Windows:
                  choco install k3d
                """);
    }

    private int executeSimpleCommand(String... command) {
        try {
            CommandResult result = executeCommand(List.of(command), SHORT_TIMEOUT);
            return result.exitCode();
        } catch (Exception e) {
            LOG.errorf(e, "Failed executing simple command: %s", String.join(" ", command));
            return -1;
        }
    }

    private CommandResult executeCommand(List<String> command, Duration timeout)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        Process process = pb.start();

        CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
        CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new ClusterCommandException("Command timed out: " + String.join(" ", command));
        }

        String stdout = stdoutFuture.join();
        String stderr = stderrFuture.join();

        return new CommandResult(process.exitValue(), stdout, stderr);
    }

    private CompletableFuture<String> readStreamAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (var reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {

                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[8192];
                int len;

                while ((len = reader.read(buffer)) != -1) {
                    if (sb.length() + len > MAX_OUTPUT_SIZE) {
                        sb.append(buffer, 0, MAX_OUTPUT_SIZE - sb.length());
                        break;
                    }
                    sb.append(buffer, 0, len);
                }
                return sb.toString();
            } catch (IOException e) {
                LOG.warn("Error reading process output", e);
                return "";
            }
        }, IO_EXECUTOR);
    }

    private void logCommandFailure(String operation, String clusterName, CommandResult result) {
        LOG.errorf("k3d %s failed for cluster %s. Exit code: %d | stderr: %s",
                operation,
                clusterName != null ? clusterName : "<none>",
                result.exitCode(),
                truncate(result.stderr(), 4096));
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "... [truncated]";
    }

    @PreDestroy
    public void shutdown() {
        IO_EXECUTOR.shutdown();
        try {
            if (!IO_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                IO_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IO_EXECUTOR.shutdownNow();
        }
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {}
}
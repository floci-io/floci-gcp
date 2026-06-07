package io.floci.gcp.core.common.kubernetes.drivers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.floci.gcp.core.common.kubernetes.Cluster;
import io.floci.gcp.core.common.kubernetes.ClusterDriverType;
import io.floci.gcp.core.common.kubernetes.ClusterStatus;
import io.floci.gcp.core.common.kubernetes.metadata.ClusterMetadata;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class K3dDriver implements ClusterDriver {

    public record ClusterConnectionInfo(
            String endpoint,
            String caData) {
    }

    @Override
    public Cluster create(String name) {

        ensureDockerInstalled();
        ensureK3dInstalled();

        try {

            ProcessBuilder builder = new ProcessBuilder(
                    "k3d",
                    "cluster",
                    "create",
                    name);

            Process process = builder.start();

            String stdout = new String(
                    process.getInputStream()
                            .readAllBytes());

            String stderr = new String(
                    process.getErrorStream()
                            .readAllBytes());

            int result = process.waitFor();

            System.out.println("STDOUT:");
            System.out.println(stdout);

            System.out.println("STDERR:");
            System.out.println(stderr);

            if (result != 0) {

                throw new RuntimeException(
                        "Failed to create cluster: "
                                + name
                                + "\nstdout:\n"
                                + stdout
                                + "\nstderr:\n"
                                + stderr);
            }

            return get(name);

        } catch (Exception e) {

            throw new RuntimeException(
                    "Failed to create cluster: "
                            + name,
                    e);
        }
    }

    @Override
    public Cluster get(String name) {

        if (!exists(name)) {

            throw new RuntimeException(
                    "Cluster not found: "
                            + name);
        }

        return new Cluster(
                name,
                name,
                ClusterStatus.RUNNING,
                ClusterDriverType.K3D);
    }

    @Override
    public boolean exists(String name) {

        return execute(
                "k3d",
                "cluster",
                "get",
                name) == 0;
    }

    @Override
    public void delete(String name) {

        ensureK3dInstalled();

        int result = execute(
                "k3d",
                "cluster",
                "delete",
                name);

        if (result != 0) {

            throw new RuntimeException(
                    "Failed to delete cluster: "
                            + name);
        }
    }

    @Override
    public List<Cluster> list() {

        ensureK3dInstalled();

        try {

            Process process = new ProcessBuilder(
                    "k3d",
                    "cluster",
                    "list",
                    "-o",
                    "json")
                    .redirectErrorStream(true)
                    .start();

            String json = new String(
                    process.getInputStream()
                            .readAllBytes());

            process.waitFor();

            ObjectMapper mapper = new ObjectMapper();

            JsonNode root = mapper.readTree(json);

            List<Cluster> clusters = new ArrayList<>();

            for (JsonNode node : root) {

                String name = node.get("name")
                        .asText();

                clusters.add(
                        new Cluster(
                                name,
                                name,
                                ClusterStatus.RUNNING,
                                ClusterDriverType.K3D));
            }

            return clusters;

        } catch (Exception e) {

            throw new RuntimeException(
                    "Failed listing clusters",
                    e);
        }
    }

    public String kubeConfig(String name) {

        ensureK3dInstalled();

        if (!exists(name)) {

            throw new RuntimeException(
                    "No cluster exists with name: "
                            + name);
        }

        try {

            Process process = new ProcessBuilder(
                    "k3d",
                    "kubeconfig",
                    "get",
                    name)
                    .redirectErrorStream(true)
                    .start();

            String output = new String(
                    process.getInputStream()
                            .readAllBytes());

            int exitCode = process.waitFor();

            if (exitCode != 0) {

                throw new RuntimeException(
                        "Failed to fetch kubeconfig for cluster: "
                                + name);
            }

            return output;

        } catch (Exception e) {

            throw new RuntimeException(
                    "Failed to fetch kubeconfig for cluster: "
                            + name,
                    e);
        }
    }

    public ClusterConnectionInfo connectionInfo(
            String name) {

        ensureK3dInstalled();

        if (!exists(name)) {

            throw new RuntimeException(
                    "No cluster exists with name: "
                            + name);
        }

        try {

            String kubeconfig = kubeConfig(name);

            ObjectMapper yaml = new ObjectMapper(
                    new YAMLFactory());

            JsonNode root = yaml.readTree(
                    kubeconfig);

            JsonNode cluster = root.path("clusters")
                    .get(0)
                    .path("cluster");

            String server = cluster.path("server")
                    .asText();

            String caData = cluster.path(
                    "certificate-authority-data")
                    .asText();

            server = server.replace(
                    "https://",
                    "");

            if (server.startsWith(
                    "0.0.0.0:")) {

                server = "127.0.0.1:"
                        + server.substring(
                                "0.0.0.0:"
                                        .length());
            }

            return new ClusterConnectionInfo(
                    server,
                    caData);

        } catch (Exception e) {

            throw new RuntimeException(
                    "Failed to parse kubeconfig for cluster: "
                            + name,
                    e);
        }
    }

    private void ensureDockerInstalled() {

        if (execute(
                "docker",
                "version") != 0) {

            throw new RuntimeException(
                    "Docker is not installed");
        }
    }

    private void ensureK3dInstalled() {

        if (execute(
                "k3d",
                "version") == 0) {

            return;
        }

        throw new RuntimeException(
                """
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

    private int execute(
            String... command) {

        try {

            Process process = new ProcessBuilder(
                    command)
                    .redirectErrorStream(true)
                    .start();

            return process.waitFor();

        } catch (Exception e) {

            return -1;
        }
    }

    public ClusterMetadata metadata(
            String clusterName,
            String location) {

        ClusterConnectionInfo connection = connectionInfo(clusterName);

        List<Map<String, Object>> nodePools = List.of(
                Map.of(
                        "name",
                        "default-pool",
                        "status",
                        "RUNNING"));

        return new ClusterMetadata(
                location,
                connection.endpoint(),
                connection.caData(),

                // TODO: fetch dynamically later
                "v1.33.1-k3s1",

                nodePools,

                // k3d has no VPC concept
                "default",
                "default",

                Map.of()

        );
    }
}
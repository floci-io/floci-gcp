package io.floci.gcp.core.common.kubernetes.cluster;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class K3dDriver {

    public boolean clusterExists(String name) {
        return execute(
                "k3d",
                "cluster",
                "get",
                name) == 0;
    }

    public boolean createCluster(String name) {

        ensureDockerInstalled();
        ensureK3dInstalled();

        boolean created = execute(
                "k3d",
                "cluster",
                "create",
                name,
                "--wait") == 0;

        if (!created) {
            return false;
        }

        try {

            String kubeConfig = getKubeConfig(name);

            Path clusterDir = Path.of(
                    System.getProperty("user.home"),
                    ".floci",
                    "clusters",
                    name);

            Files.createDirectories(clusterDir);

            Files.writeString(
                    clusterDir.resolve("kubeconfig.yaml"),
                    kubeConfig);

            Files.writeString(
                    clusterDir.resolve("metadata.json"),
                    """
                            {
                              "name":"%s",
                              "driver":"k3d",
                              "status":"RUNNING"
                            }
                            """.formatted(name));

            return true;

        } catch (Exception e) {

            throw new RuntimeException(
                    "Failed to persist cluster metadata",
                    e);
        }
    }

    public boolean deleteCluster(String name) {

        ensureK3dInstalled();

        return execute(
                "k3d",
                "cluster",
                "delete",
                name) == 0;
    }

    public String getKubeConfig(String name) {

        ensureK3dInstalled();

        try {

            Process process = new ProcessBuilder(
                    "k3d",
                    "kubeconfig",
                    "get",
                    name)
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            process.getInputStream()))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    output.append(line)
                            .append(System.lineSeparator());
                }
            }

            process.waitFor();

            return output.toString();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void ensureDockerInstalled() {

        if (execute("docker", "version") != 0) {
            throw new RuntimeException(
                    "Docker is not installed");
        }
    }

    private void ensureK3dInstalled() {

        if (execute("k3d", "version") == 0) {
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

    private int execute(String... command) {

        try {

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            return process.waitFor();

        } catch (Exception e) {
            return -1;
        }
    }
}
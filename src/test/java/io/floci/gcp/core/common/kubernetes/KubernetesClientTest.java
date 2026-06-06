package io.floci.gcp.core.common.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;

import io.floci.gcp.core.common.kubernetes.client.KubernetesClient;
import io.floci.gcp.core.common.kubernetes.client.KubernetesClientFactory;
import io.floci.gcp.core.common.kubernetes.cluster.K3dDriver;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class KubernetesClientTest {

    @Test
    void shouldConnectToCreatedCluster() {

        String clusterName = "k8s-client-test";

        K3dDriver driver = new K3dDriver();

        try {

            if (driver.clusterExists(clusterName)) {
                driver.deleteCluster(clusterName);
            }

            driver.createCluster(clusterName);

            Path kubeconfig = Paths.get(
                    System.getProperty("user.home"),
                    ".floci",
                    "clusters",
                    clusterName,
                    "kubeconfig.yaml"
            );

            assertTrue(
                    Files.exists(kubeconfig),
                    "Kubeconfig should exist after cluster creation"
            );

            KubernetesClient client =
                    KubernetesClientFactory.create(kubeconfig);

            JsonNode version =
                    client.get("/version");

            assertNotNull(version);

            System.out.println("=== VERSION ===");
            System.out.println(version.toPrettyString());

            assertTrue(
                    version.has("major"),
                    "Expected Kubernetes version response"
            );

            JsonNode nodes =
                    client.get("/api/v1/nodes");

            assertNotNull(nodes);

            System.out.println("=== NODES ===");
            System.out.println(nodes.toPrettyString());

            assertTrue(
                    nodes.has("items"),
                    "Expected nodes response"
            );

            assertFalse(
                    nodes.get("items").isEmpty(),
                    "Expected at least one node"
            );

        } finally {

            try {
                if (driver.clusterExists(clusterName)) {
                    driver.deleteCluster(clusterName);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
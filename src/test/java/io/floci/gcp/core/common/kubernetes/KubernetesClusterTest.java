package io.floci.gcp.core.common.kubernetes;

import io.floci.gcp.core.common.kubernetes.cluster.K3dDriver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;

class KubernetesClusterTest {

    @Test
    void shouldCreateCluster() {

        K3dDriver driver = new K3dDriver();

        String clusterName = "floci-test";

        try {

            if (driver.clusterExists(clusterName)) {
                driver.deleteCluster(clusterName);
            }

            boolean created = driver.createCluster(clusterName);

            assertTrue(created);

            assertTrue(
                    driver.clusterExists(clusterName));

            String kubeConfig = driver.getKubeConfig(clusterName);

            assertNotNull(kubeConfig);
            assertFalse(kubeConfig.isBlank());

            Path clusterDir = Path.of(
                    System.getProperty("user.home"),
                    ".floci",
                    "clusters",
                    clusterName);

            assertTrue(
                    Files.exists(
                            clusterDir.resolve(
                                    "metadata.json")));

            assertTrue(
                    Files.exists(
                            clusterDir.resolve(
                                    "kubeconfig.yaml")));

        } finally {

            if (driver.clusterExists(clusterName)) {
                driver.deleteCluster(clusterName);
            }
        }
    }

}
package io.floci.gcp.core.common.kubernetes;

import io.floci.gcp.core.common.kubernetes.cluster.K3dDriver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KubernetesClusterTest {

    @Test
    void shouldCreateCluster() {

        K3dDriver driver = new K3dDriver();

        String clusterName = "floci-test";

        try {

            if (driver.clusterExists(clusterName)) {
                driver.deleteCluster(clusterName);
            }

            boolean created =
                    driver.createCluster(clusterName);

            assertTrue(created);

            assertTrue(
                    driver.clusterExists(clusterName)
            );

            String kubeConfig =
                    driver.getKubeConfig(clusterName);

            assertNotNull(kubeConfig);
            assertFalse(kubeConfig.isBlank());

        } finally {

            if (driver.clusterExists(clusterName)) {
                driver.deleteCluster(clusterName);
            }
        }
    }


    @Test
void shouldNotCreateClusterTwice() {

    K3dDriver driver = new K3dDriver();

    String clusterName = "floci-test";

    try {

        driver.createCluster(clusterName);

        assertTrue(
                driver.clusterExists(clusterName)
        );

        // idempotency check
        assertTrue(
                driver.clusterExists(clusterName)
        );

    } finally {

        if (driver.clusterExists(clusterName)) {
            driver.deleteCluster(clusterName);
        }
    }
}
}
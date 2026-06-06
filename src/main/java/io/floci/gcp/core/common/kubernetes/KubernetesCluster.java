package io.floci.gcp.core.common.kubernetes;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.floci.gcp.core.common.kubernetes.cluster.K3dDriver;

@ApplicationScoped
public class KubernetesCluster {

    private static final String CLUSTER_NAME = "nice";

    @Inject
    K3dDriver driver;

    public boolean createCluster() {

        if (driver.clusterExists(CLUSTER_NAME)) {
            return true;
        }

        return driver.createCluster(CLUSTER_NAME);
    }

    public boolean deleteCluster() {
        return driver.deleteCluster(CLUSTER_NAME);
    }

    public String kubeConfig() {
        return driver.getKubeConfig(CLUSTER_NAME);
    }
}
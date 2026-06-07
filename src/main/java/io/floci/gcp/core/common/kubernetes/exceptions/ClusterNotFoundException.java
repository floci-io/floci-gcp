package io.floci.gcp.core.common.kubernetes.exceptions;

import io.floci.gcp.core.common.kubernetes.ClusterException;

public class ClusterNotFoundException
        extends ClusterException {

    public ClusterNotFoundException(
            String clusterName) {

        super(
                "Cluster not found: "
                        + clusterName);
    }
}
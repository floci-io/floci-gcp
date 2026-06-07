package io.floci.gcp.core.common.kubernetes.exceptions;

import io.floci.gcp.core.common.kubernetes.ClusterException;

public class ClusterCommandException
        extends ClusterException{

    public ClusterCommandException(
            String message) {

        super(message);
    }

    public ClusterCommandException(
            String message,
            Throwable cause) {

        super(message, cause);
    }
}
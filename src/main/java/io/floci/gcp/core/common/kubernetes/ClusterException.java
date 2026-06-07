package io.floci.gcp.core.common.kubernetes;

public class ClusterException extends RuntimeException {

    public ClusterException(String message) {
        super(message);
    }

    public ClusterException(
            String message,
            Throwable cause) {
        super(message, cause);
    }
}
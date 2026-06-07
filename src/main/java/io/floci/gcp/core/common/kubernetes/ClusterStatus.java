package io.floci.gcp.core.common.kubernetes;

public enum ClusterStatus {
    CREATING,
    RUNNING,
    UPDATING,
    DELETING,
    FAILED,
    UNKNOWN
}
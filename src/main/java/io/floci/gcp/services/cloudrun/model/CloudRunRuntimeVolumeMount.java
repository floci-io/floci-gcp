package io.floci.gcp.services.cloudrun.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record CloudRunRuntimeVolumeMount(
        String bucket,
        String objectPrefix,
        String volumeName,
        String rootPath,
        String hostPath,
        String mountPath,
        boolean readOnly
) {
    public CloudRunRuntimeVolumeMount(String bucket,
                                      String rootPath,
                                      String hostPath,
                                      String mountPath,
                                      boolean readOnly) {
        this(bucket, "", null, rootPath, hostPath, mountPath, readOnly);
    }
}

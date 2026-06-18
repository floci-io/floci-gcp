package io.floci.gcp.services.cloudrun.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record CloudRunRuntimeInstance(
        String project,
        String location,
        String serviceName,
        String revisionName,
        String image,
        String containerId,
        int ingressContainerPort,
        String dockerNetwork,
        String endpointHost,
        int endpointPort,
        String publicUrl,
        String status,
        long createTimeMillis,
        long updateTimeMillis,
        String lastError,
        long requestTimeoutMillis,
        List<CloudRunRuntimeVolumeMount> gcsVolumeMounts
) {
    public CloudRunRuntimeInstance(String project,
                                   String location,
                                   String serviceName,
                                   String revisionName,
                                   String image,
                                   String containerId,
                                   int ingressContainerPort,
                                   String dockerNetwork,
                                   String endpointHost,
                                   int endpointPort,
                                   String publicUrl,
                                   String status,
                                   long createTimeMillis,
                                   long updateTimeMillis,
                                   String lastError,
                                   long requestTimeoutMillis) {
        this(project, location, serviceName, revisionName, image, containerId, ingressContainerPort, dockerNetwork,
                endpointHost, endpointPort, publicUrl, status, createTimeMillis, updateTimeMillis, lastError,
                requestTimeoutMillis, List.of());
    }

    public CloudRunRuntimeInstance {
        gcsVolumeMounts = gcsVolumeMounts == null ? List.of() : List.copyOf(gcsVolumeMounts);
    }

    public boolean ready() {
        return "READY".equals(status);
    }

    public String endpointUri(String pathAndQuery) {
        String suffix = pathAndQuery == null || pathAndQuery.isBlank() ? "/" : pathAndQuery;
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return "http://" + endpointHost + ":" + endpointPort + suffix;
    }

    public CloudRunRuntimeInstance withStatus(String status, String lastError) {
        return new CloudRunRuntimeInstance(project, location, serviceName, revisionName, image,
                containerId, ingressContainerPort, dockerNetwork, endpointHost, endpointPort, publicUrl, status,
                createTimeMillis, System.currentTimeMillis(), lastError, requestTimeoutMillis, gcsVolumeMounts);
    }

    public CloudRunRuntimeInstance withEndpoint(String endpointHost, int endpointPort) {
        return new CloudRunRuntimeInstance(project, location, serviceName, revisionName, image,
                containerId, ingressContainerPort, dockerNetwork, endpointHost, endpointPort, publicUrl, status,
                createTimeMillis, System.currentTimeMillis(), lastError, requestTimeoutMillis, gcsVolumeMounts);
    }
}

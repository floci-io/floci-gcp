package io.floci.gcp.services.cloudrun.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

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
        long requestTimeoutMillis
) {
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
                createTimeMillis, System.currentTimeMillis(), lastError, requestTimeoutMillis);
    }

    public CloudRunRuntimeInstance withEndpoint(String endpointHost, int endpointPort) {
        return new CloudRunRuntimeInstance(project, location, serviceName, revisionName, image,
                containerId, ingressContainerPort, dockerNetwork, endpointHost, endpointPort, publicUrl, status,
                createTimeMillis, System.currentTimeMillis(), lastError, requestTimeoutMillis);
    }
}

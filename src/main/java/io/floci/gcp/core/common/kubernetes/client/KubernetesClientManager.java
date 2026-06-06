package io.floci.gcp.core.common.kubernetes.client;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class KubernetesClientManager {

    private static final Map<String, KubernetesClient> CLIENTS =
            new ConcurrentHashMap<>();

    private KubernetesClientManager() {
    }

    public static KubernetesClient getOrCreate(
            String clusterName,
            Path kubeconfigPath
    ) {

        return CLIENTS.computeIfAbsent(
                clusterName,
                ignored -> KubernetesClientFactory.create(
                        kubeconfigPath
                )
        );
    }

    public static void remove(String clusterName) {
        CLIENTS.remove(clusterName);
    }

    public static void clear() {
        CLIENTS.clear();
    }
}
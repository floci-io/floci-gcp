package io.floci.gcp.core.common.kubernetes.client;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import okhttp3.OkHttpClient;

import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

public final class KubernetesClientFactory {

    private KubernetesClientFactory() {
    }

    public static KubernetesClient create(
            Path kubeconfigPath
    ) {

        try {

            String kubeconfig =
                    Files.readString(kubeconfigPath);

            ApiClient apiClient =
                    ClientBuilder
                            .kubeconfig(
                                    KubeConfig.loadKubeConfig(
                                            new StringReader(kubeconfig)
                                    )
                            )
                            .build();

            String server =
                    apiClient.getBasePath();

            OkHttpClient okHttpClient =
                    apiClient.getHttpClient();

            return new KubernetesClient(
                    okHttpClient,
                    URI.create(server)
            );

        } catch (Exception e) {

            throw new RuntimeException(
                    "Failed creating kubernetes client",
                    e
            );
        }
    }
}
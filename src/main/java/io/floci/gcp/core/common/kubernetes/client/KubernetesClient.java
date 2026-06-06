package io.floci.gcp.core.common.kubernetes.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URI;

public class KubernetesClient {

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    private final OkHttpClient httpClient;
    private final URI serverUri;

    KubernetesClient(
            OkHttpClient httpClient,
            URI serverUri
    ) {
        this.httpClient = httpClient;
        this.serverUri = serverUri;
    }

    public JsonNode get(String path) {

        try {

            Request request =
                    new Request.Builder()
                            .url(
                                    serverUri.resolve(path)
                                            .toString()
                            )
                            .get()
                            .build();

            try (Response response =
                         httpClient.newCall(request)
                                 .execute()) {

                return MAPPER.readTree(
                        response.body().string()
                );
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
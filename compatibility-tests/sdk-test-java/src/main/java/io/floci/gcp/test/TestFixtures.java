package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.cloud.NoCredentials;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

public final class TestFixtures {

    private TestFixtures() {
    }

    public static String projectId() {
        return System.getenv().getOrDefault("FLOCI_GCP_PROJECT", "test-project");
    }

    public static String endpoint() {
        return System.getenv().getOrDefault("FLOCI_GCP_ENDPOINT", "http://localhost:4588");
    }

    public static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Creates a GCS Storage client.
     * The STORAGE_EMULATOR_HOST env var is auto-detected by the GCP SDK.
     * We also explicitly set the host and use NoCredentials for emulator use.
     */
    public static Storage storageClient() {
        return StorageOptions.newBuilder()
                .setHost(endpoint())
                .setProjectId(projectId())
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
    }

    /**
     * Creates a Firestore client pointing at the emulator.
     * GrpcFirestoreRpc uses plaintext when host contains "localhost"; setHost routes traffic there.
     */
    public static Firestore firestoreClient() {
        URI uri = URI.create(endpoint());
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 4588;
        return FirestoreOptions.newBuilder()
                .setProjectId(projectId())
                .setHost(host + ":" + port)
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
    }

    /**
     * Creates a Datastore client.
     * SDK v2.25.2 uses HttpDatastoreRpc only. setHost() routes to the emulator
     * at http://{host}:{port}/v1/projects/{projectId}:{method}.
     */
    public static Datastore datastoreClient() {
        URI uri = URI.create(endpoint());
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 4588;
        // SDK 2.x isEmulator() only recognises "localhost" — for remote hosts (e.g. Docker)
        // we must pass the full URL with scheme so the SDK builds a valid project endpoint.
        boolean isLocalhost = "localhost".equals(host) || "127.0.0.1".equals(host);
        String datastoreHost = isLocalhost ? (host + ":" + port) : (uri.getScheme() + "://" + host + ":" + port);
        return DatastoreOptions.newBuilder()
                .setProjectId(projectId())
                .setHost(datastoreHost)
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
    }

    /**
     * Creates a Secret Manager client using a plaintext gRPC channel to the emulator.
     * No emulator env var is auto-detected for Secret Manager, so we configure manually.
     */
    public static SecretManagerServiceClient secretManagerClient() throws IOException {
        URI uri = URI.create(endpoint());
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 4588;

        SecretManagerServiceSettings settings = SecretManagerServiceSettings.newBuilder()
                .setTransportChannelProvider(
                        InstantiatingGrpcChannelProvider.newBuilder()
                                .setEndpoint(host + ":" + port)
                                .setChannelConfigurator(builder -> builder.usePlaintext())
                                .build())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build();

        return SecretManagerServiceClient.create(settings);
    }
}

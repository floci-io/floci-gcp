package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.cloud.NoCredentials;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.functions.v2.FunctionServiceClient;
import com.google.cloud.functions.v2.FunctionServiceSettings;
import com.google.cloud.run.v2.RevisionsClient;
import com.google.cloud.run.v2.RevisionsSettings;
import com.google.cloud.run.v2.ServicesClient;
import com.google.cloud.run.v2.ServicesSettings;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.logging.v2.LoggingClient;
import com.google.cloud.logging.v2.LoggingSettings;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceSettings;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.CloudTasksSettings;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sqladmin.SQLAdmin;

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

    /**
     * Creates a Cloud Tasks client pointing at the emulator.
     * No standard emulator env var exists; configure explicitly via gRPC channel.
     */
    public static CloudTasksClient cloudTasksClient() throws IOException {
        URI uri = URI.create(endpoint());
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 4588;

        CloudTasksSettings settings = CloudTasksSettings.newBuilder()
                .setTransportChannelProvider(
                        InstantiatingGrpcChannelProvider.newBuilder()
                                .setEndpoint(host + ":" + port)
                                .setChannelConfigurator(builder -> builder.usePlaintext())
                                .build())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build();

        return CloudTasksClient.create(settings);
    }

    public static ServicesClient cloudRunServicesClient() throws IOException {
        ServicesSettings settings = ServicesSettings.newHttpJsonBuilder()
                .setEndpoint(endpoint())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build();
        return ServicesClient.create(settings);
    }

    public static RevisionsClient cloudRunRevisionsClient() throws IOException {
        RevisionsSettings settings = RevisionsSettings.newHttpJsonBuilder()
                .setEndpoint(endpoint())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build();
        return RevisionsClient.create(settings);
    }

    public static FunctionServiceClient cloudFunctionsClient() throws IOException {
        FunctionServiceSettings settings = FunctionServiceSettings.newHttpJsonBuilder()
                .setEndpoint(endpoint())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build();
        return FunctionServiceClient.create(settings);
    }

    public static SQLAdmin sqlAdminClient() {
        return new SQLAdmin.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance(), request -> {
        })
                .setApplicationName("floci-gcp-compat")
                .setRootUrl(endpoint() + "/")
                // The generated v1beta4 request classes already include sql/v1beta4/
                // in their URI templates. Setting it here would produce
                // /sql/v1beta4/sql/v1beta4/... and miss the emulator routes.
                .setServicePath("")
                .build();
    }

    /**
     * Creates a Cloud Logging client pointing at the emulator.
     * No standard emulator env var exists; configure explicitly via plaintext gRPC channel.
     */
    public static LoggingClient loggingClient() throws IOException {
        URI uri = URI.create(endpoint());
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 4588;

        LoggingSettings settings = LoggingSettings.newBuilder()
                .setTransportChannelProvider(
                        InstantiatingGrpcChannelProvider.newBuilder()
                                .setEndpoint(host + ":" + port)
                                .setChannelConfigurator(builder -> builder.usePlaintext())
                                .build())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build();

        return LoggingClient.create(settings);
    }

    /**
     * Creates a Cloud KMS client pointing at the emulator.
     * No standard emulator env var exists; configure explicitly via plaintext gRPC channel.
     */
    public static KeyManagementServiceClient kmsClient() throws IOException {
        URI uri = URI.create(endpoint());
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 4588;

        KeyManagementServiceSettings settings = KeyManagementServiceSettings.newBuilder()
                .setTransportChannelProvider(
                        InstantiatingGrpcChannelProvider.newBuilder()
                                .setEndpoint(host + ":" + port)
                                .setChannelConfigurator(builder -> builder.usePlaintext())
                                .build())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build();

        return KeyManagementServiceClient.create(settings);
    }
}

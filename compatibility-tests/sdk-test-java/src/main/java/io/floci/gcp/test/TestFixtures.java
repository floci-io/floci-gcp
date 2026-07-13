package io.floci.gcp.test;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.api.services.sqladmin.SQLAdmin;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.functions.v2.FunctionServiceClient;
import com.google.cloud.functions.v2.FunctionServiceSettings;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceSettings;
import com.google.cloud.logging.v2.LoggingClient;
import com.google.cloud.logging.v2.LoggingSettings;
import com.google.cloud.run.v2.RevisionsClient;
import com.google.cloud.run.v2.RevisionsSettings;
import com.google.cloud.run.v2.ServicesClient;
import com.google.cloud.run.v2.ServicesSettings;
import com.google.cloud.scheduler.v1.CloudSchedulerClient;
import com.google.cloud.scheduler.v1.CloudSchedulerSettings;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.CloudTasksSettings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

public final class TestFixtures {

    private static final String DEFAULT_ACCESS_TOKEN = "fake-token-floci-gcp";

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
     * CTF operator Bearer token. Prefers {@code FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN},
     * then {@code GOOGLE_OAUTH_ACCESS_TOKEN}, else the shared fake token.
     */
    public static String getAccessToken() {
        String root = System.getenv("FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN");
        if (root != null && !root.isBlank()) {
            return root;
        }
        String oauth = System.getenv("GOOGLE_OAUTH_ACCESS_TOKEN");
        if (oauth != null && !oauth.isBlank()) {
            return oauth;
        }
        return DEFAULT_ACCESS_TOKEN;
    }

    public static GoogleCredentials googleCredentials() {
        return GoogleCredentials.create(new AccessToken(
                getAccessToken(),
                new Date(System.currentTimeMillis() + 3_600_000L)));
    }

    public static FixedCredentialsProvider credentialsProvider() {
        return FixedCredentialsProvider.create(googleCredentials());
    }

    public static Map<String, String> authorizedHeaders() {
        return Map.of("Authorization", "Bearer " + getAccessToken());
    }

    public static HttpRequest.Builder authorize(HttpRequest.Builder builder) {
        return builder.header("Authorization", "Bearer " + getAccessToken());
    }

    /**
     * Creates a GCS Storage client.
     * The STORAGE_EMULATOR_HOST env var is auto-detected by the GCP SDK.
     * We also explicitly set the host and use the CTF operator Bearer token.
     */
    public static Storage storageClient() {
        return StorageOptions.newBuilder()
                .setHost(endpoint())
                .setProjectId(projectId())
                .setCredentials(googleCredentials())
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
                .setCredentials(googleCredentials())
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
                .setCredentials(googleCredentials())
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
                .setCredentialsProvider(credentialsProvider())
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
                .setCredentialsProvider(credentialsProvider())
                .build();

        return CloudTasksClient.create(settings);
    }

    public static ServicesClient cloudRunServicesClient() throws IOException {
        ServicesSettings settings = ServicesSettings.newHttpJsonBuilder()
                .setEndpoint(endpoint())
                .setCredentialsProvider(credentialsProvider())
                .build();
        return ServicesClient.create(settings);
    }

    /**
     * GKE (container.googleapis.com) endpoint. The Cloud Client SDK defaults to gRPC, which the
     * REST-only emulator cannot serve, so GKE tests use the HttpJson transport. The host's first
     * DNS label must be {@code container} so the emulator's ServiceRoutingFilter rewrites the
     * canonical {@code /v1/...} path onto the {@code /container} prefix (host-mode routing).
     * Defaults to {@code container.localhost} (resolves to 127.0.0.1 on the host); the Docker
     * compat run sets {@code GKE_EMULATOR_ENDPOINT=http://container.localhost.floci.io:4588}.
     */
    public static String gkeEndpoint() {
        return System.getenv().getOrDefault("GKE_EMULATOR_ENDPOINT", "http://container.localhost:4588");
    }

    public static com.google.cloud.container.v1.ClusterManagerClient gkeClient() throws IOException {
        com.google.cloud.container.v1.ClusterManagerSettings settings =
                com.google.cloud.container.v1.ClusterManagerSettings.newHttpJsonBuilder()
                        .setEndpoint(gkeEndpoint())
                        .setCredentialsProvider(credentialsProvider())
                        .build();
        return com.google.cloud.container.v1.ClusterManagerClient.create(settings);
    }

    public static RevisionsClient cloudRunRevisionsClient() throws IOException {
        RevisionsSettings settings = RevisionsSettings.newHttpJsonBuilder()
                .setEndpoint(endpoint())
                .setCredentialsProvider(credentialsProvider())
                .build();
        return RevisionsClient.create(settings);
    }

    public static FunctionServiceClient cloudFunctionsClient() throws IOException {
        FunctionServiceSettings settings = FunctionServiceSettings.newHttpJsonBuilder()
                .setEndpoint(endpoint())
                .setCredentialsProvider(credentialsProvider())
                .build();
        return FunctionServiceClient.create(settings);
    }

    /**
     * Creates a BigQuery client pointing at the emulator. The java-bigquery SDK has no
     * emulator env-var support; setHost() routes the Apiary client to the emulator, which
     * serves under /bigquery/v2/.
     */
    public static com.google.cloud.bigquery.BigQuery bigQueryClient() {
        return com.google.cloud.bigquery.BigQueryOptions.newBuilder()
                .setHost(endpoint())
                .setLocation("US")
                .setProjectId(projectId())
                .setCredentials(googleCredentials())
                .build()
                .getService();
    }

    public static SQLAdmin sqlAdminClient() {
        HttpRequestInitializer credentials = new HttpCredentialsAdapter(googleCredentials());
        return new SQLAdmin.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance(), credentials)
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
                .setCredentialsProvider(credentialsProvider())
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
                .setCredentialsProvider(credentialsProvider())
                .build();

        return KeyManagementServiceClient.create(settings);
    }

    /**
     * Creates a Cloud Monitoring client pointing at the emulator.
     */
    public static com.google.cloud.monitoring.v3.MetricServiceClient monitoringClient() throws IOException {
        URI uri = URI.create(endpoint());
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 4588;

        com.google.cloud.monitoring.v3.MetricServiceSettings settings = com.google.cloud.monitoring.v3.MetricServiceSettings.newBuilder()
                .setTransportChannelProvider(
                        InstantiatingGrpcChannelProvider.newBuilder()
                                .setEndpoint(host + ":" + port)
                                .setChannelConfigurator(builder -> builder.usePlaintext())
                                .build())
                .setCredentialsProvider(credentialsProvider())
                .build();

        return com.google.cloud.monitoring.v3.MetricServiceClient.create(settings);
    }

    /**
     * Creates a Cloud Scheduler client pointing at the emulator.
     * No standard emulator env var exists; configure explicitly via plaintext gRPC channel.
     */
    public static CloudSchedulerClient cloudSchedulerClient() throws IOException {
        URI uri = URI.create(endpoint());
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 4588;

        CloudSchedulerSettings settings = CloudSchedulerSettings.newBuilder()
                .setTransportChannelProvider(
                        InstantiatingGrpcChannelProvider.newBuilder()
                                .setEndpoint(host + ":" + port)
                                .setChannelConfigurator(builder -> builder.usePlaintext())
                                .build())
                .setCredentialsProvider(credentialsProvider())
                .build();

        return CloudSchedulerClient.create(settings);
    }

    public static com.google.cloud.eventarc.v1.EventarcClient eventarcClient() throws IOException {
        com.google.cloud.eventarc.v1.EventarcSettings settings =
                com.google.cloud.eventarc.v1.EventarcSettings.newHttpJsonBuilder()
                        .setEndpoint(endpoint())
                        .setCredentialsProvider(credentialsProvider())
                        .build();
        return com.google.cloud.eventarc.v1.EventarcClient.create(settings);
    }

    public static com.google.api.serviceusage.v1.ServiceUsageClient serviceUsageClient() throws IOException {
        com.google.api.serviceusage.v1.ServiceUsageSettings settings =
                com.google.api.serviceusage.v1.ServiceUsageSettings.newHttpJsonBuilder()
                        .setEndpoint(endpoint())
                        .setCredentialsProvider(credentialsProvider())
                        .build();
        return com.google.api.serviceusage.v1.ServiceUsageClient.create(settings);
    }
}

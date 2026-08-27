package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.auth.Credentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.functions.v2.FunctionServiceClient;
import com.google.cloud.functions.v2.FunctionServiceSettings;
import com.google.cloud.iam.credentials.v1.IamCredentialsClient;
import com.google.cloud.iam.credentials.v1.IamCredentialsSettings;
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
import com.google.cloud.scheduler.v1.CloudSchedulerClient;
import com.google.cloud.scheduler.v1.CloudSchedulerSettings;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.http.HttpTransportOptions;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

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

    // ── TLS ──────────────────────────────────────────────────────────────────────
    // floci-gcp serves HTTP and HTTPS on the same port, so the TLS endpoint is the
    // plain endpoint with the scheme swapped. Trust material is bootstrapped at
    // runtime from GET /_floci-gcp/tls-cert (served over plain HTTP), so nothing has
    // to be bundled with the suite. Everything below is inert when TLS is disabled:
    // the endpoint 404s and tlsAvailable() reports false.

    private static final Object TLS_LOCK = new Object();
    private static boolean tlsProbed;
    private static String cachedCaCertPem;
    private static Path cachedCaCertFile;

    /** The emulator's HTTPS endpoint — same host and port as {@link #endpoint()}. */
    public static String tlsEndpoint() {
        return endpoint().replaceFirst("^http://", "https://");
    }

    /**
     * The emulator's TLS certificate in PEM form, or {@code null} when TLS is disabled.
     * Fetched once over plain HTTP and cached.
     */
    public static String caCertPem() {
        synchronized (TLS_LOCK) {
            if (!tlsProbed) {
                tlsProbed = true;
                cachedCaCertPem = fetchCaCertPem();
            }
            return cachedCaCertPem;
        }
    }

    /** Whether the emulator under test is serving TLS. Drives the TLS tests' skip guard. */
    public static boolean tlsAvailable() {
        return caCertPem() != null;
    }

    private static String fetchCaCertPem() {
        try {
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(endpoint() + "/_floci-gcp/tls-cert"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            return resp.body();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static X509Certificate caCertificate() throws GeneralSecurityException {
        String pem = caCertPem();
        if (pem == null) {
            throw new IllegalStateException(
                    "TLS is not enabled on " + endpoint() + " — guard with assumeTrue(tlsAvailable())");
        }
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    }

    /** A trust store containing only the emulator's certificate, for HTTP-transport clients. */
    public static KeyStore caTrustStore() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("floci-gcp", caCertificate());
        return ks;
    }

    /**
     * The emulator's certificate as a file, for gRPC's {@code TlsChannelCredentials}.
     * Written once to a temp file that is removed on JVM exit.
     */
    public static File caCertFile() throws IOException {
        synchronized (TLS_LOCK) {
            if (cachedCaCertFile == null) {
                String pem = caCertPem();
                if (pem == null) {
                    throw new IllegalStateException(
                            "TLS is not enabled on " + endpoint() + " — guard with assumeTrue(tlsAvailable())");
                }
                Path tmp = Files.createTempFile("floci-gcp-ca-", ".crt");
                tmp.toFile().deleteOnExit();
                Files.writeString(tmp, pem);
                cachedCaCertFile = tmp;
            }
            return cachedCaCertFile.toFile();
        }
    }

    /** An {@link SSLContext} trusting the emulator, for raw {@link HttpClient} calls. */
    public static SSLContext tlsSslContext() throws GeneralSecurityException, IOException {
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(caTrustStore());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    /** An {@link HttpClient} that verifies the emulator's certificate rather than skipping checks. */
    public static HttpClient tlsHttpClient() throws GeneralSecurityException, IOException {
        return HttpClient.newBuilder().sslContext(tlsSslContext()).build();
    }

    /**
     * A GCS client speaking HTTPS to the emulator. Trust is scoped to this client's
     * transport rather than installed globally via {@code javax.net.ssl.*}.
     */
    public static Storage tlsStorageClient() throws GeneralSecurityException, IOException {
        KeyStore trustStore = caTrustStore();
        HttpTransportOptions transportOptions = HttpTransportOptions.newBuilder()
                .setHttpTransportFactory(() -> {
                    try {
                        return new NetHttpTransport.Builder().trustCertificates(trustStore).build();
                    } catch (GeneralSecurityException e) {
                        throw new IllegalStateException("Failed to build a TLS-trusting HTTP transport", e);
                    }
                })
                .build();

        return StorageOptions.newBuilder()
                .setHost(tlsEndpoint())
                .setProjectId(projectId())
                .setCredentials(NoCredentials.getInstance())
                .setTransportOptions(transportOptions)
                .build()
                .getService();
    }

    /**
     * A gRPC channel to the emulator secured with TLS, trusting only its certificate.
     * Callers own the channel and must shut it down.
     */
    public static ManagedChannel tlsGrpcChannel() throws IOException {
        return Grpc.newChannelBuilder(grpcTarget(),
                        TlsChannelCredentials.newBuilder().trustManager(caCertFile()).build())
                .build();
    }

    /** Wraps a channel so gax-based clients can use it. */
    public static TransportChannelProvider channelProviderFor(ManagedChannel channel) {
        return FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel));
    }

    /** The emulator's {@code host:port} gRPC target, derived from {@link #endpoint()}. */
    public static String grpcTarget() {
        URI uri = URI.create(endpoint());
        int port = uri.getPort() > 0 ? uri.getPort() : 4588;
        return uri.getHost() + ":" + port;
    }

    public static ServiceAccountCredentials serviceAccountCredentials() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        return ServiceAccountCredentials.newBuilder()
                .setClientId("123456789")
                .setClientEmail("storage-test@test-project.iam.gserviceaccount.com")
                .setPrivateKey(keyPair.getPrivate())
                .setPrivateKeyId("test-key")
                .setScopes(List.of("https://www.googleapis.com/auth/cloud-platform"))
                .setTokenServerUri(URI.create(endpoint() + "/token"))
                .build();
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

	public static Storage storageClient(Credentials credentials) {
		return StorageOptions.newBuilder()
				.setHost(endpoint())
				.setProjectId(projectId())
				.setCredentials(credentials)
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
                        .setCredentialsProvider(NoCredentialsProvider.create())
                        .build();
        return com.google.cloud.container.v1.ClusterManagerClient.create(settings);
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
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
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

    public static IamCredentialsClient iamCredentialsClient() throws IOException {
        IamCredentialsSettings settings = IamCredentialsSettings.newHttpJsonBuilder()
                .setEndpoint(endpoint())
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build();
        return IamCredentialsClient.create(settings);
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
                .setCredentialsProvider(NoCredentialsProvider.create())
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
                .setCredentialsProvider(NoCredentialsProvider.create())
                .build();

        return CloudSchedulerClient.create(settings);
    }

    public static com.google.cloud.eventarc.v1.EventarcClient eventarcClient() throws IOException {
        com.google.cloud.eventarc.v1.EventarcSettings settings =
                com.google.cloud.eventarc.v1.EventarcSettings.newHttpJsonBuilder()
                        .setEndpoint(endpoint())
                        .setCredentialsProvider(NoCredentialsProvider.create())
                        .build();
        return com.google.cloud.eventarc.v1.EventarcClient.create(settings);
    }

    public static com.google.api.serviceusage.v1.ServiceUsageClient serviceUsageClient() throws IOException {
        com.google.api.serviceusage.v1.ServiceUsageSettings settings =
                com.google.api.serviceusage.v1.ServiceUsageSettings.newHttpJsonBuilder()
                        .setEndpoint(endpoint())
                        .setCredentialsProvider(NoCredentialsProvider.create())
                        .build();
        return com.google.api.serviceusage.v1.ServiceUsageClient.create(settings);
    }
}

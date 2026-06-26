package io.floci.gcp.services.gke;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import io.floci.gcp.core.common.docker.PortAllocator;
import io.floci.gcp.services.gke.model.StoredCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages the Docker lifecycle of {@code rancher/k3s} containers for real-mode GKE clusters.
 * Mirrors the sibling AWS emulator's EKS cluster manager: it talks to the Docker daemon via
 * the docker-java API (no {@code k3d}/{@code docker} CLI in the image) and spawns k3s as a
 * sibling container. Not used when {@code floci-gcp.services.gke.mock=true}.
 */
@ApplicationScoped
public class GkeClusterManager {

    private static final Logger LOG = Logger.getLogger(GkeClusterManager.class);
    private static final int K3S_API_SERVER_PORT = 6443;
    private static final String ENDPOINT_MODE_NETWORK = "network";

    private static final String WEBHOOK_CONFIG_DIR = "/etc";
    private static final String WEBHOOK_CONFIG_FILE = "gke-token-webhook.yaml";
    private static final String WEBHOOK_CONFIG_PATH = WEBHOOK_CONFIG_DIR + "/" + WEBHOOK_CONFIG_FILE;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final DockerHostResolver dockerHostResolver;
    private final EmulatorConfig config;

    @Inject
    public GkeClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             PortAllocator portAllocator,
                             DockerHostResolver dockerHostResolver,
                             EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.dockerHostResolver = dockerHostResolver;
        this.config = config;
    }

    /**
     * Starts a k3s container for the given cluster, setting its container id, host port and
     * endpoints. The cluster status stays {@code PROVISIONING} until {@link #isReady} returns
     * true and {@link #finalizeCluster} extracts the CA.
     */
    public void startCluster(StoredCluster cluster) {
        String image = config.services().gke().defaultImage();
        String containerName = "floci-gke-" + cluster.getName();

        LOG.infov("Starting k3s container for GKE cluster {0} using image {1}",
                cluster.getName(), image);

        int hostPort = portAllocator.allocate(
                config.services().gke().apiServerBasePort(),
                config.services().gke().apiServerMaxPort());
        cluster.setHostPort(hostPort);

        lifecycleManager.removeIfExists(containerName);

        // A named Docker volume (not a host bind mount) for the k3s data dir: bind mounts to a
        // macOS host path make kine's unix socket chmod fail (EINVAL); named volumes live in the
        // Docker VM's Linux filesystem. k3s v1.34+ manages embedded SQLite (kine) internally.
        String volumeName = "floci-gke-" + cluster.getName();

        List<String> serverArgs = new ArrayList<>(List.of(
                "server", "--disable=traefik", "--tls-san=localhost"));

        // Wire a token-authentication webhook so the bearer token from gke-gcloud-auth-plugin
        // (i.e. `gcloud container clusters get-credentials` + kubectl) is validated by floci-gcp
        // and mapped to cluster-admin — the native GKE auth flow. The kubeconfig is streamed into
        // the container via the Docker API (not bind-mounted) so it works in Docker-in-Docker.
        String webhookLocalFile = writeWebhookKubeconfig(cluster.getName());
        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv("K3S_KUBECONFIG_MODE", "644")
                .withPortBinding(K3S_API_SERVER_PORT, hostPort)
                .withNamedVolume(volumeName, "/var/lib/rancher/k3s")
                .withDockerNetwork(config.services().gke().dockerNetwork())
                .withPrivileged(true)
                .withLogRotation();
        if (webhookLocalFile != null) {
            specBuilder.withHostDockerInternalOnLinux();
            serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-config-file=" + WEBHOOK_CONFIG_PATH);
            serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-version=v1");
            serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-cache-ttl=30s");
        }
        ContainerSpec spec = specBuilder.withCmd(serverArgs).build();

        // create -> inject webhook kubeconfig -> start, so the file exists before the API server boots.
        String containerId = lifecycleManager.create(spec);
        cluster.setContainerId(containerId);
        if (webhookLocalFile != null) {
            copyWebhookIntoContainer(containerId, webhookLocalFile, cluster.getName());
        }
        ContainerInfo info = lifecycleManager.startCreated(containerId, spec);

        cluster.setEndpoint(resolvePublicEndpoint(
                containerDetector.isRunningInContainer(),
                config.services().gke().endpointMode(),
                containerName, hostPort));

        if (containerDetector.isRunningInContainer()) {
            ContainerLifecycleManager.EndpointInfo ep = info.getEndpoint(K3S_API_SERVER_PORT);
            cluster.setInternalEndpoint(ep != null
                    ? "https://" + ep.host() + ":" + ep.port()
                    : "https://localhost:" + hostPort);
        } else {
            cluster.setInternalEndpoint("https://localhost:" + hostPort);
        }

        LOG.infov("k3s container {0} started for cluster {1} on port {2}",
                containerId, cluster.getName(), String.valueOf(hostPort));
    }

    /** Polls the k3s API server's /livez endpoint to check readiness. */
    public boolean isReady(StoredCluster cluster) {
        String endpoint = cluster.getInternalEndpoint() != null
                ? cluster.getInternalEndpoint()
                : cluster.getEndpoint();
        if (endpoint == null || cluster.getContainerId() == null) {
            return false;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint + "/livez").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            if (conn instanceof javax.net.ssl.HttpsURLConnection https) {
                disableSslVerification(https);
            }
            int code = conn.getResponseCode();
            return code == 200 || code == 401 || code == 403;
        } catch (Exception e) {
            return false;
        }
    }

    /** Extracts the kubeconfig CA from the running k3s container and sets it on the cluster. */
    public void finalizeCluster(StoredCluster cluster) {
        String containerId = cluster.getContainerId();
        if (containerId == null) {
            return;
        }
        try {
            String kubeconfigYaml = execInContainer(containerId,
                    new String[]{"cat", "/etc/rancher/k3s/k3s.yaml"});
            String caData = extractYamlField(kubeconfigYaml, "certificate-authority-data");
            if (caData != null) {
                cluster.setCaCertificate(caData.trim());
            }
            LOG.infov("Finalized GKE cluster {0} with CA data extracted", cluster.getName());
        } catch (Exception e) {
            LOG.warnv("Could not extract kubeconfig for cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    /** Stops and removes the k3s container and its data volume. */
    public void stopCluster(StoredCluster cluster) {
        if (cluster.getContainerId() == null) {
            return;
        }
        if (config.services().gke().keepRunningOnShutdown()) {
            LOG.infov("Leaving k3s container for cluster {0} running", cluster.getName());
            return;
        }
        lifecycleManager.stopAndRemove(cluster.getContainerId(), null);
        lifecycleManager.removeVolume("floci-gke-" + cluster.getName());
        LOG.infov("Stopped k3s container for cluster {0}", cluster.getName());
    }

    /** The raw k3s kubeconfig (server URL unmodified), for the internal kubeconfig endpoint. */
    public String kubeConfig(StoredCluster cluster) {
        if (cluster.getContainerId() == null) {
            return "";
        }
        try {
            return execInContainer(cluster.getContainerId(),
                    new String[]{"cat", "/etc/rancher/k3s/k3s.yaml"});
        } catch (Exception e) {
            LOG.warnv("Could not read kubeconfig for cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
            return "";
        }
    }

    /**
     * The cluster's public {@code endpoint} — a bare {@code host:port} with no scheme, matching real
     * GKE (whose endpoint is a bare host). {@code gcloud container clusters get-credentials} prepends
     * {@code https://}, so a scheme here would produce a malformed {@code https://https://...} server.
     */
    static String resolvePublicEndpoint(boolean inContainer, String endpointMode,
                                        String containerName, int hostPort) {
        if (inContainer && ENDPOINT_MODE_NETWORK.equalsIgnoreCase(endpointMode)) {
            return containerName + ":" + K3S_API_SERVER_PORT;
        }
        return "localhost:" + hostPort;
    }

    /** floci-gcp's GKE token-webhook URL, reachable from inside the k3s container. */
    String webhookUrl() {
        return "http://" + dockerHostResolver.resolve() + ":" + config.port() + "/_floci-gcp/gke/token-webhook";
    }

    /**
     * Writes the token-webhook kubeconfig (pointing the k3s API server's webhook at floci-gcp) to a
     * temp file and returns its path, or {@code null} if it could not be written (the caller then
     * skips the webhook so cluster creation still succeeds).
     */
    private String writeWebhookKubeconfig(String clusterName) {
        try {
            Path dir = Files.createTempDirectory("floci-gke-webhook-");
            Path file = dir.resolve(WEBHOOK_CONFIG_FILE);
            Files.writeString(file, buildWebhookKubeconfig(webhookUrl()));
            return file.toString();
        } catch (IOException e) {
            LOG.warnv("GKE token-webhook disabled for cluster {0}: could not write kubeconfig: {1}",
                    clusterName, e.getMessage());
            return null;
        }
    }

    /** Streams the webhook kubeconfig into the (created, not-yet-started) k3s container at /etc. */
    private void copyWebhookIntoContainer(String containerId, String localFile, String clusterName) {
        try {
            lifecycleManager.getDockerClient()
                    .copyArchiveToContainerCmd(containerId)
                    .withHostResource(localFile)
                    .withRemotePath(WEBHOOK_CONFIG_DIR)
                    .exec();
        } catch (Exception e) {
            LOG.warnv("GKE token-webhook may not authenticate for cluster {0}: could not copy kubeconfig "
                    + "into the k3s container: {1}", clusterName, e.getMessage());
        }
    }

    static String buildWebhookKubeconfig(String serverUrl) {
        return """
                apiVersion: v1
                kind: Config
                clusters:
                - name: floci-token-webhook
                  cluster:
                    server: %s
                users:
                - name: floci-token-webhook
                contexts:
                - name: floci-token-webhook
                  context:
                    cluster: floci-token-webhook
                    user: floci-token-webhook
                current-context: floci-token-webhook
                """.formatted(serverUrl);
    }

    private String execInContainer(String containerId, String[] cmd) throws Exception {
        var dockerClient = lifecycleManager.getDockerClient();
        ExecCreateCmdResponse exec = dockerClient
                .execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        StringBuilder output = new StringBuilder();
        boolean completed = dockerClient.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                })
                .awaitCompletion(10, TimeUnit.SECONDS);

        if (!completed) {
            throw new RuntimeException("exec timed out in container " + containerId);
        }
        return output.toString();
    }

    private String extractYamlField(String yaml, String fieldName) {
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(fieldName + ":")) {
                return trimmed.substring(fieldName.length() + 1).trim();
            }
        }
        return null;
    }

    @SuppressWarnings("java:S4830")
    private void disableSslVerification(javax.net.ssl.HttpsURLConnection conn) {
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
                    }

                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
                    }
                }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier((h, s) -> true);
        } catch (Exception e) {
            LOG.debugv("Could not disable SSL verification: {0}", e.getMessage());
        }
    }
}

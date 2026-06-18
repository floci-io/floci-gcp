package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.services.kafka.model.StoredCluster;
import io.floci.gcp.services.kafka.model.StoredConsumerGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RedpandaManager {

    private static final Logger LOG = Logger.getLogger(RedpandaManager.class);
    private static final int KAFKA_PORT = 9092;
    private static final int ADMIN_PORT = 9644;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;

    @Inject
    public RedpandaManager(ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager,
                           ContainerDetector containerDetector,
                           EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.config = config;
    }

    public void startContainer(StoredCluster cluster) {
        String image = config.services().kafka().defaultImage();
        String containerName = containerName(cluster);
        LOG.infov("Starting Redpanda for Managed Kafka cluster {0} using image {1}", cluster.getName(), image);

        lifecycleManager.removeIfExists(containerName);

        List<String> cmd = new ArrayList<>(List.of(
                "redpanda", "start", "--overprovisioned", "--smp", "1",
                "--memory", "512M", "--reserve-memory", "0M"));

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withLogRotation()
                .withDockerNetwork(config.services().kafka().dockerNetwork());

        if (!containerDetector.isRunningInContainer()) {
            specBuilder.withDynamicPort(KAFKA_PORT).withDynamicPort(ADMIN_PORT);
        } else {
            specBuilder.withExposedPort(KAFKA_PORT).withExposedPort(ADMIN_PORT);
        }

        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            ContainerStorageHelper.applyStorage(specBuilder, lifecycleManager,
                    "kafka", cluster.getVolumeId(), clusterId(cluster.getName()),
                    "/var/lib/redpanda/data");
        } else {
            String hostDataPath = Path.of(config.storage().hostPersistentPath(), "kafka", clusterId(cluster.getName()))
                    .toAbsolutePath().toString();
            ContainerStorageHelper.ensureHostDir(hostDataPath);
            specBuilder.withBind(hostDataPath, "/var/lib/redpanda/data");
        }

        specBuilder.withCmd(cmd);
        ContainerSpec spec = specBuilder.build();

        ContainerInfo info = lifecycleManager.createAndStart(spec);
        cluster.setContainerId(info.containerId());

        EndpointInfo kafkaEndpoint = info.getEndpoint(KAFKA_PORT);
        cluster.setBootstrapAddress(kafkaEndpoint.host() + ":" + kafkaEndpoint.port());
        LOG.infov("Redpanda started. Bootstrap: {0}", cluster.getBootstrapAddress());
    }

    public boolean isReady(StoredCluster cluster) {
        if (cluster.getContainerId() == null) {
            return false;
        }
        String base = getAdminBaseUrl(cluster);
        if (base == null) {
            return false;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(base + "/v1/node_config").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public List<StoredConsumerGroup> listConsumerGroups(StoredCluster cluster, String clusterResourceName) {
        String base = getAdminBaseUrl(cluster);
        if (base == null) {
            return List.of();
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(base + "/v1/groups").toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            if (conn.getResponseCode() != 200) {
                return List.of();
            }
            JsonNode groups = MAPPER.readTree(conn.getInputStream());
            List<StoredConsumerGroup> result = new ArrayList<>();
            for (JsonNode g : groups) {
                String groupId = g.path("group_id").asText();
                if (!groupId.isEmpty()) {
                    result.add(new StoredConsumerGroup(clusterResourceName + "/consumerGroups/" + groupId));
                }
            }
            return result;
        } catch (Exception e) {
            LOG.warnv("Failed to list consumer groups from Redpanda: {0}", e.getMessage());
            return List.of();
        }
    }

    public Optional<StoredConsumerGroup> getConsumerGroup(StoredCluster cluster, String clusterResourceName,
                                                          String groupId) {
        String base = getAdminBaseUrl(cluster);
        if (base == null) {
            return Optional.empty();
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(base + "/v1/groups/" + groupId)
                    .toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int status = conn.getResponseCode();
            if (status == 404) {
                return Optional.empty();
            }
            if (status != 200) {
                return Optional.empty();
            }
            return Optional.of(new StoredConsumerGroup(clusterResourceName + "/consumerGroups/" + groupId));
        } catch (Exception e) {
            LOG.warnv("Failed to get consumer group {0} from Redpanda: {1}", groupId, e.getMessage());
            return Optional.empty();
        }
    }

    public void deleteConsumerGroup(StoredCluster cluster, String groupId) {
        String base = getAdminBaseUrl(cluster);
        if (base == null) {
            return;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(base + "/v1/groups/" + groupId)
                    .toURL().openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.getResponseCode();
        } catch (Exception e) {
            LOG.warnv("Failed to delete consumer group {0} from Redpanda: {1}", groupId, e.getMessage());
        }
    }

    private String getAdminBaseUrl(StoredCluster cluster) {
        if (!containerDetector.isRunningInContainer()) {
            var inspect = lifecycleManager.runDockerApi("inspect Redpanda container " + cluster.getContainerId(),
                    () -> lifecycleManager.getDockerClient()
                            .inspectContainerCmd(cluster.getContainerId())
                            .exec());
            var bindings = inspect.getNetworkSettings().getPorts().getBindings();
            var binding = bindings.get(com.github.dockerjava.api.model.ExposedPort.tcp(ADMIN_PORT));
            if (binding != null && binding.length > 0) {
                return "http://localhost:" + binding[0].getHostPortSpec();
            }
            return null;
        } else {
            String host = cluster.getBootstrapAddress().split(":")[0];
            return "http://" + host + ":" + ADMIN_PORT;
        }
    }

    public void stopContainer(StoredCluster cluster) {
        if (cluster.getContainerId() == null) {
            return;
        }
        lifecycleManager.stopAndRemove(cluster.getContainerId(), null);
        LOG.infov("Redpanda container for cluster {0} stopped", cluster.getName());
    }

    public void removeClusterStorage(StoredCluster cluster) {
        ContainerStorageHelper.removeStorage(config, lifecycleManager,
                "kafka", cluster.getVolumeId(), clusterId(cluster.getName()));
    }

    private static String containerName(StoredCluster cluster) {
        return "floci-kafka-" + clusterId(cluster.getName());
    }

    private static String clusterId(String name) {
        int idx = name.lastIndexOf('/');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }
}

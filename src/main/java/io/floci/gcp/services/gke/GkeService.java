package io.floci.gcp.services.gke;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.gke.model.StoredCluster;
import io.floci.gcp.services.gke.operations.GkeOperationService;
import io.floci.gcp.services.gke.operations.OperationType;
import io.floci.gcp.services.gke.operations.StoredOperation;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@ApplicationScoped
public class GkeService {

    private static final Logger LOG = Logger.getLogger(GkeService.class);

    private static final String DEFAULT_MASTER_VERSION = "1.30.5-gke.1014001";
    private static final Pattern VALID_CLUSTER_NAME =
            Pattern.compile("^[a-z](?:[a-z0-9-]{0,38}[a-z0-9])?$");

    private final StorageBackend<String, StoredCluster> storage;
    private final EmulatorConfig config;
    private final GkeClusterManager clusterManager;
    private final GkeOperationService operationService;
    private final ServiceRegistry serviceRegistry;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gke-readiness-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public GkeService(StorageFactory storageFactory,
                      EmulatorConfig config,
                      GkeClusterManager clusterManager,
                      GkeOperationService operationService,
                      ServiceRegistry serviceRegistry) {
        this(storageFactory.createGlobal("gke", "gke-clusters.json",
                new TypeReference<Map<String, StoredCluster>>() {
                }), config, clusterManager, operationService, serviceRegistry);
    }

    GkeService(StorageBackend<String, StoredCluster> storage,
               EmulatorConfig config,
               GkeClusterManager clusterManager,
               GkeOperationService operationService,
               ServiceRegistry serviceRegistry) {
        this.storage = storage;
        this.config = config;
        this.clusterManager = clusterManager;
        this.operationService = operationService;
        this.serviceRegistry = serviceRegistry;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("gke")
                .enabled(config.services().gke().enabled())
                .storageKey("gke")
                .protocol(ServiceProtocol.REST)
                .hostToken("container")
                .pathPrefix("/container")
                .resourceClasses(KubernetesController.class)
                .build());
    }

    @PostConstruct
    public void init() {
        if (!mock()) {
            poller.scheduleAtFixedRate(this::pollReadiness, 2, 3, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!mock()) {
            for (StoredCluster cluster : storage.scan(k -> true)) {
                clusterManager.stopCluster(cluster);
            }
        }
    }

    public StoredOperation createCluster(String project, String location, Map<String, Object> clusterMap) {
        if (clusterMap == null) {
            throw GcpException.invalidArgument("Missing root 'cluster' object");
        }
        String name = (String) clusterMap.get("name");
        if (name == null || name.isBlank()) {
            throw GcpException.invalidArgument("Cluster name is required");
        }
        if (!VALID_CLUSTER_NAME.matcher(name).matches()) {
            throw GcpException.invalidArgument(
                    "Invalid cluster name: '" + name + "'. Must match " + VALID_CLUSTER_NAME.pattern());
        }
        String key = clusterKey(project, location, name);
        if (storage.get(key).isPresent()) {
            throw GcpException.alreadyExists("Already exists: cluster " + name);
        }

        StoredCluster cluster = new StoredCluster();
        cluster.setName(name);
        cluster.setProject(project);
        cluster.setLocation(location);
        cluster.setCreateTime(Instant.now().toString());
        cluster.setCurrentMasterVersion(stringField(clusterMap, "initialClusterVersion", DEFAULT_MASTER_VERSION));
        cluster.setNetwork(stringField(clusterMap, "network", "default"));
        cluster.setSubnetwork(stringField(clusterMap, "subnetwork", "default"));
        cluster.setResourceLabels(labels(clusterMap));
        cluster.setNodePools(List.of(Map.of("name", "default-pool", "status", "RUNNING")));
        cluster.setCaCertificate("");
        cluster.setSelfLink(selfLink(project, location, name));

        if (mock()) {
            cluster.setStatus("RUNNING");
            cluster.setEndpoint("127.0.0.1");
        } else {
            cluster.setStatus("PROVISIONING");
            try {
                clusterManager.startCluster(cluster);
            } catch (Exception e) {
                LOG.errorv("Failed to start k3s container for cluster {0}: {1}", name, e.getMessage());
                cluster.setStatus("ERROR");
            }
        }

        storage.put(key, cluster);
        return operationService.createOperation(project, location, name, OperationType.CREATE_CLUSTER);
    }

    public StoredCluster getCluster(String project, String location, String clusterId) {
        return storage.get(clusterKey(project, location, clusterId))
                .orElseThrow(() -> GcpException.notFound("Not found: cluster " + clusterId));
    }

    public List<StoredCluster> listClusters(String project, String location) {
        return storage.scan(k -> true).stream()
                .filter(c -> project.equals(c.getProject()) && location.equals(c.getLocation()))
                .toList();
    }

    public StoredOperation deleteCluster(String project, String location, String clusterId) {
        String key = clusterKey(project, location, clusterId);
        StoredCluster cluster = storage.get(key)
                .orElseThrow(() -> GcpException.notFound("Not found: cluster " + clusterId));
        if (!mock()) {
            clusterManager.stopCluster(cluster);
        }
        storage.delete(key);
        return operationService.createOperation(project, location, clusterId, OperationType.DELETE_CLUSTER);
    }

    public List<StoredOperation> listOperations(String project, String location) {
        return operationService.listOperations(project, location);
    }

    public StoredOperation getOperation(String operationId) {
        return operationService.getOperation(operationId);
    }

    public String kubeConfig(String project, String location, String clusterId) {
        StoredCluster cluster = getCluster(project, location, clusterId);
        return mock() ? "" : clusterManager.kubeConfig(cluster);
    }

    private void pollReadiness() {
        try {
            for (StoredCluster cluster : storage.scan(k -> true)) {
                if ("PROVISIONING".equals(cluster.getStatus()) && clusterManager.isReady(cluster)) {
                    clusterManager.finalizeCluster(cluster);
                    cluster.setStatus("RUNNING");
                    storage.put(clusterKey(cluster.getProject(), cluster.getLocation(), cluster.getName()), cluster);
                    LOG.infov("GKE cluster {0} is now RUNNING", cluster.getName());
                }
            }
        } catch (Exception e) {
            LOG.error("Error in GKE readiness poller", e);
        }
    }

    private boolean mock() {
        return config.services().gke().mock();
    }

    private static String clusterKey(String project, String location, String name) {
        return "projects/" + project + "/locations/" + location + "/clusters/" + name;
    }

    private String selfLink(String project, String location, String name) {
        return config.baseUrl() + "/container/v1/" + clusterKey(project, location, name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> labels(Map<String, Object> clusterMap) {
        Object labels = clusterMap.get("resourceLabels");
        if (labels instanceof Map<?, ?> m) {
            Map<String, String> result = new HashMap<>();
            m.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
            return result;
        }
        return Map.of();
    }

    private static String stringField(Map<String, Object> map, String field, String fallback) {
        Object v = map.get(field);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }
}

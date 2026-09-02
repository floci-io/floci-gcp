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
import io.floci.gcp.services.gke.model.StoredNodePool;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@ApplicationScoped
public class GkeService {

    private static final Logger LOG = Logger.getLogger(GkeService.class);

    private static final String DEFAULT_MASTER_VERSION = "1.30.5-gke.1014001";
    private static final String DEFAULT_NODE_POOL_NAME = "default-pool";
    private static final Pattern VALID_CLUSTER_NAME =
            Pattern.compile("^[a-z](?:[a-z0-9-]{0,38}[a-z0-9])?$");
    private static final Pattern VALID_NODE_POOL_NAME =
            Pattern.compile("^[a-z](?:[a-z0-9-]{0,38}[a-z0-9])?$");

    /** Cluster-level fields with dedicated typed storage — everything else in a create/update
     * body is stored verbatim under {@link StoredCluster#getExtraConfig()}. */
    private static final List<String> TYPED_CLUSTER_FIELDS = List.of(
            "name", "description", "initialClusterVersion", "network", "subnetwork",
            "clusterIpv4Cidr", "locations", "resourceLabels", "loggingService", "monitoringService",
            "nodePools", "initialNodeCount", "nodeConfig");

    private final StorageBackend<String, StoredCluster> clusterStore;
    private final StorageBackend<String, StoredNodePool> nodePoolStore;
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
                        }),
                storageFactory.createGlobal("gke", "gke-node-pools.json",
                        new TypeReference<Map<String, StoredNodePool>>() {
                        }),
                config, clusterManager, operationService, serviceRegistry);
    }

    GkeService(StorageBackend<String, StoredCluster> clusterStore,
               StorageBackend<String, StoredNodePool> nodePoolStore,
               EmulatorConfig config,
               GkeClusterManager clusterManager,
               GkeOperationService operationService,
               ServiceRegistry serviceRegistry) {
        this.clusterStore = clusterStore;
        this.nodePoolStore = nodePoolStore;
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
                .resourceClasses(KubernetesController.class, KubernetesProjectController.class)
                .build());
    }

    @PostConstruct
    public void init() {
        migrateEmbeddedNodePools();
        if (!mock()) {
            poller.scheduleAtFixedRate(this::pollReadiness, 2, 3, TimeUnit.SECONDS);
        }
    }

    /** Clusters persisted by a floci-gcp version before node pools had their own store embedded
     * their pools directly on the cluster record (just {@code name}/{@code status}, nothing
     * else). Without this, those pools would simply vanish after an upgrade — the node pool
     * store starts empty and nothing would ever populate it for a pre-existing cluster. Backfills
     * the fields the embedded shape never had — project/location/clusterId/selfLink/etag/status,
     * plus version/locations/initialNodeCount derived from the owning cluster — moves each pool
     * into the node pool store, then clears the embedded list so the cluster records that it has
     * been migrated. Idempotent and resumable: pools already present in the store are skipped
     * individually, so an interrupted run finishes on the next startup, and a pool deleted after
     * migration is not resurrected. */
    private void migrateEmbeddedNodePools() {
        for (StoredCluster cluster : clusterStore.scan(k -> true)) {
            List<StoredNodePool> embedded = cluster.getNodePools();
            if (embedded == null || embedded.isEmpty()) {
                continue;
            }
            int migrated = 0;
            for (StoredNodePool pool : embedded) {
                if (pool.getName() == null || pool.getName().isBlank()) {
                    continue;
                }
                String poolKey = nodePoolKey(
                        cluster.getProject(), cluster.getLocation(), cluster.getName(), pool.getName());
                if (nodePoolStore.get(poolKey).isPresent()) {
                    continue;
                }
                pool.setProject(cluster.getProject());
                pool.setLocation(cluster.getLocation());
                pool.setClusterId(cluster.getName());
                if (pool.getSelfLink() == null) {
                    pool.setSelfLink(nodePoolSelfLink(
                            cluster.getProject(), cluster.getLocation(), cluster.getName(), pool.getName()));
                }
                if (pool.getEtag() == null) {
                    pool.setEtag(newFingerprint());
                }
                if (pool.getStatus() == null) {
                    pool.setStatus("RUNNING");
                }
                if (pool.getInstanceGroupUrls() == null) {
                    pool.setInstanceGroupUrls(List.of());
                }
                if (pool.getConditions() == null) {
                    pool.setConditions(List.of());
                }
                // The embedded shape carried only name and status, so version, locations and
                // initialNodeCount arrive unset. Left alone they would surface through the
                // standalone node pool API as null, null and 0, which reads to a refreshing
                // client as real drift rather than as missing legacy data. Derive them from the
                // owning cluster, which is where a pool created today gets them from anyway.
                if (pool.getVersion() == null) {
                    pool.setVersion(cluster.getCurrentNodeVersion() != null
                            ? cluster.getCurrentNodeVersion() : DEFAULT_MASTER_VERSION);
                }
                if (pool.getLocations() == null || pool.getLocations().isEmpty()) {
                    pool.setLocations(cluster.getLocations() != null && !cluster.getLocations().isEmpty()
                            ? List.copyOf(cluster.getLocations())
                            : List.of(cluster.getLocation()));
                }
                if (pool.getInitialNodeCount() <= 0) {
                    pool.setInitialNodeCount(
                            cluster.getInitialNodeCount() > 0 ? cluster.getInitialNodeCount() : 3);
                }
                nodePoolStore.put(poolKey, pool);
                migrated++;
            }
            // Clearing the embedded list is what marks this cluster migrated, and it has to be
            // the marker rather than "the pool store has entries for this cluster": that signal
            // is erased by a later DeleteNodePool, which would let the stale embedded copy
            // resurrect a deleted pool on the next startup. Per-pool skipping above plus this
            // clear also make an interrupted migration resumable — whatever is still embedded is
            // exactly what has not been moved yet.
            cluster.setNodePools(null);
            clusterStore.put(
                    clusterKey(cluster.getProject(), cluster.getLocation(), cluster.getName()), cluster);
            if (migrated > 0) {
                LOG.infov("Migrated {0} embedded node pool(s) for cluster {1} to the node pool store",
                        migrated, cluster.getName());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!mock()) {
            for (StoredCluster cluster : clusterStore.scan(k -> true)) {
                clusterManager.stopCluster(cluster);
            }
        }
    }

    // ── Clusters ─────────────────────────────────────────────────────────────

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
        if (clusterStore.get(key).isPresent()) {
            throw GcpException.alreadyExists("Already exists: cluster " + name);
        }
        // Validate every explicit node pool spec up front — before the cluster or any pool is
        // persisted — so an invalid/duplicate name partway through the list can't leave a
        // half-created cluster behind that a retry then finds AlreadyExists against.
        validateNodePoolSpecs(clusterMap.get("nodePools"));

        StoredCluster cluster = new StoredCluster();
        cluster.setName(name);
        cluster.setProject(project);
        cluster.setLocation(location);
        cluster.setZone(location);
        cluster.setDescription(stringField(clusterMap, "description", null));
        cluster.setCreateTime(Instant.now().toString());
        cluster.setCurrentMasterVersion(stringField(clusterMap, "initialClusterVersion", DEFAULT_MASTER_VERSION));
        cluster.setCurrentNodeVersion(cluster.getCurrentMasterVersion());
        cluster.setInitialClusterVersion(cluster.getCurrentMasterVersion());
        Object initialNodeCount = clusterMap.get("initialNodeCount");
        cluster.setInitialNodeCount(initialNodeCount instanceof Number n ? n.intValue() : 3);
        cluster.setNetwork(stringField(clusterMap, "network", "default"));
        cluster.setSubnetwork(stringField(clusterMap, "subnetwork", "default"));
        cluster.setClusterIpv4Cidr(stringField(clusterMap, "clusterIpv4Cidr", "10.0.0.0/14"));
        cluster.setLocations(stringListField(clusterMap, "locations", List.of(location)));
        cluster.setLoggingService(stringField(clusterMap, "loggingService", "logging.googleapis.com/kubernetes"));
        cluster.setMonitoringService(
                stringField(clusterMap, "monitoringService", "monitoring.googleapis.com/kubernetes"));
        cluster.setResourceLabels(labels(clusterMap));
        cluster.setLabelFingerprint(newFingerprint());
        cluster.setCaCertificate("");
        cluster.setSelfLink(clusterSelfLink(project, location, name));
        cluster.setEtag(newFingerprint());
        cluster.setExtraConfig(extraFields(clusterMap, TYPED_CLUSTER_FIELDS));

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

        clusterStore.put(key, cluster);
        createInitialNodePools(project, location, name, clusterMap);

        return operationService.createOperation(project, location, name, OperationType.CREATE_CLUSTER);
    }

    /** Returns a detached copy carrying the cluster's node pools. The store hands back the live
     * record, so attaching pools to it directly would write a stale pool snapshot back to disk on
     * the next flush — which the startup migration could then replay, resurrecting deleted pools.
     * The read path has to stay side-effect free. */
    public StoredCluster getCluster(String project, String location, String clusterId) {
        StoredCluster stored = clusterStore.get(clusterKey(project, location, clusterId))
                .orElseThrow(() -> GcpException.notFound("Not found: cluster " + clusterId));
        return withNodePools(stored);
    }

    public List<StoredCluster> listClusters(String project, String location) {
        return clusterStore.scan(k -> true).stream()
                .filter(c -> project.equals(c.getProject()) && location.equals(c.getLocation()))
                .map(this::withNodePools)
                .toList();
    }

    private StoredCluster withNodePools(StoredCluster stored) {
        StoredCluster view = new StoredCluster(stored);
        view.setNodePools(listNodePools(stored.getProject(), stored.getLocation(), stored.getName()));
        return view;
    }

    public StoredOperation deleteCluster(String project, String location, String clusterId) {
        String key = clusterKey(project, location, clusterId);
        StoredCluster cluster = clusterStore.get(key)
                .orElseThrow(() -> GcpException.notFound("Not found: cluster " + clusterId));
        if (!mock()) {
            clusterManager.stopCluster(cluster);
        }
        for (StoredNodePool pool : listNodePools(project, location, clusterId)) {
            nodePoolStore.delete(nodePoolKey(project, location, clusterId, pool.getName()));
        }
        clusterStore.delete(key);
        return operationService.createOperation(project, location, clusterId, OperationType.DELETE_CLUSTER);
    }

    /** Generic {@code UpdateCluster} — {@code desired*} fields that back one of
     * {@link StoredCluster}'s own typed fields (locations, logging/monitoring service, node/master
     * version) are applied to that field directly; everything else merges into extraConfig.
     * Typed fields can't just flow through the generic extraConfig merge below: {@code
     * clusterToJson} always reads them from their typed getter, which would silently overwrite
     * whatever the merge wrote under the same JSON key, making the update a no-op that still
     * reports success. */
    public StoredOperation updateCluster(String project, String location, String clusterId,
                                         Map<String, Object> updateMap) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        if (updateMap != null) {
            String desiredNodeVersion = (String) updateMap.get("desiredNodeVersion");
            if (desiredNodeVersion != null) {
                cluster.setCurrentNodeVersion(desiredNodeVersion);
                // A cluster-wide node version update has to reach the pools as well. Each pool
                // carries its own `version`, and GetNodePool/ListNodePools read it from the pool
                // store, so updating only the cluster aggregate would report the new version on
                // the cluster while every pool still reported the old one.
                for (StoredNodePool pool : listNodePools(project, location, clusterId)) {
                    pool.setVersion(desiredNodeVersion);
                    pool.setEtag(newFingerprint());
                    nodePoolStore.put(nodePoolKey(project, location, clusterId, pool.getName()), pool);
                }
            }
            String desiredMasterVersion = (String) updateMap.get("desiredMasterVersion");
            if (desiredMasterVersion != null) {
                cluster.setCurrentMasterVersion(desiredMasterVersion);
            }
            if (updateMap.get("desiredLocations") != null) {
                cluster.setLocations(stringListField(updateMap, "desiredLocations", cluster.getLocations()));
            }
            String desiredLoggingService = (String) updateMap.get("desiredLoggingService");
            if (desiredLoggingService != null) {
                cluster.setLoggingService(desiredLoggingService);
            }
            String desiredMonitoringService = (String) updateMap.get("desiredMonitoringService");
            if (desiredMonitoringService != null) {
                cluster.setMonitoringService(desiredMonitoringService);
            }

            Map<String, Object> stripped = stripPrefix(updateMap, "desired");
            // Already applied above to their typed field — drop so they aren't also duplicated,
            // inertly but pointlessly, into extraConfig under the same key.
            stripped.remove("nodeVersion");
            stripped.remove("masterVersion");
            stripped.remove("locations");
            stripped.remove("loggingService");
            stripped.remove("monitoringService");
            mergeExtraConfig(cluster, stripped);
        }
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.UPDATE_CLUSTER);
    }

    public StoredOperation setLabels(String project, String location, String clusterId,
                                     Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        cluster.setResourceLabels(labels(body));
        cluster.setLabelFingerprint(newFingerprint());
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.SET_LABELS);
    }

    public StoredOperation setMasterAuth(String project, String location, String clusterId,
                                         Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        putExtraConfig(cluster, "masterAuth", body == null ? null : body.get("update"));
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.SET_MASTER_AUTH);
    }

    public StoredOperation setNetworkPolicy(String project, String location, String clusterId,
                                            Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        putExtraConfig(cluster, "networkPolicy", body == null ? null : body.get("networkPolicy"));
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.SET_NETWORK_POLICY);
    }

    public StoredOperation setAddonsConfig(String project, String location, String clusterId,
                                           Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        putExtraConfig(cluster, "addonsConfig", body == null ? null : body.get("addonsConfig"));
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.SET_ADDONS_CONFIG);
    }

    public StoredOperation setLoggingService(String project, String location, String clusterId,
                                             Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        cluster.setLoggingService(body == null ? null : (String) body.get("loggingService"));
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.SET_LOGGING_SERVICE);
    }

    public StoredOperation setMonitoringService(String project, String location, String clusterId,
                                                Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        cluster.setMonitoringService(body == null ? null : (String) body.get("monitoringService"));
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.SET_MONITORING_SERVICE);
    }

    public StoredOperation setLocations(String project, String location, String clusterId,
                                        Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        cluster.setLocations(stringListField(body == null ? Map.of() : body, "locations", cluster.getLocations()));
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.SET_LOCATIONS);
    }

    public StoredOperation setLegacyAbac(String project, String location, String clusterId,
                                        Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        putExtraConfig(cluster, "legacyAbac", Map.of("enabled", enabled));
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.SET_LEGACY_ABAC);
    }

    /** An empty {@code maintenancePolicy} in the request clears the existing policy, matching
     * real GKE's documented semantics for this RPC. */
    public StoredOperation setMaintenancePolicy(String project, String location, String clusterId,
                                                Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        Object policy = body == null ? null : body.get("maintenancePolicy");
        putExtraConfig(cluster, "maintenancePolicy", (policy instanceof Map<?, ?> m && !m.isEmpty()) ? policy : null);
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.SET_MAINTENANCE_POLICY);
    }

    /** floci-gcp does not serve a real dual-cert rotation window — there is no live client
     * traffic to migrate off the old certificate, so this only acknowledges the request
     * (bumping the fingerprint/etag) rather than performing an actual CA rotation. */
    public StoredOperation startIpRotation(String project, String location, String clusterId) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.START_IP_ROTATION);
    }

    public StoredOperation completeIpRotation(String project, String location, String clusterId) {
        requireCluster(project, location, clusterId);
        return operationService.createOperation(project, location, clusterId, OperationType.COMPLETE_IP_ROTATION);
    }

    /** {@code CompleteNodePoolUpgrade} returns {@code google.protobuf.Empty}, not an Operation
     * (cluster_service.proto#L382-L388) — unlike {@code RollbackNodePoolUpgrade}, which really
     * does return one. No real node-version upgrade is in flight in this emulator (node pools
     * don't run real node VMs), so this is a validated acknowledgment with no state to change
     * and no operation to report. */
    public void completeNodePoolUpgrade(String project, String location, String clusterId,
                                        String nodePoolId) {
        requireNodePool(project, location, clusterId, nodePoolId);
    }

    public StoredOperation rollbackNodePoolUpgrade(String project, String location, String clusterId,
                                                   String nodePoolId) {
        requireNodePool(project, location, clusterId, nodePoolId);
        return operationService.createNodePoolOperation(
                project, location, clusterId, nodePoolId, OperationType.ROLLBACK_NODE_POOL_UPGRADE);
    }

    /** {@code container.v1.ClusterManager.GetServerConfig} — the version/channel info gcloud
     * and some SDKs check before create/upgrade calls. Versions are pinned to this emulator's
     * single supported master/node version; there is no real multi-version fleet to report. */
    public Map<String, Object> getServerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("defaultClusterVersion", DEFAULT_MASTER_VERSION);
        config.put("validNodeVersions", List.of(DEFAULT_MASTER_VERSION));
        config.put("defaultImageType", "COS_CONTAINERD");
        config.put("validImageTypes", List.of("COS_CONTAINERD", "UBUNTU_CONTAINERD"));
        config.put("validMasterVersions", List.of(DEFAULT_MASTER_VERSION));
        config.put("channels", List.of(
                releaseChannelConfig("RAPID"),
                releaseChannelConfig("REGULAR"),
                releaseChannelConfig("STABLE")));
        return config;
    }

    private Map<String, Object> releaseChannelConfig(String channel) {
        Map<String, Object> config = new HashMap<>();
        config.put("channel", channel);
        config.put("defaultVersion", DEFAULT_MASTER_VERSION);
        config.put("validVersions", List.of(DEFAULT_MASTER_VERSION));
        config.put("upgradeTargetVersion", DEFAULT_MASTER_VERSION);
        return config;
    }

    /** {@code GetJSONWebKeys} — floci-gcp does not issue real, independently-verifiable
     * workload-identity tokens (any bearer token is accepted, per the emulator's auth-bypass
     * model), so there is no real signing key to expose. Returns an empty key set rather than a
     * fabricated key, since a fabricated key would misleadingly imply real token verification is
     * possible against it. */
    public Map<String, Object> getJsonWebKeys(String project, String location, String clusterId) {
        requireCluster(project, location, clusterId);
        return Map.of("keys", List.of());
    }

    /** {@code ListUsableSubnetworks} — floci-gcp does not emulate Compute Engine/VPC, so there is
     * no real subnetwork inventory. Returns the single synthetic "default" network/subnetwork
     * every cluster in this emulator already defaults to, for tools that expect at least one
     * usable entry rather than an empty list. */
    public Map<String, Object> listUsableSubnetworks(String project) {
        Map<String, Object> subnetwork = new HashMap<>();
        subnetwork.put("subnetwork", "projects/" + project + "/regions/us-central1/subnetworks/default");
        subnetwork.put("network", "projects/" + project + "/global/networks/default");
        subnetwork.put("ipCidrRange", "10.0.0.0/20");
        subnetwork.put("secondaryIpRanges", List.of());
        Map<String, Object> result = new HashMap<>();
        result.put("subnetworks", List.of(subnetwork));
        return result;
    }

    /** {@code CheckAutopilotCompatibility} — floci-gcp does not perform real Autopilot
     * compatibility analysis (no real node scheduling, no policy engine); reports no issues
     * rather than fabricating findings it cannot actually verify. */
    public Map<String, Object> checkAutopilotCompatibility(String project, String location, String clusterId) {
        requireCluster(project, location, clusterId);
        Map<String, Object> result = new HashMap<>();
        result.put("issues", List.of());
        result.put("summary", "No compatibility issues found (floci-gcp does not perform real Autopilot "
                + "compatibility analysis; this cluster's configuration was not evaluated against a real "
                + "policy engine)");
        return result;
    }

    /** {@code FetchClusterUpgradeInfo} — floci-gcp has a single fixed master version, so there is
     * never a real upgrade target; reports the current version as both current and target with no
     * upgrade pending, rather than fabricating an upgrade path that doesn't exist. */
    public Map<String, Object> fetchClusterUpgradeInfo(String project, String location, String clusterId) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        return upgradeInfo(cluster.getCurrentMasterVersion());
    }

    public Map<String, Object> fetchNodePoolUpgradeInfo(String project, String location, String clusterId,
                                                        String nodePoolId) {
        StoredNodePool pool = requireNodePool(project, location, clusterId, nodePoolId);
        return upgradeInfo(pool.getVersion());
    }

    private Map<String, Object> upgradeInfo(String currentVersion) {
        Map<String, Object> info = new HashMap<>();
        info.put("minorTargetVersion", currentVersion);
        info.put("patchTargetVersion", currentVersion);
        info.put("autoUpgradeStatus", List.of());
        info.put("pausedReason", List.of());
        info.put("upgradeDetails", List.of());
        return info;
    }

    // ── Node pools ───────────────────────────────────────────────────────────

    public StoredOperation createNodePool(String project, String location, String clusterId,
                                          Map<String, Object> nodePoolMap) {
        requireCluster(project, location, clusterId);
        StoredNodePool pool = createNodePoolInternal(project, location, clusterId, nodePoolMap);
        return operationService.createNodePoolOperation(
                project, location, clusterId, pool.getName(), OperationType.CREATE_NODE_POOL);
    }

    public StoredNodePool getNodePool(String project, String location, String clusterId, String nodePoolId) {
        return nodePoolStore.get(nodePoolKey(project, location, clusterId, nodePoolId))
                .orElseThrow(() -> GcpException.notFound("Not found: nodePool " + nodePoolId));
    }

    public List<StoredNodePool> listNodePools(String project, String location, String clusterId) {
        return nodePoolStore.scan(k -> true).stream()
                .filter(p -> project.equals(p.getProject()) && location.equals(p.getLocation())
                        && clusterId.equals(p.getClusterId()))
                .toList();
    }

    public StoredOperation deleteNodePool(String project, String location, String clusterId, String nodePoolId) {
        String key = nodePoolKey(project, location, clusterId, nodePoolId);
        if (nodePoolStore.get(key).isEmpty()) {
            throw GcpException.notFound("Not found: nodePool " + nodePoolId);
        }
        nodePoolStore.delete(key);
        return operationService.createNodePoolOperation(
                project, location, clusterId, nodePoolId, OperationType.DELETE_NODE_POOL);
    }

    public StoredOperation updateNodePool(String project, String location, String clusterId, String nodePoolId,
                                          Map<String, Object> body) {
        StoredNodePool pool = requireNodePool(project, location, clusterId, nodePoolId);
        if (body != null) {
            String nodeVersion = (String) body.get("nodeVersion");
            if (nodeVersion != null) {
                pool.setVersion(nodeVersion);
            }
            if (body.get("upgradeSettings") != null) {
                pool.setUpgradeSettings(asMap(body.get("upgradeSettings")));
            }
        }
        pool.setEtag(newFingerprint());
        nodePoolStore.put(nodePoolKey(project, location, clusterId, nodePoolId), pool);
        return operationService.createNodePoolOperation(
                project, location, clusterId, nodePoolId, OperationType.UPDATE_NODE_POOL);
    }

    public StoredOperation setNodePoolAutoscaling(String project, String location, String clusterId,
                                                  String nodePoolId, Map<String, Object> body) {
        StoredNodePool pool = requireNodePool(project, location, clusterId, nodePoolId);
        pool.setAutoscaling(asMap(body == null ? null : body.get("autoscaling")));
        pool.setEtag(newFingerprint());
        nodePoolStore.put(nodePoolKey(project, location, clusterId, nodePoolId), pool);
        return operationService.createNodePoolOperation(
                project, location, clusterId, nodePoolId, OperationType.SET_NODE_POOL_AUTOSCALING);
    }

    public StoredOperation setNodePoolManagement(String project, String location, String clusterId,
                                                 String nodePoolId, Map<String, Object> body) {
        StoredNodePool pool = requireNodePool(project, location, clusterId, nodePoolId);
        pool.setManagement(asMap(body == null ? null : body.get("management")));
        pool.setEtag(newFingerprint());
        nodePoolStore.put(nodePoolKey(project, location, clusterId, nodePoolId), pool);
        return operationService.createNodePoolOperation(
                project, location, clusterId, nodePoolId, OperationType.SET_NODE_POOL_MANAGEMENT);
    }

    public StoredOperation setNodePoolSize(String project, String location, String clusterId,
                                           String nodePoolId, Map<String, Object> body) {
        StoredNodePool pool = requireNodePool(project, location, clusterId, nodePoolId);
        Object nodeCount = body == null ? null : body.get("nodeCount");
        if (nodeCount instanceof Number n) {
            pool.setInitialNodeCount(n.intValue());
        }
        pool.setEtag(newFingerprint());
        nodePoolStore.put(nodePoolKey(project, location, clusterId, nodePoolId), pool);
        return operationService.createNodePoolOperation(
                project, location, clusterId, nodePoolId, OperationType.SET_NODE_POOL_SIZE);
    }

    // ── Operations ───────────────────────────────────────────────────────────

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

    // ── Internal ─────────────────────────────────────────────────────────────

    /** Validates every entry of an explicit {@code nodePools[]} create-time spec — name present,
     * pattern-valid, and unique within the list — without persisting anything, so a bad entry
     * partway through the list can be rejected before the cluster or any earlier pool exists. */
    private void validateNodePoolSpecs(Object requestedPools) {
        if (!(requestedPools instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Object poolObj : list) {
            if (!(poolObj instanceof Map<?, ?> poolMap)) {
                throw GcpException.invalidArgument("Each entry in 'nodePools' must be an object");
            }
            Object nameObj = poolMap.get("name");
            if (!(nameObj instanceof String poolName) || poolName.isBlank()) {
                throw GcpException.invalidArgument("Node pool name is required");
            }
            if (!VALID_NODE_POOL_NAME.matcher(poolName).matches()) {
                throw GcpException.invalidArgument(
                        "Invalid node pool name: '" + poolName + "'. Must match " + VALID_NODE_POOL_NAME.pattern());
            }
            if (!seen.add(poolName)) {
                throw GcpException.alreadyExists("Already exists: nodePool " + poolName);
            }
        }
    }

    private void createInitialNodePools(String project, String location, String clusterName,
                                        Map<String, Object> clusterMap) {
        Object requestedPools = clusterMap.get("nodePools");
        if (requestedPools instanceof List<?> list && !list.isEmpty()) {
            for (Object poolObj : list) {
                createNodePoolInternal(project, location, clusterName, (Map<String, Object>) poolObj);
            }
            return;
        }

        Map<String, Object> defaultPool = new HashMap<>();
        defaultPool.put("name", DEFAULT_NODE_POOL_NAME);
        Object initialNodeCount = clusterMap.get("initialNodeCount");
        defaultPool.put("initialNodeCount", initialNodeCount != null ? initialNodeCount : 3);
        Object nodeConfig = clusterMap.get("nodeConfig");
        if (nodeConfig != null) {
            defaultPool.put("config", nodeConfig);
        }
        createNodePoolInternal(project, location, clusterName, defaultPool);
    }

    @SuppressWarnings("unchecked")
    private StoredNodePool createNodePoolInternal(String project, String location, String clusterId,
                                                  Map<String, Object> poolMap) {
        if (poolMap == null) {
            throw GcpException.invalidArgument("Missing node pool object");
        }
        String name = (String) poolMap.get("name");
        if (name == null || name.isBlank()) {
            throw GcpException.invalidArgument("Node pool name is required");
        }
        if (!VALID_NODE_POOL_NAME.matcher(name).matches()) {
            throw GcpException.invalidArgument(
                    "Invalid node pool name: '" + name + "'. Must match " + VALID_NODE_POOL_NAME.pattern());
        }
        String key = nodePoolKey(project, location, clusterId, name);
        if (nodePoolStore.get(key).isPresent()) {
            throw GcpException.alreadyExists("Already exists: nodePool " + name);
        }

        StoredNodePool pool = new StoredNodePool();
        pool.setName(name);
        pool.setProject(project);
        pool.setLocation(location);
        pool.setClusterId(clusterId);
        Object initialNodeCount = poolMap.get("initialNodeCount");
        pool.setInitialNodeCount(initialNodeCount instanceof Number n ? n.intValue() : 3);
        pool.setLocations(stringListField(poolMap, "locations", List.of(location)));
        // Default the version from the owning cluster, not the build-time constant: after an
        // UpdateCluster carrying desiredNodeVersion, the cluster and its existing pools have
        // moved off DEFAULT_MASTER_VERSION, and a pool created afterwards without an explicit
        // version would otherwise come back on the old one and read as drift.
        String clusterNodeVersion = clusterStore.get(clusterKey(project, location, clusterId))
                .map(StoredCluster::getCurrentNodeVersion)
                .filter(v -> v != null && !v.isBlank())
                .orElse(DEFAULT_MASTER_VERSION);
        pool.setVersion(stringField(poolMap, "version", clusterNodeVersion));
        pool.setStatus("RUNNING");
        pool.setSelfLink(nodePoolSelfLink(project, location, clusterId, name));
        pool.setEtag(newFingerprint());
        pool.setInstanceGroupUrls(List.of());
        pool.setConditions(List.of());
        pool.setConfig(asMap(poolMap.get("config")));
        pool.setAutoscaling(asMap(poolMap.get("autoscaling")));
        pool.setManagement(asMap(poolMap.get("management")));
        pool.setUpgradeSettings(asMap(poolMap.get("upgradeSettings")));
        pool.setPlacementPolicy(asMap(poolMap.get("placementPolicy")));
        pool.setNetworkConfig(asMap(poolMap.get("networkConfig")));

        nodePoolStore.put(key, pool);
        return pool;
    }

    private StoredCluster requireCluster(String project, String location, String clusterId) {
        return clusterStore.get(clusterKey(project, location, clusterId))
                .orElseThrow(() -> GcpException.notFound("Not found: cluster " + clusterId));
    }

    private StoredNodePool requireNodePool(String project, String location, String clusterId, String nodePoolId) {
        return nodePoolStore.get(nodePoolKey(project, location, clusterId, nodePoolId))
                .orElseThrow(() -> GcpException.notFound("Not found: nodePool " + nodePoolId));
    }

    private void touch(StoredCluster cluster) {
        cluster.setLabelFingerprint(newFingerprint());
        cluster.setEtag(newFingerprint());
    }

    private void putExtraConfig(StoredCluster cluster, String key, Object value) {
        Map<String, Object> extra = cluster.getExtraConfig();
        if (extra == null) {
            extra = new HashMap<>();
        } else {
            extra = new HashMap<>(extra);
        }
        if (value == null) {
            extra.remove(key);
        } else {
            extra.put(key, value);
        }
        cluster.setExtraConfig(extra);
    }

    private void mergeExtraConfig(StoredCluster cluster, Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        Map<String, Object> extra = cluster.getExtraConfig();
        extra = extra == null ? new HashMap<>() : new HashMap<>(extra);
        extra.putAll(updates);
        cluster.setExtraConfig(extra);
    }

    private void pollReadiness() {
        try {
            for (StoredCluster cluster : clusterStore.scan(k -> true)) {
                if ("PROVISIONING".equals(cluster.getStatus()) && clusterManager.isReady(cluster)) {
                    clusterManager.finalizeCluster(cluster);
                    cluster.setStatus("RUNNING");
                    clusterStore.put(clusterKey(cluster.getProject(), cluster.getLocation(), cluster.getName()),
                            cluster);
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

    private static String nodePoolKey(String project, String location, String clusterId, String nodePoolId) {
        return clusterKey(project, location, clusterId) + "/nodePools/" + nodePoolId;
    }

    private String clusterSelfLink(String project, String location, String name) {
        return config.baseUrl() + "/container/v1/" + clusterKey(project, location, name);
    }

    private String nodePoolSelfLink(String project, String location, String clusterId, String name) {
        return config.baseUrl() + "/container/v1/" + nodePoolKey(project, location, clusterId, name);
    }

    private static String newFingerprint() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> labels(Map<String, Object> map) {
        if (map == null) {
            return Map.of();
        }
        Object labels = map.containsKey("resourceLabels") ? map.get("resourceLabels") : map.get("labels");
        if (labels instanceof Map<?, ?> m) {
            Map<String, String> result = new HashMap<>();
            m.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
            return result;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> result = new HashMap<>();
            m.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringListField(Map<String, Object> map, String field, List<String> fallback) {
        Object v = map.get(field);
        if (v instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                result.add(String.valueOf(o));
            }
            return result;
        }
        return fallback;
    }

    private static String stringField(Map<String, Object> map, String field, String fallback) {
        Object v = map.get(field);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    /** Everything in {@code map} not in {@code typedFields}, stored verbatim for read-back fidelity. */
    private static Map<String, Object> extraFields(Map<String, Object> map, List<String> typedFields) {
        Map<String, Object> extra = new HashMap<>(map);
        typedFields.forEach(extra::remove);
        return extra;
    }

    /** Strips a leading {@code desired} prefix from ClusterUpdate field names, lower-camel-casing
     * the remainder to match the equivalent Cluster field name (e.g. {@code desiredAddonsConfig}
     * -&gt; {@code addonsConfig}). */
    private static Map<String, Object> stripPrefix(Map<String, Object> map, String prefix) {
        Map<String, Object> result = new HashMap<>();
        map.forEach((k, v) -> {
            if (k.startsWith(prefix) && k.length() > prefix.length()) {
                String rest = k.substring(prefix.length());
                String unwrapped = Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
                result.put(unwrapped, v);
            }
        });
        return result;
    }
}

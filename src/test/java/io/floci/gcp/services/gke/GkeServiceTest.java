package io.floci.gcp.services.gke;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.gke.model.StoredCluster;
import io.floci.gcp.services.gke.model.StoredNodePool;
import io.floci.gcp.services.gke.operations.GkeOperationService;
import io.floci.gcp.services.gke.operations.OperationType;
import io.floci.gcp.services.gke.operations.StoredOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GkeServiceTest {

    private static final String PROJECT = "test-project";
    private static final String LOCATION = "us-central1";

    @Mock
    EmulatorConfig config;
    @Mock
    EmulatorConfig.ServicesConfig services;
    @Mock
    EmulatorConfig.GkeServiceConfig gkeConfig;
    @Mock
    GkeClusterManager clusterManager;

    private GkeService service;

    @BeforeEach
    void setUp() {
        when(config.services()).thenReturn(services);
        when(services.gke()).thenReturn(gkeConfig);
        when(gkeConfig.mock()).thenReturn(true);
        when(config.baseUrl()).thenReturn("http://localhost:4588");

        GkeOperationService operationService =
                new GkeOperationService(new InMemoryStorage<String, StoredOperation>());
        service = new GkeService(new InMemoryStorage<String, StoredCluster>(),
                new InMemoryStorage<String, StoredNodePool>(), config,
                clusterManager, operationService, null);
    }

    @Test
    void createClusterReturnsDoneOperationAndClusterIsRunning() {
        StoredOperation op = service.createCluster(PROJECT, LOCATION, Map.of("name", "my-cluster"));

        assertNotNull(op);
        assertEquals(OperationType.CREATE_CLUSTER, op.getOperationType());
        assertEquals("DONE", op.getStatus());
        assertTrue(op.getName().startsWith("operation-"));

        StoredCluster cluster = service.getCluster(PROJECT, LOCATION, "my-cluster");
        assertEquals("RUNNING", cluster.getStatus());
        assertEquals(LOCATION, cluster.getLocation());
        assertNotNull(cluster.getCurrentMasterVersion());
    }

    @Test
    void createClusterRejectsMissingName() {
        assertThrows(GcpException.class,
                () -> service.createCluster(PROJECT, LOCATION, Map.of()));
    }

    @Test
    void createClusterRejectsDuplicate() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "dup"));
        assertThrows(GcpException.class,
                () -> service.createCluster(PROJECT, LOCATION, Map.of("name", "dup")));
    }

    @Test
    void listClustersIsScopedToProjectAndLocation() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "a"));
        service.createCluster(PROJECT, LOCATION, Map.of("name", "b"));
        service.createCluster(PROJECT, "europe-west1", Map.of("name", "c"));

        List<StoredCluster> central = service.listClusters(PROJECT, LOCATION);
        assertEquals(2, central.size());
        assertTrue(service.listClusters("other-project", LOCATION).isEmpty());
    }

    @Test
    void getOperationResolvesByName() {
        StoredOperation op = service.createCluster(PROJECT, LOCATION, Map.of("name", "with-op"));
        assertEquals(op.getName(), service.getOperation(op.getName()).getName());
    }

    @Test
    void deleteClusterRemovesItAndReturnsOperation() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "to-delete"));
        StoredOperation op = service.deleteCluster(PROJECT, LOCATION, "to-delete");

        assertEquals(OperationType.DELETE_CLUSTER, op.getOperationType());
        assertThrows(GcpException.class, () -> service.getCluster(PROJECT, LOCATION, "to-delete"));
    }

    @Test
    void createClusterCreatesADefaultNodePool() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "with-default-pool"));

        List<StoredNodePool> pools = service.listNodePools(PROJECT, LOCATION, "with-default-pool");
        assertEquals(1, pools.size());
        assertEquals("default-pool", pools.get(0).getName());
        assertEquals("RUNNING", pools.get(0).getStatus());

        StoredCluster cluster = service.getCluster(PROJECT, LOCATION, "with-default-pool");
        assertEquals(1, cluster.getNodePools().size());
    }

    @Test
    void removeDefaultNodePoolThenCreateSeparatePoolMatchesTerraformPattern() {
        // Mirrors the real-world Terraform pattern: remove_default_node_pool = true
        // followed by a standalone google_container_node_pool resource.
        service.createCluster(PROJECT, LOCATION, Map.of("name", "tf-style"));
        service.deleteNodePool(PROJECT, LOCATION, "tf-style", "default-pool");
        assertTrue(service.listNodePools(PROJECT, LOCATION, "tf-style").isEmpty());

        StoredOperation op = service.createNodePool(PROJECT, LOCATION, "tf-style", Map.of(
                "name", "primary",
                "initialNodeCount", 3,
                "autoscaling", Map.of("enabled", true, "minNodeCount", 1, "maxNodeCount", 5),
                "config", Map.of("machineType", "e2-medium"),
                "management", Map.of("autoRepair", true, "autoUpgrade", true)));

        assertEquals(OperationType.CREATE_NODE_POOL, op.getOperationType());
        StoredNodePool pool = service.getNodePool(PROJECT, LOCATION, "tf-style", "primary");
        assertEquals(3, pool.getInitialNodeCount());
        assertEquals(true, pool.getAutoscaling().get("enabled"));
        assertEquals("e2-medium", pool.getConfig().get("machineType"));
    }

    @Test
    void deleteClusterCascadesToNodePools() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "cascade-delete"));
        service.deleteCluster(PROJECT, LOCATION, "cascade-delete");

        assertTrue(service.listNodePools(PROJECT, LOCATION, "cascade-delete").isEmpty());
    }

    @Test
    void getNodePoolThrowsWhenMissing() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "no-such-pool"));
        assertThrows(GcpException.class,
                () -> service.getNodePool(PROJECT, LOCATION, "no-such-pool", "missing"));
    }

    @Test
    void createNodePoolRejectsDuplicateName() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "dup-pool"));
        assertThrows(GcpException.class,
                () -> service.createNodePool(PROJECT, LOCATION, "dup-pool", Map.of("name", "default-pool")));
    }

    @Test
    void setNodePoolAutoscalingUpdatesStoredValue() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "autoscale-me"));
        service.setNodePoolAutoscaling(PROJECT, LOCATION, "autoscale-me", "default-pool",
                Map.of("autoscaling", Map.of("enabled", true, "minNodeCount", 2, "maxNodeCount", 10)));

        StoredNodePool pool = service.getNodePool(PROJECT, LOCATION, "autoscale-me", "default-pool");
        assertEquals(2, pool.getAutoscaling().get("minNodeCount"));
        assertEquals(10, pool.getAutoscaling().get("maxNodeCount"));
    }

    @Test
    void setNodePoolManagementUpdatesStoredValue() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "manage-me"));
        service.setNodePoolManagement(PROJECT, LOCATION, "manage-me", "default-pool",
                Map.of("management", Map.of("autoRepair", false, "autoUpgrade", false)));

        StoredNodePool pool = service.getNodePool(PROJECT, LOCATION, "manage-me", "default-pool");
        assertEquals(false, pool.getManagement().get("autoRepair"));
    }

    @Test
    void setNodePoolSizeUpdatesNodeCount() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "resize-me"));
        service.setNodePoolSize(PROJECT, LOCATION, "resize-me", "default-pool", Map.of("nodeCount", 7));

        StoredNodePool pool = service.getNodePool(PROJECT, LOCATION, "resize-me", "default-pool");
        assertEquals(7, pool.getInitialNodeCount());
    }

    @Test
    void setLabelsUpdatesResourceLabelsAndFingerprint() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "labeled"));
        StoredCluster before = service.getCluster(PROJECT, LOCATION, "labeled");
        String oldFingerprint = before.getLabelFingerprint();

        service.setLabels(PROJECT, LOCATION, "labeled", Map.of("resourceLabels", Map.of("env", "prod")));

        StoredCluster after = service.getCluster(PROJECT, LOCATION, "labeled");
        assertEquals("prod", after.getResourceLabels().get("env"));
        assertNotEquals(oldFingerprint, after.getLabelFingerprint());
    }

    @Test
    void setNetworkPolicyStoresConfigVerbatimForReadBack() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "netpol"));
        service.setNetworkPolicy(PROJECT, LOCATION, "netpol",
                Map.of("networkPolicy", Map.of("enabled", true, "provider", "CALICO")));

        StoredCluster cluster = service.getCluster(PROJECT, LOCATION, "netpol");
        assertEquals(Map.of("enabled", true, "provider", "CALICO"), cluster.getExtraConfig().get("networkPolicy"));
    }

    @Test
    void extraConfigRoundTripsUnknownClusterFieldsFromCreation() {
        // ip_allocation_policy / private_cluster_config / workload_identity_config style blocks:
        // the emulator doesn't act on them, but must echo them back unchanged.
        service.createCluster(PROJECT, LOCATION, Map.of(
                "name", "full-config",
                "ipAllocationPolicy", Map.of("useIpAliases", true, "clusterSecondaryRangeName", "pods"),
                "privateClusterConfig", Map.of("enablePrivateNodes", true),
                "workloadIdentityConfig", Map.of("workloadPool", "test-project.svc.id.goog")));

        StoredCluster cluster = service.getCluster(PROJECT, LOCATION, "full-config");
        assertEquals(true, ((Map<?, ?>) cluster.getExtraConfig().get("ipAllocationPolicy")).get("useIpAliases"));
        assertEquals(true, ((Map<?, ?>) cluster.getExtraConfig().get("privateClusterConfig"))
                .get("enablePrivateNodes"));
        assertEquals("test-project.svc.id.goog",
                ((Map<?, ?>) cluster.getExtraConfig().get("workloadIdentityConfig")).get("workloadPool"));
    }

    @Test
    void setLoggingAndMonitoringServiceUpdateTypedFields() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "observability"));

        service.setLoggingService(PROJECT, LOCATION, "observability", Map.of("loggingService", "none"));
        service.setMonitoringService(PROJECT, LOCATION, "observability", Map.of("monitoringService", "none"));

        StoredCluster cluster = service.getCluster(PROJECT, LOCATION, "observability");
        assertEquals("none", cluster.getLoggingService());
        assertEquals("none", cluster.getMonitoringService());
    }

    @Test
    void setLegacyAbacStoresEnabledFlag() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "abac-cluster"));
        StoredOperation op = service.setLegacyAbac(PROJECT, LOCATION, "abac-cluster", Map.of("enabled", true));

        assertEquals(OperationType.SET_LEGACY_ABAC, op.getOperationType());
        StoredCluster cluster = service.getCluster(PROJECT, LOCATION, "abac-cluster");
        assertEquals(Map.of("enabled", true), cluster.getExtraConfig().get("legacyAbac"));
    }

    @Test
    void setMaintenancePolicyStoresPolicyAndEmptyPolicyClearsIt() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "maint-cluster"));
        service.setMaintenancePolicy(PROJECT, LOCATION, "maint-cluster",
                Map.of("maintenancePolicy", Map.of("window", Map.of("recurrence", "FREQ=WEEKLY"))));

        StoredCluster withPolicy = service.getCluster(PROJECT, LOCATION, "maint-cluster");
        assertNotNull(withPolicy.getExtraConfig().get("maintenancePolicy"));

        service.setMaintenancePolicy(PROJECT, LOCATION, "maint-cluster", Map.of("maintenancePolicy", Map.of()));
        StoredCluster cleared = service.getCluster(PROJECT, LOCATION, "maint-cluster");
        assertEquals(null, cleared.getExtraConfig().get("maintenancePolicy"));
    }

    @Test
    void ipRotationOperationsSucceedForExistingCluster() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "rotate-me"));

        StoredOperation start = service.startIpRotation(PROJECT, LOCATION, "rotate-me");
        assertEquals(OperationType.START_IP_ROTATION, start.getOperationType());

        StoredOperation complete = service.completeIpRotation(PROJECT, LOCATION, "rotate-me");
        assertEquals(OperationType.COMPLETE_IP_ROTATION, complete.getOperationType());
    }

    @Test
    void nodePoolUpgradeOperationsRequireAnExistingPool() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "upgrade-me"));

        StoredOperation complete = service.completeNodePoolUpgrade(PROJECT, LOCATION, "upgrade-me", "default-pool");
        assertEquals(OperationType.COMPLETE_NODE_POOL_UPGRADE, complete.getOperationType());

        StoredOperation rollback = service.rollbackNodePoolUpgrade(PROJECT, LOCATION, "upgrade-me", "default-pool");
        assertEquals(OperationType.ROLLBACK_NODE_POOL_UPGRADE, rollback.getOperationType());

        assertThrows(GcpException.class,
                () -> service.completeNodePoolUpgrade(PROJECT, LOCATION, "upgrade-me", "no-such-pool"));
    }

    @Test
    void getServerConfigReturnsVersionAndChannelInfo() {
        Map<String, Object> config = service.getServerConfig();

        assertNotNull(config.get("defaultClusterVersion"));
        assertNotNull(config.get("validNodeVersions"));
        assertNotNull(config.get("validMasterVersions"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> channels = (List<Map<String, Object>>) config.get("channels");
        assertEquals(3, channels.size());
        assertTrue(channels.stream().anyMatch(c -> "REGULAR".equals(c.get("channel"))));
    }

    @Test
    void getJsonWebKeysReturnsEmptyKeySetForExistingCluster() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "jwks-cluster"));
        Map<String, Object> result = service.getJsonWebKeys(PROJECT, LOCATION, "jwks-cluster");
        assertEquals(List.of(), result.get("keys"));
    }

    @Test
    void getJsonWebKeysThrowsForMissingCluster() {
        assertThrows(GcpException.class, () -> service.getJsonWebKeys(PROJECT, LOCATION, "no-such-cluster"));
    }

    @Test
    void listUsableSubnetworksReturnsSyntheticDefaultEntry() {
        Map<String, Object> result = service.listUsableSubnetworks(PROJECT);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subnetworks = (List<Map<String, Object>>) result.get("subnetworks");
        assertEquals(1, subnetworks.size());
        assertTrue(((String) subnetworks.get(0).get("subnetwork")).contains(PROJECT));
    }

    @Test
    void checkAutopilotCompatibilityReturnsNoIssues() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "autopilot-check"));
        Map<String, Object> result = service.checkAutopilotCompatibility(PROJECT, LOCATION, "autopilot-check");
        assertEquals(List.of(), result.get("issues"));
        assertNotNull(result.get("summary"));
    }

    @Test
    void fetchClusterUpgradeInfoReportsCurrentVersionAsTarget() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "upgrade-info-cluster"));
        StoredCluster cluster = service.getCluster(PROJECT, LOCATION, "upgrade-info-cluster");

        Map<String, Object> info = service.fetchClusterUpgradeInfo(PROJECT, LOCATION, "upgrade-info-cluster");
        assertEquals(cluster.getCurrentMasterVersion(), info.get("minorTargetVersion"));
        assertEquals(cluster.getCurrentMasterVersion(), info.get("patchTargetVersion"));
    }

    @Test
    void fetchNodePoolUpgradeInfoReportsCurrentVersionAsTarget() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "np-upgrade-info"));
        StoredNodePool pool = service.getNodePool(PROJECT, LOCATION, "np-upgrade-info", "default-pool");

        Map<String, Object> info = service.fetchNodePoolUpgradeInfo(PROJECT, LOCATION, "np-upgrade-info", "default-pool");
        assertEquals(pool.getVersion(), info.get("minorTargetVersion"));
    }

    @Test
    void autopilotAndFleetConfigRoundTripThroughExtraConfig() {
        // Autopilot mode and Fleet/Anthos registration aren't semantically modeled, but they
        // must round-trip exactly like every other unknown config block via extraConfig.
        service.createCluster(PROJECT, LOCATION, Map.of(
                "name", "autopilot-fleet-cluster",
                "autopilot", Map.of("enabled", true),
                "fleet", Map.of("project", "test-project")));

        StoredCluster cluster = service.getCluster(PROJECT, LOCATION, "autopilot-fleet-cluster");
        assertEquals(true, ((Map<?, ?>) cluster.getExtraConfig().get("autopilot")).get("enabled"));
        assertEquals("test-project", ((Map<?, ?>) cluster.getExtraConfig().get("fleet")).get("project"));
    }

    @Test
    void updateClusterAppliesTypedDesiredFieldsRatherThanLeavingThemStale() {
        // Regression: locations/loggingService/monitoringService are typed StoredCluster fields,
        // not extraConfig-only — clusterToJson reads them from their typed getter, which would
        // silently overwrite whatever a naive extraConfig-only merge wrote under the same key.
        service.createCluster(PROJECT, LOCATION, Map.of("name", "typed-update"));

        service.updateCluster(PROJECT, LOCATION, "typed-update", Map.of(
                "desiredLocations", List.of("us-central1-a", "us-central1-b"),
                "desiredLoggingService", "none",
                "desiredMonitoringService", "none"));

        StoredCluster cluster = service.getCluster(PROJECT, LOCATION, "typed-update");
        assertEquals(List.of("us-central1-a", "us-central1-b"), cluster.getLocations());
        assertEquals("none", cluster.getLoggingService());
        assertEquals("none", cluster.getMonitoringService());
    }

    @Test
    void createClusterWithDuplicateNodePoolNameLeavesNoPartialState() {
        // Regression: an invalid/duplicate entry partway through an explicit nodePools[] list
        // used to leave the cluster and any earlier pools persisted despite the overall create
        // failing, so a retry hit AlreadyExists instead of succeeding.
        assertThrows(GcpException.class, () -> service.createCluster(PROJECT, LOCATION, Map.of(
                "name", "partial-create",
                "nodePools", List.of(
                        Map.of("name", "pool-a"),
                        Map.of("name", "pool-a")))));

        assertThrows(GcpException.class, () -> service.getCluster(PROJECT, LOCATION, "partial-create"));
        assertTrue(service.listNodePools(PROJECT, LOCATION, "partial-create").isEmpty());

        // A retry with a valid spec must succeed — not fail with AlreadyExists against
        // leftover state from the failed attempt.
        StoredOperation op = service.createCluster(PROJECT, LOCATION, Map.of(
                "name", "partial-create",
                "nodePools", List.of(Map.of("name", "pool-a"))));
        assertEquals(OperationType.CREATE_CLUSTER, op.getOperationType());
    }

    @Test
    void startupMigratesNodePoolsEmbeddedByAPreviousVersion() {
        // Regression: clusters persisted by a floci-gcp version before node pools had their own
        // store embedded pools directly on the cluster record. Without migration, upgrading
        // silently drops that data — the new node pool store starts empty and nothing populates
        // it for a pre-existing cluster.
        StoredCluster legacyCluster = new StoredCluster();
        legacyCluster.setName("legacy-cluster");
        legacyCluster.setProject(PROJECT);
        legacyCluster.setLocation(LOCATION);
        legacyCluster.setStatus("RUNNING");
        StoredNodePool embeddedPool = new StoredNodePool();
        embeddedPool.setName("default-pool");
        embeddedPool.setStatus("RUNNING");
        legacyCluster.setNodePools(List.of(embeddedPool));

        InMemoryStorage<String, StoredCluster> clusterStore = new InMemoryStorage<>();
        clusterStore.put("projects/" + PROJECT + "/locations/" + LOCATION + "/clusters/legacy-cluster",
                legacyCluster);
        GkeOperationService operationService =
                new GkeOperationService(new InMemoryStorage<String, StoredOperation>());
        GkeService migratingService = new GkeService(clusterStore, new InMemoryStorage<String, StoredNodePool>(),
                config, clusterManager, operationService, null);

        migratingService.init();

        List<StoredNodePool> migrated = migratingService.listNodePools(PROJECT, LOCATION, "legacy-cluster");
        assertEquals(1, migrated.size());
        assertEquals("default-pool", migrated.get(0).getName());
        assertEquals(PROJECT, migrated.get(0).getProject());
        assertEquals(LOCATION, migrated.get(0).getLocation());
        assertEquals("legacy-cluster", migrated.get(0).getClusterId());
        assertNotNull(migrated.get(0).getSelfLink());
    }

    @Test
    void updateClusterMergesDesiredFieldsIntoExtraConfig() {
        service.createCluster(PROJECT, LOCATION, Map.of("name", "updatable"));

        StoredOperation op = service.updateCluster(PROJECT, LOCATION, "updatable", Map.of(
                "desiredNodeVersion", "1.31.0-gke.1",
                "desiredAddonsConfig", Map.of("httpLoadBalancing", Map.of("disabled", true))));

        assertEquals(OperationType.UPDATE_CLUSTER, op.getOperationType());
        StoredCluster cluster = service.getCluster(PROJECT, LOCATION, "updatable");
        assertEquals("1.31.0-gke.1", cluster.getCurrentNodeVersion());
        assertNotNull(cluster.getExtraConfig().get("addonsConfig"));
    }
}

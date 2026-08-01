package io.floci.gcp.services.gke;

import io.floci.gcp.services.gke.model.StoredCluster;
import io.floci.gcp.services.gke.model.StoredNodePool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GKE (container.googleapis.com) REST controller, mounted under the {@code /container}
 * prefix to avoid colliding with the canonical {@code /v1/projects} path owned by other
 * services. The {@code ServiceRoutingFilter} rewrites canonical SDK/CLI requests
 * (Host {@code container.*}) to this prefix; Terraform/gcloud reach it directly via a
 * {@code /container/v1/} custom endpoint.
 *
 * <p>Shapes mirror {@code google.container.v1.Cluster} / {@code NodePool} / {@code Operation}.
 * Path params that precede a GCP "custom method" colon suffix (e.g. {@code :setNetworkPolicy})
 * are regex-constrained to exclude {@code :} so JAX-RS does not swallow the suffix into the
 * param value — the same pattern GCS/Cloud Run/Datastore already use here for wildcard segments.
 */
@Path("/container/v1/projects/{project}/locations/{location}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KubernetesController {

    private final GkeService gkeService;

    @Inject
    public KubernetesController(GkeService gkeService) {
        this.gkeService = gkeService;
    }

    // ── Clusters ─────────────────────────────────────────────────────────────

    @POST
    @Path("/clusters")
    public Response createCluster(
            @PathParam("project") String project,
            @PathParam("location") String location,
            Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        Map<String, Object> clusterMap = body == null ? null : (Map<String, Object>) body.get("cluster");
        return Response.ok(gkeService.createCluster(project, location, clusterMap)).build();
    }

    @GET
    @Path("/clusters")
    public Response listClusters(
            @PathParam("project") String project,
            @PathParam("location") String location) {

        List<Map<String, Object>> clusters = gkeService.listClusters(project, location).stream()
                .map(KubernetesController::clusterToJson)
                .toList();
        return Response.ok(Map.of("clusters", clusters)).build();
    }

    @GET
    @Path("/clusters/{clusterId}")
    public Response getCluster(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId) {

        return Response.ok(clusterToJson(gkeService.getCluster(project, location, clusterId))).build();
    }

    @PUT
    @Path("/clusters/{clusterId}")
    public Response updateCluster(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        Map<String, Object> updateMap = body == null ? null : (Map<String, Object>) body.get("update");
        return Response.ok(gkeService.updateCluster(project, location, clusterId, updateMap)).build();
    }

    @DELETE
    @Path("/clusters/{clusterId}")
    public Response deleteCluster(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId) {

        return Response.ok(gkeService.deleteCluster(project, location, clusterId)).build();
    }

    @POST
    @Path("/clusters/{clusterId: [^:/]+}:setResourceLabels")
    public Response setResourceLabels(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            Map<String, Object> body) {

        return Response.ok(gkeService.setLabels(project, location, clusterId, body)).build();
    }

    @POST
    @Path("/clusters/{clusterId: [^:/]+}:setMasterAuth")
    public Response setMasterAuth(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            Map<String, Object> body) {

        return Response.ok(gkeService.setMasterAuth(project, location, clusterId, body)).build();
    }

    @POST
    @Path("/clusters/{clusterId: [^:/]+}:setNetworkPolicy")
    public Response setNetworkPolicy(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            Map<String, Object> body) {

        return Response.ok(gkeService.setNetworkPolicy(project, location, clusterId, body)).build();
    }

    @POST
    @Path("/clusters/{clusterId: [^:/]+}:setAddons")
    public Response setAddonsConfig(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            Map<String, Object> body) {

        return Response.ok(gkeService.setAddonsConfig(project, location, clusterId, body)).build();
    }

    @POST
    @Path("/clusters/{clusterId: [^:/]+}:setLogging")
    public Response setLoggingService(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            Map<String, Object> body) {

        return Response.ok(gkeService.setLoggingService(project, location, clusterId, body)).build();
    }

    @POST
    @Path("/clusters/{clusterId: [^:/]+}:setMonitoring")
    public Response setMonitoringService(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            Map<String, Object> body) {

        return Response.ok(gkeService.setMonitoringService(project, location, clusterId, body)).build();
    }

    @POST
    @Path("/clusters/{clusterId: [^:/]+}:setLocations")
    public Response setLocations(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            Map<String, Object> body) {

        return Response.ok(gkeService.setLocations(project, location, clusterId, body)).build();
    }

    @POST
    @Path("/clusters/{clusterId: [^:/]+}:setLegacyAbac")
    public Response setLegacyAbac(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            Map<String, Object> body) {

        return Response.ok(gkeService.setLegacyAbac(project, location, clusterId, body)).build();
    }

    @POST
    @Path("/clusters/{clusterId: [^:/]+}:setMaintenancePolicy")
    public Response setMaintenancePolicy(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            Map<String, Object> body) {

        return Response.ok(gkeService.setMaintenancePolicy(project, location, clusterId, body)).build();
    }

    @POST
    @Path("/clusters/{clusterId: [^:/]+}:startIpRotation")
    public Response startIpRotation(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            Map<String, Object> body) {

        return Response.ok(gkeService.startIpRotation(project, location, clusterId)).build();
    }

    @POST
    @Path("/clusters/{clusterId: [^:/]+}:completeIpRotation")
    public Response completeIpRotation(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            Map<String, Object> body) {

        return Response.ok(gkeService.completeIpRotation(project, location, clusterId)).build();
    }

    @GET
    @Path("/serverConfig")
    public Response getServerConfig(
            @PathParam("project") String project,
            @PathParam("location") String location) {

        return Response.ok(gkeService.getServerConfig()).build();
    }

    @GET
    @Path("/clusters/{clusterId}/jwks")
    public Response getJsonWebKeys(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId) {

        return Response.ok(gkeService.getJsonWebKeys(project, location, clusterId)).build();
    }

    @GET
    @Path("/clusters/{clusterId: [^:/]+}:checkAutopilotCompatibility")
    public Response checkAutopilotCompatibility(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId) {

        return Response.ok(gkeService.checkAutopilotCompatibility(project, location, clusterId)).build();
    }

    @GET
    @Path("/clusters/{clusterId: [^:/]+}:fetchClusterUpgradeInfo")
    public Response fetchClusterUpgradeInfo(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId) {

        return Response.ok(gkeService.fetchClusterUpgradeInfo(project, location, clusterId)).build();
    }

    @GET
    @Path("/clusters/{clusterId}/nodePools/{nodePoolId: [^:/]+}:fetchNodePoolUpgradeInfo")
    public Response fetchNodePoolUpgradeInfo(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            @PathParam("nodePoolId") String nodePoolId) {

        return Response.ok(gkeService.fetchNodePoolUpgradeInfo(project, location, clusterId, nodePoolId)).build();
    }

    // ── Node pools ───────────────────────────────────────────────────────────

    @POST
    @Path("/clusters/{clusterId}/nodePools")
    public Response createNodePool(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        Map<String, Object> nodePoolMap = body == null ? null : (Map<String, Object>) body.get("nodePool");
        return Response.ok(gkeService.createNodePool(project, location, clusterId, nodePoolMap)).build();
    }

    @GET
    @Path("/clusters/{clusterId}/nodePools")
    public Response listNodePools(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId) {

        List<Map<String, Object>> pools = gkeService.listNodePools(project, location, clusterId).stream()
                .map(KubernetesController::nodePoolToJson)
                .toList();
        return Response.ok(Map.of("nodePools", pools)).build();
    }

    @GET
    @Path("/clusters/{clusterId}/nodePools/{nodePoolId}")
    public Response getNodePool(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            @PathParam("nodePoolId") String nodePoolId) {

        return Response.ok(nodePoolToJson(gkeService.getNodePool(project, location, clusterId, nodePoolId))).build();
    }

    @PUT
    @Path("/clusters/{clusterId}/nodePools/{nodePoolId}")
    public Response updateNodePool(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            @PathParam("nodePoolId") String nodePoolId,
            Map<String, Object> body) {

        return Response.ok(gkeService.updateNodePool(project, location, clusterId, nodePoolId, body)).build();
    }

    @DELETE
    @Path("/clusters/{clusterId}/nodePools/{nodePoolId}")
    public Response deleteNodePool(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            @PathParam("nodePoolId") String nodePoolId) {

        return Response.ok(gkeService.deleteNodePool(project, location, clusterId, nodePoolId)).build();
    }

    @POST
    @Path("/clusters/{clusterId}/nodePools/{nodePoolId: [^:/]+}:setAutoscaling")
    public Response setNodePoolAutoscaling(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            @PathParam("nodePoolId") String nodePoolId,
            Map<String, Object> body) {

        return Response.ok(gkeService.setNodePoolAutoscaling(project, location, clusterId, nodePoolId, body)).build();
    }

    @POST
    @Path("/clusters/{clusterId}/nodePools/{nodePoolId: [^:/]+}:setManagement")
    public Response setNodePoolManagement(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            @PathParam("nodePoolId") String nodePoolId,
            Map<String, Object> body) {

        return Response.ok(gkeService.setNodePoolManagement(project, location, clusterId, nodePoolId, body)).build();
    }

    @POST
    @Path("/clusters/{clusterId}/nodePools/{nodePoolId: [^:/]+}:setSize")
    public Response setNodePoolSize(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            @PathParam("nodePoolId") String nodePoolId,
            Map<String, Object> body) {

        return Response.ok(gkeService.setNodePoolSize(project, location, clusterId, nodePoolId, body)).build();
    }

    @POST
    @Path("/clusters/{clusterId}/nodePools/{nodePoolId: [^:/]+}:completeUpgrade")
    public Response completeNodePoolUpgrade(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            @PathParam("nodePoolId") String nodePoolId,
            Map<String, Object> body) {

        return Response.ok(gkeService.completeNodePoolUpgrade(project, location, clusterId, nodePoolId)).build();
    }

    @POST
    @Path("/clusters/{clusterId}/nodePools/{nodePoolId: [^:/]+}:rollback")
    public Response rollbackNodePoolUpgrade(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId,
            @PathParam("nodePoolId") String nodePoolId,
            Map<String, Object> body) {

        return Response.ok(gkeService.rollbackNodePoolUpgrade(project, location, clusterId, nodePoolId)).build();
    }

    // ── Operations ───────────────────────────────────────────────────────────

    @GET
    @Path("/operations")
    public Response listOperations(
            @PathParam("project") String project,
            @PathParam("location") String location) {

        return Response.ok(Map.of("operations", gkeService.listOperations(project, location))).build();
    }

    @GET
    @Path("/operations/{operationId}")
    public Response getOperation(
            @PathParam("operationId") String operationId) {

        return Response.ok(gkeService.getOperation(operationId)).build();
    }

    /** Non-standard convenience endpoint (no GKE API equivalent): raw k3s kubeconfig. */
    @GET
    @Path("/clusters/{clusterId}/kubeconfig")
    @Produces(MediaType.TEXT_PLAIN)
    public Response kubeConfig(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId) {

        return Response.ok(gkeService.kubeConfig(project, location, clusterId)).build();
    }

    private static Map<String, Object> clusterToJson(StoredCluster cluster) {
        Map<String, Object> result = new HashMap<>();
        if (cluster.getExtraConfig() != null) {
            result.putAll(cluster.getExtraConfig());
        }
        result.put("name", cluster.getName());
        if (cluster.getDescription() != null) {
            result.put("description", cluster.getDescription());
        }
        result.put("location", cluster.getLocation());
        result.put("zone", cluster.getZone());
        result.put("status", cluster.getStatus());
        if (cluster.getStatusMessage() != null) {
            result.put("statusMessage", cluster.getStatusMessage());
        }
        result.put("endpoint", cluster.getEndpoint());
        result.put("selfLink", cluster.getSelfLink());
        Map<String, Object> masterAuth = new HashMap<>();
        Object extraMasterAuth = cluster.getExtraConfig() == null ? null : cluster.getExtraConfig().get("masterAuth");
        if (extraMasterAuth instanceof Map<?, ?> m) {
            m.forEach((k, v) -> masterAuth.put(String.valueOf(k), v));
        }
        masterAuth.put("clusterCaCertificate", cluster.getCaCertificate() == null ? "" : cluster.getCaCertificate());
        result.put("masterAuth", masterAuth);
        result.put("currentMasterVersion", cluster.getCurrentMasterVersion());
        result.put("currentNodeVersion", cluster.getCurrentNodeVersion());
        result.put("initialClusterVersion", cluster.getInitialClusterVersion());
        result.put("initialNodeCount", cluster.getInitialNodeCount());
        result.put("network", cluster.getNetwork());
        result.put("subnetwork", cluster.getSubnetwork());
        // Real GKE mirrors the deprecated top-level network/subnetwork fields into
        // networkConfig server-side; the terraform-provider-google Read path reads
        // exclusively from networkConfig.network/.subnetwork, not the top-level fields.
        Map<String, Object> networkConfig = new HashMap<>();
        Object extraNetworkConfig = cluster.getExtraConfig() == null ? null : cluster.getExtraConfig().get("networkConfig");
        if (extraNetworkConfig instanceof Map<?, ?> nc) {
            nc.forEach((k, v) -> networkConfig.put(String.valueOf(k), v));
        }
        networkConfig.put("network", cluster.getNetwork());
        networkConfig.put("subnetwork", cluster.getSubnetwork());
        result.put("networkConfig", networkConfig);
        result.put("clusterIpv4Cidr", cluster.getClusterIpv4Cidr());
        result.put("locations", cluster.getLocations());
        result.put("loggingService", cluster.getLoggingService());
        result.put("monitoringService", cluster.getMonitoringService());
        result.put("nodePools", cluster.getNodePools() == null
                ? List.of()
                : cluster.getNodePools().stream().map(KubernetesController::nodePoolToJson).toList());
        result.put("resourceLabels", cluster.getResourceLabels());
        result.put("labelFingerprint", cluster.getLabelFingerprint());
        result.put("createTime", cluster.getCreateTime());
        if (cluster.getExpireTime() != null) {
            result.put("expireTime", cluster.getExpireTime());
        }
        result.put("etag", cluster.getEtag());
        result.put("conditions", cluster.getConditions() == null ? List.of() : cluster.getConditions());
        return result;
    }

    private static Map<String, Object> nodePoolToJson(StoredNodePool pool) {
        Map<String, Object> result = new HashMap<>();
        result.put("name", pool.getName());
        if (pool.getConfig() != null) {
            result.put("config", pool.getConfig());
        }
        result.put("initialNodeCount", pool.getInitialNodeCount());
        result.put("locations", pool.getLocations());
        if (pool.getNetworkConfig() != null) {
            result.put("networkConfig", pool.getNetworkConfig());
        }
        result.put("selfLink", pool.getSelfLink());
        result.put("version", pool.getVersion());
        result.put("instanceGroupUrls", pool.getInstanceGroupUrls());
        result.put("status", pool.getStatus());
        if (pool.getStatusMessage() != null) {
            result.put("statusMessage", pool.getStatusMessage());
        }
        if (pool.getAutoscaling() != null) {
            result.put("autoscaling", pool.getAutoscaling());
        }
        if (pool.getManagement() != null) {
            result.put("management", pool.getManagement());
        }
        result.put("conditions", pool.getConditions() == null ? List.of() : pool.getConditions());
        if (pool.getUpgradeSettings() != null) {
            result.put("upgradeSettings", pool.getUpgradeSettings());
        }
        if (pool.getPlacementPolicy() != null) {
            result.put("placementPolicy", pool.getPlacementPolicy());
        }
        result.put("etag", pool.getEtag());
        return result;
    }
}

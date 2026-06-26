package io.floci.gcp.services.gke;

import io.floci.gcp.services.gke.model.StoredCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
 * <p>Shapes mirror {@code google.container.v1.Cluster} / {@code Operation}.
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

    @DELETE
    @Path("/clusters/{clusterId}")
    public Response deleteCluster(
            @PathParam("project") String project,
            @PathParam("location") String location,
            @PathParam("clusterId") String clusterId) {

        return Response.ok(gkeService.deleteCluster(project, location, clusterId)).build();
    }

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
        result.put("name", cluster.getName());
        result.put("location", cluster.getLocation());
        result.put("status", cluster.getStatus());
        result.put("endpoint", cluster.getEndpoint());
        result.put("selfLink", cluster.getSelfLink());
        result.put("masterAuth", Map.of(
                "clusterCaCertificate", cluster.getCaCertificate() == null ? "" : cluster.getCaCertificate()));
        result.put("currentMasterVersion", cluster.getCurrentMasterVersion());
        result.put("currentNodeVersion", cluster.getCurrentMasterVersion());
        result.put("initialClusterVersion", cluster.getCurrentMasterVersion());
        result.put("network", cluster.getNetwork());
        result.put("subnetwork", cluster.getSubnetwork());
        result.put("nodePools", cluster.getNodePools());
        result.put("resourceLabels", cluster.getResourceLabels());
        result.put("createTime", cluster.getCreateTime());
        return result;
    }
}

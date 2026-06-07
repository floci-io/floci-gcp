package io.floci.gcp.services.gke;

import io.floci.gcp.core.common.kubernetes.Cluster;
import io.floci.gcp.core.common.kubernetes.drivers.K3dDriver;
import io.floci.gcp.core.common.kubernetes.metadata.ClusterMetadata;
import io.floci.gcp.services.gke.operations.GkeOperationService;
import io.floci.gcp.services.gke.operations.OperationType;
import io.floci.gcp.services.gke.operations.StoredOperation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/v1/projects/{project}/locations/{location}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KubernetesController {

        @Inject
        K3dDriver driver;

        @Inject
        GkeOperationService operationService;

        @POST
        @Path("/clusters")
        public Response createCluster(
                        @PathParam("project") String project,
                        @PathParam("location") String location,
                        Map<String, Object> body) {

                Map<String, Object> clusterMap = (Map<String, Object>) body.get("cluster");

                if (clusterMap == null) {
                        return gcpError(
                                        400,
                                        "Missing root 'cluster' object",
                                        "INVALID_ARGUMENT");
                }

                String clusterName = (String) clusterMap.get("name");

                if (clusterName == null || clusterName.isBlank()) {
                        return gcpError(
                                        400,
                                        "Cluster name is required",
                                        "INVALID_ARGUMENT");
                }

                driver.create(clusterName);

                StoredOperation op = operationService.createOperation(
                                project,
                                location,
                                clusterName,
                                OperationType.CREATE_CLUSTER);

                return Response.ok(op).build();
        }

        @GET
        @Path("/clusters")
        public Response listClusters(
                        @PathParam("location") String location) {

                List<Map<String, Object>> clusters = driver.list()
                                .stream()
                                .map(cluster -> {

                                        ClusterMetadata metadata = driver.metadata(
                                                        cluster.getName(),
                                                        location);

                                        return clusterToJson(
                                                        cluster,
                                                        metadata);
                                })
                                .toList();

                return Response.ok(
                                Map.of(
                                                "clusters",
                                                clusters))
                                .build();
        }

        @GET
        @Path("/clusters/{clusterId}")
        public Response getCluster(
                        @PathParam("location") String location,
                        @PathParam("clusterId") String clusterId) {

                Cluster cluster = driver.get(clusterId);

                ClusterMetadata metadata = driver.metadata(
                                clusterId,
                                location);

                return Response.ok(
                                clusterToJson(
                                                cluster,
                                                metadata))
                                .build();
        }

        @DELETE
        @Path("/clusters/{clusterId}")
        public Response deleteCluster(
                        @PathParam("project") String project,
                        @PathParam("location") String location,
                        @PathParam("clusterId") String clusterId) {

                driver.delete(clusterId);

                StoredOperation op = operationService.createOperation(
                                project,
                                location,
                                clusterId,
                                OperationType.DELETE_CLUSTER);

                return Response.ok(op).build();
        }

        @GET
        @Path("/operations")
        public Response listOperations(
                        @PathParam("project") String project,
                        @PathParam("location") String location) {

                return Response.ok(
                                Map.of(
                                                "operations",
                                                operationService.listOperations(
                                                                project,
                                                                location)))
                                .build();
        }

        @GET
        @Path("/operations/{operationId}")
        public Response getOperation(
                        @PathParam("operationId") String operationId) {

                return Response.ok(
                                operationService.getOperation(operationId))
                                .build();
        }

        @GET
        @Path("/clusters/{clusterId}/kubeconfig")
        @Produces(MediaType.TEXT_PLAIN)
        public Response kubeConfig(
                        @PathParam("clusterId") String clusterId) {

                return Response.ok(
                                driver.kubeConfig(clusterId))
                                .build();
        }

        private Map<String, Object> clusterToJson(
                        Cluster cluster,
                        ClusterMetadata metadata) {

                Map<String, Object> result = new HashMap<>();

                result.put("name", cluster.getName());
                result.put("location", metadata.location());
                result.put("status", cluster.getStatus().name());
                result.put("endpoint", metadata.endpoint());

                result.put(
                                "masterAuth",
                                Map.of(
                                                "clusterCaCertificate",
                                                metadata.caCertificate()));

                result.put(
                                "currentMasterVersion",
                                metadata.kubernetesVersion());

                result.put(
                                "currentNodeVersion",
                                metadata.kubernetesVersion());

                result.put(
                                "network",
                                metadata.network());

                result.put(
                                "subnetwork",
                                metadata.subnetwork());

                result.put(
                                "nodePools",
                                metadata.nodePools());

                result.put(
                                "resourceLabels",
                                metadata.labels());

                return result;
        }

        private static Response gcpError(
                        int code,
                        String message,
                        String status) {

                return Response.status(code)
                                .entity(
                                                Map.of(
                                                                "error",
                                                                Map.of(
                                                                                "code", code,
                                                                                "message", message,
                                                                                "status", status)))
                                .build();
        }
}
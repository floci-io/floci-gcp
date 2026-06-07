package io.floci.gcp.services.kafka;

import io.floci.gcp.services.kafka.model.StoredCluster;
import io.floci.gcp.services.kafka.model.StoredConsumerGroup;
import io.floci.gcp.services.kafka.model.StoredTopic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for the GCP Managed Service for Apache Kafka v1 API.
 *
 * Cluster create/delete return immediately-complete LRO responses (done=true).
 */
@Path("/v1/projects/{project}/locations/{location}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KafkaController {

    private static final Logger LOG = Logger.getLogger(KafkaController.class);

    private final KafkaService service;

    @Inject
    public KafkaController(KafkaService service) {
        this.service = service;
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    @POST
    @Path("/clusters")
    public Response createCluster(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @QueryParam("clusterId") String clusterId,
                                  Map<String, Object> body) {
        LOG.debugf("Kafka createCluster project=%s location=%s clusterId=%s", project, location, clusterId);
        if (clusterId == null || clusterId.isBlank()) {
            return gcpError(400, "clusterId query parameter is required", "INVALID_ARGUMENT");
        }
        StoredCluster cluster = service.createCluster(project, location, clusterId, body);
        return Response.ok(operationDone(cluster.getName(), cluster)).build();
    }

    @GET
    @Path("/clusters/{clusterId}")
    public Response getCluster(@PathParam("project") String project,
                               @PathParam("location") String location,
                               @PathParam("clusterId") String clusterId) {
        LOG.debugf("Kafka getCluster project=%s location=%s clusterId=%s", project, location, clusterId);
        StoredCluster cluster = service.getCluster(project, location, clusterId);
        return Response.ok(cluster).build();
    }

    @GET
    @Path("/clusters")
    public Response listClusters(@PathParam("project") String project,
                                 @PathParam("location") String location) {
        LOG.debugf("Kafka listClusters project=%s location=%s", project, location);
        List<StoredCluster> clusters = service.listClusters(project, location);
        return Response.ok(Map.of("clusters", clusters)).build();
    }

    @PATCH
    @Path("/clusters/{clusterId}")
    public Response updateCluster(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @PathParam("clusterId") String clusterId,
                                  Map<String, Object> body) {
        LOG.debugf("Kafka updateCluster project=%s location=%s clusterId=%s", project, location, clusterId);
        StoredCluster cluster = service.updateCluster(project, location, clusterId, body);
        return Response.ok(operationDone(cluster.getName(), cluster)).build();
    }

    @DELETE
    @Path("/clusters/{clusterId}")
    public Response deleteCluster(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @PathParam("clusterId") String clusterId) {
        LOG.debugf("Kafka deleteCluster project=%s location=%s clusterId=%s", project, location, clusterId);
        service.deleteCluster(project, location, clusterId);
        return Response.ok(operationDone(
                "projects/" + project + "/locations/" + location + "/operations/" + UUID.randomUUID(),
                Map.of())).build();
    }

    // ── Topics ────────────────────────────────────────────────────────────────

    @POST
    @Path("/clusters/{clusterId}/topics")
    public Response createTopic(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @PathParam("clusterId") String clusterId,
                                @QueryParam("topicId") String topicId,
                                Map<String, Object> body) {
        LOG.debugf("Kafka createTopic project=%s location=%s clusterId=%s topicId=%s",
                project, location, clusterId, topicId);
        if (topicId == null || topicId.isBlank()) {
            return gcpError(400, "topicId query parameter is required", "INVALID_ARGUMENT");
        }
        StoredTopic topic = service.createTopic(project, location, clusterId, topicId, body);
        return Response.ok(topic).build();
    }

    @GET
    @Path("/clusters/{clusterId}/topics/{topicId}")
    public Response getTopic(@PathParam("project") String project,
                             @PathParam("location") String location,
                             @PathParam("clusterId") String clusterId,
                             @PathParam("topicId") String topicId) {
        LOG.debugf("Kafka getTopic project=%s location=%s clusterId=%s topicId=%s",
                project, location, clusterId, topicId);
        StoredTopic topic = service.getTopic(project, location, clusterId, topicId);
        return Response.ok(topic).build();
    }

    @GET
    @Path("/clusters/{clusterId}/topics")
    public Response listTopics(@PathParam("project") String project,
                               @PathParam("location") String location,
                               @PathParam("clusterId") String clusterId) {
        LOG.debugf("Kafka listTopics project=%s location=%s clusterId=%s", project, location, clusterId);
        List<StoredTopic> topics = service.listTopics(project, location, clusterId);
        return Response.ok(Map.of("topics", topics)).build();
    }

    @PATCH
    @Path("/clusters/{clusterId}/topics/{topicId}")
    public Response updateTopic(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @PathParam("clusterId") String clusterId,
                                @PathParam("topicId") String topicId,
                                Map<String, Object> body) {
        LOG.debugf("Kafka updateTopic project=%s location=%s clusterId=%s topicId=%s",
                project, location, clusterId, topicId);
        StoredTopic topic = service.updateTopic(project, location, clusterId, topicId, body);
        return Response.ok(topic).build();
    }

    @DELETE
    @Path("/clusters/{clusterId}/topics/{topicId}")
    public Response deleteTopic(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @PathParam("clusterId") String clusterId,
                                @PathParam("topicId") String topicId) {
        LOG.debugf("Kafka deleteTopic project=%s location=%s clusterId=%s topicId=%s",
                project, location, clusterId, topicId);
        service.deleteTopic(project, location, clusterId, topicId);
        return Response.ok(Map.of()).build();
    }

    // ── Consumer Groups ───────────────────────────────────────────────────────

    @GET
    @Path("/clusters/{clusterId}/consumerGroups")
    public Response listConsumerGroups(@PathParam("project") String project,
                                       @PathParam("location") String location,
                                       @PathParam("clusterId") String clusterId) {
        LOG.debugf("Kafka listConsumerGroups project=%s location=%s clusterId=%s", project, location, clusterId);
        List<StoredConsumerGroup> groups = service.listConsumerGroups(project, location, clusterId);
        return Response.ok(Map.of("consumerGroups", groups)).build();
    }

    @GET
    @Path("/clusters/{clusterId}/consumerGroups/{groupId}")
    public Response getConsumerGroup(@PathParam("project") String project,
                                     @PathParam("location") String location,
                                     @PathParam("clusterId") String clusterId,
                                     @PathParam("groupId") String groupId) {
        LOG.debugf("Kafka getConsumerGroup project=%s location=%s clusterId=%s groupId=%s",
                project, location, clusterId, groupId);
        StoredConsumerGroup group = service.getConsumerGroup(project, location, clusterId, groupId);
        return Response.ok(group).build();
    }

    @PATCH
    @Path("/clusters/{clusterId}/consumerGroups/{groupId}")
    public Response updateConsumerGroup(@PathParam("project") String project,
                                        @PathParam("location") String location,
                                        @PathParam("clusterId") String clusterId,
                                        @PathParam("groupId") String groupId,
                                        StoredConsumerGroup body) {
        LOG.debugf("Kafka updateConsumerGroup project=%s location=%s clusterId=%s groupId=%s",
                project, location, clusterId, groupId);
        StoredConsumerGroup group = service.updateConsumerGroup(project, location, clusterId, groupId, body);
        return Response.ok(group).build();
    }

    @DELETE
    @Path("/clusters/{clusterId}/consumerGroups/{groupId}")
    public Response deleteConsumerGroup(@PathParam("project") String project,
                                        @PathParam("location") String location,
                                        @PathParam("clusterId") String clusterId,
                                        @PathParam("groupId") String groupId) {
        LOG.debugf("Kafka deleteConsumerGroup project=%s location=%s clusterId=%s groupId=%s",
                project, location, clusterId, groupId);
        service.deleteConsumerGroup(project, location, clusterId, groupId);
        return Response.ok(Map.of()).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Map<String, Object> operationDone(String resourceName, Object response) {
        String opName = resourceName.replaceAll("/clusters/[^/]+$", "") + "/operations/" + UUID.randomUUID();
        return Map.of(
                "name", opName,
                "done", true,
                "response", response);
    }

    private static Response gcpError(int code, String message, String status) {
        return Response.status(code)
                .entity(Map.of("error", Map.of("code", code, "message", message, "status", status)))
                .build();
    }
}

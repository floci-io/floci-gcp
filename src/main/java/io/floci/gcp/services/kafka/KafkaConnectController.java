package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.kafka.model.ConnectorState;
import io.floci.gcp.services.kafka.model.StoredConnectCluster;
import io.floci.gcp.services.kafka.model.StoredConnector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for {@code google.cloud.managedkafka.v1.ManagedKafkaConnect}, served beside
 * {@link KafkaController} on the same {@code /v1/projects/{p}/locations/{l}} root.
 *
 * <p>The Connect cluster RPCs return immediately-complete LROs ({@code done: true}), as the Kafka
 * cluster RPCs do, with the {@code response} carrying its {@code Any} type URL so a generated
 * client ({@code createConnectClusterAsync(...).get()}) can unpack it. Connector RPCs return the
 * resource directly, and the four lifecycle methods return their empty
 * {@code *ConnectorResponse} messages, as the proto declares.
 */
@Path("/v1/projects/{project}/locations/{location}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KafkaConnectController {

    private static final String CONNECT_CLUSTER_TYPE = "type.googleapis.com/google.cloud.managedkafka.v1.ConnectCluster";
    private static final String EMPTY_TYPE = "type.googleapis.com/google.protobuf.Empty";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final KafkaConnectService service;
    private final ObjectMapper objectMapper;

    @Inject
    public KafkaConnectController(KafkaConnectService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    // ── Connect clusters ──────────────────────────────────────────────────────

    @POST
    @Path("/connectClusters")
    public Response createConnectCluster(@PathParam("project") String project,
                                         @PathParam("location") String location,
                                         @QueryParam("connectClusterId") String connectClusterId,
                                         Map<String, Object> body) {
        if (connectClusterId == null || connectClusterId.isBlank()) {
            throw GcpException.invalidArgument("connectClusterId query parameter is required");
        }
        StoredConnectCluster cluster = service.createConnectCluster(project, location, connectClusterId, body);
        return Response.ok(operationDone(project, location, any(CONNECT_CLUSTER_TYPE, cluster))).build();
    }

    @GET
    @Path("/connectClusters/{connectClusterId}")
    public Response getConnectCluster(@PathParam("project") String project,
                                      @PathParam("location") String location,
                                      @PathParam("connectClusterId") String connectClusterId) {
        return Response.ok(service.getConnectCluster(project, location, connectClusterId)).build();
    }

    @GET
    @Path("/connectClusters")
    public Response listConnectClusters(@PathParam("project") String project,
                                        @PathParam("location") String location,
                                        @QueryParam("pageSize") Integer pageSize,
                                        @QueryParam("pageToken") String pageToken) {
        PageToken.Page<StoredConnectCluster> page = service.listConnectClusters(project, location, pageSize, pageToken);
        return Response.ok(listResponse("connectClusters", page)).build();
    }

    @PATCH
    @Path("/connectClusters/{connectClusterId}")
    public Response updateConnectCluster(@PathParam("project") String project,
                                         @PathParam("location") String location,
                                         @PathParam("connectClusterId") String connectClusterId,
                                         @QueryParam("updateMask") String updateMask,
                                         Map<String, Object> body) {
        StoredConnectCluster cluster =
                service.updateConnectCluster(project, location, connectClusterId, updateMask, body);
        return Response.ok(operationDone(project, location, any(CONNECT_CLUSTER_TYPE, cluster))).build();
    }

    @DELETE
    @Path("/connectClusters/{connectClusterId}")
    public Response deleteConnectCluster(@PathParam("project") String project,
                                         @PathParam("location") String location,
                                         @PathParam("connectClusterId") String connectClusterId) {
        service.deleteConnectCluster(project, location, connectClusterId);
        return Response.ok(operationDone(project, location, Map.of("@type", EMPTY_TYPE))).build();
    }

    // ── Connectors ────────────────────────────────────────────────────────────

    @POST
    @Path("/connectClusters/{connectClusterId}/connectors")
    public Response createConnector(@PathParam("project") String project,
                                    @PathParam("location") String location,
                                    @PathParam("connectClusterId") String connectClusterId,
                                    @QueryParam("connectorId") String connectorId,
                                    Map<String, Object> body) {
        if (connectorId == null || connectorId.isBlank()) {
            throw GcpException.invalidArgument("connectorId query parameter is required");
        }
        return Response.ok(service.createConnector(project, location, connectClusterId, connectorId, body)).build();
    }

    @GET
    @Path("/connectClusters/{connectClusterId}/connectors/{connectorId}")
    public Response getConnector(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @PathParam("connectClusterId") String connectClusterId,
                                 @PathParam("connectorId") String connectorId) {
        return Response.ok(service.getConnector(project, location, connectClusterId, connectorId)).build();
    }

    @GET
    @Path("/connectClusters/{connectClusterId}/connectors")
    public Response listConnectors(@PathParam("project") String project,
                                   @PathParam("location") String location,
                                   @PathParam("connectClusterId") String connectClusterId,
                                   @QueryParam("pageSize") Integer pageSize,
                                   @QueryParam("pageToken") String pageToken) {
        PageToken.Page<StoredConnector> page =
                service.listConnectors(project, location, connectClusterId, pageSize, pageToken);
        return Response.ok(listResponse("connectors", page)).build();
    }

    @PATCH
    @Path("/connectClusters/{connectClusterId}/connectors/{connectorId}")
    public Response updateConnector(@PathParam("project") String project,
                                    @PathParam("location") String location,
                                    @PathParam("connectClusterId") String connectClusterId,
                                    @PathParam("connectorId") String connectorId,
                                    @QueryParam("updateMask") String updateMask,
                                    Map<String, Object> body) {
        return Response.ok(service.updateConnector(project, location, connectClusterId, connectorId, updateMask, body))
                .build();
    }

    @DELETE
    @Path("/connectClusters/{connectClusterId}/connectors/{connectorId}")
    public Response deleteConnector(@PathParam("project") String project,
                                    @PathParam("location") String location,
                                    @PathParam("connectClusterId") String connectClusterId,
                                    @PathParam("connectorId") String connectorId) {
        service.deleteConnector(project, location, connectClusterId, connectorId);
        // DeleteConnector returns google.protobuf.Empty, which is `{}` over REST.
        return Response.ok(Map.of()).build();
    }

    // The connector id is bound as [^:/]+ so `.../connectors/{id}:pause` reaches the custom method
    // rather than being captured whole as an id. The request messages carry nothing but the name,
    // so the `{}` body the SDK sends (`body: "*"`) is accepted and ignored.

    @POST
    @Path("/connectClusters/{connectClusterId}/connectors/{connectorId: [^:/]+}:pause")
    public Response pauseConnector(@PathParam("project") String project,
                                   @PathParam("location") String location,
                                   @PathParam("connectClusterId") String connectClusterId,
                                   @PathParam("connectorId") String connectorId,
                                      Map<String, Object> body) {
        service.transitionConnector(project, location, connectClusterId, connectorId, ConnectorState.PAUSED);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/connectClusters/{connectClusterId}/connectors/{connectorId: [^:/]+}:resume")
    public Response resumeConnector(@PathParam("project") String project,
                                    @PathParam("location") String location,
                                    @PathParam("connectClusterId") String connectClusterId,
                                    @PathParam("connectorId") String connectorId,
                                      Map<String, Object> body) {
        service.transitionConnector(project, location, connectClusterId, connectorId, ConnectorState.RUNNING);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/connectClusters/{connectClusterId}/connectors/{connectorId: [^:/]+}:restart")
    public Response restartConnector(@PathParam("project") String project,
                                     @PathParam("location") String location,
                                     @PathParam("connectClusterId") String connectClusterId,
                                     @PathParam("connectorId") String connectorId,
                                      Map<String, Object> body) {
        service.restartConnector(project, location, connectClusterId, connectorId);
        return Response.ok(Map.of()).build();
    }

    @POST
    @Path("/connectClusters/{connectClusterId}/connectors/{connectorId: [^:/]+}:stop")
    public Response stopConnector(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @PathParam("connectClusterId") String connectClusterId,
                                  @PathParam("connectorId") String connectorId,
                                      Map<String, Object> body) {
        service.transitionConnector(project, location, connectClusterId, connectorId, ConnectorState.STOPPED);
        return Response.ok(Map.of()).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** An already-complete {@code google.longrunning.Operation}, the shape the Kafka cluster RPCs return. */
    private static Map<String, Object> operationDone(String project, String location, Object response) {
        return Map.of(
                "name", "projects/" + project + "/locations/" + location + "/operations/" + UUID.randomUUID(),
                "done", true,
                "response", response);
    }

    /** The JSON form of a {@code google.protobuf.Any}: the message's fields plus its type URL under {@code @type}. */
    private Map<String, Object> any(String typeUrl, Object message) {
        Map<String, Object> any = new LinkedHashMap<>();
        any.put("@type", typeUrl);
        any.putAll(objectMapper.convertValue(message, MAP_TYPE));
        return any;
    }

    private static Map<String, Object> listResponse(String field, PageToken.Page<?> page) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(field, page.items());
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return response;
    }
}

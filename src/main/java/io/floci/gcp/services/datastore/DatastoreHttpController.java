package io.floci.gcp.services.datastore;

import com.google.datastore.v1.*;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GqlParser;
import io.floci.gcp.services.datastore.model.StoredEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * REST controller for the Datastore v1 API using binary protobuf transport.
 *
 * The google-cloud-datastore SDK v2.25.2 uses HTTP-only transport via HttpDatastoreRpc.
 * Requests are sent as POST /v1/projects/{projectId}:{method} with body:
 * Content-Type: application/x-protobuf (serialized proto)
 * Responses are expected as: Content-Type: application/x-protobuf (serialized proto)
 */
@Path("/v1/projects")
@ApplicationScoped
public class DatastoreHttpController {

    private static final Logger LOG = Logger.getLogger(DatastoreHttpController.class);

    @Inject
    DatastoreService service;

    @POST
    @Path("/{rest:.*}")
    @Consumes("application/x-protobuf")
    @Produces("application/x-protobuf")
    public Response handle(@PathParam("rest") String rest, byte[] body) {
        int colonIdx = rest.lastIndexOf(':');
        if (colonIdx < 0) {
            return protoError(Response.Status.NOT_FOUND, "Unknown Datastore path: " + rest);
        }
        String projectId = rest.substring(0, colonIdx);
        String method = rest.substring(colonIdx + 1);
        LOG.debugf("Datastore HTTP %s project=%s", method, projectId);

        try {
            byte[] responseBytes = switch (method) {
                case "lookup" -> handleLookup(projectId, body);
                case "commit" -> handleCommit(projectId, body);
                case "runQuery" -> handleRunQuery(projectId, body);
                case "runAggregationQuery" -> handleRunAggregationQuery(projectId, body);
                case "beginTransaction" -> handleBeginTransaction(projectId, body);
                case "rollback" -> handleRollback(projectId, body);
                case "allocateIds" -> handleAllocateIds(projectId, body);
                case "reserveIds" -> handleReserveIds(projectId, body);
                default -> null;
            };
            if (responseBytes == null) {
                return protoError(Response.Status.NOT_FOUND, "Unknown method: " + method);
            }
            return Response.ok(responseBytes, "application/x-protobuf").build();
        } catch (GcpException e) {
            return protoError(toHttpStatus(e), e.getMessage());
        } catch (Exception e) {
            LOG.warnf("Datastore %s failed: %s", method, e.getMessage());
            return protoError(Response.Status.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private byte[] handleLookup(String projectId, byte[] body) throws Exception {
        LookupRequest request = LookupRequest.parseFrom(body);
        Instant readTime = Instant.now();
        LookupResponse.Builder resp = LookupResponse.newBuilder()
                .setReadTime(DatastoreService.toTimestamp(readTime.toString()));

        for (Key key : request.getKeysList()) {
            Optional<StoredEntity> found = service.lookupEntity(projectId, key);
            if (found.isPresent()) {
                resp.addFound(EntityResult.newBuilder()
                        .setEntity(service.toProto(found.get()))
                        .setVersion(found.get().getVersion())
                        .build());
            } else {
                resp.addMissing(EntityResult.newBuilder()
                        .setEntity(Entity.newBuilder().setKey(key))
                        .build());
            }
        }
        return resp.build().toByteArray();
    }

    private byte[] handleCommit(String projectId, byte[] body) throws Exception {
        CommitRequest request = CommitRequest.parseFrom(body);
        Instant commitTime = Instant.now();
        CommitResponse.Builder resp = CommitResponse.newBuilder()
                .setCommitTime(DatastoreService.toTimestamp(commitTime.toString()));

        for (Mutation mutation : request.getMutationsList()) {
            DatastoreService.MutationApplyResult result =
                    service.applyMutation(projectId, mutation, commitTime);
            resp.addMutationResults(result.mutationResult());
        }
        return resp.build().toByteArray();
    }

    private byte[] handleRunQuery(String projectId, byte[] body) throws Exception {
        RunQueryRequest request = RunQueryRequest.parseFrom(body);
        Instant readTime = Instant.now();

        Query query;
        if (request.hasGqlQuery()) {
            query = GqlParser.toStructuredQuery(request.getGqlQuery());
        } else {
            query = request.getQuery();
        }

        List<StoredEntity> results = service.runQuery(projectId, request.getPartitionId(), query);

        QueryResultBatch.Builder batch = QueryResultBatch.newBuilder()
                .setEntityResultType(EntityResult.ResultType.FULL)
                .setMoreResults(QueryResultBatch.MoreResultsType.NO_MORE_RESULTS)
                .setReadTime(DatastoreService.toTimestamp(readTime.toString()));

        for (StoredEntity entity : results) {
            batch.addEntityResults(EntityResult.newBuilder()
                    .setEntity(service.toProto(entity))
                    .setVersion(entity.getVersion())
                    .build());
        }
        return RunQueryResponse.newBuilder().setBatch(batch.build()).build().toByteArray();
    }

    private byte[] handleRunAggregationQuery(String projectId, byte[] body) throws Exception {
        RunAggregationQueryRequest request = RunAggregationQueryRequest.parseFrom(body);
        Instant readTime = Instant.now();
        List<StoredEntity> results = service.runQuery(
                projectId,
                request.getPartitionId(),
                request.getAggregationQuery().getNestedQuery());

        AggregationResultBatch.Builder batch = AggregationResultBatch.newBuilder()
                .setMoreResults(QueryResultBatch.MoreResultsType.NO_MORE_RESULTS)
                .setReadTime(DatastoreService.toTimestamp(readTime.toString()));

        AggregationResult.Builder agg = AggregationResult.newBuilder();
        for (AggregationQuery.Aggregation aggregation : request.getAggregationQuery().getAggregationsList()) {
            String alias = aggregation.getAlias().isEmpty() ? "property_1" : aggregation.getAlias();
            agg.putAggregateProperties(alias,
                    Value.newBuilder().setIntegerValue(results.size()).build());
        }
        batch.addAggregationResults(agg.build());

        return RunAggregationQueryResponse.newBuilder().setBatch(batch.build()).build().toByteArray();
    }

    private byte[] handleBeginTransaction(String projectId, byte[] body) throws Exception {
        BeginTransactionRequest.parseFrom(body);
        byte[] txId = service.beginTransaction();
        return BeginTransactionResponse.newBuilder()
                .setTransaction(com.google.protobuf.ByteString.copyFrom(txId))
                .build().toByteArray();
    }

    private byte[] handleRollback(String projectId, byte[] body) throws Exception {
        RollbackRequest.parseFrom(body);
        return RollbackResponse.getDefaultInstance().toByteArray();
    }

    private byte[] handleAllocateIds(String projectId, byte[] body) throws Exception {
        AllocateIdsRequest request = AllocateIdsRequest.parseFrom(body);
        List<Key> allocated = service.allocateIds(projectId, request.getKeysList());
        AllocateIdsResponse.Builder resp = AllocateIdsResponse.newBuilder();
        allocated.forEach(resp::addKeys);
        return resp.build().toByteArray();
    }

    private byte[] handleReserveIds(String projectId, byte[] body) throws Exception {
        ReserveIdsRequest.parseFrom(body);
        return ReserveIdsResponse.getDefaultInstance().toByteArray();
    }

    private Response protoError(Response.Status status, String message) {
        com.google.rpc.Status errStatus = com.google.rpc.Status.newBuilder()
                .setCode(status.getStatusCode())
                .setMessage(message != null ? message : status.getReasonPhrase())
                .build();
        return Response.status(status)
                .entity(errStatus.toByteArray())
                .type("application/x-protobuf")
                .build();
    }

    private Response.Status toHttpStatus(GcpException e) {
        return switch (e.getGrpcCode().value()) {
            case 5 -> Response.Status.NOT_FOUND;        // NOT_FOUND
            case 6 -> Response.Status.CONFLICT;         // ALREADY_EXISTS
            case 7 -> Response.Status.FORBIDDEN;        // PERMISSION_DENIED
            case 3 -> Response.Status.BAD_REQUEST;      // INVALID_ARGUMENT
            default -> Response.Status.INTERNAL_SERVER_ERROR;
        };
    }
}

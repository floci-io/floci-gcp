package io.floci.gcp.services.datastore;

import com.google.datastore.v1.AllocateIdsRequest;
import com.google.datastore.v1.AllocateIdsResponse;
import com.google.datastore.v1.BeginTransactionRequest;
import com.google.datastore.v1.BeginTransactionResponse;
import com.google.datastore.v1.CommitRequest;
import com.google.datastore.v1.CommitResponse;
import com.google.datastore.v1.Entity;
import com.google.datastore.v1.EntityResult;
import com.google.datastore.v1.Key;
import com.google.datastore.v1.LookupRequest;
import com.google.datastore.v1.LookupResponse;
import com.google.datastore.v1.Mutation;
import com.google.datastore.v1.QueryResultBatch;
import com.google.datastore.v1.ReserveIdsRequest;
import com.google.datastore.v1.ReserveIdsResponse;
import com.google.datastore.v1.RollbackRequest;
import com.google.datastore.v1.RollbackResponse;
import com.google.datastore.v1.RunAggregationQueryRequest;
import com.google.datastore.v1.RunAggregationQueryResponse;
import com.google.datastore.v1.RunQueryRequest;
import com.google.datastore.v1.RunQueryResponse;
import com.google.protobuf.ByteString;
import io.floci.gcp.core.common.GcpGrpcController;
import io.floci.gcp.services.datastore.model.StoredEntity;
import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class DatastoreController implements BindableService {

    private static final Logger LOG = Logger.getLogger(DatastoreController.class);
    private static final String SERVICE_NAME = "google.datastore.v1.Datastore";

    private static final MethodDescriptor<LookupRequest, LookupResponse> LOOKUP_METHOD =
            MethodDescriptor.<LookupRequest, LookupResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(SERVICE_NAME + "/Lookup")
                    .setRequestMarshaller(ProtoUtils.marshaller(LookupRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(LookupResponse.getDefaultInstance()))
                    .build();

    private static final MethodDescriptor<RunQueryRequest, RunQueryResponse> RUN_QUERY_METHOD =
            MethodDescriptor.<RunQueryRequest, RunQueryResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(SERVICE_NAME + "/RunQuery")
                    .setRequestMarshaller(ProtoUtils.marshaller(RunQueryRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(RunQueryResponse.getDefaultInstance()))
                    .build();

    private static final MethodDescriptor<CommitRequest, CommitResponse> COMMIT_METHOD =
            MethodDescriptor.<CommitRequest, CommitResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(SERVICE_NAME + "/Commit")
                    .setRequestMarshaller(ProtoUtils.marshaller(CommitRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(CommitResponse.getDefaultInstance()))
                    .build();

    private static final MethodDescriptor<BeginTransactionRequest, BeginTransactionResponse> BEGIN_TX_METHOD =
            MethodDescriptor.<BeginTransactionRequest, BeginTransactionResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(SERVICE_NAME + "/BeginTransaction")
                    .setRequestMarshaller(ProtoUtils.marshaller(BeginTransactionRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(BeginTransactionResponse.getDefaultInstance()))
                    .build();

    private static final MethodDescriptor<RollbackRequest, RollbackResponse> ROLLBACK_METHOD =
            MethodDescriptor.<RollbackRequest, RollbackResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(SERVICE_NAME + "/Rollback")
                    .setRequestMarshaller(ProtoUtils.marshaller(RollbackRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(RollbackResponse.getDefaultInstance()))
                    .build();

    private static final MethodDescriptor<AllocateIdsRequest, AllocateIdsResponse> ALLOCATE_IDS_METHOD =
            MethodDescriptor.<AllocateIdsRequest, AllocateIdsResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(SERVICE_NAME + "/AllocateIds")
                    .setRequestMarshaller(ProtoUtils.marshaller(AllocateIdsRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(AllocateIdsResponse.getDefaultInstance()))
                    .build();

    private static final MethodDescriptor<ReserveIdsRequest, ReserveIdsResponse> RESERVE_IDS_METHOD =
            MethodDescriptor.<ReserveIdsRequest, ReserveIdsResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(SERVICE_NAME + "/ReserveIds")
                    .setRequestMarshaller(ProtoUtils.marshaller(ReserveIdsRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(ReserveIdsResponse.getDefaultInstance()))
                    .build();

    private static final MethodDescriptor<RunAggregationQueryRequest, RunAggregationQueryResponse> RUN_AGG_METHOD =
            MethodDescriptor.<RunAggregationQueryRequest, RunAggregationQueryResponse>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(SERVICE_NAME + "/RunAggregationQuery")
                    .setRequestMarshaller(ProtoUtils.marshaller(RunAggregationQueryRequest.getDefaultInstance()))
                    .setResponseMarshaller(ProtoUtils.marshaller(RunAggregationQueryResponse.getDefaultInstance()))
                    .build();

    private final DatastoreService service;

    DatastoreController(DatastoreService service) {
        this.service = service;
    }

    @Override
    public ServerServiceDefinition bindService() {
        return ServerServiceDefinition.builder(SERVICE_NAME)
                .addMethod(LOOKUP_METHOD, ServerCalls.asyncUnaryCall(this::lookup))
                .addMethod(RUN_QUERY_METHOD, ServerCalls.asyncUnaryCall(this::runQuery))
                .addMethod(COMMIT_METHOD, ServerCalls.asyncUnaryCall(this::commit))
                .addMethod(BEGIN_TX_METHOD, ServerCalls.asyncUnaryCall(this::beginTransaction))
                .addMethod(ROLLBACK_METHOD, ServerCalls.asyncUnaryCall(this::rollback))
                .addMethod(ALLOCATE_IDS_METHOD, ServerCalls.asyncUnaryCall(this::allocateIds))
                .addMethod(RESERVE_IDS_METHOD, ServerCalls.asyncUnaryCall(this::reserveIds))
                .addMethod(RUN_AGG_METHOD, ServerCalls.asyncUnaryCall(this::runAggregationQuery))
                .build();
    }

    private void lookup(LookupRequest request, StreamObserver<LookupResponse> responseObserver) {
        LOG.debugf("lookup project=%s keys=%d", request.getProjectId(), request.getKeysCount());
        try {
            Instant readTime = Instant.now();
            LookupResponse.Builder resp = LookupResponse.newBuilder()
                    .setReadTime(DatastoreService.toTimestamp(readTime.toString()));

            for (Key key : request.getKeysList()) {
                Optional<StoredEntity> found = service.lookupEntity(request.getProjectId(), key);
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

            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("lookup failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    private void runQuery(RunQueryRequest request, StreamObserver<RunQueryResponse> responseObserver) {
        LOG.debugf("runQuery project=%s", request.getProjectId());
        try {
            Instant readTime = Instant.now();
            List<StoredEntity> results = service.runQuery(
                    request.getProjectId(), request.getPartitionId(), request.getQuery());

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

            responseObserver.onNext(RunQueryResponse.newBuilder().setBatch(batch.build()).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("runQuery failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    private void commit(CommitRequest request, StreamObserver<CommitResponse> responseObserver) {
        LOG.debugf("commit project=%s mutations=%d", request.getProjectId(), request.getMutationsCount());
        try {
            Instant commitTime = Instant.now();
            CommitResponse.Builder resp = CommitResponse.newBuilder()
                    .setCommitTime(DatastoreService.toTimestamp(commitTime.toString()));

            for (Mutation mutation : request.getMutationsList()) {
                DatastoreService.MutationApplyResult result =
                        service.applyMutation(request.getProjectId(), mutation, commitTime);
                resp.addMutationResults(result.mutationResult());
            }

            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("commit failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    private void beginTransaction(BeginTransactionRequest request,
            StreamObserver<BeginTransactionResponse> responseObserver) {
        LOG.debugf("beginTransaction project=%s", request.getProjectId());
        try {
            byte[] txId = service.beginTransaction();
            responseObserver.onNext(BeginTransactionResponse.newBuilder()
                    .setTransaction(ByteString.copyFrom(txId))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("beginTransaction failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    private void rollback(RollbackRequest request, StreamObserver<RollbackResponse> responseObserver) {
        LOG.debugf("rollback project=%s", request.getProjectId());
        responseObserver.onNext(RollbackResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    private void allocateIds(AllocateIdsRequest request,
            StreamObserver<AllocateIdsResponse> responseObserver) {
        LOG.debugf("allocateIds project=%s keys=%d", request.getProjectId(), request.getKeysCount());
        try {
            List<Key> allocated = service.allocateIds(request.getProjectId(), request.getKeysList());
            AllocateIdsResponse.Builder resp = AllocateIdsResponse.newBuilder();
            allocated.forEach(resp::addKeys);
            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("allocateIds failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    private void reserveIds(ReserveIdsRequest request,
            StreamObserver<ReserveIdsResponse> responseObserver) {
        LOG.debugf("reserveIds project=%s", request.getProjectId());
        responseObserver.onNext(ReserveIdsResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    private void runAggregationQuery(RunAggregationQueryRequest request,
            StreamObserver<RunAggregationQueryResponse> responseObserver) {
        LOG.debugf("runAggregationQuery project=%s", request.getProjectId());
        responseObserver.onNext(RunAggregationQueryResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }
}

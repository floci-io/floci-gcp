package io.floci.gcp.services.firestore;

import com.google.firestore.v1.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import com.google.rpc.Status;
import io.floci.gcp.core.common.GcpGrpcController;
import io.floci.gcp.services.firestore.model.StoredDocument;
import io.floci.gcp.services.firestore.model.StoredValue;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class FirestoreController extends FirestoreGrpc.FirestoreImplBase {

    private static final Logger LOG = Logger.getLogger(FirestoreController.class);

    private final FirestoreService service;

    FirestoreController(FirestoreService service) {
        this.service = service;
    }

    @Override
    public void commit(CommitRequest request, StreamObserver<CommitResponse> responseObserver) {
        LOG.debugf("commit database=%s writes=%d", request.getDatabase(), request.getWritesCount());
        try {
            Instant commitTime = Instant.now();
            CommitResponse.Builder response = CommitResponse.newBuilder()
                    .setCommitTime(toTimestamp(commitTime.toString()));

            for (Write write : request.getWritesList()) {
                FirestoreService.WriteCommitResult result = service.applyWrite(write, commitTime);
                WriteResult.Builder wr = WriteResult.newBuilder();
                if (result.updateTime() != null) {
                    wr.setUpdateTime(toTimestamp(result.updateTime()));
                }
                response.addWriteResults(wr.build());
            }

            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("commit failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getDocument(GetDocumentRequest request, StreamObserver<Document> responseObserver) {
        LOG.debugf("getDocument name=%s", request.getName());
        try {
            StoredDocument stored = service.getDocument(request.getName())
                    .orElseThrow(() -> io.floci.gcp.core.common.GcpException.notFound(
                            "Document not found: " + request.getName()));
            responseObserver.onNext(toProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("getDocument failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void batchGetDocuments(BatchGetDocumentsRequest request,
            StreamObserver<BatchGetDocumentsResponse> responseObserver) {
        LOG.debugf("batchGetDocuments database=%s docs=%d", request.getDatabase(), request.getDocumentsCount());
        try {
            Instant readTime = Instant.now();
            for (String docName : request.getDocumentsList()) {
                Optional<StoredDocument> stored = service.getDocument(docName);
                BatchGetDocumentsResponse.Builder resp = BatchGetDocumentsResponse.newBuilder()
                        .setReadTime(toTimestamp(readTime.toString()));
                if (stored.isPresent()) {
                    resp.setFound(toProto(stored.get()));
                } else {
                    resp.setMissing(docName);
                }
                responseObserver.onNext(resp.build());
            }
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("batchGetDocuments failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void runQuery(RunQueryRequest request, StreamObserver<RunQueryResponse> responseObserver) {
        LOG.debugf("runQuery parent=%s", request.getParent());
        try {
            Instant readTime = Instant.now();
            List<StoredDocument> results = service.runQuery(request.getParent(), request.getStructuredQuery());

            for (StoredDocument doc : results) {
                responseObserver.onNext(RunQueryResponse.newBuilder()
                        .setDocument(toProto(doc))
                        .setReadTime(toTimestamp(readTime.toString()))
                        .build());
            }

            // terminal message
            responseObserver.onNext(RunQueryResponse.newBuilder()
                    .setReadTime(toTimestamp(readTime.toString()))
                    .setDone(true)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("runQuery failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void beginTransaction(BeginTransactionRequest request,
            StreamObserver<BeginTransactionResponse> responseObserver) {
        LOG.debugf("beginTransaction database=%s", request.getDatabase());
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

    @Override
    public void rollback(RollbackRequest request, StreamObserver<Empty> responseObserver) {
        LOG.debugf("rollback database=%s", request.getDatabase());
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void listDocuments(ListDocumentsRequest request, StreamObserver<ListDocumentsResponse> responseObserver) {
        LOG.debugf("listDocuments parent=%s collection=%s", request.getParent(), request.getCollectionId());
        try {
            String prefix = request.getParent() + "/" + request.getCollectionId() + "/";
            List<StoredDocument> docs = service.runQuery(request.getParent(),
                    StructuredQuery.newBuilder()
                            .addFrom(StructuredQuery.CollectionSelector.newBuilder()
                                    .setCollectionId(request.getCollectionId())
                                    .build())
                            .build());
            ListDocumentsResponse.Builder resp = ListDocumentsResponse.newBuilder();
            docs.forEach(d -> resp.addDocuments(toProto(d)));
            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("listDocuments failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void updateDocument(UpdateDocumentRequest request, StreamObserver<Document> responseObserver) {
        LOG.debugf("updateDocument name=%s", request.getDocument().getName());
        try {
            Write write = Write.newBuilder()
                    .setUpdate(request.getDocument())
                    .setUpdateMask(request.getUpdateMask())
                    .build();
            service.applyWrite(write, Instant.now());
            StoredDocument stored = service.getDocument(request.getDocument().getName())
                    .orElseThrow();
            responseObserver.onNext(toProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("updateDocument failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void deleteDocument(DeleteDocumentRequest request, StreamObserver<Empty> responseObserver) {
        LOG.debugf("deleteDocument name=%s", request.getName());
        try {
            Write write = Write.newBuilder().setDelete(request.getName()).build();
            service.applyWrite(write, Instant.now());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("deleteDocument failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void createDocument(CreateDocumentRequest request, StreamObserver<Document> responseObserver) {
        LOG.debugf("createDocument parent=%s collection=%s", request.getParent(), request.getCollectionId());
        try {
            String docId = request.getDocumentId().isEmpty()
                    ? java.util.UUID.randomUUID().toString()
                    : request.getDocumentId();
            String name = request.getParent() + "/" + request.getCollectionId() + "/" + docId;
            Document doc = request.getDocument().toBuilder().setName(name).build();
            Write write = Write.newBuilder().setUpdate(doc).build();
            service.applyWrite(write, Instant.now());
            responseObserver.onNext(toProto(service.getDocument(name).orElseThrow()));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("createDocument failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listCollectionIds(ListCollectionIdsRequest request,
            StreamObserver<ListCollectionIdsResponse> responseObserver) {
        LOG.debugf("listCollectionIds parent=%s", request.getParent());
        try {
            List<String> ids = service.listCollectionIds(request.getParent());
            responseObserver.onNext(ListCollectionIdsResponse.newBuilder()
                    .addAllCollectionIds(ids).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("listCollectionIds failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void batchWrite(BatchWriteRequest request, StreamObserver<BatchWriteResponse> responseObserver) {
        LOG.debugf("batchWrite database=%s writes=%d", request.getDatabase(), request.getWritesCount());
        try {
            Instant commitTime = Instant.now();
            BatchWriteResponse.Builder resp = BatchWriteResponse.newBuilder();
            for (Write write : request.getWritesList()) {
                FirestoreService.WriteCommitResult result = service.applyWrite(write, commitTime);
                WriteResult.Builder wr = WriteResult.newBuilder();
                if (result.updateTime() != null) {
                    wr.setUpdateTime(toTimestamp(result.updateTime()));
                }
                resp.addWriteResults(wr.build());
                resp.addStatus(com.google.rpc.Status.newBuilder().setCode(0).build());
            }
            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("batchWrite failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void runAggregationQuery(RunAggregationQueryRequest request,
            StreamObserver<RunAggregationQueryResponse> responseObserver) {
        LOG.debugf("runAggregationQuery parent=%s", request.getParent());
        try {
            Instant readTime = Instant.now();
            AggregationResult.Builder agg = AggregationResult.newBuilder();
            if (request.hasStructuredAggregationQuery()) {
                StructuredAggregationQuery saq = request.getStructuredAggregationQuery();
                long count = service.countDocuments(request.getParent(),
                        saq.hasStructuredQuery() ? saq.getStructuredQuery()
                                : com.google.firestore.v1.StructuredQuery.getDefaultInstance());
                for (StructuredAggregationQuery.Aggregation aggregation : saq.getAggregationsList()) {
                    String alias = aggregation.getAlias().isEmpty() ? "field_1" : aggregation.getAlias();
                    agg.putAggregateFields(alias,
                            Value.newBuilder().setIntegerValue(count).build());
                }
            }
            responseObserver.onNext(RunAggregationQueryResponse.newBuilder()
                    .setResult(agg.build())
                    .setReadTime(toTimestamp(readTime.toString()))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("runAggregationQuery failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void partitionQuery(PartitionQueryRequest request,
            StreamObserver<PartitionQueryResponse> responseObserver) {
        responseObserver.onNext(PartitionQueryResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public StreamObserver<WriteRequest> write(StreamObserver<WriteResponse> responseObserver) {
        return new StreamObserver<>() {
            public void onNext(WriteRequest req) {
                try {
                    Instant now = Instant.now();
                    WriteResponse.Builder resp = WriteResponse.newBuilder()
                            .setStreamId(req.getStreamId())
                            .setCommitTime(toTimestamp(now.toString()));
                    for (Write w : req.getWritesList()) {
                        FirestoreService.WriteCommitResult r = service.applyWrite(w, now);
                        WriteResult.Builder wr = WriteResult.newBuilder();
                        if (r.updateTime() != null) {
                            wr.setUpdateTime(toTimestamp(r.updateTime()));
                        }
                        resp.addWriteResults(wr.build());
                    }
                    responseObserver.onNext(resp.build());
                } catch (Exception e) {
                    LOG.warnf("write stream error: %s", e.getMessage());
                    responseObserver.onError(GcpGrpcController.grpcException(e));
                }
            }
            public void onError(Throwable t) { LOG.debugf("write stream closed by client: %s", t.getMessage()); }
            public void onCompleted() { responseObserver.onCompleted(); }
        };
    }

    @Override
    public StreamObserver<ListenRequest> listen(StreamObserver<ListenResponse> responseObserver) {
        return new StreamObserver<>() {
            private volatile boolean initialized = false;

            public void onNext(ListenRequest req) {
                try {
                    if (req.hasAddTarget() && !initialized) {
                        initialized = true;
                        Target target = req.getAddTarget();
                        int targetId = target.getTargetId();
                        Instant now = Instant.now();
                        Timestamp readTime = toTimestamp(now.toString());

                        responseObserver.onNext(ListenResponse.newBuilder()
                                .setTargetChange(TargetChange.newBuilder()
                                        .setTargetChangeType(TargetChange.TargetChangeType.ADD)
                                        .addTargetIds(targetId)
                                        .setReadTime(readTime)
                                        .build())
                                .build());

                        if (target.hasQuery()) {
                            Target.QueryTarget qt = target.getQuery();
                            List<StoredDocument> docs = service.runQuery(qt.getParent(),
                                    qt.hasStructuredQuery() ? qt.getStructuredQuery()
                                            : StructuredQuery.getDefaultInstance());
                            for (StoredDocument doc : docs) {
                                responseObserver.onNext(ListenResponse.newBuilder()
                                        .setDocumentChange(DocumentChange.newBuilder()
                                                .setDocument(toProto(doc))
                                                .addTargetIds(targetId)
                                                .build())
                                        .build());
                            }
                        }

                        responseObserver.onNext(ListenResponse.newBuilder()
                                .setTargetChange(TargetChange.newBuilder()
                                        .setTargetChangeType(TargetChange.TargetChangeType.CURRENT)
                                        .addTargetIds(targetId)
                                        .setReadTime(readTime)
                                        .build())
                                .build());
                    }
                } catch (Exception e) {
                    LOG.warnf("listen error: %s", e.getMessage());
                    responseObserver.onError(GcpGrpcController.grpcException(e));
                }
            }

            public void onError(Throwable t) {
                LOG.debugf("listen stream closed by client: %s", t.getMessage());
            }

            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static Document toProto(StoredDocument stored) {
        Document.Builder builder = Document.newBuilder()
                .setName(stored.getName())
                .setCreateTime(toTimestamp(stored.getCreateTime()))
                .setUpdateTime(toTimestamp(stored.getUpdateTime()));
        if (stored.getFields() != null) {
            stored.getFields().forEach((k, v) -> builder.putFields(k, v.toProto()));
        }
        return builder.build();
    }

    static Timestamp toTimestamp(String isoTime) {
        if (isoTime == null) return Timestamp.getDefaultInstance();
        try {
            Instant instant = Instant.parse(isoTime);
            return Timestamp.newBuilder()
                    .setSeconds(instant.getEpochSecond())
                    .setNanos(instant.getNano())
                    .build();
        } catch (Exception e) {
            return Timestamp.getDefaultInstance();
        }
    }
}

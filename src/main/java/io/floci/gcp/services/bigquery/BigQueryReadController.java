package io.floci.gcp.services.bigquery;

import com.google.cloud.bigquery.storage.v1.BigQueryReadGrpc;
import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.ReadRowsRequest;
import com.google.cloud.bigquery.storage.v1.ReadRowsResponse;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import com.google.cloud.bigquery.storage.v1.SplitReadStreamRequest;
import com.google.cloud.bigquery.storage.v1.SplitReadStreamResponse;
import io.floci.gcp.core.common.GcpGrpcController;
import io.grpc.stub.StreamObserver;
import io.vertx.core.Vertx;

/**
 * gRPC endpoint of the BigQuery Storage Read API. Session creation runs with the project of the
 * table being read, on a worker thread: its snapshot query waits on the SQL engine sidecar, which
 * reads the staged rows back over HTTP from this server, so blocking the event loop that has to
 * accept that callback would stall the call until its deadline.
 */
public class BigQueryReadController extends BigQueryReadGrpc.BigQueryReadImplBase {

    private final BigQueryStorageRead read;
    private final Vertx vertx;

    public BigQueryReadController(BigQueryStorageRead read, Vertx vertx) {
        this.read = read;
        this.vertx = vertx;
    }

    @Override
    public void createReadSession(CreateReadSessionRequest request, StreamObserver<ReadSession> observer) {
        vertx.executeBlocking(() -> BigQueryGrpcContext.withProject(BigQueryStorageRead.projectOf(request),
                        () -> read.createReadSession(request)), false)
                .onSuccess(session -> {
                    observer.onNext(session);
                    observer.onCompleted();
                })
                .onFailure(t -> GcpGrpcController.grpcError(observer, t));
    }

    @Override
    public void readRows(ReadRowsRequest request, StreamObserver<ReadRowsResponse> observer) {
        try {
            read.readRows(request, observer);
        } catch (Throwable t) {
            GcpGrpcController.grpcError(observer, t);
        }
    }

    @Override
    public void splitReadStream(SplitReadStreamRequest request, StreamObserver<SplitReadStreamResponse> observer) {
        try {
            read.checkStreamExists(request.getName());
            // An empty response: "the original stream can no longer be split".
            observer.onNext(SplitReadStreamResponse.getDefaultInstance());
            observer.onCompleted();
        } catch (Throwable t) {
            GcpGrpcController.grpcError(observer, t);
        }
    }
}

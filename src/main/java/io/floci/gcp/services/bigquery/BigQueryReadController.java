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

/**
 * gRPC endpoint of the BigQuery Storage Read API. Session creation runs with the project of the
 * table being read.
 */
public class BigQueryReadController extends BigQueryReadGrpc.BigQueryReadImplBase {

    private final BigQueryStorageRead read;

    public BigQueryReadController(BigQueryStorageRead read) {
        this.read = read;
    }

    @Override
    public void createReadSession(CreateReadSessionRequest request, StreamObserver<ReadSession> observer) {
        try {
            ReadSession session = BigQueryGrpcContext.withProject(BigQueryStorageRead.projectOf(request),
                    () -> read.createReadSession(request));
            observer.onNext(session);
            observer.onCompleted();
        } catch (Throwable t) {
            GcpGrpcController.grpcError(observer, t);
        }
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

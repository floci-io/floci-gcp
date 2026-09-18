package io.floci.gcp.services.bigquery;

import com.google.cloud.bigquery.storage.v1.AppendRowsRequest;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.BatchCommitWriteStreamsRequest;
import com.google.cloud.bigquery.storage.v1.BatchCommitWriteStreamsResponse;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteGrpc;
import com.google.cloud.bigquery.storage.v1.CreateWriteStreamRequest;
import com.google.cloud.bigquery.storage.v1.FinalizeWriteStreamRequest;
import com.google.cloud.bigquery.storage.v1.FinalizeWriteStreamResponse;
import com.google.cloud.bigquery.storage.v1.FlushRowsRequest;
import com.google.cloud.bigquery.storage.v1.FlushRowsResponse;
import com.google.cloud.bigquery.storage.v1.GetWriteStreamRequest;
import com.google.cloud.bigquery.storage.v1.WriteStream;
import io.floci.gcp.core.common.GcpGrpcController;
import io.grpc.stub.StreamObserver;

import java.util.function.Supplier;

/**
 * gRPC endpoint of the BigQuery Storage Write API. Every call runs with the project of the table
 * it writes to. AppendRows answers each request in order; a rejected append is an in-stream
 * error response, while a malformed connection (no destination) ends the call.
 */
public class BigQueryWriteController extends BigQueryWriteGrpc.BigQueryWriteImplBase {

    private final BigQueryStorageWrite write;

    public BigQueryWriteController(BigQueryStorageWrite write) {
        this.write = write;
    }

    @Override
    public void createWriteStream(CreateWriteStreamRequest request, StreamObserver<WriteStream> observer) {
        unary(observer, request.getParent(), () -> write.createWriteStream(request));
    }

    @Override
    public void getWriteStream(GetWriteStreamRequest request, StreamObserver<WriteStream> observer) {
        unary(observer, request.getName(), () -> write.getWriteStream(request));
    }

    @Override
    public void finalizeWriteStream(FinalizeWriteStreamRequest request,
                                    StreamObserver<FinalizeWriteStreamResponse> observer) {
        unary(observer, request.getName(), () -> write.finalizeWriteStream(request.getName()));
    }

    @Override
    public void batchCommitWriteStreams(BatchCommitWriteStreamsRequest request,
                                        StreamObserver<BatchCommitWriteStreamsResponse> observer) {
        unary(observer, request.getParent(), () -> write.batchCommitWriteStreams(request));
    }

    @Override
    public void flushRows(FlushRowsRequest request, StreamObserver<FlushRowsResponse> observer) {
        unary(observer, request.getWriteStream(), () -> write.flushRows(request));
    }

    @Override
    public StreamObserver<AppendRowsRequest> appendRows(StreamObserver<AppendRowsResponse> observer) {
        BigQueryStorageWrite.Connection connection = new BigQueryStorageWrite.Connection();
        return new StreamObserver<>() {
            private boolean closed;

            @Override
            public void onNext(AppendRowsRequest request) {
                if (closed) {
                    return;
                }
                String destination = request.getWriteStream().isEmpty() ? connection.writeStream
                        : request.getWriteStream();
                try {
                    observer.onNext(respond(request, destination));
                } catch (Throwable t) {
                    closed = true;
                    GcpGrpcController.grpcError(observer, t);
                }
            }

            private AppendRowsResponse respond(AppendRowsRequest request, String destination) {
                try {
                    return BigQueryGrpcContext.withProject(
                            destination != null ? BigQueryStorageWrite.projectOf(destination) : null,
                            () -> write.append(connection, request));
                } catch (BigQueryStorageWrite.AppendException e) {
                    return e.toResponse(destination);
                }
            }

            @Override
            public void onError(Throwable t) {
                closed = true;
            }

            @Override
            public void onCompleted() {
                if (!closed) {
                    closed = true;
                    observer.onCompleted();
                }
            }
        };
    }

    private static <T> void unary(StreamObserver<T> observer, String name, Supplier<T> action) {
        try {
            T response = BigQueryGrpcContext.withProject(BigQueryStorageWrite.projectOf(name), action);
            observer.onNext(response);
            observer.onCompleted();
        } catch (Throwable t) {
            GcpGrpcController.grpcError(observer, t);
        }
    }
}

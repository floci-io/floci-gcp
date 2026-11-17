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
import io.floci.gcp.core.common.RequestScopes;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * gRPC endpoint of the BigQuery Storage Write API. Every call runs with the project of the table
 * it writes to. AppendRows requests are processed serially on bounded workers because table writes can block;
 * responses are sent on the original gRPC context. A rejected append is an in-stream error response, while a
 * malformed connection (no destination) ends the call.
 *
 * <p>An AppendRows call takes its requests one at a time through gRPC inbound flow control
 * ({@link ServerCallStreamObserver#disableAutoRequest()}) and asks for the next message only once the
 * previous one has started, so a client that pipelines appends is held back by HTTP/2 instead of having its
 * call closed, which is what a hard cap on pending requests did.
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
        return new AppendRowsObserver(observer, new BigQueryStorageWrite.Connection(), Vertx.currentContext());
    }

    private final class AppendRowsObserver implements StreamObserver<AppendRowsRequest> {
        private final StreamObserver<AppendRowsResponse> observer;
        private final ServerCallStreamObserver<AppendRowsResponse> call;
        private final BigQueryStorageWrite.Connection connection;
        private final Context grpcContext;
        private final Deque<AppendRowsRequest> pending = new ArrayDeque<>();

        private boolean closed;
        private boolean inFlight;
        private boolean awaitingSlot;
        private boolean completionRequested;

        private AppendRowsObserver(StreamObserver<AppendRowsResponse> observer,
                                   BigQueryStorageWrite.Connection connection, Context grpcContext) {
            this.observer = observer;
            this.call = observer instanceof ServerCallStreamObserver<AppendRowsResponse> call ? call : null;
            this.connection = connection;
            this.grpcContext = grpcContext;
            if (call != null) {
                // One request in hand at a time: the next message is requested once this one starts, so a
                // pipelining client waits in HTTP/2 flow control rather than in a queue that could overflow.
                call.disableAutoRequest();
                call.request(1);
            }
        }

        @Override
        public void onNext(AppendRowsRequest request) {
            boolean start = false;
            synchronized (this) {
                if (closed || completionRequested) {
                    return;
                }
                pending.addLast(request);
                start = !inFlight && !awaitingSlot;
            }
            if (start) {
                startNext();
            }
        }

        @Override
        public void onError(Throwable t) {
            synchronized (this) {
                closed = true;
                awaitingSlot = false;
                pending.clear();
            }
        }

        @Override
        public void onCompleted() {
            boolean complete = false;
            synchronized (this) {
                if (closed) {
                    return;
                }
                completionRequested = true;
                if (!inFlight && !awaitingSlot && pending.isEmpty()) {
                    closed = true;
                    complete = true;
                }
            }
            if (complete) {
                onGrpcContext(observer::onCompleted);
            }
        }

        /**
         * Starts the head of {@code pending} once a worker slot is free, then asks for the next message. A
         * full pool parks the connection instead of rejecting the request: the slot is taken here, not when
         * the request arrives, so a parked connection holds no slot and the client is not told to retry.
         */
        private void startNext() {
            AppendRowsRequest next = null;
            boolean stopped = false;
            synchronized (this) {
                if (closed || inFlight) {
                    return;
                }
                if (write.stopping()) {
                    closed = true;
                    awaitingSlot = false;
                    pending.clear();
                    stopped = true;
                } else if (pending.isEmpty()) {
                    awaitingSlot = false;
                } else if (write.acquireAppendRequestSlotOrWait(this::slotFreed)) {
                    awaitingSlot = false;
                    inFlight = true;
                    next = pending.removeFirst();
                } else {
                    awaitingSlot = true;
                }
            }
            if (next != null) {
                submit(next);
                requestNext();
            } else if (stopped) {
                onGrpcContext(() -> observer.onError(Status.UNAVAILABLE
                        .withDescription("The emulator is shutting down").asRuntimeException()));
            }
        }

        /** The registered waiter: its registration is spent, so a slot taken elsewhere registers again. */
        private void slotFreed() {
            synchronized (this) {
                awaitingSlot = false;
            }
            startNext();
        }

        /** Asks the client for one more request, which flow control then holds back until the next slot. */
        private void requestNext() {
            if (call == null) {
                return;
            }
            boolean request;
            synchronized (this) {
                request = !closed && !completionRequested;
            }
            if (request) {
                call.request(1);
            }
        }

        private void submit(AppendRowsRequest request) {
            try {
                write.appendExecutor().execute(() -> process(request));
            } catch (RejectedExecutionException e) {
                boolean report;
                synchronized (this) {
                    // As in finishRequest, only the first terminal callback reaches the client.
                    report = !closed;
                    closed = true;
                    inFlight = false;
                    awaitingSlot = false;
                    pending.clear();
                }
                write.releaseAppendRequestSlot();
                if (report) {
                    onGrpcContext(() -> observer.onError(Status.UNAVAILABLE
                            .withDescription("BigQuery Storage Write is shutting down").asRuntimeException()));
                }
            }
        }

        private void process(AppendRowsRequest request) {
            AppendRowsResponse response;
            try {
                response = respond(request);
            } catch (Throwable failure) {
                onGrpcContext(() -> finishRequest(failure));
                return;
            }
            onGrpcContext(() -> {
                Throwable callbackFailure = null;
                try {
                    synchronized (this) {
                        if (!closed) {
                            observer.onNext(response);
                        }
                    }
                } catch (Throwable failure) {
                    callbackFailure = failure;
                }
                finishRequest(callbackFailure);
            });
        }

        private void finishRequest(Throwable failure) {
            write.releaseAppendRequestSlot();
            boolean report = false;
            boolean more = false;
            boolean complete = false;
            synchronized (this) {
                inFlight = false;
                if (failure != null && !closed) {
                    // Only the first terminal callback reaches the client: a call the client already
                    // cancelled, or one this observer closed, has had its onError or onCompleted.
                    closed = true;
                    report = true;
                }
                if (closed) {
                    awaitingSlot = false;
                    pending.clear();
                } else if (!pending.isEmpty()) {
                    more = true;
                } else if (completionRequested) {
                    closed = true;
                    complete = true;
                }
            }
            if (report) {
                GcpGrpcController.grpcError(observer, failure);
            } else if (more) {
                startNext();
            } else if (complete) {
                observer.onCompleted();
            }
        }

        private void onGrpcContext(Runnable action) {
            // The project-context interceptor keeps request scope on the original Vert.x context for the call.
            if (grpcContext == null || Vertx.currentContext() == grpcContext) {
                action.run();
            } else {
                grpcContext.runOnContext(ignored -> action.run());
            }
        }

        private AppendRowsResponse respond(AppendRowsRequest request) {
            String destination = request.getWriteStream().isEmpty() ? connection.writeStream
                    : request.getWriteStream();
            try {
                return RequestScopes.callAs(
                        destination != null ? BigQueryStorageWrite.projectOf(destination) : null,
                        () -> write.append(connection, request));
            } catch (BigQueryStorageWrite.AppendException e) {
                return e.toResponse(destination);
            }
        }
    }

    private static <T> void unary(StreamObserver<T> observer, String name, Supplier<T> action) {
        try {
            T response = RequestScopes.callAs(BigQueryStorageWrite.projectOf(name), action);
            observer.onNext(response);
            observer.onCompleted();
        } catch (Throwable t) {
            GcpGrpcController.grpcError(observer, t);
        }
    }
}

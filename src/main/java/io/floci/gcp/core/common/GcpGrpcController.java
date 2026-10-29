package io.floci.gcp.core.common;

import com.google.protobuf.Any;
import com.google.rpc.ErrorInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

/**
 * Base class for gRPC service implementations. Provides project-ID extraction
 * from gRPC metadata and uniform exception → {@link StatusRuntimeException} mapping.
 *
 * <p>Subclasses call {@link #error(StreamObserver, Throwable)} in catch blocks
 * instead of constructing StatusRuntimeException manually.
 */
public abstract class GcpGrpcController {

    private static final Logger LOG = Logger.getLogger(GcpGrpcController.class);

    protected <T> void error(StreamObserver<T> observer, Throwable t) {
        observer.onError(toStatusException(t));
    }

    protected StatusRuntimeException toStatusException(Throwable t) {
        return grpcException(t);
    }

    public static <T> void grpcError(StreamObserver<T> observer, Throwable t) {
        observer.onError(grpcException(t));
    }

    public static StatusRuntimeException grpcException(Throwable t) {
        if (t instanceof GcpException ex) {
            if (ex.getErrorInfo() != null) {
                GcpException.ErrorInfoDetail info = ex.getErrorInfo();
                ErrorInfo detail = ErrorInfo.newBuilder().setReason(info.reason()).setDomain(info.domain())
                        .putAllMetadata(info.metadata()).build();
                // com.google.rpc.Status is the wire detail; io.grpc.Status handles ordinary errors here.
                com.google.rpc.Status status = com.google.rpc.Status.newBuilder()
                        .setCode(ex.getGrpcCode().value()).setMessage(ex.getMessage())
                        .addDetails(Any.pack(detail)).build();
                return StatusProto.toStatusRuntimeException(status);
            }
            return ex.getGrpcCode().toStatus()
                    .withDescription(ex.getMessage())
                    .asRuntimeException();
        }
        if (t instanceof StatusRuntimeException sre) {
            return sre;
        }
        LOG.errorv(t, "Unexpected error in gRPC handler");
        return Status.INTERNAL.withDescription("Internal error").asRuntimeException();
    }
}

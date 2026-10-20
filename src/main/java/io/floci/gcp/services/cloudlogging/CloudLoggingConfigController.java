package io.floci.gcp.services.cloudlogging;

import com.google.logging.v2.ConfigServiceV2Grpc;
import com.google.logging.v2.CreateSinkRequest;
import com.google.logging.v2.DeleteSinkRequest;
import com.google.logging.v2.GetSinkRequest;
import com.google.logging.v2.ListSinksRequest;
import com.google.logging.v2.ListSinksResponse;
import com.google.logging.v2.LogSink;
import com.google.logging.v2.UpdateSinkRequest;
import com.google.protobuf.Empty;
import io.floci.gcp.core.common.GcpGrpcController;
import io.grpc.stub.StreamObserver;

public class CloudLoggingConfigController extends ConfigServiceV2Grpc.ConfigServiceV2ImplBase {

    private final CloudLoggingSinkService service;

    CloudLoggingConfigController(CloudLoggingSinkService service) {
        this.service = service;
    }

    @Override
    public void createSink(CreateSinkRequest request, StreamObserver<LogSink> observer) {
        try {
            observer.onNext(service.createSink(request.getParent(), request.getSink(), request.getUniqueWriterIdentity()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void getSink(GetSinkRequest request, StreamObserver<LogSink> observer) {
        try {
            observer.onNext(service.getSink(request.getSinkName()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void listSinks(ListSinksRequest request, StreamObserver<ListSinksResponse> observer) {
        try {
            observer.onNext(service.listSinks(request.getParent(), request.getPageSize(), request.getPageToken()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void updateSink(UpdateSinkRequest request, StreamObserver<LogSink> observer) {
        try {
            observer.onNext(service.updateSink(request.getSinkName(), request.getSink(),
                    request.getUpdateMask(), request.getUniqueWriterIdentity()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void deleteSink(DeleteSinkRequest request, StreamObserver<Empty> observer) {
        try {
            service.deleteSink(request.getSinkName());
            observer.onNext(Empty.getDefaultInstance());
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }
}

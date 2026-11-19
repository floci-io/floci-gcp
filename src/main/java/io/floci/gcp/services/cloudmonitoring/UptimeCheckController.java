package io.floci.gcp.services.cloudmonitoring;

import com.google.monitoring.v3.CreateUptimeCheckConfigRequest;
import com.google.monitoring.v3.DeleteUptimeCheckConfigRequest;
import com.google.monitoring.v3.GetUptimeCheckConfigRequest;
import com.google.monitoring.v3.ListUptimeCheckConfigsRequest;
import com.google.monitoring.v3.ListUptimeCheckConfigsResponse;
import com.google.monitoring.v3.ListUptimeCheckIpsRequest;
import com.google.monitoring.v3.ListUptimeCheckIpsResponse;
import com.google.monitoring.v3.UpdateUptimeCheckConfigRequest;
import com.google.monitoring.v3.UptimeCheckConfig;
import com.google.monitoring.v3.UptimeCheckServiceGrpc;
import com.google.protobuf.Empty;
import io.floci.gcp.core.common.GcpGrpcController;
import io.grpc.stub.StreamObserver;

public class UptimeCheckController extends UptimeCheckServiceGrpc.UptimeCheckServiceImplBase {

    private final UptimeCheckService service;

    UptimeCheckController(UptimeCheckService service) {
        this.service = service;
    }

    @Override
    public void listUptimeCheckConfigs(ListUptimeCheckConfigsRequest request,
            StreamObserver<ListUptimeCheckConfigsResponse> observer) {
        try {
            observer.onNext(service.list(request.getParent(), request.getPageSize(), request.getPageToken()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void getUptimeCheckConfig(GetUptimeCheckConfigRequest request, StreamObserver<UptimeCheckConfig> observer) {
        try {
            observer.onNext(service.get(request.getName()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void createUptimeCheckConfig(CreateUptimeCheckConfigRequest request, StreamObserver<UptimeCheckConfig> observer) {
        try {
            observer.onNext(service.create(request.getParent(), request.getUptimeCheckConfig()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void updateUptimeCheckConfig(UpdateUptimeCheckConfigRequest request, StreamObserver<UptimeCheckConfig> observer) {
        try {
            observer.onNext(service.update(request.getUptimeCheckConfig(), request.getUpdateMask()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void deleteUptimeCheckConfig(DeleteUptimeCheckConfigRequest request, StreamObserver<Empty> observer) {
        try {
            service.delete(request.getName());
            observer.onNext(Empty.getDefaultInstance());
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void listUptimeCheckIps(ListUptimeCheckIpsRequest request, StreamObserver<ListUptimeCheckIpsResponse> observer) {
        try {
            observer.onNext(service.listIps(request.getPageSize(), request.getPageToken()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }
}

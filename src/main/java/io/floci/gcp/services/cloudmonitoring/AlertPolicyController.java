package io.floci.gcp.services.cloudmonitoring;

import com.google.monitoring.v3.AlertPolicy;
import com.google.monitoring.v3.AlertPolicyServiceGrpc;
import com.google.monitoring.v3.CreateAlertPolicyRequest;
import com.google.monitoring.v3.DeleteAlertPolicyRequest;
import com.google.monitoring.v3.GetAlertPolicyRequest;
import com.google.monitoring.v3.ListAlertPoliciesRequest;
import com.google.monitoring.v3.ListAlertPoliciesResponse;
import com.google.monitoring.v3.UpdateAlertPolicyRequest;
import com.google.protobuf.Empty;
import io.floci.gcp.core.common.GcpGrpcController;
import io.grpc.stub.StreamObserver;

public class AlertPolicyController extends AlertPolicyServiceGrpc.AlertPolicyServiceImplBase {

    private final AlertPolicyService service;

    AlertPolicyController(AlertPolicyService service) {
        this.service = service;
    }

    @Override
    public void listAlertPolicies(ListAlertPoliciesRequest request, StreamObserver<ListAlertPoliciesResponse> observer) {
        try {
            observer.onNext(service.list(request.getName(), request.getFilter(), request.getPageSize(),
                    request.getPageToken()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void getAlertPolicy(GetAlertPolicyRequest request, StreamObserver<AlertPolicy> observer) {
        try {
            observer.onNext(service.get(request.getName()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void createAlertPolicy(CreateAlertPolicyRequest request, StreamObserver<AlertPolicy> observer) {
        try {
            observer.onNext(service.create(request.getName(), request.getAlertPolicy()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void updateAlertPolicy(UpdateAlertPolicyRequest request, StreamObserver<AlertPolicy> observer) {
        try {
            observer.onNext(service.update(request.getAlertPolicy(), request.getUpdateMask()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void deleteAlertPolicy(DeleteAlertPolicyRequest request, StreamObserver<Empty> observer) {
        try {
            service.delete(request.getName());
            observer.onNext(Empty.getDefaultInstance());
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }
}

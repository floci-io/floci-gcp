package io.floci.gcp.services.cloudlogging;

import com.google.logging.v2.CreateLogMetricRequest;
import com.google.logging.v2.DeleteLogMetricRequest;
import com.google.logging.v2.GetLogMetricRequest;
import com.google.logging.v2.ListLogMetricsRequest;
import com.google.logging.v2.ListLogMetricsResponse;
import com.google.logging.v2.LogMetric;
import com.google.logging.v2.MetricsServiceV2Grpc;
import com.google.logging.v2.UpdateLogMetricRequest;
import com.google.protobuf.Empty;
import io.floci.gcp.core.common.GcpGrpcController;
import io.grpc.stub.StreamObserver;

public class CloudLoggingMetricsController extends MetricsServiceV2Grpc.MetricsServiceV2ImplBase {

    private final CloudLoggingMetricService service;

    CloudLoggingMetricsController(CloudLoggingMetricService service) {
        this.service = service;
    }

    @Override
    public void listLogMetrics(ListLogMetricsRequest request, StreamObserver<ListLogMetricsResponse> observer) {
        try {
            observer.onNext(service.listLogMetrics(request.getParent(), request.getPageSize(), request.getPageToken()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void getLogMetric(GetLogMetricRequest request, StreamObserver<LogMetric> observer) {
        try {
            observer.onNext(service.getLogMetric(request.getMetricName()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void createLogMetric(CreateLogMetricRequest request, StreamObserver<LogMetric> observer) {
        try {
            observer.onNext(service.createLogMetric(request.getParent(), request.getMetric()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void updateLogMetric(UpdateLogMetricRequest request, StreamObserver<LogMetric> observer) {
        try {
            observer.onNext(service.updateLogMetric(request.getMetricName(), request.getMetric()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void deleteLogMetric(DeleteLogMetricRequest request, StreamObserver<Empty> observer) {
        try {
            service.deleteLogMetric(request.getMetricName());
            observer.onNext(Empty.getDefaultInstance());
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }
}

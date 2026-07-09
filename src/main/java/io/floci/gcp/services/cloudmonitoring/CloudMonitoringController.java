package io.floci.gcp.services.cloudmonitoring;

import com.google.api.MetricDescriptor;
import com.google.api.MonitoredResourceDescriptor;
import com.google.monitoring.v3.*;
import com.google.protobuf.Empty;
import io.floci.gcp.core.common.GcpGrpcController;
import io.floci.gcp.core.common.PageToken;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

public class CloudMonitoringController extends MetricServiceGrpc.MetricServiceImplBase {

    private static final Logger LOG = Logger.getLogger(CloudMonitoringController.class);

    private final CloudMonitoringService service;

    public CloudMonitoringController(CloudMonitoringService service) {
        this.service = service;
    }

    @Override
    public void createMetricDescriptor(CreateMetricDescriptorRequest request, StreamObserver<MetricDescriptor> responseObserver) {
        LOG.debugf("createMetricDescriptor name=%s type=%s", request.getName(), request.getMetricDescriptor().getType());
        try {
            MetricDescriptor created = service.createMetricDescriptor(request.getName(), request.getMetricDescriptor());
            responseObserver.onNext(created);
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getMetricDescriptor(GetMetricDescriptorRequest request, StreamObserver<MetricDescriptor> responseObserver) {
        LOG.debugf("getMetricDescriptor name=%s", request.getName());
        try {
            MetricDescriptor desc = service.getMetricDescriptor(request.getName());
            responseObserver.onNext(desc);
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listMetricDescriptors(ListMetricDescriptorsRequest request, StreamObserver<ListMetricDescriptorsResponse> responseObserver) {
        LOG.debugf("listMetricDescriptors name=%s filter=%s", request.getName(), request.getFilter());
        try {
            PageToken.Page<MetricDescriptor> page = service.listMetricDescriptors(
                    request.getName(), request.getFilter(), request.getPageSize(), request.getPageToken());
            ListMetricDescriptorsResponse.Builder response = ListMetricDescriptorsResponse.newBuilder()
                    .addAllMetricDescriptors(page.items());
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void deleteMetricDescriptor(DeleteMetricDescriptorRequest request, StreamObserver<Empty> responseObserver) {
        LOG.debugf("deleteMetricDescriptor name=%s", request.getName());
        try {
            service.deleteMetricDescriptor(request.getName());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listMonitoredResourceDescriptors(ListMonitoredResourceDescriptorsRequest request,
                                                 StreamObserver<ListMonitoredResourceDescriptorsResponse> responseObserver) {
        LOG.debugf("listMonitoredResourceDescriptors name=%s", request.getName());
        try {
            PageToken.Page<MonitoredResourceDescriptor> page = service.listMonitoredResourceDescriptors(
                    request.getName(), request.getPageSize(), request.getPageToken());
            ListMonitoredResourceDescriptorsResponse.Builder response = ListMonitoredResourceDescriptorsResponse.newBuilder()
                    .addAllResourceDescriptors(page.items());
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getMonitoredResourceDescriptor(GetMonitoredResourceDescriptorRequest request,
                                               StreamObserver<MonitoredResourceDescriptor> responseObserver) {
        LOG.debugf("getMonitoredResourceDescriptor name=%s", request.getName());
        try {
            MonitoredResourceDescriptor desc = service.getMonitoredResourceDescriptor(request.getName());
            responseObserver.onNext(desc);
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void createTimeSeries(CreateTimeSeriesRequest request, StreamObserver<Empty> responseObserver) {
        LOG.debugf("createTimeSeries name=%s count=%d", request.getName(), request.getTimeSeriesCount());
        try {
            service.createTimeSeries(request.getName(), request.getTimeSeriesList());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listTimeSeries(ListTimeSeriesRequest request, StreamObserver<ListTimeSeriesResponse> responseObserver) {
        LOG.debugf("listTimeSeries name=%s filter=%s", request.getName(), request.getFilter());
        try {
            PageToken.Page<TimeSeries> page = service.listTimeSeries(
                    request.getName(),
                    request.getFilter(),
                    request.getInterval(),
                    request.getAggregation(),
                    request.getView().name(),
                    request.getPageSize(),
                    request.getPageToken()
            );
            ListTimeSeriesResponse.Builder response = ListTimeSeriesResponse.newBuilder()
                    .addAllTimeSeries(page.items());
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }
}

package io.floci.gcp.services.cloudmonitoring;

import com.google.monitoring.v3.CreateNotificationChannelRequest;
import com.google.monitoring.v3.DeleteNotificationChannelRequest;
import com.google.monitoring.v3.GetNotificationChannelDescriptorRequest;
import com.google.monitoring.v3.GetNotificationChannelRequest;
import com.google.monitoring.v3.GetNotificationChannelVerificationCodeRequest;
import com.google.monitoring.v3.GetNotificationChannelVerificationCodeResponse;
import com.google.monitoring.v3.ListNotificationChannelDescriptorsRequest;
import com.google.monitoring.v3.ListNotificationChannelDescriptorsResponse;
import com.google.monitoring.v3.ListNotificationChannelsRequest;
import com.google.monitoring.v3.ListNotificationChannelsResponse;
import com.google.monitoring.v3.NotificationChannel;
import com.google.monitoring.v3.NotificationChannelDescriptor;
import com.google.monitoring.v3.NotificationChannelServiceGrpc;
import com.google.monitoring.v3.SendNotificationChannelVerificationCodeRequest;
import com.google.monitoring.v3.UpdateNotificationChannelRequest;
import com.google.monitoring.v3.VerifyNotificationChannelRequest;
import com.google.protobuf.Empty;
import io.floci.gcp.core.common.GcpGrpcController;
import io.grpc.stub.StreamObserver;

public class NotificationChannelController extends NotificationChannelServiceGrpc.NotificationChannelServiceImplBase {

    private final NotificationChannelService service;

    NotificationChannelController(NotificationChannelService service) {
        this.service = service;
    }

    @Override
    public void listNotificationChannelDescriptors(ListNotificationChannelDescriptorsRequest request,
            StreamObserver<ListNotificationChannelDescriptorsResponse> observer) {
        try {
            observer.onNext(service.listDescriptors(request.getName(), request.getPageSize(), request.getPageToken()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void getNotificationChannelDescriptor(GetNotificationChannelDescriptorRequest request,
            StreamObserver<NotificationChannelDescriptor> observer) {
        try {
            observer.onNext(service.getDescriptor(request.getName()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void listNotificationChannels(ListNotificationChannelsRequest request,
            StreamObserver<ListNotificationChannelsResponse> observer) {
        try {
            observer.onNext(service.list(request.getName(), request.getFilter(), request.getPageSize(),
                    request.getPageToken()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void getNotificationChannel(GetNotificationChannelRequest request,
            StreamObserver<NotificationChannel> observer) {
        try {
            observer.onNext(service.get(request.getName()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void createNotificationChannel(CreateNotificationChannelRequest request,
            StreamObserver<NotificationChannel> observer) {
        try {
            observer.onNext(service.create(request.getName(), request.getNotificationChannel()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void updateNotificationChannel(UpdateNotificationChannelRequest request,
            StreamObserver<NotificationChannel> observer) {
        try {
            observer.onNext(service.update(request.getNotificationChannel(), request.getUpdateMask()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void deleteNotificationChannel(DeleteNotificationChannelRequest request, StreamObserver<Empty> observer) {
        try {
            service.delete(request.getName(), request.getForce());
            observer.onNext(Empty.getDefaultInstance());
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void sendNotificationChannelVerificationCode(SendNotificationChannelVerificationCodeRequest request,
            StreamObserver<Empty> observer) {
        try {
            service.sendVerificationCode(request.getName());
            observer.onNext(Empty.getDefaultInstance());
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void getNotificationChannelVerificationCode(GetNotificationChannelVerificationCodeRequest request,
            StreamObserver<GetNotificationChannelVerificationCodeResponse> observer) {
        try {
            observer.onNext(service.getVerificationCode(request.getName(),
                    request.hasExpireTime() ? request.getExpireTime() : null));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void verifyNotificationChannel(VerifyNotificationChannelRequest request,
            StreamObserver<NotificationChannel> observer) {
        try {
            observer.onNext(service.verify(request.getName(), request.getCode()));
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }
}

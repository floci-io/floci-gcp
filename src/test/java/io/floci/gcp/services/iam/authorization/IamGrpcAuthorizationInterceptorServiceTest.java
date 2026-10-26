package io.floci.gcp.services.iam.authorization;

import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
import com.google.protobuf.Message;
import io.floci.gcp.core.common.GcpException;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IamGrpcAuthorizationInterceptorServiceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void extensionServiceIsAuthorizedBeforeItsRequestReachesTheHandler(boolean denied) throws Exception {
        IamAuthorizationAdapter adapter = mock(IamAuthorizationAdapter.class);
        when(adapter.restControllers()).thenReturn(Set.of());
        when(adapter.grpcServices()).thenReturn(Set.of("example.v1.Service"));
        IamOperation operation = new IamOperation("Read", "projects/p/examples/e");
        when(adapter.grpcOperation(eq("Read"), any(Message.class))).thenAnswer(invocation -> {
            GetIamPolicyRequest request = invocation.getArgument(1);
            return new IamOperation("Read", request.getResource());
        });
        IamAuthorizationService authorization = mock(IamAuthorizationService.class);
        when(authorization.enabled()).thenReturn(true);
        when(authorization.applies("Bearer example", adapter)).thenReturn(true);
        if (denied) {
            doThrow(GcpException.permissionDenied("example.resources.get denied"))
                    .when(authorization).authorize("Bearer example", adapter, operation);
        }
        Instance<IamAuthorizationService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(authorization);
        IamGrpcAuthorizationInterceptor interceptor = new IamGrpcAuthorizationInterceptor(instance,
                new IamAuthorizationRegistry(List.of(adapter)));
        ServerCall<GetIamPolicyRequest, Policy> call = mock(ServerCall.class);
        when(call.getMethodDescriptor()).thenReturn(MethodDescriptor.<GetIamPolicyRequest, Policy>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("example.v1.Service/Read")
                .setRequestMarshaller(ProtoUtils.marshaller(GetIamPolicyRequest.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(Policy.getDefaultInstance())).build());
        ServerCallHandler<GetIamPolicyRequest, Policy> handler = mock(ServerCallHandler.class);
        ServerCall.Listener<GetIamPolicyRequest> downstream = mock(ServerCall.Listener.class);
        when(handler.startCall(any(), any())).thenReturn(downstream);
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer example");
        ServerCall.Listener<GetIamPolicyRequest> listener = interceptor.interceptCall(call, headers, handler);
        GetIamPolicyRequest request = GetIamPolicyRequest.newBuilder().setResource(operation.resource()).build();
        listener.onMessage(request);
        listener.onHalfClose();
        verify(authorization).authorize("Bearer example", adapter, operation);
        verify(adapter).grpcOperation("Read", request);
        if (denied) {
            verify(call).close(argThat(status -> status.getCode() == Status.Code.PERMISSION_DENIED), any());
            verify(downstream, never()).onMessage(any());
            verify(downstream, never()).onHalfClose();
            verify(downstream).onCancel();
        } else {
            verify(downstream).onMessage(request);
            verify(downstream).onHalfClose();
            verify(call, never()).close(any(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void handlerExceptionPropagatesWithoutTheInterceptorClosingTheCall(boolean enabled) {
        IamAuthorizationAdapter adapter = mock(IamAuthorizationAdapter.class);
        when(adapter.restControllers()).thenReturn(Set.of());
        when(adapter.grpcServices()).thenReturn(Set.of("example.v1.Service"));
        when(adapter.grpcOperation(eq("Read"), any(Message.class)))
                .thenReturn(new IamOperation("Read", "projects/p/examples/e"));
        IamAuthorizationService authorization = mock(IamAuthorizationService.class);
        when(authorization.enabled()).thenReturn(enabled);
        when(authorization.applies("Bearer example", adapter)).thenReturn(true);
        Instance<IamAuthorizationService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(authorization);
        IamGrpcAuthorizationInterceptor interceptor = new IamGrpcAuthorizationInterceptor(instance,
                new IamAuthorizationRegistry(List.of(adapter)));
        ServerCall<GetIamPolicyRequest, Policy> call = mock(ServerCall.class);
        when(call.getMethodDescriptor()).thenReturn(MethodDescriptor.<GetIamPolicyRequest, Policy>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("example.v1.Service/Read")
                .setRequestMarshaller(ProtoUtils.marshaller(GetIamPolicyRequest.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(Policy.getDefaultInstance())).build());
        ServerCallHandler<GetIamPolicyRequest, Policy> handler = mock(ServerCallHandler.class);
        ServerCall.Listener<GetIamPolicyRequest> downstream = mock(ServerCall.Listener.class);
        when(handler.startCall(any(), any())).thenReturn(downstream);
        RuntimeException failure = new IllegalStateException("handler failed");
        doThrow(failure).when(downstream).onMessage(any());
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer example");
        ServerCall.Listener<GetIamPolicyRequest> listener = interceptor.interceptCall(call, headers, handler);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> listener.onMessage(
                GetIamPolicyRequest.newBuilder().setResource("projects/p/examples/e").build()));

        assertSame(failure, thrown);
        verify(call, never()).close(any(), any());
        verify(downstream, never()).onCancel();
        listener.onHalfClose();
        verify(downstream).onHalfClose();
    }
}

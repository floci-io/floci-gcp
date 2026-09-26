package io.floci.gcp.services.iam.authorization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IamGrpcAuthorizationInterceptorServiceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void extensionServiceIsAuthorizedBeforeItsRequestReachesTheHandler(boolean denied) {
        IamAuthorizationAdapter adapter = mock(IamAuthorizationAdapter.class);
        when(adapter.restControllers()).thenReturn(Set.of());
        when(adapter.grpcServices()).thenReturn(Set.of("example.v1.Service"));
        IamOperation operation = new IamOperation("Read", "projects/p/examples/e");
        when(adapter.grpcOperation(eq("Read"), any(JsonNode.class))).thenAnswer(invocation -> {
            JsonNode request = invocation.getArgument(1);
            return new IamOperation("Read", request.path("resource").asText());
        });
        IamAuthorizationService authorization = mock(IamAuthorizationService.class);
        when(authorization.enabled()).thenReturn(true);
        when(authorization.applies("Bearer example")).thenReturn(true);
        if (denied) {
            doThrow(GcpException.permissionDenied("example.resources.get denied"))
                    .when(authorization).authorize("Bearer example", adapter, operation);
        }
        Instance<IamAuthorizationService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(authorization);
        IamGrpcAuthorizationInterceptor interceptor = new IamGrpcAuthorizationInterceptor(instance,
                new IamAuthorizationRegistry(List.of(adapter)), new ObjectMapper());
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
}

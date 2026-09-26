package io.floci.gcp.services.iam.authorization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpGrpcController;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.quarkus.grpc.GlobalInterceptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Optional;

/** Installed on the actual Vert.x gRPC bridge, including dynamically registered services. */
@GlobalInterceptor
@ApplicationScoped
public class IamGrpcAuthorizationInterceptor implements ServerInterceptor {
    static final Context.Key<String> AUTHORIZATION = Context.key("iam-authorization");
    static final Context.Key<Boolean> GRPC_REQUEST = Context.key("iam-grpc-request");
    private static final Metadata.Key<String> AUTHORIZATION_HEADER =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String IAM_POLICY_SERVICE = "google.iam.v1.IAMPolicy";

    private final Instance<IamAuthorizationService> authorization;
    private final IamAuthorizationRegistry registry;
    private final ObjectMapper mapper;

    @Inject
    public IamGrpcAuthorizationInterceptor(Instance<IamAuthorizationService> authorization,
            IamAuthorizationRegistry registry, ObjectMapper mapper) {
        this.authorization = authorization;
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String credential = headers.get(AUTHORIZATION_HEADER);
        Context context = Context.current().withValue(AUTHORIZATION, credential).withValue(GRPC_REQUEST, true);
        return Contexts.interceptCall(context, call, headers, (contextCall, contextHeaders) -> {
            ServerCall.Listener<ReqT> listener = next.startCall(contextCall, contextHeaders);
            return new SimpleForwardingServerCallListener<>(listener) {
                private boolean rejected;

                @Override
                public void onMessage(ReqT message) {
                    if (rejected) {
                        return;
                    }
                    try {
                        String service = call.getMethodDescriptor().getServiceName();
                        Optional<IamAuthorizationAdapter> adapter = registry.grpc(service);
                        boolean mixin = IAM_POLICY_SERVICE.equals(service);
                        if ((adapter.isPresent() || mixin) && authorization.get().enabled()
                                && authorization.get().applies(credential)) {
                            if (!(message instanceof Message proto)) {
                                throw GcpException.failedPrecondition("IAM requires a protobuf resource mapping: " + service);
                            }
                            JsonNode request = mapper.readTree(JsonFormat.printer().print(proto));
                            String method = call.getMethodDescriptor().getBareMethodName();
                            IamOperation operation;
                            if (mixin) {
                                String resource = request.path("resource").asText();
                                adapter = registry.resource(resource);
                                if (adapter.isEmpty()) {
                                    // The mixin accepts arbitrary resource names; an unknown kind must not fail open.
                                    throw GcpException.failedPrecondition("IAM enforcement has no resource mapping: " + resource);
                                }
                                operation = new IamOperation(method, resource);
                            } else {
                                operation = adapter.orElseThrow().grpcOperation(method, request);
                            }
                            authorization.get().authorize(credential, adapter.orElseThrow(), operation);
                        }
                        super.onMessage(message);
                    } catch (Exception e) {
                        rejected = true;
                        call.close(GcpGrpcController.grpcException(e).getStatus(), new Metadata());
                        // Release streaming listeners already registered by previous messages.
                        super.onCancel();
                    }
                }

                @Override
                public void onHalfClose() {
                    if (!rejected) {
                        super.onHalfClose();
                    }
                }

                @Override
                public void onCancel() {
                    if (!rejected) {
                        super.onCancel();
                    }
                }
            };
        });
    }
}

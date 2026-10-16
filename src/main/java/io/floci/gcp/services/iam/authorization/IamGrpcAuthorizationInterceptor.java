package io.floci.gcp.services.iam.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.protobuf.Message;
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
import java.util.concurrent.atomic.AtomicReference;

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
        String serviceName = call.getMethodDescriptor().getServiceName();
        String method = call.getMethodDescriptor().getBareMethodName();
        Optional<IamAuthorizationAdapter> callAdapter = registry.grpc(serviceName);
        Optional<IamAuthorizationAdapter.GrpcStreamAuthorization> streamAuthorization =
                callAdapter.flatMap(adapter -> adapter.grpcStreamAuthorization(method));
        AtomicReference<IamOperation> streamOperation = new AtomicReference<>();
        Context context = Context.current().withValue(AUTHORIZATION, credential).withValue(GRPC_REQUEST, true);
        if (streamAuthorization.isPresent()) {
            IamAuthorizationAdapter adapter = callAdapter.orElseThrow();
            context = IamGrpcDeliveryAuthorization.withDelivery(context, (resource, delivery) -> {
                IamAuthorizationService service = authorization.get();
                if (!service.applies(credential, adapter)) {
                    delivery.run();
                    return;
                }
                IamOperation operation = streamOperation.get();
                if (operation == null || !operation.resource().equals(resource)) {
                    throw GcpException.failedPrecondition("IAM streaming delivery has no matching resource mapping");
                }
                service.withPolicyLocks(adapter.requireResource(operation.resource()), () -> {
                    service.authorize(credential, adapter, operation);
                    delivery.run();
                    return null;
                });
            });
        }
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
                        Optional<IamAuthorizationAdapter> adapter = callAdapter;
                        boolean mixin = IAM_POLICY_SERVICE.equals(serviceName);
                        if ((adapter.isPresent() || mixin) && authorization.get().enabled()) {
                            if (!(message instanceof Message proto)) {
                                throw GcpException.failedPrecondition("IAM requires a protobuf resource mapping: " + serviceName);
                            }
                            IamOperation operation = null;
                            if (mixin) {
                                String resource = switch (proto) {
                                    case GetIamPolicyRequest get -> get.getResource();
                                    case SetIamPolicyRequest set -> set.getResource();
                                    case TestIamPermissionsRequest test -> test.getResource();
                                    default -> throw GcpException.failedPrecondition(
                                            "IAM requires a resource mapping: " + method);
                                };
                                adapter = registry.resource(resource);
                                if (adapter.isEmpty()) {
                                    if (authorization.get().applies(credential)) {
                                        // The mixin accepts arbitrary resource names; an evaluated caller must not fail open.
                                        throw GcpException.failedPrecondition(
                                                "IAM enforcement has no resource mapping: " + resource);
                                    }
                                } else {
                                    operation = new IamOperation(method, resource);
                                }
                            } else {
                                if (streamAuthorization.isPresent()) {
                                    operation = streamAuthorization.orElseThrow().requestOperation(
                                            proto, streamOperation.get());
                                } else {
                                    operation = adapter.orElseThrow().grpcOperation(method, proto, mapper);
                                }
                            }
                            if (adapter.isPresent() && authorization.get().applies(credential, adapter.get())) {
                                authorization.get().authorize(credential, adapter.get(), operation);
                            }
                            if (streamAuthorization.isPresent()) {
                                streamOperation.set(operation);
                            }
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

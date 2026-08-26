package io.floci.gcp.services.gcs;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.quarkus.grpc.GlobalInterceptor;
import jakarta.enterprise.context.ApplicationScoped;

@GlobalInterceptor
@ApplicationScoped
final class GcsGrpcAuthorizationInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION_HEADER =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    static final Context.Key<String> AUTHORIZATION = Context.key("gcs-authorization");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        return Contexts.interceptCall(
                Context.current().withValue(AUTHORIZATION, headers.get(AUTHORIZATION_HEADER)),
                call, headers, next);
    }
}

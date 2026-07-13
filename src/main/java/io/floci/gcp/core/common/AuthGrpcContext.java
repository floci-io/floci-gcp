package io.floci.gcp.core.common;

import io.grpc.Context;

/**
 * gRPC {@link Context} keys for authenticated principal data.
 * Prefer these over {@link RequestContext} on the gRPC path (RequestScoped CDI
 * may be inactive outside JAX-RS).
 */
public final class AuthGrpcContext {

    public static final Context.Key<String> PRINCIPAL_EMAIL =
            Context.key("io.floci.gcp.auth.principalEmail");

    public static final Context.Key<Boolean> OPERATOR_ROOT =
            Context.key("io.floci.gcp.auth.operatorRoot");

    private AuthGrpcContext() {
    }
}

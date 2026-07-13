package io.floci.gcp.core.common;

import io.floci.gcp.config.EmulatorConfig;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.quarkus.grpc.GlobalInterceptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Validates {@code authorization: Bearer} metadata when
 * {@code floci-gcp.auth.validate-tokens} is enabled.
 * Populates {@link AuthGrpcContext} and, when a request scope is active,
 * {@link RequestContext}.
 */
@ApplicationScoped
@GlobalInterceptor
public class TokenValidationGrpcInterceptor implements ServerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTH_MESSAGE =
            "Request had invalid authentication credentials.";

    static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final EmulatorConfig config;
    private final TokenRegistry tokenRegistry;
    private final Instance<RequestContext> requestContextInstance;

    @Inject
    public TokenValidationGrpcInterceptor(EmulatorConfig config,
                                          TokenRegistry tokenRegistry,
                                          Instance<RequestContext> requestContextInstance) {
        this.config = config;
        this.tokenRegistry = tokenRegistry;
        this.requestContextInstance = requestContextInstance;
    }

    /** Package-visible for unit tests that inject mocks without CDI. */
    TokenValidationGrpcInterceptor(EmulatorConfig config,
                                   TokenRegistry tokenRegistry) {
        this(config, tokenRegistry, null);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        if (!config.auth().validateTokens()) {
            return next.startCall(call, headers);
        }

        Optional<ValidatedPrincipal> principal = validate(headers);
        if (principal.isEmpty()) {
            call.close(Status.UNAUTHENTICATED.withDescription(UNAUTH_MESSAGE), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        ValidatedPrincipal p = principal.get();
        applyRequestContext(p);
        Context ctx = Context.current()
                .withValue(AuthGrpcContext.PRINCIPAL_EMAIL, p.email())
                .withValue(AuthGrpcContext.OPERATOR_ROOT, p.operatorRoot());
        return Contexts.interceptCall(ctx, call, headers, next);
    }

    /**
     * Parses and validates the Bearer token from metadata.
     * Empty means the call must be closed as {@link Status#UNAUTHENTICATED}.
     */
    Optional<ValidatedPrincipal> validate(Metadata headers) {
        if (headers == null) {
            return Optional.empty();
        }
        String auth = headers.get(AUTHORIZATION_KEY);
        if (auth == null || auth.isBlank()) {
            return Optional.empty();
        }

        String trimmed = auth.trim();
        if (!trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.empty();
        }

        String token = trimmed.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }

        if (OperatorRootAuth.matches(config, token)) {
            return Optional.of(new ValidatedPrincipal(
                    OperatorRootAuth.rootEmail(config), token, true));
        }

        var resolved = tokenRegistry.resolve(token);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ValidatedPrincipal(resolved.get(), token, false));
    }

    private void applyRequestContext(ValidatedPrincipal principal) {
        if (requestContextInstance == null) {
            return;
        }
        try {
            RequestContext requestContext = requestContextInstance.get();
            requestContext.setPrincipalEmail(principal.email());
            requestContext.setAccessToken(principal.token());
            requestContext.setOperatorRoot(principal.operatorRoot());
        } catch (ContextNotActiveException | IllegalStateException ignored) {
            // Request scope is often inactive on pure gRPC threads.
        }
    }

    record ValidatedPrincipal(String email, String token, boolean operatorRoot) {
    }
}

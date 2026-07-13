package io.floci.gcp.core.common;

import io.floci.gcp.config.EmulatorConfig;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Validates {@code Authorization: Bearer} tokens when
 * {@code floci-gcp.auth.validate-tokens} is enabled.
 * Populates {@link RequestContext} principal fields for downstream IAM enforcement.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION)
public class TokenValidationFilter implements ContainerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTH_MESSAGE =
            "Request had invalid authentication credentials.";

    private final EmulatorConfig config;
    private final TokenRegistry tokenRegistry;
    private final RequestContext requestContext;

    @Inject
    public TokenValidationFilter(EmulatorConfig config,
                                 TokenRegistry tokenRegistry,
                                 RequestContext requestContext) {
        this.config = config;
        this.tokenRegistry = tokenRegistry;
        this.requestContext = requestContext;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!config.auth().validateTokens()) {
            return;
        }

        String path = SecurityBypassPaths.normalizePath(ctx.getUriInfo().getPath());
        if (SecurityBypassPaths.isHealthPath(path)
                || SecurityBypassPaths.isGkeTokenWebhookPath(path)) {
            return;
        }

        String auth = ctx.getHeaderString("Authorization");
        if (auth == null || auth.isBlank()) {
            abortUnauthenticated(ctx);
            return;
        }

        String trimmed = auth.trim();
        if (!trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            abortUnauthenticated(ctx);
            return;
        }

        String token = trimmed.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            abortUnauthenticated(ctx);
            return;
        }

        if (OperatorRootAuth.matches(config, token)) {
            requestContext.setPrincipalEmail(OperatorRootAuth.rootEmail(config));
            requestContext.setAccessToken(token);
            requestContext.setOperatorRoot(true);
            return;
        }

        var resolved = tokenRegistry.resolve(token);
        if (resolved.isEmpty()) {
            abortUnauthenticated(ctx);
            return;
        }

        requestContext.setPrincipalEmail(resolved.get());
        requestContext.setAccessToken(token);
        requestContext.setOperatorRoot(false);
    }

    private static void abortUnauthenticated(ContainerRequestContext ctx) {
        GcpException ex = GcpException.unauthenticated(UNAUTH_MESSAGE);
        ctx.abortWith(Response.status(ex.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .entity(new GcpExceptionMapper.ErrorWrapper(
                        GcpExceptionMapper.ErrorDetail.of(
                                ex.getHttpStatus(), ex.getMessage(), ex.getGcpStatus())))
                .build());
    }
}

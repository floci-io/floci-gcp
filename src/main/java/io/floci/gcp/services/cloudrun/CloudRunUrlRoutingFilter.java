package io.floci.gcp.services.cloudrun;

import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;

@Provider
@PreMatching
@ApplicationScoped
public class CloudRunUrlRoutingFilter implements ContainerRequestFilter {

    static final String ORIGINAL_SCHEME = "io.floci.gcp.cloudrun.originalScheme";
    static final String ORIGINAL_AUTHORITY = "io.floci.gcp.cloudrun.originalAuthority";
    static final String ORIGINAL_PATH_QUERY = "io.floci.gcp.cloudrun.originalPathQuery";

    private final CloudRunService cloudRunService;

    @Inject
    public CloudRunUrlRoutingFilter(CloudRunService cloudRunService) {
        this.cloudRunService = cloudRunService;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        URI original = ctx.getUriInfo().getRequestUri();
        String host = firstHeader(ctx, HttpHeaders.HOST);
        if (host == null || host.isBlank()) {
            host = original.getRawAuthority();
        }
        String requestHost = host;

        cloudRunService.resolveInvocationHost(requestHost).ifPresentOrElse(route -> {
            String originalPathAndQuery = rawPathAndQuery(original);
            ctx.setProperty(ORIGINAL_SCHEME, original.getScheme());
            ctx.setProperty(ORIGINAL_AUTHORITY, requestHost);
            ctx.setProperty(ORIGINAL_PATH_QUERY, originalPathAndQuery);

            String internalPath = "/run/v2/projects/" + route.project()
                    + "/locations/" + route.location()
                    + "/services/" + route.serviceId()
                    + original.getRawPath();
            URI rewritten = URI.create(original.getScheme() + "://" + original.getRawAuthority()
                    + internalPath
                    + query(original));
            ctx.setRequestUri(rewritten);
        }, () -> {
            if (cloudRunService.isGeneratedInvocationHost(requestHost)) {
                throw GcpException.notFound("Cloud Run service not found for host: " + requestHost);
            }
        });
    }

    private static String firstHeader(ContainerRequestContext ctx, String name) {
        return ctx.getHeaders().getFirst(name);
    }

    private static String rawPathAndQuery(URI uri) {
        return uri.getRawPath() + query(uri);
    }

    private static String query(URI uri) {
        String query = uri.getRawQuery();
        return query == null || query.isBlank() ? "" : "?" + query;
    }
}

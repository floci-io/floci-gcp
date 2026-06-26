package io.floci.gcp.core.common.routing;

import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceRegistry;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;

/**
 * Single-port REST routing for services that share the canonical {@code /v1/projects/...}
 * path shape on real GCP but are disambiguated only by hostname (e.g.
 * {@code container.googleapis.com} vs {@code pubsub.googleapis.com}). On one origin those
 * paths collide, so colliding services mount under a unique prefix (GKE → {@code /container}).
 *
 * <p>Before JAX-RS matching, for each routable service in the {@link ServiceRegistry}:
 * <ul>
 *   <li><b>Path mode</b> (Terraform/gcloud): if the path already starts with the service's
 *       prefix ({@code /container/}) it is left as-is.</li>
 *   <li><b>Host mode</b> (SDKs): if the path is canonical ({@code /v1/...}) and the first DNS
 *       label of the {@code Host}/{@code :authority} header equals the service's
 *       {@code hostToken} ({@code container}), the path is rewritten to prepend the prefix
 *       ({@code /v1/projects/...} → {@code /container/v1/projects/...}).</li>
 * </ul>
 * Requests that match neither are untouched, so IAM/Datastore/SecretManager/GCS keep working.
 * Only {@code /v1/} paths are ever rewritten, so GCS XML/object, {@code /storage}, {@code /v2}
 * and health endpoints are never affected.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 100)
@ApplicationScoped
public class ServiceRoutingFilter implements ContainerRequestFilter {

    private final ServiceRegistry serviceRegistry;

    @Inject
    public ServiceRoutingFilter(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String methodOverride = ctx.getHeaderString("X-HTTP-Method-Override");
        if (methodOverride == null || methodOverride.isBlank()) {
            methodOverride = ctx.getUriInfo().getQueryParameters().getFirst("$method");
        }
        if (methodOverride != null && !methodOverride.isBlank()) {
            ctx.setMethod(methodOverride.toUpperCase());
        }

        URI original = ctx.getUriInfo().getRequestUri();
        String path = original.getRawPath();
        if (path == null || !path.startsWith("/v1/")) {
            return;
        }

        for (ServiceDescriptor route : serviceRegistry.getRoutableServices()) {
            String prefix = route.pathPrefix();
            // Already in path mode for this (or another) prefixed service → nothing to do.
            if (path.startsWith(prefix + "/")) {
                return;
            }
            if (route.hostToken() == null || route.hostToken().isBlank()) {
                continue;
            }
            if (route.hostToken().equals(firstHostLabel(ctx, original))) {
                String newPath = prefix + path;
                URI rewritten = URI.create(original.getScheme() + "://" + original.getRawAuthority()
                        + newPath + query(original));
                ctx.setRequestUri(rewritten);
                return;
            }
        }
    }

    private static String firstHostLabel(ContainerRequestContext ctx, URI original) {
        String host = ctx.getHeaders().getFirst(HttpHeaders.HOST);
        if (host == null || host.isBlank()) {
            host = original.getRawAuthority();
        }
        if (host == null || host.isBlank()) {
            return null;
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        int dot = host.indexOf('.');
        return dot >= 0 ? host.substring(0, dot) : host;
    }

    private static String query(URI uri) {
        String q = uri.getRawQuery();
        return q == null || q.isBlank() ? "" : "?" + q;
    }
}

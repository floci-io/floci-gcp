package io.floci.gcp.core.common.routing;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpExceptionMapper;
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
import java.util.regex.Pattern;

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
 *   <li><b>Host mode</b> (SDKs): if the path starts with an API version ({@code /v1/...},
 *       {@code /v1beta1/...}) and the first DNS label of the {@code Host}/{@code :authority}
 *       header equals the service's {@code hostToken} ({@code container}), the path is rewritten
 *       to prepend the prefix ({@code /v1beta1/projects/...} → {@code /container/v1beta1/projects/...}).
 *       Versions the service does not serve are rewritten too.</li>
 * </ul>
 * For a service that lists {@code apiVersions}, a prefixed path on an alias version
 * ({@code /container/v1beta1/...}) is rewritten onto the mounted one ({@code /container/v1/...}),
 * and a version it does not list is answered with a GCP 404 here, before matching; otherwise it
 * would fall through to the GCS XML routes, which read the prefix as a bucket name.
 * Requests that match neither are untouched, so IAM/Datastore/SecretManager/GCS keep working.
 * Only versioned paths on a routable service's host are ever rewritten, so GCS XML/object,
 * {@code /storage} and health endpoints are never affected.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 100)
@ApplicationScoped
public class ServiceRoutingFilter implements ContainerRequestFilter {

    private static final Pattern VERSIONED_PATH = Pattern.compile("^/v\\d+[a-z0-9]*/");

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
        if (path == null) {
            return;
        }

        for (ServiceDescriptor route : serviceRegistry.getRoutableServices()) {
            String prefix = route.pathPrefix();
            if (path.startsWith(prefix + "/")) {
                mapVersion(ctx, original, route, path);
                return;
            }
        }
        if (!VERSIONED_PATH.matcher(path).find()) {
            return;
        }
        for (ServiceDescriptor route : serviceRegistry.getRoutableServices()) {
            if (route.hostToken() == null || route.hostToken().isBlank()) {
                continue;
            }
            if (route.hostToken().equals(firstHostLabel(ctx, original))) {
                String newPath = route.pathPrefix() + path;
                ctx.setRequestUri(rewrite(original, newPath));
                mapVersion(ctx, original, route, newPath);
                return;
            }
        }
    }

    /** {@code prefixedPath} starts with the route's prefix; maps its version segment onto the mounted one. */
    private static void mapVersion(ContainerRequestContext ctx, URI original, ServiceDescriptor route,
                                   String prefixedPath) {
        String versionedPath = prefixedPath.substring(route.pathPrefix().length());
        if (route.apiVersions().isEmpty() || !VERSIONED_PATH.matcher(versionedPath).find()) {
            return;
        }
        int end = versionedPath.indexOf('/', 1);
        String version = versionedPath.substring(1, end);
        String mounted = route.apiVersions().getFirst();
        if (version.equals(mounted)) {
            return;
        }
        if (!route.apiVersions().contains(version)) {
            ctx.abortWith(new GcpExceptionMapper().toResponse(
                    GcpException.notFound("Method not found: " + ctx.getMethod() + " " + prefixedPath)));
            return;
        }
        ctx.setRequestUri(rewrite(original, route.pathPrefix() + "/" + mounted + versionedPath.substring(end)));
    }

    private static URI rewrite(URI original, String path) {
        return URI.create(original.getScheme() + "://" + original.getRawAuthority() + path + query(original));
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

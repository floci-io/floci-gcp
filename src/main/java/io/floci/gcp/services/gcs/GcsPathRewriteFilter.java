package io.floci.gcp.services.gcs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;

/**
 * Rewrites GCS Node.js SDK paths to the standard JSON API format.
 * The Node.js @google-cloud/storage v7+ uses paths like /b/... and /upload/b/...
 * instead of /storage/v1/b/... and /upload/storage/v1/b/...
 */
@Provider
@PreMatching
@ApplicationScoped
public class GcsPathRewriteFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getRequestUri().getRawPath();
        String newPath = null;

        if (path.startsWith("/upload/b/")) {
            newPath = "/upload/storage/v1" + path.substring("/upload".length());
        } else if (path.equals("/b") || path.startsWith("/b/")) {
            newPath = "/storage/v1" + path;
        }

        if (newPath == null) {
            newPath = path;
        }

        if (("POST".equalsIgnoreCase(ctx.getMethod()) || "PUT".equalsIgnoreCase(ctx.getMethod()))
                && (newPath.startsWith("/storage/v1/b/") && (newPath.endsWith("/o") || newPath.endsWith("/o/")))) {
            newPath = "/upload" + newPath;
        }

        if (!newPath.equals(path)) {
            URI original = ctx.getUriInfo().getRequestUri();
            URI rewritten = URI.create(
                    original.getScheme() + "://" + original.getAuthority()
                    + newPath
                    + (original.getRawQuery() != null ? "?" + original.getRawQuery() : ""));
            ctx.setRequestUri(rewritten);
        }
    }
}

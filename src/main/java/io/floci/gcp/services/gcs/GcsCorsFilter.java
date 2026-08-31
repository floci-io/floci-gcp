package io.floci.gcp.services.gcs;

import io.floci.gcp.services.gcs.model.GcsBucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.List;
import java.util.Map;

@Provider
@ApplicationScoped
public class GcsCorsFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";

    @Context
    ResourceInfo resourceInfo;

    private final GcsService gcsService;

    @Inject
    public GcsCorsFilter(GcsService gcsService) {
        this.gcsService = gcsService;
    }

    @Override
    public void filter(ContainerRequestContext req) {
        if (!"OPTIONS".equals(req.getMethod())) {
            return;
        }
        String origin = req.getHeaderString("Origin");
        if (origin == null) {
            return;
        }
        String bucket = extractBucket(req.getUriInfo().getRequestUri().getRawPath());
        if (bucket == null) {
            // Not a GCS-shaped path at all. Leave OPTIONS handling (and any other
            // service's own CORS filter, e.g. FirebaseAuthCorsFilter) alone instead of
            // absorbing the preflight here with a bare, header-less 200.
            return;
        }
        String requestedMethod = req.getHeaderString(ACCESS_CONTROL_REQUEST_METHOD);
        Map<String, Object> rule = matchCorsRule(bucket, origin, requestedMethod);
        Response.ResponseBuilder rb = Response.ok();
        // Real GCS only answers a preflight with CORS headers when the bucket's CORS
        // configuration actually permits the origin and the requested method. Without a
        // matching rule the response carries no CORS headers at all, so the browser
        // blocks the request instead of it being silently allowed.
        if (rule != null) {
            rb.header("Access-Control-Allow-Origin", origin);
            rb.header("Vary", "Origin");
            appendMethodsHeader(rb, rule);
            appendHeadersHeader(rb, rule);
            appendMaxAge(rb, rule);
        }
        req.abortWith(rb.build());
    }

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext resp) {
        String origin = req.getHeaderString("Origin");
        if (origin == null) {
            return;
        }
        String bucket = extractBucket(req.getUriInfo().getRequestUri().getRawPath());
        Map<String, Object> rule = matchCorsRule(bucket, origin, req.getMethod());
        if (rule == null) {
            return;
        }
        resp.getHeaders().putSingle("Access-Control-Allow-Origin", origin);
        resp.getHeaders().putSingle("Vary", "Origin");
        // `Access-Control-Allow-Methods`/`-Headers`/`-Max-Age` are preflight-only; an
        // actual response advertises which response headers the browser may read.
        appendExposeHeaders(resp, rule);
    }

    /**
     * Find the first CORS rule of the bucket that permits both the origin and the method,
     * mirroring how real GCS evaluates a request against the bucket's CORS configuration.
     *
     * @param method the method being authorized — the requested method for a preflight,
     *               otherwise the method of the request itself. {@code null} matches on origin only.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> matchCorsRule(String bucketName, String origin, String method) {
        if (bucketName == null) {
            return null;
        }
        GcsBucket bucket = gcsService.findBucket(bucketName).orElse(null);
        if (bucket == null || bucket.getCors() == null) {
            return null;
        }
        for (Map<String, Object> rule : bucket.getCors()) {
            List<String> origins = (List<String>) rule.get("origin");
            if (origins == null || !(origins.contains("*") || origins.contains(origin))) {
                continue;
            }
            if (methodAllowed((List<String>) rule.get("method"), method)) {
                return rule;
            }
        }
        return null;
    }

    private static boolean methodAllowed(List<String> methods, String method) {
        if (method == null) {
            return true;
        }
        if (methods == null || methods.isEmpty()) {
            return false;
        }
        for (String allowed : methods) {
            if ("*".equals(allowed) || allowed.equalsIgnoreCase(method)) {
                return true;
            }
        }
        return false;
    }

    private String extractBucket(String path) {
        String[] prefixes = {
                "/storage/v1/b/",
                "/download/storage/v1/b/",
                "/upload/storage/v1/b/"
        };
        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) {
                String rest = path.substring(prefix.length());
                int slash = rest.indexOf('/');
                return slash >= 0 ? rest.substring(0, slash) : rest;
            }
        }
        // XML API (path-style) URLs — `/{bucket}/{object}`, the form `blob.public_url`
        // produces. The leading segment is only a bucket when JAX-RS routed the request
        // to the path-style controller; otherwise an unrelated endpoint whose first
        // segment happens to match a bucket name (e.g. a bucket called `batch` and
        // `/batch/storage/v1`) would inherit that bucket's CORS configuration.
        if (!isPathStyleObjectRoute()) {
            return null;
        }
        String rest = path.startsWith("/") ? path.substring(1) : path;
        if (rest.isEmpty()) {
            return null;
        }
        int slash = rest.indexOf('/');
        String candidate = slash >= 0 ? rest.substring(0, slash) : rest;
        return candidate.isEmpty() ? null : candidate;
    }

    private boolean isPathStyleObjectRoute() {
        return resourceInfo != null
                && resourceInfo.getResourceClass() == GcsXmlDownloadController.class;
    }

    @SuppressWarnings("unchecked")
    private static void appendMethodsHeader(Response.ResponseBuilder rb, Map<String, Object> rule) {
        List<String> methods = (List<String>) rule.get("method");
        if (methods != null && !methods.isEmpty()) {
            rb.header("Access-Control-Allow-Methods", String.join(", ", methods));
        }
    }

    @SuppressWarnings("unchecked")
    private static void appendHeadersHeader(Response.ResponseBuilder rb, Map<String, Object> rule) {
        List<String> headers = (List<String>) rule.get("responseHeader");
        if (headers != null && !headers.isEmpty()) {
            rb.header("Access-Control-Allow-Headers", String.join(", ", headers));
            rb.header("Access-Control-Expose-Headers", String.join(", ", headers));
        }
    }

    private static void appendMaxAge(Response.ResponseBuilder rb, Map<String, Object> rule) {
        Object maxAge = rule.get("maxAgeSeconds");
        if (maxAge != null) {
            rb.header("Access-Control-Max-Age", maxAge.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static void appendExposeHeaders(ContainerResponseContext resp, Map<String, Object> rule) {
        List<String> headers = (List<String>) rule.get("responseHeader");
        if (headers != null && !headers.isEmpty()) {
            resp.getHeaders().putSingle("Access-Control-Expose-Headers", String.join(", ", headers));
        }
    }
}

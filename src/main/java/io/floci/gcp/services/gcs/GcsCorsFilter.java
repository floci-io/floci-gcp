package io.floci.gcp.services.gcs;

import io.floci.gcp.services.gcs.model.GcsBucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.List;
import java.util.Map;

@Provider
@ApplicationScoped
public class GcsCorsFilter implements ContainerRequestFilter, ContainerResponseFilter {

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
        Map<String, Object> rule = matchCorsRule(bucket, origin);
        Response.ResponseBuilder rb = Response.ok();
        rb.header("Access-Control-Allow-Origin", origin);
        rb.header("Vary", "Origin");
        if (rule != null) {
            appendMethodsHeader(rb, rule);
            appendHeadersHeader(rb, rule);
            appendMaxAge(rb, rule);
        } else {
            rb.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD");
            rb.header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Goog-*");
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
        Map<String, Object> rule = matchCorsRule(bucket, origin);
        if (rule == null && bucket == null) {
            return;
        }
        resp.getHeaders().putSingle("Access-Control-Allow-Origin", origin);
        resp.getHeaders().putSingle("Vary", "Origin");
        if (rule != null) {
            appendMethodsHeader(resp, rule);
            appendHeadersHeader(resp, rule);
            appendMaxAge(resp, rule);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> matchCorsRule(String bucketName, String origin) {
        if (bucketName == null) {
            return null;
        }
        GcsBucket bucket = gcsService.findBucket(bucketName).orElse(null);
        if (bucket == null || bucket.getCors() == null) {
            return null;
        }
        for (Map<String, Object> rule : bucket.getCors()) {
            List<String> origins = (List<String>) rule.get("origin");
            if (origins != null && (origins.contains("*") || origins.contains(origin))) {
                return rule;
            }
        }
        return null;
    }

    private static String extractBucket(String path) {
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
        return null;
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
    private static void appendMethodsHeader(ContainerResponseContext resp, Map<String, Object> rule) {
        List<String> methods = (List<String>) rule.get("method");
        if (methods != null && !methods.isEmpty()) {
            resp.getHeaders().putSingle("Access-Control-Allow-Methods", String.join(", ", methods));
        }
    }

    @SuppressWarnings("unchecked")
    private static void appendHeadersHeader(ContainerResponseContext resp, Map<String, Object> rule) {
        List<String> headers = (List<String>) rule.get("responseHeader");
        if (headers != null && !headers.isEmpty()) {
            resp.getHeaders().putSingle("Access-Control-Allow-Headers", String.join(", ", headers));
            resp.getHeaders().putSingle("Access-Control-Expose-Headers", String.join(", ", headers));
        }
    }

    private static void appendMaxAge(ContainerResponseContext resp, Map<String, Object> rule) {
        Object maxAge = rule.get("maxAgeSeconds");
        if (maxAge != null) {
            resp.getHeaders().putSingle("Access-Control-Max-Age", maxAge.toString());
        }
    }
}

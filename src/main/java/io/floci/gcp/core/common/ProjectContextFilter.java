package io.floci.gcp.core.common;

import io.floci.gcp.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the GCP project ID from the request and populates {@link RequestContext}.
 * Resolution order:
 * <ol>
 *   <li>URL path segment {@code /projects/{project}/...} right after the API prefix</li>
 *   <li>{@code project} query parameter</li>
 *   <li>{@code x-goog-request-params} header ({@code project=...})</li>
 *   <li>Falls back to {@code EmulatorConfig.defaultProjectId()}</li>
 * </ol>
 * A registered project's number resolves to its ID. Every explicit project goes through
 * {@link ProjectRegistry#observe}, which registers it or, in strict mode, rejects it.
 */
@Provider
@ApplicationScoped
public class ProjectContextFilter implements ContainerRequestFilter {

    /**
     * A project in the path sits right after the API prefix: {@code /projects/p}, up to two
     * segments and a version ({@code /v1/projects/p}, {@code /storage/v1/projects/p},
     * {@code /upload/bigquery/v2/projects/p}), or a Floci internal route
     * ({@code /_floci-gcp/bigquery/projects/p}). Anchoring it keeps a {@code projects/} inside a GCS
     * object name ({@code /bucket/dir/projects/x/file}) from being read as the request's project;
     * {@code _floci-gcp} cannot be a bucket name, which must start with a letter or digit.
     */
    private static final Pattern PATH_PROJECT = Pattern.compile(
            "^(?:/_floci-gcp/[^/]+|(?:/[^/]+){0,2}/v\\d+[a-z0-9]*)?/projects/([^/]+)");
    private static final Pattern PARAM_PROJECT = Pattern.compile("(?:^|&)project=([^&]+)");

    private final RequestContext requestContext;
    private final ProjectRegistry projectRegistry;
    private final String defaultProjectId;

    @Inject
    public ProjectContextFilter(RequestContext requestContext, ProjectRegistry projectRegistry, EmulatorConfig config) {
        this.requestContext = requestContext;
        this.projectRegistry = projectRegistry;
        this.defaultProjectId = config.defaultProjectId();
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String explicit = explicitProject(ctx.getUriInfo().getRequestUri().getRawPath(),
                ctx.getUriInfo().getQueryParameters().getFirst("project"),
                ctx.getHeaderString("x-goog-request-params"));
        if (explicit == null) {
            requestContext.setProjectId(defaultProjectId);
            return;
        }
        try {
            requestContext.setProjectId(projectRegistry.observe(explicit));
        } catch (GcpException e) {
            ctx.abortWith(new GcpExceptionMapper().toResponse(e));
        }
    }

    public String defaultProjectId() {
        return defaultProjectId;
    }

    /** The project the request names, or the default project; it is neither registered nor validated. */
    public String resolveProjectId(ContainerRequestContext ctx) {
        String explicit = explicitProject(ctx.getUriInfo().getRequestUri().getRawPath(),
                ctx.getUriInfo().getQueryParameters().getFirst("project"),
                ctx.getHeaderString("x-goog-request-params"));
        return explicit != null ? explicit : defaultProjectId;
    }

    static String explicitProject(String rawPath, String projectParam, String requestParams) {
        if (rawPath != null) {
            Matcher m = PATH_PROJECT.matcher(rawPath);
            if (m.find()) {
                return stripCustomMethod(decode(m.group(1)));
            }
        }
        if (projectParam != null && !projectParam.isBlank()) {
            return projectParam;
        }
        if (requestParams != null) {
            Matcher m = PARAM_PROJECT.matcher(requestParams);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    /**
     * Drops a trailing {@code :verb} ({@code my-project:getIamPolicy} → {@code my-project}) while
     * keeping domain-scoped IDs such as {@code example.com:my-project} intact.
     */
    static String stripCustomMethod(String segment) {
        int first = segment.indexOf(':');
        if (first < 0) {
            return segment;
        }
        boolean domainScoped = segment.substring(0, first).indexOf('.') >= 0;
        if (!domainScoped) {
            return segment.substring(0, first);
        }
        int second = segment.indexOf(':', first + 1);
        return second < 0 ? segment : segment.substring(0, second);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}

package io.floci.gcp.core.common;

import io.floci.gcp.config.EmulatorConfig;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the GCP project ID from the request and populates {@link RequestContext}.
 * Resolution order:
 * <ol>
 *   <li>URL path segment {@code /projects/{project}/...}</li>
 *   <li>{@code x-goog-request-params} header ({@code project=...})</li>
 *   <li>Falls back to {@code EmulatorConfig.defaultProjectId()}</li>
 * </ol>
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 10)
public class ProjectContextFilter implements ContainerRequestFilter {

    private static final Pattern PATH_PROJECT = Pattern.compile("/projects/([^/]+)");
    private static final Pattern PARAM_PROJECT = Pattern.compile("(?:^|&)project=([^&]+)");

    private final RequestContext requestContext;
    private final String defaultProjectId;

    @Inject
    public ProjectContextFilter(RequestContext requestContext, EmulatorConfig config) {
        this.requestContext = requestContext;
        this.defaultProjectId = config.defaultProjectId();
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        requestContext.setProjectId(resolveProjectId(ctx));
    }

    private String resolveProjectId(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (path != null) {
            Matcher m = PATH_PROJECT.matcher(path);
            if (m.find()) {
                // Datastore REST uses /v1/projects/{projectId}:{method}; strip the custom method.
                String projectId = m.group(1);
                int colon = projectId.indexOf(':');
                return colon >= 0 ? projectId.substring(0, colon) : projectId;
            }
        }
        String projectParam = ctx.getUriInfo().getQueryParameters().getFirst("project");
        if (projectParam != null && !projectParam.isBlank()) {
            return projectParam;
        }
        String params = ctx.getHeaderString("x-goog-request-params");
        if (params != null) {
            Matcher m = PARAM_PROJECT.matcher(params);
            if (m.find()) {
                return m.group(1);
            }
        }
        return defaultProjectId;
    }
}

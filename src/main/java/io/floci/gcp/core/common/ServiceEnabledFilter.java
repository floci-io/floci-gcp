package io.floci.gcp.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs just before {@code IamRestAuthorizationFilter}, so a disabled API answers SERVICE_DISABLED before
 * an IAM denial, the same order the gRPC interceptors use.
 */
@Provider
@Priority(Priorities.AUTHORIZATION - 1)
@ApplicationScoped
public class ServiceEnabledFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(ServiceEnabledFilter.class);
    private static final Pattern CUSTOM_METHOD_ON_PROJECT = Pattern.compile("/projects/([^/]+):[A-Za-z]+$");

    @Context
    ResourceInfo resourceInfo;

    private final ServiceRegistry serviceRegistry;
    private final ServiceUsageGate serviceUsageGate;
    private final ProjectContextFilter projectContextFilter;
    private final ObjectMapper mapper;

    @Inject
    public ServiceEnabledFilter(ServiceRegistry serviceRegistry, ServiceUsageGate serviceUsageGate,
                                ProjectContextFilter projectContextFilter, ObjectMapper mapper) {
        this.serviceRegistry = serviceRegistry;
        this.serviceUsageGate = serviceUsageGate;
        this.projectContextFilter = projectContextFilter;
        this.mapper = mapper;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (resourceInfo == null || resourceInfo.getResourceClass() == null) {
            return;
        }
        Class<?> resourceClass = resourceInfo.getResourceClass();
        Optional<ServiceDescriptor> registered = serviceRegistry.byResourceClass(resourceClass);
        if (registered.isPresent() && !registered.get().enabled()) {
            ctx.abortWith(disabledResponse(registered.get().name()));
            return;
        }
        if (serviceUsageGate.enforced() && !ctx.getMethod().equals("OPTIONS") && !isEmulatorPath(ctx)) {
            serviceRegistry.byApiResourceClass(resourceClass)
                    .filter(serviceUsageGate::gates)
                    .ifPresent(descriptor -> gate(ctx, descriptor, new FilteredRequest(ctx, resourceClass)));
        }
    }

    private void gate(ContainerRequestContext ctx, ServiceDescriptor descriptor, FilteredRequest request) {
        ServiceUsageAdapter adapter = descriptor.usageAdapter();
        if (adapter.restPassThrough(request)) {
            return;
        }
        for (String project : consumerProjects(ctx, adapter, request)) {
            if (serviceUsageGate.isDenied(descriptor, project)) {
                ctx.abortWith(adapter.restDenial(request, serviceUsageGate.status(descriptor, project))
                        .orElseGet(() -> serviceUsageGate.restResponse(descriptor, project)));
                return;
            }
        }
    }

    /** Routes such as Datastore's {@code /v1/projects/p1:lookup} carry the method on the project segment. */
    private List<String> consumerProjects(ContainerRequestContext ctx, ServiceUsageAdapter adapter,
                                          FilteredRequest request) {
        Optional<List<String>> owned = adapter.restProjects(request);
        if (owned.isPresent()) {
            return owned.get().isEmpty() ? List.of(projectContextFilter.defaultProjectId()) : owned.get();
        }
        Matcher customMethod = CUSTOM_METHOD_ON_PROJECT.matcher(ctx.getUriInfo().getPath());
        if (customMethod.find()) {
            return List.of(customMethod.group(1));
        }
        return List.of(projectContextFilter.resolveProjectId(ctx));
    }

    private final class FilteredRequest implements ServiceUsageAdapter.RestRequest {

        private final ContainerRequestContext ctx;
        private final Class<?> resourceClass;
        private JsonNode body;

        FilteredRequest(ContainerRequestContext ctx, Class<?> resourceClass) {
            this.ctx = ctx;
            this.resourceClass = resourceClass;
        }

        @Override
        public Class<?> resourceClass() {
            return resourceClass;
        }

        @Override
        public String path() {
            return ctx.getUriInfo().getPath();
        }

        @Override
        public String pathParameter(String name) {
            return ctx.getUriInfo().getPathParameters().getFirst(name);
        }

        @Override
        public String queryParameter(String name) {
            return ctx.getUriInfo().getQueryParameters().getFirst(name);
        }

        @Override
        public JsonNode jsonBody() {
            if (body == null) {
                body = readBody();
            }
            return body;
        }

        private JsonNode readBody() {
            if (!ctx.hasEntity()) {
                return mapper.createObjectNode();
            }
            try {
                byte[] bytes = ctx.getEntityStream().readAllBytes();
                ctx.setEntityStream(new ByteArrayInputStream(bytes));
                JsonNode parsed = bytes.length > 0 ? mapper.readTree(bytes) : null;
                return parsed != null && parsed.isObject() ? parsed : mapper.createObjectNode();
            } catch (IOException e) {
                LOG.debugf(e, "Service Usage gate could not read the body of %s %s as JSON",
                        ctx.getMethod(), ctx.getUriInfo().getPath());
                return mapper.createObjectNode();
            }
        }
    }

    private static boolean isEmulatorPath(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path.startsWith("_floci-gcp") || path.startsWith("emulator/");
    }

    private Response disabledResponse(String serviceName) {
        return Response.status(503)
                .type(MediaType.APPLICATION_JSON)
                .entity(new GcpExceptionMapper.ErrorWrapper(
                        GcpExceptionMapper.ErrorDetail.of(503, "Service " + serviceName + " is not enabled.", "UNAVAILABLE")))
                .build();
    }
}

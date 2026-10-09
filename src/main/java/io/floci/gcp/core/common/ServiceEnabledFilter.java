package io.floci.gcp.core.common;

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

import java.util.Optional;

/**
 * Runs just before {@code IamRestAuthorizationFilter}, so a disabled API answers SERVICE_DISABLED before
 * an IAM denial, the same order the gRPC interceptors use.
 */
@Provider
@Priority(Priorities.AUTHORIZATION - 1)
@ApplicationScoped
public class ServiceEnabledFilter implements ContainerRequestFilter {

    @Context
    ResourceInfo resourceInfo;

    private final ServiceRegistry serviceRegistry;
    private final ServiceUsageGate serviceUsageGate;
    private final ProjectContextFilter projectContextFilter;

    @Inject
    public ServiceEnabledFilter(ServiceRegistry serviceRegistry, ServiceUsageGate serviceUsageGate,
                                ProjectContextFilter projectContextFilter) {
        this.serviceRegistry = serviceRegistry;
        this.serviceUsageGate = serviceUsageGate;
        this.projectContextFilter = projectContextFilter;
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
                    .ifPresent(descriptor -> {
                        String project = projectContextFilter.resolveProjectId(ctx);
                        if (serviceUsageGate.isDenied(descriptor, project)) {
                            ctx.abortWith(serviceUsageGate.restResponse(descriptor, project));
                        }
                    });
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

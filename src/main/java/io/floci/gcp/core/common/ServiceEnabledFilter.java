package io.floci.gcp.core.common;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

@Provider
@ApplicationScoped
public class ServiceEnabledFilter implements ContainerRequestFilter {

    @Context
    ResourceInfo resourceInfo;

    private final ServiceRegistry serviceRegistry;

    @Inject
    public ServiceEnabledFilter(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (resourceInfo == null || resourceInfo.getResourceClass() == null) {
            return;
        }
        serviceRegistry.byResourceClass(resourceInfo.getResourceClass()).ifPresent(descriptor -> {
            if (!descriptor.enabled()) {
                ctx.abortWith(disabledResponse(descriptor.name()));
            }
        });
    }

    private Response disabledResponse(String serviceName) {
        return Response.status(503)
                .type(MediaType.APPLICATION_JSON)
                .entity(new GcpExceptionMapper.ErrorWrapper(
                        GcpExceptionMapper.ErrorDetail.of(503, "Service " + serviceName + " is not enabled.", "UNAVAILABLE")))
                .build();
    }
}

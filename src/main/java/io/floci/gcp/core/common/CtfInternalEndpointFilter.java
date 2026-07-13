package io.floci.gcp.core.common;

import io.floci.gcp.config.EmulatorConfig;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Returns 404 for floci-gcp introspection routes when CTF hardening hides them.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 50)
public class CtfInternalEndpointFilter implements ContainerRequestFilter {

    private final CtfHideInternalEndpointsMode mode;

    @Inject
    public CtfInternalEndpointFilter(EmulatorConfig config) {
        this.mode = CtfHideInternalEndpointsMode.parse(config.ctf().hideInternalEndpoints());
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!mode.hidesAnything()) {
            return;
        }
        if (mode.isPathHidden(ctx.getUriInfo().getPath())) {
            ctx.abortWith(Response.status(Response.Status.NOT_FOUND).build());
        }
    }
}

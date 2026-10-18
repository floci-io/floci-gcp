package io.floci.gcp.core.common;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;

import java.util.function.Supplier;

/**
 * Runs code as a given project when no REST request set one: a gRPC call, or work on another
 * thread. Project-aware stores read the project from {@link RequestContext}, which
 * {@link ProjectContextFilter} fills for REST only. The caller's own project is restored
 * afterwards.
 */
public final class RequestScopes {

    private RequestScopes() {}

    public static <T> T callAs(String projectId, Supplier<T> action) {
        ManagedContext context = Arc.container().requestContext();
        boolean activated = !context.isActive();
        if (activated) {
            context.activate();
        }
        RequestContext request = Arc.container().instance(RequestContext.class).get();
        String previous = request.getProjectId();
        try {
            if (projectId != null) {
                request.setProjectId(projectId);
            }
            return action.get();
        } finally {
            if (activated) {
                context.terminate();
            } else {
                request.setProjectId(previous);
            }
        }
    }

    public static void runAs(String projectId, Runnable action) {
        callAs(projectId, () -> {
            action.run();
            return null;
        });
    }
}

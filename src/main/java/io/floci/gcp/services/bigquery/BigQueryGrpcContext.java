package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.RequestContext;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;

import java.util.function.Supplier;

/**
 * Table storage is namespaced by project, and gRPC calls do not pass through the REST project
 * filter, so Storage API calls run their storage access with the project of the table they touch.
 */
final class BigQueryGrpcContext {

    private BigQueryGrpcContext() {}

    static <T> T withProject(String projectId, Supplier<T> action) {
        ManagedContext context = Arc.container().requestContext();
        boolean activated = !context.isActive();
        if (activated) {
            context.activate();
        }
        try {
            if (projectId != null) {
                Arc.container().instance(RequestContext.class).get().setProjectId(projectId);
            }
            return action.get();
        } finally {
            if (activated) {
                context.terminate();
            }
        }
    }
}

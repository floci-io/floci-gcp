package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import java.util.Map;

/** Resource-specific validation runs against a private transactional project snapshot. */
public interface ComputeResourceHandler {
    boolean handles(String collection);
    void create(ComputeService.Context context, ObjectNode resource);
    default void update(ComputeService.Context context, ObjectNode resource, ObjectNode input, String verb) {
        throw GcpException.unimplemented("Updating " + context.collection() + " is not implemented");
    }
    default void action(ComputeService.Context context, ObjectNode resource, String action,
                        ObjectNode input, Map<String, String> query) {
        throw GcpException.unimplemented("Action " + action + " is not implemented for " + context.collection());
    }
    default void delete(ComputeService.Context context, ObjectNode resource) {}
}

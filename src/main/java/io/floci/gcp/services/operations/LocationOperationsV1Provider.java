package io.floci.gcp.services.operations;

import java.util.List;
import java.util.Optional;

/**
 * A v1 API whose {@code google.longrunning.Operations} mixin is bound at
 * {@code /v1/{name=projects/*&#47;locations/*&#47;operations/*}}. Several APIs share that path on floci's
 * single port, so {@link LocationOperationsV1Controller} owns the route and asks each provider
 * whether it owns an operation.
 */
public interface LocationOperationsV1Provider {

    /** A controller registered on the owning service's descriptor; gates these operations like its routes. */
    Class<?> resourceClass();

    /** The API's host prefix, {@code eventarc} for {@code eventarc.googleapis.com}. */
    String api();

    /**
     * Whether a list that names no API by its host is answered with this API's operations. Only one
     * provider says yes: an SDK client cannot unpack another API's metadata types, so a list must
     * never mix APIs. Eventarc owned this route before it was shared, so it keeps unhinted lists.
     */
    default boolean listedWithoutHost() {
        return false;
    }

    /** The operation as REST JSON, when this API owns it. */
    Optional<String> find(String name);

    /** Every operation this API owns under {@code projects/{project}/locations/{location}}. */
    List<Entry> list(String project, String location);

    /** Called only for an operation {@link #find} returned. */
    void delete(String name);

    /** Called only for an operation {@link #find} returned; cancellation is best effort (operations.proto). */
    void cancel(String name);

    record Entry(String name, String json) {}
}

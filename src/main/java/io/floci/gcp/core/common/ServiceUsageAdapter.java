package io.floci.gcp.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.protobuf.Message;
import com.google.rpc.Status;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Service-owned answers for {@link ServiceUsageGate}: which projects a request acts on, and how a
 * denial is rendered on a transport that is not REST JSON. A service registers one when its
 * handlers pick the project differently from the generic resolution (path, query, routing header,
 * resource names in the message). An empty result leaves the generic behaviour in place; an empty
 * list means the default project.
 */
public interface ServiceUsageAdapter {

    ServiceUsageAdapter GENERIC = new ServiceUsageAdapter() {};

    default Optional<List<String>> grpcProjects(Message request) {
        return Optional.empty();
    }

    /**
     * Domains of resource types, besides the API's own, whose projects a gRPC request to this
     * service acts on: BigQuery Storage reads and writes {@code bigquery.googleapis.com} tables.
     */
    default Set<String> referenceDomains() {
        return Set.of();
    }

    default Optional<List<String>> restProjects(RestRequest request) {
        return Optional.empty();
    }

    /** Whether a REST request only carries requests that are gated on their own, such as a batch. */
    default boolean restPassThrough(RestRequest request) {
        return false;
    }

    default Optional<Response> restDenial(RestRequest request, Status status) {
        return Optional.empty();
    }

    /** The project a {@code projects/{project}/...} resource name belongs to, or null. */
    static String projectOf(String resourceName) {
        if (resourceName == null || !resourceName.startsWith("projects/")) {
            return null;
        }
        int end = resourceName.indexOf('/', "projects/".length());
        String project = end < 0 ? resourceName.substring("projects/".length())
                : resourceName.substring("projects/".length(), end);
        return project.isEmpty() || project.equals("_") || project.equals("-") ? null : project;
    }

    interface RestRequest {

        Class<?> resourceClass();

        String path();

        /** The named path parameter, or null. */
        String pathParameter(String name);

        /** The named query parameter, or null. */
        String queryParameter(String name);

        /** The JSON request body; an empty object when there is none or it is not JSON. */
        JsonNode jsonBody();
    }
}

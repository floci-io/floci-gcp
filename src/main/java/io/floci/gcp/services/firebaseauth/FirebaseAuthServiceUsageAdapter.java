package io.floci.gcp.services.firebaseauth;

import io.floci.gcp.core.common.ServiceUsageAdapter;

import java.util.List;
import java.util.Optional;

/**
 * The client endpoints ({@code accounts:signUp}, the secure token endpoint) carry no project in
 * the path and always act on the default project, whatever a query parameter or header names.
 */
final class FirebaseAuthServiceUsageAdapter implements ServiceUsageAdapter {

    private final String defaultProjectId;

    FirebaseAuthServiceUsageAdapter(String defaultProjectId) {
        this.defaultProjectId = defaultProjectId;
    }

    @Override
    public Optional<List<String>> restProjects(RestRequest request) {
        if (request.pathParameter("project") != null) {
            return Optional.empty();
        }
        return Optional.of(List.of(defaultProjectId));
    }
}

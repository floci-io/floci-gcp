package io.floci.gcp.services.gke.operations;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpResourceNames;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class GkeOperationService {

    private final StorageBackend<String, StoredOperation> operationStore;
    private final String defaultProjectId;

    @Inject
    public GkeOperationService(StorageFactory storageFactory, EmulatorConfig config) {
        this(storageFactory.createGlobal(
                "gke",
                "gke-operations.json",
                new TypeReference<Map<String, StoredOperation>>() {
                }),
                config.defaultProjectId());
    }

    public GkeOperationService(StorageBackend<String, StoredOperation> operationStore, String defaultProjectId) {
        this.operationStore = operationStore;
        this.defaultProjectId = defaultProjectId;
    }

    /**
     * Creates a synchronous (already {@code DONE}) operation, mirroring the
     * emulator's instant cluster lifecycle. The operation {@code name} follows
     * GKE's {@code operation-<id>} convention so gcloud/Terraform can poll it via
     * {@code GetOperation}.
     */
    public StoredOperation createOperation(
            String project,
            String location,
            String clusterId,
            OperationType type) {

        return createOperation(project, location, type,
                "projects/" + project + "/locations/" + location + "/clusters/" + clusterId);
    }

    /** For node-pool-scoped mutations, whose target is the pool, not the cluster. */
    public StoredOperation createNodePoolOperation(
            String project,
            String location,
            String clusterId,
            String nodePoolId,
            OperationType type) {

        return createOperation(project, location, type,
                "projects/" + project + "/locations/" + location
                        + "/clusters/" + clusterId + "/nodePools/" + nodePoolId);
    }

    private StoredOperation createOperation(String project, String location, OperationType type, String targetLink) {
        String operationId = "operation-" + UUID.randomUUID();
        String now = Instant.now().toString();

        String selfLink = "projects/" + project
                + "/locations/" + location
                + "/operations/" + operationId;

        StoredOperation op = new StoredOperation(
                operationId,
                type,
                "DONE",
                location,
                targetLink,
                selfLink,
                now,
                now);

        operationStore.put(operationId, op);

        return op;
    }

    public List<StoredOperation> listOperations(
            String project,
            String location) {

        return operationStore.scan(k -> true)
                .stream()
                .filter(op -> project.equals(projectOf(op)))
                .filter(op -> location.equals(op.getLocation()))
                .toList();
    }

    public StoredOperation getOperation(
            String project,
            String operationId) {

        return operationStore
                .get(operationId)
                .filter(op -> project.equals(projectOf(op)))
                .orElseThrow(() -> GcpException.notFound(
                        "Operation not found: " + operationId));
    }

    /**
     * The owning project, read from the operation's {@code selfLink}
     * ({@code projects/{project}/locations/{location}/operations/{id}}), which every operation
     * has carried since GKE support landed. A record without one falls back to the default
     * project rather than leaking into every project's listing.
     */
    private String projectOf(StoredOperation op) {
        String project = GcpResourceNames.parseProject(op.getSelfLink());
        return project == null || project.isEmpty() ? defaultProjectId : project;
    }
}

package io.floci.gcp.services.gke.operations;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
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

    @Inject
    public GkeOperationService(StorageFactory storageFactory) {
        this(storageFactory.createGlobal(
                "gke",
                "gke-operations.json",
                new TypeReference<Map<String, StoredOperation>>() {
                }));
    }

    public GkeOperationService(StorageBackend<String, StoredOperation> operationStore) {
        this.operationStore = operationStore;
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

        String operationId = "operation-" + UUID.randomUUID();
        String now = Instant.now().toString();

        String selfLink = "projects/" + project
                + "/locations/" + location
                + "/operations/" + operationId;

        String targetLink = "projects/" + project
                + "/locations/" + location
                + "/clusters/" + clusterId;

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
                .filter(op -> location.equals(op.getLocation()))
                .toList();
    }

    public StoredOperation getOperation(
            String operationId) {

        return operationStore
                .get(operationId)
                .orElseThrow(() -> GcpException.notFound(
                        "Operation not found: " + operationId));
    }
}

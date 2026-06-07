package io.floci.gcp.services.gke.operations;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class GkeOperationService {

        private final StorageBackend<String, StoredOperation> operationStore;

        @Inject
        public GkeOperationService(
                        StorageFactory storageFactory) {

                this.operationStore = storageFactory.createGlobal(
                                "gke",
                                "gke-operations.json",
                                new TypeReference<Map<String, StoredOperation>>() {
                                });
        }

        public StoredOperation createOperation(
                        String project,
                        String location,
                        String clusterId,
                        OperationType type) {

                String operationId = UUID.randomUUID().toString();

                String selfLink = "projects/" + project +
                                "/locations/" + location +
                                "/operations/" + operationId;

                String targetLink = "projects/" + project +
                                "/locations/" + location +
                                "/clusters/" + clusterId;

                StoredOperation op = new StoredOperation(
                                operationId,
                                type,
                                location,
                                targetLink,
                                selfLink);

                operationStore.put(
                                operationId,
                                op);

                return op;
        }

        public StoredOperation getOperation(
                        String operationId) {

                return operationStore
                                .get(operationId)
                                .orElseThrow(() -> GcpException.notFound(
                                                "Operation not found: "
                                                                + operationId));
        }
}
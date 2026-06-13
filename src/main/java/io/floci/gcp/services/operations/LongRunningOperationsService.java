package io.floci.gcp.services.operations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.longrunning.ListOperationsResponse;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.Message;
import com.google.rpc.Status;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class LongRunningOperationsService {

    private final StorageBackend<String, String> operationStore;

    @Inject
    public LongRunningOperationsService(StorageFactory storageFactory) {
        this.operationStore = storageFactory.createGlobal("operations", "operations.json",
                new TypeReference<Map<String, String>>() {});
    }

    LongRunningOperationsService(StorageBackend<String, String> operationStore) {
        this.operationStore = operationStore;
    }

    public Operation done(String parent, Message response, Message metadata) {
        Operation operation = completed(parent, response, metadata);
        operationStore.put(operation.getName(), ProtoJson.print(operation));
        return operation;
    }

    public Operation doneTransient(String parent, Message response, Message metadata) {
        return completed(parent, response, metadata);
    }

    public Operation pending(String parent, Message metadata) {
        Operation.Builder builder = Operation.newBuilder()
                .setName(parent + "/operations/" + UUID.randomUUID())
                .setDone(false);
        if (metadata != null) {
            builder.setMetadata(Any.pack(metadata));
        }
        Operation operation = builder.build();
        operationStore.put(operation.getName(), ProtoJson.print(operation));
        return operation;
    }

    public Operation complete(String name, Message response, Message metadata) {
        Operation.Builder builder = get(name).toBuilder()
                .setDone(true)
                .clearError();
        if (response != null) {
            builder.setResponse(Any.pack(response));
        }
        if (metadata != null) {
            builder.setMetadata(Any.pack(metadata));
        }
        Operation operation = builder.build();
        operationStore.put(name, ProtoJson.print(operation));
        return operation;
    }

    public Operation fail(String name, Status error, Message metadata) {
        Operation.Builder builder = get(name).toBuilder()
                .setDone(true)
                .clearResponse()
                .setError(error);
        if (metadata != null) {
            builder.setMetadata(Any.pack(metadata));
        }
        Operation operation = builder.build();
        operationStore.put(name, ProtoJson.print(operation));
        return operation;
    }

    public Operation wait(String name, Duration timeout) {
        Instant deadline = Instant.now().plus(toJavaDuration(timeout));
        Operation operation = get(name);
        while (!operation.getDone() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return get(name);
            }
            operation = get(name);
        }
        return operation;
    }

    private Operation completed(String parent, Message response, Message metadata) {
        Operation.Builder builder = Operation.newBuilder()
                .setName(parent + "/operations/" + UUID.randomUUID())
                .setDone(true);
        if (response != null) {
            builder.setResponse(Any.pack(response));
        }
        if (metadata != null) {
            builder.setMetadata(Any.pack(metadata));
        }
        return builder.build();
    }

    public Operation get(String name) {
        return operationStore.get(name)
                .map(this::parse)
                .orElseThrow(() -> GcpException.notFound("Operation not found: " + name));
    }

    public ListOperationsResponse list(String parent, int pageSize, String pageToken) {
        String prefix = parent + "/operations/";
        List<Operation> operations = operationStore.scan(k -> k.startsWith(prefix)).stream()
                .map(this::parse)
                .sorted(Comparator.comparing(Operation::getName))
                .toList();
        PageToken.Page<Operation> page = PageToken.paginate(operations, pageSize, pageToken);
        ListOperationsResponse.Builder response = ListOperationsResponse.newBuilder()
                .addAllOperations(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    public void delete(String name) {
        operationStore.delete(name);
    }

    private Operation parse(String json) {
        return ProtoJson.merge(json, Operation.newBuilder()).build();
    }

    private static java.time.Duration toJavaDuration(Duration timeout) {
        if (timeout == null || (timeout.getSeconds() == 0 && timeout.getNanos() == 0)) {
            return java.time.Duration.ofSeconds(60);
        }
        return java.time.Duration.ofSeconds(timeout.getSeconds(), timeout.getNanos());
    }
}

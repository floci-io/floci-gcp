package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.operations.LocationOperationsV1Provider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Managed Kafka's {@code google.longrunning.Operation}s, kept as REST JSON because floci has no
 * managedkafka proto classes: metadata is {@code google.cloud.managedkafka.v1.OperationMetadata},
 * the response a {@code Cluster} or {@code google.protobuf.Empty}, each carrying its {@code @type}.
 * Served on the shared v1 operations route.
 */
@ApplicationScoped
public class KafkaOperations implements LocationOperationsV1Provider {

    static final String METADATA_TYPE = "type.googleapis.com/google.cloud.managedkafka.v1.OperationMetadata";
    static final String CLUSTER_TYPE = "type.googleapis.com/google.cloud.managedkafka.v1.Cluster";
    static final String EMPTY_TYPE = "type.googleapis.com/google.protobuf.Empty";

    /** {@code google.rpc.Code} values used for failed operations. */
    static final int DEADLINE_EXCEEDED = 4;
    static final int ABORTED = 10;
    static final int INTERNAL = 13;

    private final StorageBackend<String, String> store;
    private final ObjectMapper mapper;

    @Inject
    public KafkaOperations(StorageFactory storageFactory, ObjectMapper mapper) {
        this(storageFactory.createGlobal("kafka", "kafka-operations.json",
                new TypeReference<Map<String, String>>() {}), mapper);
    }

    KafkaOperations(StorageBackend<String, String> store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    /** A running operation on {@code target}; finished later with {@link #complete} or {@link #fail}. */
    synchronized ObjectNode start(String target, String verb) {
        ObjectNode operation = newOperation(target, verb);
        save(operation);
        return operation;
    }

    /** An operation that finished as it was created. */
    synchronized ObjectNode done(String target, String verb, ObjectNode response) {
        ObjectNode operation = newOperation(target, verb);
        finish(operation);
        operation.set("response", response);
        save(operation);
        return operation;
    }

    /** Finishes a running operation; one deleted meanwhile stays deleted. */
    synchronized void complete(String name, ObjectNode response) {
        load(name).ifPresent(operation -> {
            finish(operation);
            operation.set("response", response);
            save(operation);
        });
    }

    synchronized void fail(String name, int code, String message) {
        load(name).ifPresent(operation -> {
            finish(operation);
            ObjectNode error = operation.putObject("error");
            error.put("code", code);
            error.put("message", message);
            save(operation);
        });
    }

    /** Operations still running, for recovery after a restart. */
    synchronized List<ObjectNode> unfinished() {
        return store.scan(k -> true).stream()
                .map(this::parse)
                .filter(operation -> !operation.path("done").asBoolean())
                .toList();
    }

    ObjectNode clusterResponse(Object cluster) {
        ObjectNode response = mapper.createObjectNode();
        response.put("@type", CLUSTER_TYPE);
        response.setAll((ObjectNode) mapper.valueToTree(cluster));
        return response;
    }

    ObjectNode emptyResponse() {
        return mapper.createObjectNode().put("@type", EMPTY_TYPE);
    }

    // ── Shared v1 operations route ──────────────────────────────────────────

    @Override
    public Class<?> resourceClass() {
        return KafkaController.class;
    }

    @Override
    public String api() {
        return "managedkafka";
    }

    @Override
    public Optional<String> find(String name) {
        return store.get(name);
    }

    @Override
    public List<Entry> list(String project, String location) {
        String prefix = "projects/" + project + "/locations/" + location + "/operations/";
        return store.scan(k -> k.startsWith(prefix)).stream()
                .map(json -> new Entry(parse(json).path("name").asText(), json))
                .sorted(Comparator.comparing(Entry::name))
                .toList();
    }

    @Override
    public synchronized void delete(String name) {
        store.delete(name);
    }

    /** Provisioning and teardown run to completion; like Eventarc, cancel leaves the operation as it is. */
    @Override
    public void cancel(String name) {
        // Best effort per operations.proto: the caller reads the outcome back with GetOperation.
    }

    private ObjectNode newOperation(String target, String verb) {
        String parent = target.substring(0, target.indexOf("/", target.indexOf("/locations/") + "/locations/".length()));
        ObjectNode operation = mapper.createObjectNode();
        operation.put("name", parent + "/operations/" + UUID.randomUUID());
        ObjectNode metadata = operation.putObject("metadata");
        metadata.put("@type", METADATA_TYPE);
        metadata.put("createTime", Instant.now().toString());
        metadata.put("target", target);
        metadata.put("verb", verb);
        metadata.put("apiVersion", "v1");
        return operation;
    }

    private static void finish(ObjectNode operation) {
        ((ObjectNode) operation.get("metadata")).put("endTime", Instant.now().toString());
        operation.put("done", true);
    }

    private Optional<ObjectNode> load(String name) {
        return store.get(name).map(this::parse);
    }

    private void save(ObjectNode operation) {
        store.put(operation.get("name").asText(), operation.toString());
    }

    private ObjectNode parse(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw GcpException.internal("Stored Kafka operation is not JSON: " + e.getOriginalMessage());
        }
    }
}

package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.LocationCatalog;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.kafka.model.StoredSchemaRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * {@code google.cloud.managedkafka.schemaregistry.v1.ManagedSchemaRegistry}: registry resources
 * are floci's own; everything inside a registry is delegated to a {@link SchemaRegistryDataPlane}
 * in the Confluent API that the Google API mirrors, and mapped back to the Google shapes here.
 *
 * <p>Invariant: no data plane runs for a registry that is not stored. Create and delete hold the
 * registry's write lock; every data call holds its read lock and checks the registry exists
 * first, so a call racing a delete either finishes before the delete starts or sees NOT_FOUND,
 * and never restarts the deleted registry's container.
 */
@ApplicationScoped
public class SchemaRegistryService {

    /** Letters, digits and underscores, not starting with a digit, up to 63 characters (the proto). */
    private static final Pattern REGISTRY_ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final List<String> COMPATIBILITY_BY_NUMBER = List.of("NONE", "BACKWARD", "BACKWARD_TRANSITIVE",
            "FORWARD", "FORWARD_TRANSITIVE", "FULL", "FULL_TRANSITIVE");
    private static final List<String> MODE_BY_NUMBER = List.of("NONE", "READONLY", "READWRITE", "IMPORT");
    private static final List<String> SCHEMA_TYPE_BY_NUMBER = List.of("SCHEMA_TYPE_UNSPECIFIED", "AVRO", "JSON",
            "PROTOBUF");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackend<String, StoredSchemaRegistry> registryStore;
    private final SchemaRegistryDataPlane dataPlane;
    private final EmulatorConfig config;
    private final LocationCatalog locations;
    private final Map<String, ReadWriteLock> locks = new ConcurrentHashMap<>();

    @Inject
    public SchemaRegistryService(StorageFactory storageFactory, EmulatorConfig config, LocationCatalog locations,
            RedpandaSchemaRegistryDataPlane redpanda, InMemorySchemaRegistryDataPlane inMemory) {
        this(storageFactory.createGlobal("kafka", "kafka-schema-registries.json",
                        new TypeReference<Map<String, StoredSchemaRegistry>>() {}),
                config, locations, config.services().kafka().mock() ? inMemory : redpanda);
    }

    SchemaRegistryService(StorageBackend<String, StoredSchemaRegistry> registryStore, EmulatorConfig config,
            LocationCatalog locations, SchemaRegistryDataPlane dataPlane) {
        this.registryStore = registryStore;
        this.config = config;
        this.locations = locations;
        this.dataPlane = dataPlane;
    }

    @PreDestroy
    void shutdown() {
        for (StoredSchemaRegistry registry : registryStore.scan(k -> true)) {
            dataPlane.stop(registry);
        }
    }

    // ── Registries ────────────────────────────────────────────────────────────

    public Map<String, Object> createRegistry(String project, String location, String registryId) {
        locations.requireLocation(location, LocationCatalog.Kind.REGION);
        if (registryId == null || registryId.isBlank()) {
            throw GcpException.invalidArgument("schema_registry_id is required");
        }
        if (!REGISTRY_ID.matcher(registryId).matches()) {
            throw GcpException.invalidArgument("schema_registry_id must not start with a digit and must contain only "
                    + "letters, digits and underscores, up to 63 characters: " + registryId);
        }
        String name = parent(project, location) + "/schemaRegistries/" + registryId;
        ReadWriteLock lock = lock(name);
        lock.writeLock().lock();
        try {
            if (registryStore.get(name).isPresent()) {
                throw GcpException.alreadyExists("SchemaRegistry already exists: " + name);
            }
            StoredSchemaRegistry registry = new StoredSchemaRegistry(name);
            registry.setVolumeId(String.format("%06x", new SecureRandom().nextInt(0xFFFFFF)));
            registry.setVolumeName(ContainerStorageHelper.resourceName(config,
                    RedpandaSchemaRegistryDataPlane.SERVICE, registry.getVolumeId(), registryId));
            registryStore.put(name, registry);
            try {
                dataPlane.ensureRunning(registry);
            } catch (RuntimeException e) {
                registryStore.delete(name);
                dataPlane.delete(registry);
                throw e;
            }
            return registryJson(registry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Map<String, Object> getRegistry(String project, String location, String registryId) {
        return registryJson(requireRegistry(project, location, registryId));
    }

    public List<Map<String, Object>> listRegistries(String project, String location) {
        String prefix = parent(project, location) + "/schemaRegistries/";
        return registryStore.scan(k -> k.startsWith(prefix)).stream()
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .map(SchemaRegistryService::registryJson)
                .toList();
    }

    public void deleteRegistry(String project, String location, String registryId) {
        String name = parent(project, location) + "/schemaRegistries/" + registryId;
        ReadWriteLock lock = lock(name);
        lock.writeLock().lock();
        try {
            StoredSchemaRegistry registry = registryStore.get(name)
                    .orElseThrow(() -> GcpException.notFound("SchemaRegistry not found: " + name));
            registryStore.delete(name);
            dataPlane.delete(registry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ── Subjects and versions ─────────────────────────────────────────────────

    public JsonNode listSubjects(String project, String location, String registryId, String subjectPrefix,
            boolean deleted) {
        return call(project, location, registryId, r -> dataPlane.listSubjects(r, subjectPrefix, deleted));
    }

    public JsonNode deleteSubject(String project, String location, String registryId, String subject,
            boolean permanent) {
        return call(project, location, registryId, r -> dataPlane.deleteSubject(r, subject, permanent));
    }

    public JsonNode lookupVersion(String project, String location, String registryId, String subject,
            JsonNode body, Boolean normalizeParam, Boolean deletedParam) {
        ObjectNode schema = schemaPayload(body);
        boolean normalize = flag(normalizeParam, body, "normalize");
        boolean deleted = flag(deletedParam, body, "deleted");
        return schemaVersion(call(project, location, registryId,
                r -> dataPlane.lookupVersion(r, subject, schema, normalize, deleted)));
    }

    public JsonNode getVersion(String project, String location, String registryId, String subject, String version,
            boolean deleted) {
        return schemaVersion(call(project, location, registryId,
                r -> dataPlane.getVersion(r, subject, version, deleted)));
    }

    public JsonNode listVersions(String project, String location, String registryId, String subject,
            boolean deleted) {
        return call(project, location, registryId, r -> dataPlane.listVersions(r, subject, deleted));
    }

    public JsonNode createVersion(String project, String location, String registryId, String subject,
            JsonNode body, Boolean normalizeParam) {
        ObjectNode schema = schemaPayload(body);
        if (body.hasNonNull("version")) {
            schema.put("version", body.get("version").asInt());
        }
        if (body.hasNonNull("id")) {
            schema.put("id", body.get("id").asInt());
        }
        boolean normalize = flag(normalizeParam, body, "normalize");
        JsonNode created = call(project, location, registryId, r -> dataPlane.createVersion(r, subject, schema,
                normalize));
        return MAPPER.createObjectNode().put("id", created.path("id").asInt());
    }

    public JsonNode deleteVersion(String project, String location, String registryId, String subject,
            String version, boolean permanent) {
        return call(project, location, registryId, r -> dataPlane.deleteVersion(r, subject, version, permanent));
    }

    public JsonNode getSchema(String project, String location, String registryId, String id, String subject) {
        int schemaId;
        try {
            schemaId = Integer.parseInt(id);
        } catch (NumberFormatException e) {
            throw GcpException.invalidArgument("Schema id must be an integer: " + id);
        }
        JsonNode schema = call(project, location, registryId, r -> dataPlane.getSchema(r, schemaId, subject));
        ObjectNode result = MAPPER.createObjectNode();
        copy(schema, result, "schemaType", "schema", "references");
        return result;
    }

    /**
     * Checks {@code body}'s schema against one version of the subject, or, with {@code version}
     * {@code null}, against the versions the subject's compatibility level covers: every version
     * for the transitive levels, the latest otherwise.
     */
    public JsonNode checkCompatibility(String project, String location, String registryId, String subject,
            String version, JsonNode body, Boolean verboseParam) {
        ObjectNode schema = schemaPayload(body);
        boolean verbose = flag(verboseParam, body, "verbose");
        return call(project, location, registryId, r -> {
            if (version != null) {
                return compatibilityResult(dataPlane.checkCompatibility(r, subject, version, schema, verbose));
            }
            try {
                String level = dataPlane.getConfig(r, subject, true).path("compatibilityLevel").asText();
                List<String> versions = level.endsWith("_TRANSITIVE")
                        ? toStrings(dataPlane.listVersions(r, subject, false))
                        : List.of("latest");
                ObjectNode result = MAPPER.createObjectNode().put("is_compatible", true);
                ArrayNode messages = MAPPER.createArrayNode();
                for (String v : versions) {
                    JsonNode one = dataPlane.checkCompatibility(r, subject, v, schema, verbose);
                    if (!one.path("is_compatible").asBoolean()) {
                        result.put("is_compatible", false);
                    }
                    one.path("messages").forEach(messages::add);
                }
                if (!messages.isEmpty()) {
                    result.set("messages", messages);
                }
                return result;
            } catch (SchemaRegistryException e) {
                if (e.errorCode() == 40401) {
                    // Nothing registered under the subject yet: nothing to be incompatible with.
                    return MAPPER.createObjectNode().put("is_compatible", true);
                }
                throw e;
            }
        });
    }

    // ── Config and mode ───────────────────────────────────────────────────────

    public JsonNode getConfig(String project, String location, String registryId, String subject,
            boolean defaultToGlobal) {
        return schemaConfig(call(project, location, registryId,
                r -> dataPlane.getConfig(r, subject, defaultToGlobal)));
    }

    /**
     * An unset compatibility removes a subject's compatibility (the proto); for the registry it is
     * required.
     */
    public JsonNode updateConfig(String project, String location, String registryId, String subject,
            JsonNode body) {
        if (body.path("normalize").asBoolean(false)) {
            throw GcpException.invalidArgument("floci-gcp does not support normalize in a schema config; "
                    + "pass normalize on CreateVersion or LookupVersion instead");
        }
        String compatibility = enumName(body.get("compatibility"), COMPATIBILITY_BY_NUMBER);
        if (compatibility == null) {
            if (subject == null) {
                throw GcpException.invalidArgument("compatibility is required for a schema registry config");
            }
            return call(project, location, registryId, r -> {
                try {
                    dataPlane.deleteConfig(r, subject);
                } catch (SchemaRegistryException e) {
                    if (e.errorCode() != 40408) {
                        throw e;
                    }
                }
                return MAPPER.createObjectNode();
            });
        }
        return schemaConfig(call(project, location, registryId,
                r -> dataPlane.updateConfig(r, subject, compatibility)));
    }

    public JsonNode deleteConfig(String project, String location, String registryId, String subject) {
        return schemaConfig(call(project, location, registryId, r -> dataPlane.deleteConfig(r, subject)));
    }

    public JsonNode getMode(String project, String location, String registryId, String subject) {
        return schemaMode(call(project, location, registryId, r -> dataPlane.getMode(r, subject)));
    }

    public JsonNode updateMode(String project, String location, String registryId, String subject, JsonNode body) {
        String mode = enumName(body.get("mode"), MODE_BY_NUMBER);
        if (mode == null) {
            throw GcpException.invalidArgument("mode is required");
        }
        return schemaMode(call(project, location, registryId, r -> dataPlane.updateMode(r, subject, mode)));
    }

    public JsonNode deleteMode(String project, String location, String registryId, String subject) {
        return schemaMode(call(project, location, registryId, r -> dataPlane.deleteMode(r, subject)));
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private StoredSchemaRegistry requireRegistry(String project, String location, String registryId) {
        String name = parent(project, location) + "/schemaRegistries/" + registryId;
        return registryStore.get(name).orElseThrow(() -> GcpException.notFound("SchemaRegistry not found: " + name));
    }

    private JsonNode call(String project, String location, String registryId,
            Function<StoredSchemaRegistry, JsonNode> action) {
        String name = parent(project, location) + "/schemaRegistries/" + registryId;
        ReadWriteLock lock = lock(name);
        lock.readLock().lock();
        try {
            StoredSchemaRegistry registry = requireRegistry(project, location, registryId);
            return translate(() -> action.apply(registry));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Confluent errors as Google errors, keeping the Confluent message. */
    private static JsonNode translate(Supplier<JsonNode> action) {
        try {
            return action.get();
        } catch (SchemaRegistryException e) {
            throw switch (e.httpStatus()) {
                case 404 -> GcpException.notFound(e.getMessage());
                case 409 -> GcpException.failedPrecondition(e.getMessage());
                case 400, 422 -> GcpException.invalidArgument(e.getMessage());
                default -> GcpException.internal(e.getMessage());
            };
        }
    }

    private ReadWriteLock lock(String name) {
        return locks.computeIfAbsent(name, k -> new ReentrantReadWriteLock());
    }

    private static String parent(String project, String location) {
        return "projects/" + project + "/locations/" + location;
    }

    private static Map<String, Object> registryJson(StoredSchemaRegistry registry) {
        return Map.of("name", registry.getName());
    }

    /** The Confluent request body for a schema: {@code schema}, {@code schemaType}, {@code references}. */
    private static ObjectNode schemaPayload(JsonNode body) {
        JsonNode schema = body.get("schema");
        if (schema == null || !schema.isTextual() || schema.asText().isEmpty()) {
            throw GcpException.invalidArgument("schema is required");
        }
        ObjectNode payload = MAPPER.createObjectNode().put("schema", schema.asText());
        String schemaType = enumName(body.get("schemaType"), SCHEMA_TYPE_BY_NUMBER);
        if (schemaType != null && !"SCHEMA_TYPE_UNSPECIFIED".equals(schemaType)) {
            payload.put("schemaType", schemaType);
        }
        JsonNode references = body.get("references");
        if (references != null && references.isArray() && !references.isEmpty()) {
            payload.set("references", references);
        }
        return payload;
    }

    private static ObjectNode schemaVersion(JsonNode version) {
        ObjectNode result = MAPPER.createObjectNode();
        copy(version, result, "subject", "version", "id", "schemaType", "schema", "references");
        return result;
    }

    private static ObjectNode compatibilityResult(JsonNode check) {
        ObjectNode result = MAPPER.createObjectNode();
        copy(check, result, "is_compatible", "messages");
        return result;
    }

    private static ObjectNode schemaConfig(JsonNode config) {
        ObjectNode result = MAPPER.createObjectNode();
        JsonNode level = config.hasNonNull("compatibilityLevel") ? config.get("compatibilityLevel")
                : config.get("compatibility");
        if (level != null && !level.isNull()) {
            result.set("compatibility", level);
        }
        copy(config, result, "normalize", "alias");
        return result;
    }

    private static ObjectNode schemaMode(JsonNode mode) {
        ObjectNode result = MAPPER.createObjectNode();
        copy(mode, result, "mode");
        return result;
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            JsonNode value = from.get(field);
            if (value != null && !value.isNull()) {
                to.set(field, value);
            }
        }
    }

    /**
     * A boolean request flag: the query parameter a Confluent client sends, else the field of the
     * Google request body.
     */
    private static boolean flag(Boolean queryParam, JsonNode body, String field) {
        return queryParam != null ? queryParam : body.path(field).asBoolean(false);
    }

    /** A proto3 JSON enum value, which may be written as its name or its number. */
    private static String enumName(JsonNode value, List<String> byNumber) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isInt()) {
            int n = value.asInt();
            if (n < 0 || n >= byNumber.size()) {
                throw GcpException.invalidArgument("Unknown enum value " + n);
            }
            return byNumber.get(n);
        }
        return value.asText();
    }

    private static List<String> toStrings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }
}

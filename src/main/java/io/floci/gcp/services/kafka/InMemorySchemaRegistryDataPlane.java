package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.kafka.model.StoredSchemaRegistry;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Mock-mode schema registry data: subjects, versions, schema ids, config and mode kept in floci's
 * own storage, with the Confluent API's rules and error codes. Schemas are not checked for
 * compatibility (every check passes) or normalized, and only Avro and JSON Schema payloads are
 * checked for being JSON.
 */
@ApplicationScoped
public class InMemorySchemaRegistryDataPlane implements SchemaRegistryDataPlane {

    static final Set<String> COMPATIBILITY_LEVELS = Set.of("NONE", "BACKWARD", "BACKWARD_TRANSITIVE", "FORWARD",
            "FORWARD_TRANSITIVE", "FULL", "FULL_TRANSITIVE");
    static final Set<String> MODES = Set.of("READONLY", "READWRITE", "IMPORT");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackend<String, RegistryData> store;

    @Inject
    public InMemorySchemaRegistryDataPlane(StorageFactory storageFactory) {
        this.store = storageFactory.createGlobal("kafka", "kafka-schema-registry-data.json",
                new TypeReference<Map<String, RegistryData>>() {});
    }

    @RegisterForReflection
    public static class RegistryData {
        public int nextId = 1;
        public Map<Integer, SchemaDef> schemas = new TreeMap<>();
        public Map<String, List<VersionDef>> subjects = new TreeMap<>();
        public String compatibility = "BACKWARD";
        public String mode = "READWRITE";
        public Map<String, String> subjectCompatibility = new TreeMap<>();
        public Map<String, String> subjectModes = new TreeMap<>();
    }

    @RegisterForReflection
    public static class SchemaDef {
        public String schemaType;
        public String schema;
        public String references;

        boolean sameAs(SchemaDef other) {
            return Objects.equals(schemaType, other.schemaType) && Objects.equals(schema, other.schema)
                    && Objects.equals(references, other.references);
        }
    }

    @RegisterForReflection
    public static class VersionDef {
        public int version;
        public int id;
        public boolean deleted;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void ensureRunning(StoredSchemaRegistry registry) {
    }

    @Override
    public void stop(StoredSchemaRegistry registry) {
    }

    @Override
    public synchronized void delete(StoredSchemaRegistry registry) {
        store.delete(registry.getName());
    }

    // ── Subjects and versions ─────────────────────────────────────────────────

    @Override
    public synchronized JsonNode listSubjects(StoredSchemaRegistry registry, String subjectPrefix, boolean deleted) {
        ArrayNode result = MAPPER.createArrayNode();
        data(registry).subjects.forEach((subject, versions) -> {
            boolean visible = versions.stream().anyMatch(v -> deleted || !v.deleted);
            if (visible && (subjectPrefix == null || subject.startsWith(subjectPrefix))) {
                result.add(subject);
            }
        });
        return result;
    }

    @Override
    public synchronized JsonNode deleteSubject(StoredSchemaRegistry registry, String subject, boolean permanent) {
        RegistryData data = data(registry);
        List<VersionDef> versions = data.subjects.get(subject);
        if (versions == null || versions.isEmpty()) {
            throw SchemaRegistryException.subjectNotFound(subject);
        }
        requireWritable(data, subject);
        boolean softDeleted = versions.stream().allMatch(v -> v.deleted);
        ArrayNode result = MAPPER.createArrayNode();
        if (permanent) {
            if (!softDeleted) {
                throw new SchemaRegistryException(404, 40405,
                        "Subject '" + subject + "' was not deleted first before being permanently deleted");
            }
            versions.forEach(v -> result.add(v.version));
            data.subjects.remove(subject);
            data.subjectCompatibility.remove(subject);
            data.subjectModes.remove(subject);
        } else {
            if (softDeleted) {
                throw new SchemaRegistryException(404, 40404, "Subject '" + subject + "' was soft deleted.");
            }
            versions.stream().filter(v -> !v.deleted).forEach(v -> {
                v.deleted = true;
                result.add(v.version);
            });
        }
        save(registry, data);
        return result;
    }

    @Override
    public synchronized JsonNode lookupVersion(StoredSchemaRegistry registry, String subject, ObjectNode schema,
            boolean normalize, boolean deleted) {
        RegistryData data = data(registry);
        List<VersionDef> versions = visibleVersions(data, subject, deleted);
        SchemaDef wanted = toSchemaDef(schema);
        for (VersionDef v : versions) {
            if (data.schemas.get(v.id).sameAs(wanted)) {
                return versionJson(data, subject, v, false);
            }
        }
        throw SchemaRegistryException.schemaNotFound("Schema not found");
    }

    @Override
    public synchronized JsonNode getVersion(StoredSchemaRegistry registry, String subject, String version,
            boolean deleted) {
        RegistryData data = data(registry);
        return versionJson(data, subject, findVersion(data, subject, version, deleted), true);
    }

    @Override
    public synchronized JsonNode listVersions(StoredSchemaRegistry registry, String subject, boolean deleted) {
        ArrayNode result = MAPPER.createArrayNode();
        visibleVersions(data(registry), subject, deleted).forEach(v -> result.add(v.version));
        return result;
    }

    @Override
    public synchronized JsonNode createVersion(StoredSchemaRegistry registry, String subject, ObjectNode schema,
            boolean normalize) {
        RegistryData data = data(registry);
        boolean explicit = schema.hasNonNull("version") || schema.hasNonNull("id");
        String mode = data.subjectModes.getOrDefault(subject, data.mode);
        if ("READONLY".equals(mode)) {
            throw new SchemaRegistryException(422, 42205, "Subject " + subject + " is in read-only mode");
        }
        if (explicit && !"IMPORT".equals(mode)) {
            throw new SchemaRegistryException(422, 42205, "Subject " + subject + " is not in import mode");
        }
        SchemaDef def = toSchemaDef(schema);
        validate(def);
        List<VersionDef> versions = data.subjects.computeIfAbsent(subject, k -> new ArrayList<>());
        if (!explicit) {
            for (VersionDef v : versions) {
                if (!v.deleted && data.schemas.get(v.id).sameAs(def)) {
                    return MAPPER.createObjectNode().put("id", v.id);
                }
            }
        }

        int id;
        if (schema.hasNonNull("id")) {
            id = schema.get("id").asInt();
            SchemaDef existing = data.schemas.get(id);
            if (existing != null && !existing.sameAs(def)) {
                throw new SchemaRegistryException(422, 42207,
                        "Schema id " + id + " already exists with a different schema");
            }
        } else {
            id = data.schemas.entrySet().stream().filter(e -> e.getValue().sameAs(def))
                    .map(Map.Entry::getKey).findFirst().orElse(data.nextId);
        }
        int version;
        if (schema.hasNonNull("version")) {
            version = schema.get("version").asInt();
            if (versions.stream().anyMatch(v -> v.version == version)) {
                throw new SchemaRegistryException(422, 42206, "Version " + version + " already exists");
            }
        } else {
            version = versions.stream().mapToInt(v -> v.version).max().orElse(0) + 1;
        }

        data.schemas.putIfAbsent(id, def);
        data.nextId = Math.max(data.nextId, id + 1);
        VersionDef created = new VersionDef();
        created.version = version;
        created.id = id;
        versions.add(created);
        versions.sort((a, b) -> Integer.compare(a.version, b.version));
        save(registry, data);
        return MAPPER.createObjectNode().put("id", id);
    }

    @Override
    public synchronized JsonNode deleteVersion(StoredSchemaRegistry registry, String subject, String version,
            boolean permanent) {
        RegistryData data = data(registry);
        VersionDef v = findVersion(data, subject, version, true);
        requireWritable(data, subject);
        if (permanent) {
            if (!v.deleted) {
                throw new SchemaRegistryException(404, 40407, "Subject '" + subject + "' Version " + v.version
                        + " was not deleted first before being permanently deleted");
            }
            List<VersionDef> versions = data.subjects.get(subject);
            versions.remove(v);
            if (versions.isEmpty()) {
                data.subjects.remove(subject);
            }
        } else {
            if (v.deleted) {
                throw new SchemaRegistryException(404, 40406,
                        "Subject '" + subject + "' Version " + v.version + " was soft deleted.");
            }
            v.deleted = true;
        }
        save(registry, data);
        return MAPPER.getNodeFactory().numberNode(v.version);
    }

    @Override
    public synchronized JsonNode getSchema(StoredSchemaRegistry registry, int id, String subject) {
        RegistryData data = data(registry);
        SchemaDef def = data.schemas.get(id);
        boolean inSubject = subject == null || data.subjects.getOrDefault(subject, List.of()).stream()
                .anyMatch(v -> v.id == id);
        if (def == null || !inSubject) {
            throw SchemaRegistryException.schemaNotFound("Schema " + id + " not found");
        }
        ObjectNode result = MAPPER.createObjectNode();
        if (!"AVRO".equals(def.schemaType)) {
            result.put("schemaType", def.schemaType);
        }
        result.put("schema", def.schema);
        putReferences(result, def);
        return result;
    }

    @Override
    public synchronized JsonNode checkCompatibility(StoredSchemaRegistry registry, String subject, String version,
            ObjectNode schema, boolean verbose) {
        findVersion(data(registry), subject, version, false);
        ObjectNode result = MAPPER.createObjectNode().put("is_compatible", true);
        if (verbose) {
            result.putArray("messages");
        }
        return result;
    }

    // ── Config and mode ───────────────────────────────────────────────────────

    @Override
    public synchronized JsonNode getConfig(StoredSchemaRegistry registry, String subject, boolean defaultToGlobal) {
        RegistryData data = data(registry);
        String level = subject == null ? data.compatibility : data.subjectCompatibility.get(subject);
        if (level == null && defaultToGlobal) {
            level = data.compatibility;
        }
        if (level == null) {
            throw new SchemaRegistryException(404, 40408,
                    "Subject '" + subject + "' does not have subject-level compatibility configured");
        }
        return MAPPER.createObjectNode().put("compatibilityLevel", level);
    }

    @Override
    public synchronized JsonNode updateConfig(StoredSchemaRegistry registry, String subject, String compatibility) {
        if (compatibility == null || !COMPATIBILITY_LEVELS.contains(compatibility)) {
            throw new SchemaRegistryException(422, 42203, "Invalid compatibility level. Valid values are NONE, "
                    + "BACKWARD, FORWARD, FULL, BACKWARD_TRANSITIVE, FORWARD_TRANSITIVE, and FULL_TRANSITIVE");
        }
        RegistryData data = data(registry);
        if (subject == null) {
            data.compatibility = compatibility;
        } else {
            data.subjectCompatibility.put(subject, compatibility);
        }
        save(registry, data);
        return MAPPER.createObjectNode().put("compatibility", compatibility);
    }

    @Override
    public synchronized JsonNode deleteConfig(StoredSchemaRegistry registry, String subject) {
        RegistryData data = data(registry);
        String removed = data.subjectCompatibility.remove(subject);
        if (removed == null) {
            throw new SchemaRegistryException(404, 40408,
                    "Subject '" + subject + "' does not have subject-level compatibility configured");
        }
        save(registry, data);
        return MAPPER.createObjectNode().put("compatibilityLevel", removed);
    }

    @Override
    public synchronized JsonNode getMode(StoredSchemaRegistry registry, String subject) {
        RegistryData data = data(registry);
        String mode = subject == null ? data.mode : data.subjectModes.get(subject);
        if (mode == null) {
            throw new SchemaRegistryException(404, 40409,
                    "Subject '" + subject + "' does not have subject-level mode configured");
        }
        return MAPPER.createObjectNode().put("mode", mode);
    }

    @Override
    public synchronized JsonNode updateMode(StoredSchemaRegistry registry, String subject, String mode) {
        if (mode == null || !MODES.contains(mode)) {
            throw new SchemaRegistryException(422, 42204, "Invalid mode. Valid values are READONLY, READWRITE "
                    + "and IMPORT");
        }
        RegistryData data = data(registry);
        if (subject == null) {
            data.mode = mode;
        } else {
            data.subjectModes.put(subject, mode);
        }
        save(registry, data);
        return MAPPER.createObjectNode().put("mode", mode);
    }

    @Override
    public synchronized JsonNode deleteMode(StoredSchemaRegistry registry, String subject) {
        RegistryData data = data(registry);
        String removed = data.subjectModes.remove(subject);
        if (removed == null) {
            throw new SchemaRegistryException(404, 40409,
                    "Subject '" + subject + "' does not have subject-level mode configured");
        }
        save(registry, data);
        return MAPPER.createObjectNode().put("mode", removed);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private RegistryData data(StoredSchemaRegistry registry) {
        return store.get(registry.getName()).orElseGet(RegistryData::new);
    }

    private void save(StoredSchemaRegistry registry, RegistryData data) {
        store.put(registry.getName(), data);
    }

    private static void requireWritable(RegistryData data, String subject) {
        if ("READONLY".equals(data.subjectModes.getOrDefault(subject, data.mode))) {
            throw new SchemaRegistryException(422, 42205, "Subject " + subject + " is in read-only mode");
        }
    }

    private static List<VersionDef> visibleVersions(RegistryData data, String subject, boolean deleted) {
        List<VersionDef> versions = data.subjects.getOrDefault(subject, List.of()).stream()
                .filter(v -> deleted || !v.deleted).toList();
        if (versions.isEmpty()) {
            throw SchemaRegistryException.subjectNotFound(subject);
        }
        return versions;
    }

    private static VersionDef findVersion(RegistryData data, String subject, String version, boolean deleted) {
        List<VersionDef> versions = visibleVersions(data, subject, deleted);
        if ("latest".equals(version) || "-1".equals(version)) {
            return versions.get(versions.size() - 1);
        }
        int number;
        try {
            number = Integer.parseInt(version);
        } catch (NumberFormatException e) {
            throw new SchemaRegistryException(422, 42202,
                    "The specified version '" + version + "' is not a valid version id. Allowed values are "
                            + "between [1, 2^31-1] and the string \"latest\"");
        }
        return versions.stream().filter(v -> v.version == number).findFirst()
                .orElseThrow(() -> SchemaRegistryException.versionNotFound(version));
    }

    private static ObjectNode versionJson(RegistryData data, String subject, VersionDef v, boolean withDeleted) {
        SchemaDef def = data.schemas.get(v.id);
        ObjectNode result = MAPPER.createObjectNode()
                .put("subject", subject)
                .put("version", v.version)
                .put("id", v.id)
                .put("schemaType", def.schemaType)
                .put("schema", def.schema);
        putReferences(result, def);
        if (withDeleted) {
            result.put("deleted", v.deleted);
        }
        return result;
    }

    private static void putReferences(ObjectNode target, SchemaDef def) {
        if (def.references != null) {
            try {
                target.set("references", MAPPER.readTree(def.references));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static SchemaDef toSchemaDef(ObjectNode schema) {
        SchemaDef def = new SchemaDef();
        def.schemaType = schema.hasNonNull("schemaType") ? schema.get("schemaType").asText() : "AVRO";
        def.schema = schema.path("schema").asText(null);
        JsonNode references = schema.get("references");
        def.references = references != null && references.isArray() && !references.isEmpty()
                ? references.toString() : null;
        return def;
    }

    private static void validate(SchemaDef def) {
        if (def.schema == null || def.schema.isBlank()) {
            throw new SchemaRegistryException(422, 42201, "Invalid schema: empty schema");
        }
        if (!Set.of("AVRO", "JSON", "PROTOBUF").contains(def.schemaType)) {
            throw new SchemaRegistryException(422, 422, "Invalid schema type " + def.schemaType);
        }
        if (!"PROTOBUF".equals(def.schemaType)) {
            try {
                MAPPER.readTree(def.schema);
            } catch (JsonProcessingException e) {
                throw new SchemaRegistryException(422, 42201, "Invalid schema: " + e.getOriginalMessage());
            }
        }
    }
}

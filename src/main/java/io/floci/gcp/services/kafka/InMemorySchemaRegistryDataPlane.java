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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Mock-mode schema registry data: subjects, versions, schema ids, config and mode kept in floci's
 * own storage, with the Confluent API's rules and error codes. Schemas are not checked for
 * compatibility (every check passes) or normalized, and only Avro and JSON Schema payloads are
 * checked for being JSON.
 *
 * <p>Contexts follow Confluent: each has its own schema ids, subjects and config; a subject named
 * {@code :.ctx:name} belongs to context {@code .ctx} wherever it is addressed; subjects outside
 * the default context are reported in that qualified form; and a context with no config or mode of
 * its own uses the built in default ({@code BACKWARD}, {@code READWRITE}), not the default
 * context's, as Confluent 7.9 and Redpanda do.
 */
@ApplicationScoped
public class InMemorySchemaRegistryDataPlane implements SchemaRegistryDataPlane {

    static final Set<String> COMPATIBILITY_LEVELS = Set.of("NONE", "BACKWARD", "BACKWARD_TRANSITIVE", "FORWARD",
            "FORWARD_TRANSITIVE", "FULL", "FULL_TRANSITIVE");
    static final Set<String> MODES = Set.of("READONLY", "READWRITE", "IMPORT");
    private static final String DEFAULT_CONTEXT = ".";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StorageBackend<String, RegistryData> store;

    @Inject
    public InMemorySchemaRegistryDataPlane(StorageFactory storageFactory) {
        this.store = storageFactory.createGlobal("kafka", "kafka-schema-registry-data.json",
                new TypeReference<Map<String, RegistryData>>() {});
    }

    @RegisterForReflection
    public static class RegistryData {
        public Map<String, ContextData> contexts = new TreeMap<>();
    }

    @RegisterForReflection
    public static class ContextData {
        public int nextId = 1;
        public Map<Integer, SchemaDef> schemas = new TreeMap<>();
        public Map<String, List<VersionDef>> subjects = new TreeMap<>();
        /** Context-level settings; {@code null} means the built in default. */
        public String compatibility;
        public String mode;
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

    /** A subject resolved to its context. */
    private record Subject(String context, ContextData data, String name) {
        String qualified() {
            return qualify(context, name);
        }
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

    // ── Contexts and subjects ─────────────────────────────────────────────────

    @Override
    public synchronized JsonNode listContexts(StoredSchemaRegistry registry) {
        Set<String> names = new TreeSet<>();
        names.add(DEFAULT_CONTEXT);
        data(registry).contexts.forEach((name, context) -> {
            if (!context.subjects.isEmpty()) {
                names.add(name);
            }
        });
        ArrayNode result = MAPPER.createArrayNode();
        names.forEach(result::add);
        return result;
    }

    @Override
    public synchronized JsonNode listSubjects(Scope scope, String subjectPrefix, boolean deleted) {
        RegistryData data = data(scope.registry());
        ArrayNode result = MAPPER.createArrayNode();
        data.contexts.forEach((contextName, context) -> {
            if (scope.context() != null && !contextName.equals(contextName(scope.context()))) {
                return;
            }
            context.subjects.forEach((subject, versions) -> {
                String qualified = qualify(contextName, subject);
                boolean visible = versions.stream().anyMatch(v -> deleted || !v.deleted);
                if (visible && (subjectPrefix == null || qualified.startsWith(subjectPrefix))) {
                    result.add(qualified);
                }
            });
        });
        return result;
    }

    @Override
    public synchronized JsonNode deleteSubject(Scope scope, String subjectName, boolean permanent) {
        RegistryData data = data(scope.registry());
        Subject subject = resolve(data, scope, subjectName);
        List<VersionDef> versions = subject.data().subjects.get(subject.name());
        if (versions == null || versions.isEmpty()) {
            throw SchemaRegistryException.subjectNotFound(subject.qualified());
        }
        requireWritable(subject);
        boolean softDeleted = versions.stream().allMatch(v -> v.deleted);
        ArrayNode result = MAPPER.createArrayNode();
        if (permanent) {
            if (!softDeleted) {
                throw new SchemaRegistryException(404, 40405, "Subject '" + subject.qualified()
                        + "' was not deleted first before being permanently deleted");
            }
            versions.forEach(v -> result.add(v.version));
            subject.data().subjects.remove(subject.name());
            subject.data().subjectCompatibility.remove(subject.name());
            subject.data().subjectModes.remove(subject.name());
            dropUnreferencedSchemas(subject.data());
        } else {
            if (softDeleted) {
                throw new SchemaRegistryException(404, 40404,
                        "Subject '" + subject.qualified() + "' was soft deleted.");
            }
            versions.stream().filter(v -> !v.deleted).forEach(v -> {
                v.deleted = true;
                result.add(v.version);
            });
        }
        save(scope.registry(), data);
        return result;
    }

    // ── Versions ──────────────────────────────────────────────────────────────

    @Override
    public synchronized JsonNode lookupVersion(Scope scope, String subjectName, ObjectNode schema,
            boolean normalize, boolean deleted) {
        Subject subject = resolve(data(scope.registry()), scope, subjectName);
        SchemaDef wanted = toSchemaDef(schema);
        for (VersionDef v : visibleVersions(subject, deleted)) {
            if (subject.data().schemas.get(v.id).sameAs(wanted)) {
                return versionJson(subject, v, false);
            }
        }
        throw SchemaRegistryException.schemaNotFound("Schema not found");
    }

    @Override
    public synchronized JsonNode getVersion(Scope scope, String subjectName, String version, boolean deleted) {
        Subject subject = resolve(data(scope.registry()), scope, subjectName);
        return versionJson(subject, findVersion(subject, version, deleted), true);
    }

    @Override
    public synchronized String getRawSchemaVersion(Scope scope, String subjectName, String version,
            boolean deleted) {
        Subject subject = resolve(data(scope.registry()), scope, subjectName);
        return subject.data().schemas.get(findVersion(subject, version, deleted).id).schema;
    }

    @Override
    public synchronized JsonNode listVersions(Scope scope, String subjectName, boolean deleted) {
        Subject subject = resolve(data(scope.registry()), scope, subjectName);
        ArrayNode result = MAPPER.createArrayNode();
        visibleVersions(subject, deleted).forEach(v -> result.add(v.version));
        return result;
    }

    @Override
    public synchronized JsonNode createVersion(Scope scope, String subjectName, ObjectNode schema,
            boolean normalize) {
        RegistryData data = data(scope.registry());
        Subject subject = resolve(data, scope, subjectName);
        ContextData context = subject.data();
        boolean explicit = schema.hasNonNull("version") || schema.hasNonNull("id");
        String mode = effectiveMode(subject);
        if ("READONLY".equals(mode)) {
            throw readOnly(subject);
        }
        if (explicit && !"IMPORT".equals(mode)) {
            throw new SchemaRegistryException(422, 42205,
                    "Subject " + subject.qualified() + " is not in import mode");
        }
        SchemaDef def = toSchemaDef(schema);
        validate(def);
        List<VersionDef> versions = context.subjects.computeIfAbsent(subject.name(), k -> new ArrayList<>());
        if (!explicit) {
            for (VersionDef v : versions) {
                if (!v.deleted && context.schemas.get(v.id).sameAs(def)) {
                    return MAPPER.createObjectNode().put("id", v.id);
                }
            }
        }

        int id;
        if (schema.hasNonNull("id")) {
            id = schema.get("id").asInt();
            SchemaDef existing = context.schemas.get(id);
            if (existing != null && !existing.sameAs(def)) {
                throw new SchemaRegistryException(422, 42207,
                        "Schema id " + id + " already exists with a different schema");
            }
        } else {
            id = context.schemas.entrySet().stream().filter(e -> e.getValue().sameAs(def))
                    .map(Map.Entry::getKey).findFirst().orElse(context.nextId);
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

        context.schemas.putIfAbsent(id, def);
        context.nextId = Math.max(context.nextId, id + 1);
        VersionDef created = new VersionDef();
        created.version = version;
        created.id = id;
        versions.add(created);
        versions.sort((a, b) -> Integer.compare(a.version, b.version));
        save(scope.registry(), data);
        return MAPPER.createObjectNode().put("id", id);
    }

    @Override
    public synchronized JsonNode deleteVersion(Scope scope, String subjectName, String version, boolean permanent) {
        RegistryData data = data(scope.registry());
        Subject subject = resolve(data, scope, subjectName);
        VersionDef v = findVersion(subject, version, true);
        requireWritable(subject);
        if (permanent) {
            if (!v.deleted) {
                throw new SchemaRegistryException(404, 40407, "Subject '" + subject.qualified() + "' Version "
                        + v.version + " was not deleted first before being permanently deleted");
            }
            List<VersionDef> versions = subject.data().subjects.get(subject.name());
            versions.remove(v);
            if (versions.isEmpty()) {
                subject.data().subjects.remove(subject.name());
            }
            dropUnreferencedSchemas(subject.data());
        } else {
            if (v.deleted) {
                throw new SchemaRegistryException(404, 40406,
                        "Subject '" + subject.qualified() + "' Version " + v.version + " was soft deleted.");
            }
            v.deleted = true;
        }
        save(scope.registry(), data);
        return MAPPER.getNodeFactory().numberNode(v.version);
    }

    /**
     * Redpanda forgets a schema once no subject version of its context, soft deleted or not, uses it: its id then
     * answers 40403, and registering the same schema again gets a new id ({@code nextId} never goes back).
     */
    private static void dropUnreferencedSchemas(ContextData data) {
        Set<Integer> referenced = new HashSet<>();
        data.subjects.values().forEach(versions -> versions.forEach(v -> referenced.add(v.id)));
        data.schemas.keySet().retainAll(referenced);
    }

    @Override
    public synchronized JsonNode listReferencedSchemas(Scope scope, String subjectName, String version) {
        Subject subject = resolve(data(scope.registry()), scope, subjectName);
        VersionDef target = findVersion(subject, version, false);
        Set<Integer> ids = new TreeSet<>();
        subject.data().subjects.values().forEach(versions -> versions.stream().filter(v -> !v.deleted)
                .forEach(v -> {
                    for (JsonNode ref : references(subject.data().schemas.get(v.id))) {
                        String refSubject = ref.path("subject").asText();
                        boolean sameSubject = refSubject.equals(subject.name()) || refSubject.equals(subject.qualified());
                        if (sameSubject && ref.path("version").asInt() == target.version) {
                            ids.add(v.id);
                        }
                    }
                }));
        ArrayNode result = MAPPER.createArrayNode();
        ids.forEach(result::add);
        return result;
    }

    // ── Schemas by id ─────────────────────────────────────────────────────────

    @Override
    public synchronized JsonNode getSchema(Scope scope, int id, String subjectName) {
        SchemaDef def = schemaById(scope, id, subjectName);
        ObjectNode result = MAPPER.createObjectNode();
        if (!"AVRO".equals(def.schemaType)) {
            result.put("schemaType", def.schemaType);
        }
        result.put("schema", def.schema);
        putReferences(result, def);
        return result;
    }

    @Override
    public synchronized String getRawSchema(Scope scope, int id, String subjectName) {
        return schemaById(scope, id, subjectName).schema;
    }

    @Override
    public synchronized JsonNode listSchemaVersions(Scope scope, int id, String subjectName, boolean deleted) {
        ArrayNode result = MAPPER.createArrayNode();
        usesOf(scope, id, subjectName, deleted).forEach((subject, versions) -> versions.forEach(version ->
                result.addObject().put("subject", subject).put("version", version)));
        return result;
    }

    @Override
    public synchronized JsonNode listSubjectsBySchemaId(Scope scope, int id, String subjectName, boolean deleted) {
        ArrayNode result = MAPPER.createArrayNode();
        usesOf(scope, id, subjectName, deleted).keySet().forEach(result::add);
        return result;
    }

    @Override
    public JsonNode listSchemaTypes(Scope scope) {
        return MAPPER.createArrayNode().add("JSON").add("PROTOBUF").add("AVRO");
    }

    @Override
    public synchronized JsonNode checkCompatibility(Scope scope, String subjectName, String version,
            ObjectNode schema, boolean verbose) {
        findVersion(resolve(data(scope.registry()), scope, subjectName), version, false);
        ObjectNode result = MAPPER.createObjectNode().put("is_compatible", true);
        if (verbose) {
            result.putArray("messages");
        }
        return result;
    }

    // ── Config and mode ───────────────────────────────────────────────────────

    @Override
    public synchronized JsonNode getConfig(Scope scope, String subjectName, boolean defaultToGlobal) {
        RegistryData data = data(scope.registry());
        String level;
        if (subjectName == null) {
            String context = contextName(scope.context());
            level = context(data, context).compatibility;
            if (level == null && DEFAULT_CONTEXT.equals(context)) {
                level = "BACKWARD";
            }
            if (level == null) {
                throw new SchemaRegistryException(404, 40408,
                        "Context '" + context + "' does not have context-level compatibility configured");
            }
        } else {
            Subject subject = resolve(data, scope, subjectName);
            level = subject.data().subjectCompatibility.get(subject.name());
            if (level == null && defaultToGlobal) {
                level = effectiveCompatibility(data, subject.context());
            }
            if (level == null) {
                throw new SchemaRegistryException(404, 40408, "Subject '" + subject.qualified()
                        + "' does not have subject-level compatibility configured");
            }
        }
        return MAPPER.createObjectNode().put("compatibilityLevel", level);
    }

    @Override
    public synchronized JsonNode updateConfig(Scope scope, String subjectName, String compatibility) {
        if (compatibility == null || !COMPATIBILITY_LEVELS.contains(compatibility)) {
            throw new SchemaRegistryException(422, 42203, "Invalid compatibility level. Valid values are NONE, "
                    + "BACKWARD, FORWARD, FULL, BACKWARD_TRANSITIVE, FORWARD_TRANSITIVE, and FULL_TRANSITIVE");
        }
        RegistryData data = data(scope.registry());
        if (subjectName == null) {
            context(data, contextName(scope.context())).compatibility = compatibility;
        } else {
            Subject subject = resolve(data, scope, subjectName);
            subject.data().subjectCompatibility.put(subject.name(), compatibility);
        }
        save(scope.registry(), data);
        return MAPPER.createObjectNode().put("compatibility", compatibility);
    }

    @Override
    public synchronized JsonNode deleteConfig(Scope scope, String subjectName) {
        RegistryData data = data(scope.registry());
        Subject subject = resolve(data, scope, subjectName);
        String removed = subject.data().subjectCompatibility.remove(subject.name());
        if (removed == null) {
            throw new SchemaRegistryException(404, 40408, "Subject '" + subject.qualified()
                    + "' does not have subject-level compatibility configured");
        }
        save(scope.registry(), data);
        return MAPPER.createObjectNode().put("compatibilityLevel", removed);
    }

    @Override
    public synchronized JsonNode getMode(Scope scope, String subjectName) {
        RegistryData data = data(scope.registry());
        String mode;
        String owner;
        if (subjectName == null) {
            owner = "Context '" + contextName(scope.context()) + "' does not have context-level";
            mode = context(data, contextName(scope.context())).mode;
            if (mode == null && DEFAULT_CONTEXT.equals(contextName(scope.context()))) {
                mode = "READWRITE";
            }
        } else {
            Subject subject = resolve(data, scope, subjectName);
            owner = "Subject '" + subject.qualified() + "' does not have subject-level";
            mode = subject.data().subjectModes.get(subject.name());
        }
        if (mode == null) {
            throw new SchemaRegistryException(404, 40409, owner + " mode configured");
        }
        return MAPPER.createObjectNode().put("mode", mode);
    }

    @Override
    public synchronized JsonNode updateMode(Scope scope, String subjectName, String mode) {
        if (mode == null || !MODES.contains(mode)) {
            throw new SchemaRegistryException(422, 42204, "Invalid mode. Valid values are READONLY, READWRITE "
                    + "and IMPORT");
        }
        RegistryData data = data(scope.registry());
        if (subjectName == null) {
            context(data, contextName(scope.context())).mode = mode;
        } else {
            Subject subject = resolve(data, scope, subjectName);
            subject.data().subjectModes.put(subject.name(), mode);
        }
        save(scope.registry(), data);
        return MAPPER.createObjectNode().put("mode", mode);
    }

    @Override
    public synchronized JsonNode deleteMode(Scope scope, String subjectName) {
        RegistryData data = data(scope.registry());
        Subject subject = resolve(data, scope, subjectName);
        String removed = subject.data().subjectModes.remove(subject.name());
        if (removed == null) {
            throw new SchemaRegistryException(404, 40409, "Subject '" + subject.qualified()
                    + "' does not have subject-level mode configured");
        }
        save(scope.registry(), data);
        return MAPPER.createObjectNode().put("mode", removed);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private RegistryData data(StoredSchemaRegistry registry) {
        return store.get(registry.getName()).orElseGet(RegistryData::new);
    }

    private void save(StoredSchemaRegistry registry, RegistryData data) {
        store.put(registry.getName(), data);
    }

    private static ContextData context(RegistryData data, String name) {
        return data.contexts.computeIfAbsent(name, k -> new ContextData());
    }

    /** Confluent context names start with a dot; {@code null} is the default context. */
    private static String contextName(String context) {
        if (context == null || context.isEmpty() || DEFAULT_CONTEXT.equals(context)) {
            return DEFAULT_CONTEXT;
        }
        return context.startsWith(".") ? context : "." + context;
    }

    private static String qualify(String context, String subject) {
        return DEFAULT_CONTEXT.equals(context) ? subject : ":" + context + ":" + subject;
    }

    /** A {@code :.ctx:name} subject names its context; any other is in the scope's context. */
    private static Subject resolve(RegistryData data, Scope scope, String subject) {
        if (subject.startsWith(":.")) {
            int end = subject.indexOf(':', 1);
            if (end > 1) {
                String context = contextName(subject.substring(1, end));
                return new Subject(context, context(data, context), subject.substring(end + 1));
            }
        }
        String context = contextName(scope.context());
        return new Subject(context, context(data, context), subject);
    }

    /**
     * A context with no level of its own uses the built in default, not the default context's, as
     * Confluent (7.9) and Redpanda do.
     */
    private static String effectiveCompatibility(RegistryData data, String context) {
        String level = context(data, context).compatibility;
        return level != null ? level : "BACKWARD";
    }

    /** Subject, then its own context, then the built in default; never the default context's mode. */
    private static String effectiveMode(Subject subject) {
        String mode = subject.data().subjectModes.get(subject.name());
        if (mode == null) {
            mode = subject.data().mode;
        }
        return mode != null ? mode : "READWRITE";
    }

    private static void requireWritable(Subject subject) {
        if ("READONLY".equals(effectiveMode(subject))) {
            throw readOnly(subject);
        }
    }

    private static SchemaRegistryException readOnly(Subject subject) {
        return new SchemaRegistryException(422, 42205, "Subject " + subject.qualified() + " is in read-only mode");
    }

    private static List<VersionDef> visibleVersions(Subject subject, boolean deleted) {
        List<VersionDef> versions = subject.data().subjects.getOrDefault(subject.name(), List.of()).stream()
                .filter(v -> deleted || !v.deleted).toList();
        if (versions.isEmpty()) {
            throw SchemaRegistryException.subjectNotFound(subject.qualified());
        }
        return versions;
    }

    private static VersionDef findVersion(Subject subject, String version, boolean deleted) {
        List<VersionDef> versions = visibleVersions(subject, deleted);
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

    /** The schema with {@code id} in the scope's context (or the subject's), optionally used by the subject. */
    private SchemaDef schemaById(Scope scope, int id, String subjectName) {
        RegistryData data = data(scope.registry());
        Subject subject = subjectName != null ? resolve(data, scope, subjectName) : null;
        ContextData context = subject != null ? subject.data() : context(data, contextName(scope.context()));
        SchemaDef def = context.schemas.get(id);
        boolean used = subject == null || context.subjects.getOrDefault(subject.name(), List.of()).stream()
                .anyMatch(v -> v.id == id);
        if (def == null || !used) {
            throw SchemaRegistryException.schemaNotFound("Schema " + id + " not found");
        }
        return def;
    }

    /** Qualified subject names and their versions that use schema {@code id}. */
    private Map<String, List<Integer>> usesOf(Scope scope, int id, String subjectName, boolean deleted) {
        RegistryData data = data(scope.registry());
        Subject only = subjectName != null ? resolve(data, scope, subjectName) : null;
        String contextName = only != null ? only.context() : contextName(scope.context());
        ContextData context = context(data, contextName);
        if (!context.schemas.containsKey(id)) {
            throw SchemaRegistryException.schemaNotFound("Schema " + id + " not found");
        }
        Map<String, List<Integer>> uses = new TreeMap<>();
        context.subjects.forEach((subject, versions) -> {
            if (only != null && !only.name().equals(subject)) {
                return;
            }
            versions.stream().filter(v -> v.id == id && (deleted || !v.deleted)).forEach(v ->
                    uses.computeIfAbsent(qualify(contextName, subject), k -> new ArrayList<>()).add(v.version));
        });
        return uses;
    }

    private static ObjectNode versionJson(Subject subject, VersionDef v, boolean withDeleted) {
        SchemaDef def = subject.data().schemas.get(v.id);
        ObjectNode result = MAPPER.createObjectNode()
                .put("subject", subject.qualified())
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

    private static JsonNode references(SchemaDef def) {
        if (def.references == null) {
            return MAPPER.createArrayNode();
        }
        try {
            return MAPPER.readTree(def.references);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored schema references are not JSON: " + def.references, e);
        }
    }

    private static void putReferences(ObjectNode target, SchemaDef def) {
        if (def.references != null) {
            target.set("references", references(def));
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

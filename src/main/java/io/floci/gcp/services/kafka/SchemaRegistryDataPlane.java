package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.services.kafka.model.StoredSchemaRegistry;

/**
 * The data behind a schema registry, spoken in the Confluent Schema Registry API that the Google
 * API mirrors. Every method returns the Confluent response body and signals a Confluent error
 * ({@code {"error_code": ..., "message": ...}}) as a {@link SchemaRegistryException}.
 * {@link SchemaRegistryService} maps both to the Google resource shapes and errors.
 *
 * <p>Data calls take a {@link Scope}: the registry, and the context the request names
 * ({@code null} for the default context). {@code version} arguments are the path segment as
 * given: a number, {@code latest} or {@code -1}. {@code subject} is {@code null} for the
 * registry- or context-wide config and mode.
 */
public interface SchemaRegistryDataPlane {

    /** A registry, and the context a request is addressed to ({@code null}: the default context). */
    record Scope(StoredSchemaRegistry registry, String context) {}

    /** Makes the registry's data reachable, starting whatever backs it if needed. */
    void ensureRunning(StoredSchemaRegistry registry);

    /** Stops whatever backs the registry, keeping its data. */
    void stop(StoredSchemaRegistry registry);

    /** Stops whatever backs the registry and drops its data. */
    void delete(StoredSchemaRegistry registry);

    /** The registry's contexts, as Confluent names them ({@code "."} for the default). */
    JsonNode listContexts(StoredSchemaRegistry registry);

    JsonNode listSubjects(Scope scope, String subjectPrefix, boolean deleted);

    JsonNode deleteSubject(Scope scope, String subject, boolean permanent);

    /** {@code schema} carries {@code schema}, and optionally {@code schemaType} and {@code references}. */
    JsonNode lookupVersion(Scope scope, String subject, ObjectNode schema, boolean normalize, boolean deleted);

    JsonNode getVersion(Scope scope, String subject, String version, boolean deleted);

    /** The version's schema text alone. */
    String getRawSchemaVersion(Scope scope, String subject, String version, boolean deleted);

    JsonNode listVersions(Scope scope, String subject, boolean deleted);

    /** {@code schema} may also carry an explicit {@code version} and {@code id} (import mode only). */
    JsonNode createVersion(Scope scope, String subject, ObjectNode schema, boolean normalize);

    JsonNode deleteVersion(Scope scope, String subject, String version, boolean permanent);

    /** Ids of the schemas whose references name this version. */
    JsonNode listReferencedSchemas(Scope scope, String subject, String version);

    JsonNode getSchema(Scope scope, int id, String subject);

    /** The schema's text alone. */
    String getRawSchema(Scope scope, int id, String subject);

    /** {@code [{"subject": ..., "version": ...}]} for every version that uses the schema. */
    JsonNode listSchemaVersions(Scope scope, int id, String subject, boolean deleted);

    JsonNode listSubjectsBySchemaId(Scope scope, int id, String subject, boolean deleted);

    JsonNode listSchemaTypes(Scope scope);

    /** Checks {@code schema} against one version of the subject. */
    JsonNode checkCompatibility(Scope scope, String subject, String version, ObjectNode schema, boolean verbose);

    JsonNode getConfig(Scope scope, String subject, boolean defaultToGlobal);

    JsonNode updateConfig(Scope scope, String subject, String compatibility);

    JsonNode deleteConfig(Scope scope, String subject);

    JsonNode getMode(Scope scope, String subject);

    JsonNode updateMode(Scope scope, String subject, String mode);

    JsonNode deleteMode(Scope scope, String subject);
}

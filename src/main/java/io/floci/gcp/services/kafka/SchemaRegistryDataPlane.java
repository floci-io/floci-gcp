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
 * <p>{@code version} arguments are the path segment as given: a number, {@code latest} or
 * {@code -1}. {@code subject} is {@code null} for the registry-wide config and mode.
 */
public interface SchemaRegistryDataPlane {

    /** Makes the registry's data reachable, starting whatever backs it if needed. */
    void ensureRunning(StoredSchemaRegistry registry);

    /** Stops whatever backs the registry, keeping its data. */
    void stop(StoredSchemaRegistry registry);

    /** Stops whatever backs the registry and drops its data. */
    void delete(StoredSchemaRegistry registry);

    JsonNode listSubjects(StoredSchemaRegistry registry, String subjectPrefix, boolean deleted);

    JsonNode deleteSubject(StoredSchemaRegistry registry, String subject, boolean permanent);

    /** {@code schema} carries {@code schema}, and optionally {@code schemaType} and {@code references}. */
    JsonNode lookupVersion(StoredSchemaRegistry registry, String subject, ObjectNode schema,
            boolean normalize, boolean deleted);

    JsonNode getVersion(StoredSchemaRegistry registry, String subject, String version, boolean deleted);

    JsonNode listVersions(StoredSchemaRegistry registry, String subject, boolean deleted);

    /** {@code schema} may also carry an explicit {@code version} and {@code id} (import mode only). */
    JsonNode createVersion(StoredSchemaRegistry registry, String subject, ObjectNode schema, boolean normalize);

    JsonNode deleteVersion(StoredSchemaRegistry registry, String subject, String version, boolean permanent);

    JsonNode getSchema(StoredSchemaRegistry registry, int id, String subject);

    /** Checks {@code schema} against one version of the subject. */
    JsonNode checkCompatibility(StoredSchemaRegistry registry, String subject, String version, ObjectNode schema,
            boolean verbose);

    JsonNode getConfig(StoredSchemaRegistry registry, String subject, boolean defaultToGlobal);

    JsonNode updateConfig(StoredSchemaRegistry registry, String subject, String compatibility);

    JsonNode deleteConfig(StoredSchemaRegistry registry, String subject);

    JsonNode getMode(StoredSchemaRegistry registry, String subject);

    JsonNode updateMode(StoredSchemaRegistry registry, String subject, String mode);

    JsonNode deleteMode(StoredSchemaRegistry registry, String subject);
}

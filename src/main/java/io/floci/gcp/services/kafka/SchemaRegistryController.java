package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.kafka.SchemaRegistryService.Target;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * REST JSON for {@code google.cloud.managedkafka.schemaregistry.v1.ManagedSchemaRegistry}. The
 * routes under a registry mirror the Confluent Schema Registry API, so a Confluent serializer
 * pointed at {@code .../schemaRegistries/{registry}} works unchanged; bodies are therefore read
 * whatever their content type ({@code application/vnd.schemaregistry.v1+json} included).
 * Methods whose proto response is {@code google.api.HttpBody} return the Confluent JSON as is.
 * Flags a Confluent client sends as query parameters ({@code normalize}, {@code deleted},
 * {@code verbose}) are read there as well as from the Google request body.
 *
 * <p>Every route under a registry also has the proto's {@code contexts/{context}} binding: the
 * optional {@code ctx} segment ({@value #CONTEXT}) addresses that context.
 */
@Path("/v1/projects/{project}/locations/{location}/schemaRegistries")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class SchemaRegistryController {

    /** An optional {@code /contexts/{context}} segment after the registry id. */
    private static final String CONTEXT = "{ctx: (/contexts/[^/]+)?}";
    private static final String CONTEXT_PREFIX = "/contexts/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SchemaRegistryService service;

    @Inject
    public SchemaRegistryController(SchemaRegistryService service) {
        this.service = service;
    }

    // ── Registries ────────────────────────────────────────────────────────────

    @POST
    public Response createRegistry(@PathParam("project") String project, @PathParam("location") String location,
            String body) {
        JsonNode request = parse(body);
        return ok(service.createRegistry(project, location, request.path("schemaRegistryId").asText(null)));
    }

    @GET
    public Response listRegistries(@PathParam("project") String project, @PathParam("location") String location) {
        return ok(Map.of("schemaRegistries", service.listRegistries(project, location)));
    }

    @GET
    @Path("/{registry}")
    public Response getRegistry(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry) {
        return ok(service.getRegistry(project, location, registry));
    }

    @DELETE
    @Path("/{registry}")
    public Response deleteRegistry(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry) {
        service.deleteRegistry(project, location, registry);
        return ok(Map.of());
    }

    // ── Contexts ──────────────────────────────────────────────────────────────

    @GET
    @Path("/{registry}/contexts")
    public Response listContexts(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry) {
        return ok(service.listContexts(new Target(project, location, registry, null)));
    }

    @GET
    @Path("/{registry}/contexts/{context}")
    public Response getContext(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("context") String context) {
        return ok(service.getContext(new Target(project, location, registry, context)));
    }

    // ── Subjects and versions ─────────────────────────────────────────────────

    @GET
    @Path("/{registry}" + CONTEXT + "/subjects")
    public Response listSubjects(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx,
            @QueryParam("subjectPrefix") String subjectPrefix, @QueryParam("deleted") boolean deleted) {
        return ok(service.listSubjects(target(project, location, registry, ctx), subjectPrefix, deleted));
    }

    @POST
    @Path("/{registry}" + CONTEXT + "/subjects/{subject}")
    public Response lookupVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx,
            @PathParam("subject") String subject, @QueryParam("normalize") Boolean normalize,
            @QueryParam("deleted") Boolean deleted, String body) {
        return ok(service.lookupVersion(target(project, location, registry, ctx), subject, parse(body), normalize,
                deleted));
    }

    @DELETE
    @Path("/{registry}" + CONTEXT + "/subjects/{subject}")
    public Response deleteSubject(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx,
            @PathParam("subject") String subject, @QueryParam("permanent") boolean permanent) {
        return ok(service.deleteSubject(target(project, location, registry, ctx), subject, permanent));
    }

    @GET
    @Path("/{registry}" + CONTEXT + "/subjects/{subject}/versions")
    public Response listVersions(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx,
            @PathParam("subject") String subject, @QueryParam("deleted") boolean deleted) {
        return ok(service.listVersions(target(project, location, registry, ctx), subject, deleted));
    }

    @POST
    @Path("/{registry}" + CONTEXT + "/subjects/{subject}/versions")
    public Response createVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx,
            @PathParam("subject") String subject, @QueryParam("normalize") Boolean normalize, String body) {
        return ok(service.createVersion(target(project, location, registry, ctx), subject, parse(body), normalize));
    }

    @GET
    @Path("/{registry}" + CONTEXT + "/subjects/{subject}/versions/{version}")
    public Response getVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx,
            @PathParam("subject") String subject, @PathParam("version") String version,
            @QueryParam("deleted") boolean deleted) {
        return ok(service.getVersion(target(project, location, registry, ctx), subject, version, deleted));
    }

    @GET
    @Path("/{registry}" + CONTEXT + "/subjects/{subject}/versions/{version}/schema")
    public Response getRawSchemaVersion(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry,
            @PathParam("ctx") String ctx, @PathParam("subject") String subject,
            @PathParam("version") String version, @QueryParam("deleted") boolean deleted) {
        return raw(service.getRawSchemaVersion(target(project, location, registry, ctx), subject, version, deleted));
    }

    @GET
    @Path("/{registry}" + CONTEXT + "/subjects/{subject}/versions/{version}/referencedby")
    public Response listReferencedSchemas(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry,
            @PathParam("ctx") String ctx, @PathParam("subject") String subject,
            @PathParam("version") String version) {
        return ok(service.listReferencedSchemas(target(project, location, registry, ctx), subject, version));
    }

    @DELETE
    @Path("/{registry}" + CONTEXT + "/subjects/{subject}/versions/{version}")
    public Response deleteVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx,
            @PathParam("subject") String subject, @PathParam("version") String version,
            @QueryParam("permanent") boolean permanent) {
        return ok(service.deleteVersion(target(project, location, registry, ctx), subject, version, permanent));
    }

    // ── Schemas by id ─────────────────────────────────────────────────────────

    @GET
    @Path("/{registry}" + CONTEXT + "/schemas/types")
    public Response listSchemaTypes(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx) {
        return ok(service.listSchemaTypes(target(project, location, registry, ctx)));
    }

    @GET
    @Path("/{registry}" + CONTEXT + "/schemas/ids/{id}")
    public Response getSchema(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx, @PathParam("id") String id,
            @QueryParam("subject") String subject) {
        return ok(service.getSchema(target(project, location, registry, ctx), id, subject));
    }

    @GET
    @Path("/{registry}" + CONTEXT + "/schemas/ids/{id}/schema")
    public Response getRawSchema(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx, @PathParam("id") String id,
            @QueryParam("subject") String subject) {
        return raw(service.getRawSchema(target(project, location, registry, ctx), id, subject));
    }

    @GET
    @Path("/{registry}" + CONTEXT + "/schemas/ids/{id}/versions")
    public Response listSchemaVersions(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx, @PathParam("id") String id,
            @QueryParam("subject") String subject, @QueryParam("deleted") boolean deleted) {
        return ok(service.listSchemaVersions(target(project, location, registry, ctx), id, subject, deleted));
    }

    @GET
    @Path("/{registry}" + CONTEXT + "/schemas/ids/{id}/subjects")
    public Response listSubjectsBySchemaId(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry,
            @PathParam("ctx") String ctx, @PathParam("id") String id, @QueryParam("subject") String subject,
            @QueryParam("deleted") boolean deleted) {
        return ok(service.listSubjectsBySchemaId(target(project, location, registry, ctx), id, subject, deleted));
    }

    // ── Compatibility ─────────────────────────────────────────────────────────

    @POST
    @Path("/{registry}" + CONTEXT + "/compatibility/subjects/{subject}/versions")
    public Response checkCompatibilityWithVersions(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry,
            @PathParam("ctx") String ctx, @PathParam("subject") String subject,
            @QueryParam("verbose") Boolean verbose, String body) {
        return ok(service.checkCompatibility(target(project, location, registry, ctx), subject, null, parse(body),
                verbose));
    }

    @POST
    @Path("/{registry}" + CONTEXT + "/compatibility/subjects/{subject}/versions/{version}")
    public Response checkCompatibilityWithVersion(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry,
            @PathParam("ctx") String ctx, @PathParam("subject") String subject,
            @PathParam("version") String version, @QueryParam("verbose") Boolean verbose, String body) {
        return ok(service.checkCompatibility(target(project, location, registry, ctx), subject, version,
                parse(body), verbose));
    }

    // ── Config ────────────────────────────────────────────────────────────────

    @GET
    @Path("/{registry}" + CONTEXT + "/config")
    public Response getRegistryConfig(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx) {
        return ok(service.getConfig(target(project, location, registry, ctx), null, false));
    }

    @PUT
    @Path("/{registry}" + CONTEXT + "/config")
    public Response updateRegistryConfig(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry,
            @PathParam("ctx") String ctx, String body) {
        return ok(service.updateConfig(target(project, location, registry, ctx), null, parse(body)));
    }

    @GET
    @Path("/{registry}" + CONTEXT + "/config/{subject}")
    public Response getSubjectConfig(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx,
            @PathParam("subject") String subject, @QueryParam("defaultToGlobal") boolean defaultToGlobal) {
        return ok(service.getConfig(target(project, location, registry, ctx), subject, defaultToGlobal));
    }

    @PUT
    @Path("/{registry}" + CONTEXT + "/config/{subject}")
    public Response updateSubjectConfig(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry,
            @PathParam("ctx") String ctx, @PathParam("subject") String subject, String body) {
        return ok(service.updateConfig(target(project, location, registry, ctx), subject, parse(body)));
    }

    @DELETE
    @Path("/{registry}" + CONTEXT + "/config/{subject}")
    public Response deleteSubjectConfig(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry,
            @PathParam("ctx") String ctx, @PathParam("subject") String subject) {
        return ok(service.deleteConfig(target(project, location, registry, ctx), subject));
    }

    // ── Mode ──────────────────────────────────────────────────────────────────

    @GET
    @Path("/{registry}" + CONTEXT + "/mode")
    public Response getRegistryMode(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx) {
        return ok(service.getMode(target(project, location, registry, ctx), null));
    }

    @PUT
    @Path("/{registry}" + CONTEXT + "/mode")
    public Response updateRegistryMode(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry,
            @PathParam("ctx") String ctx, String body) {
        return ok(service.updateMode(target(project, location, registry, ctx), null, parse(body)));
    }

    @GET
    @Path("/{registry}" + CONTEXT + "/mode/{subject}")
    public Response getSubjectMode(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx,
            @PathParam("subject") String subject) {
        return ok(service.getMode(target(project, location, registry, ctx), subject));
    }

    @PUT
    @Path("/{registry}" + CONTEXT + "/mode/{subject}")
    public Response updateSubjectMode(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx,
            @PathParam("subject") String subject, String body) {
        return ok(service.updateMode(target(project, location, registry, ctx), subject, parse(body)));
    }

    @DELETE
    @Path("/{registry}" + CONTEXT + "/mode/{subject}")
    public Response deleteSubjectMode(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("ctx") String ctx,
            @PathParam("subject") String subject) {
        return ok(service.deleteMode(target(project, location, registry, ctx), subject));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Target target(String project, String location, String registry, String ctx) {
        String context = ctx == null || ctx.isEmpty() ? null : ctx.substring(CONTEXT_PREFIX.length());
        return new Target(project, location, registry, context);
    }

    private static Response ok(Object entity) {
        return Response.ok(entity, MediaType.APPLICATION_JSON_TYPE).build();
    }

    /**
     * An {@code HttpBody} holding a schema's text: JSON for Avro and JSON Schema, the {@code .proto}
     * source for Protobuf.
     */
    private static Response raw(String schema) {
        MediaType type = MediaType.TEXT_PLAIN_TYPE;
        try {
            MAPPER.readTree(schema);
            type = MediaType.APPLICATION_JSON_TYPE;
        } catch (JsonProcessingException expected) {
            // Not JSON: a Protobuf schema, served as text.
        }
        return Response.ok(schema, type).build();
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (!node.isObject()) {
                throw GcpException.invalidArgument("Request body must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw GcpException.invalidArgument("Invalid JSON payload: " + e.getOriginalMessage());
        }
    }
}

package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
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
 */
@Path("/v1/projects/{project}/locations/{location}/schemaRegistries")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class SchemaRegistryController {

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

    // ── Subjects and versions ─────────────────────────────────────────────────

    @GET
    @Path("/{registry}/subjects")
    public Response listSubjects(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @QueryParam("subjectPrefix") String subjectPrefix,
            @QueryParam("deleted") boolean deleted) {
        return ok(service.listSubjects(project, location, registry, subjectPrefix, deleted));
    }

    @POST
    @Path("/{registry}/subjects/{subject}")
    public Response lookupVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("subject") String subject,
            @QueryParam("normalize") Boolean normalize, @QueryParam("deleted") Boolean deleted, String body) {
        return ok(service.lookupVersion(project, location, registry, subject, parse(body), normalize, deleted));
    }

    @DELETE
    @Path("/{registry}/subjects/{subject}")
    public Response deleteSubject(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("subject") String subject,
            @QueryParam("permanent") boolean permanent) {
        return ok(service.deleteSubject(project, location, registry, subject, permanent));
    }

    @GET
    @Path("/{registry}/subjects/{subject}/versions")
    public Response listVersions(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("subject") String subject,
            @QueryParam("deleted") boolean deleted) {
        return ok(service.listVersions(project, location, registry, subject, deleted));
    }

    @POST
    @Path("/{registry}/subjects/{subject}/versions")
    public Response createVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("subject") String subject,
            @QueryParam("normalize") Boolean normalize, String body) {
        return ok(service.createVersion(project, location, registry, subject, parse(body), normalize));
    }

    @GET
    @Path("/{registry}/subjects/{subject}/versions/{version}")
    public Response getVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("subject") String subject,
            @PathParam("version") String version, @QueryParam("deleted") boolean deleted) {
        return ok(service.getVersion(project, location, registry, subject, version, deleted));
    }

    @DELETE
    @Path("/{registry}/subjects/{subject}/versions/{version}")
    public Response deleteVersion(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("subject") String subject,
            @PathParam("version") String version, @QueryParam("permanent") boolean permanent) {
        return ok(service.deleteVersion(project, location, registry, subject, version, permanent));
    }

    @GET
    @Path("/{registry}/schemas/ids/{id}")
    public Response getSchema(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("id") String id,
            @QueryParam("subject") String subject) {
        return ok(service.getSchema(project, location, registry, id, subject));
    }

    @POST
    @Path("/{registry}/compatibility/subjects/{subject}/versions")
    public Response checkCompatibilityWithVersions(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry,
            @PathParam("subject") String subject, @QueryParam("verbose") Boolean verbose, String body) {
        return ok(service.checkCompatibility(project, location, registry, subject, null, parse(body), verbose));
    }

    @POST
    @Path("/{registry}/compatibility/subjects/{subject}/versions/{version}")
    public Response checkCompatibilityWithVersion(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry,
            @PathParam("subject") String subject, @PathParam("version") String version,
            @QueryParam("verbose") Boolean verbose, String body) {
        return ok(service.checkCompatibility(project, location, registry, subject, version, parse(body), verbose));
    }

    // ── Config ────────────────────────────────────────────────────────────────

    @GET
    @Path("/{registry}/config")
    public Response getRegistryConfig(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry) {
        return ok(service.getConfig(project, location, registry, null, false));
    }

    @PUT
    @Path("/{registry}/config")
    public Response updateRegistryConfig(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry, String body) {
        return ok(service.updateConfig(project, location, registry, null, parse(body)));
    }

    @GET
    @Path("/{registry}/config/{subject}")
    public Response getSubjectConfig(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("subject") String subject,
            @QueryParam("defaultToGlobal") boolean defaultToGlobal) {
        return ok(service.getConfig(project, location, registry, subject, defaultToGlobal));
    }

    @PUT
    @Path("/{registry}/config/{subject}")
    public Response updateSubjectConfig(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry,
            @PathParam("subject") String subject, String body) {
        return ok(service.updateConfig(project, location, registry, subject, parse(body)));
    }

    @DELETE
    @Path("/{registry}/config/{subject}")
    public Response deleteSubjectConfig(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry,
            @PathParam("subject") String subject) {
        return ok(service.deleteConfig(project, location, registry, subject));
    }

    // ── Mode ──────────────────────────────────────────────────────────────────

    @GET
    @Path("/{registry}/mode")
    public Response getRegistryMode(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry) {
        return ok(service.getMode(project, location, registry, null));
    }

    @PUT
    @Path("/{registry}/mode")
    public Response updateRegistryMode(@PathParam("project") String project,
            @PathParam("location") String location, @PathParam("registry") String registry, String body) {
        return ok(service.updateMode(project, location, registry, null, parse(body)));
    }

    @GET
    @Path("/{registry}/mode/{subject}")
    public Response getSubjectMode(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("subject") String subject) {
        return ok(service.getMode(project, location, registry, subject));
    }

    @PUT
    @Path("/{registry}/mode/{subject}")
    public Response updateSubjectMode(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("subject") String subject, String body) {
        return ok(service.updateMode(project, location, registry, subject, parse(body)));
    }

    @DELETE
    @Path("/{registry}/mode/{subject}")
    public Response deleteSubjectMode(@PathParam("project") String project, @PathParam("location") String location,
            @PathParam("registry") String registry, @PathParam("subject") String subject) {
        return ok(service.deleteMode(project, location, registry, subject));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Response ok(Object entity) {
        return Response.ok(entity, MediaType.APPLICATION_JSON_TYPE).build();
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

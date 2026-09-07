package io.floci.gcp.services.gcs;

import io.floci.gcp.services.gcs.model.GcsHmacKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST JSON surface for Cloud Storage HMAC keys, {@code /storage/v1/projects/{project}/hmacKeys}.
 * Shapes follow the storage/v1 discovery document; the lifecycle rules live in
 * {@link GcsHmacKeyService}.
 */
@ApplicationScoped
@Path("/storage/v1/projects/{project}/hmacKeys")
@Produces(MediaType.APPLICATION_JSON)
public class GcsHmacKeyController {

    private final GcsHmacKeyService service;

    @Inject
    public GcsHmacKeyController(GcsHmacKeyService service) {
        this.service = service;
    }

    @POST
    public Response create(@PathParam("project") String project,
            @QueryParam("serviceAccountEmail") String serviceAccountEmail) {
        GcsHmacKeyService.CreatedKey created = service.create(project, serviceAccountEmail);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "storage#hmacKey");
        response.put("metadata", created.metadata());
        response.put("secret", created.secret());
        return Response.ok(response).build();
    }

    @GET
    public Response list(@PathParam("project") String project,
            @QueryParam("serviceAccountEmail") String serviceAccountEmail,
            @QueryParam("showDeletedKeys") @DefaultValue("false") boolean showDeletedKeys) {
        List<GcsHmacKey> items = service.list(project, serviceAccountEmail, showDeletedKeys);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "storage#hmacKeysMetadata");
        response.put("items", items);
        return Response.ok(response).build();
    }

    @GET
    @Path("/{accessId}")
    public Response get(@PathParam("project") String project, @PathParam("accessId") String accessId) {
        return Response.ok(service.get(project, accessId)).build();
    }

    @PUT
    @Path("/{accessId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("project") String project, @PathParam("accessId") String accessId,
            Map<String, Object> body) {
        Object state = body != null ? body.get("state") : null;
        return Response.ok(service.updateState(project, accessId, state)).build();
    }

    @DELETE
    @Path("/{accessId}")
    public Response delete(@PathParam("project") String project, @PathParam("accessId") String accessId) {
        service.delete(project, accessId);
        return Response.noContent().build();
    }
}

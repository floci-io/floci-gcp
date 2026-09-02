package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.gcs.model.GcsHmacKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cloud Storage HMAC keys.
 *
 * <p>These are the credentials S3-compatible clients use against GCS, boto3, the AWS SDKs, and
 * {@code gsutil} in interop mode. Without them the XML interop path cannot be exercised locally at
 * all.
 *
 * <p>The keys are not used to sign anything here: as with the rest of the emulator's credential
 * handling, signatures are never verified. What matters for a client is the resource lifecycle,
 * including the rule that a key must be moved to {@code INACTIVE} before it can be deleted , 
 * deleting an {@code ACTIVE} key is a 400, and code that does not handle that gets stuck.
 */
@ApplicationScoped
@Path("/storage/v1/projects/{project}/hmacKeys")
@Produces(MediaType.APPLICATION_JSON)
public class GcsHmacKeyController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, GcsHmacKey> keys = new ConcurrentHashMap<>();
    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    @POST
    public Response create(@PathParam("project") String project,
            @QueryParam("serviceAccountEmail") String serviceAccountEmail) {
        if (serviceAccountEmail == null || serviceAccountEmail.isBlank()) {
            throw GcpException.invalidArgument("serviceAccountEmail is required");
        }
        String accessId = randomAccessId();
        GcsHmacKey key = new GcsHmacKey();
        key.setId(project + "/" + accessId);
        key.setAccessId(accessId);
        key.setProjectId(project);
        key.setServiceAccountEmail(serviceAccountEmail);
        key.setState("ACTIVE");
        key.setTimeCreated(now());
        key.setUpdated(now());
        key.setEtag("etag-" + accessId);
        keys.put(accessId, key);

        // Returned once and never again, as in real GCS.
        byte[] raw = new byte[28];
        RANDOM.nextBytes(raw);
        String secret = Base64.getEncoder().encodeToString(raw);
        secrets.put(accessId, secret);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "storage#hmacKey");
        response.put("metadata", key);
        response.put("secret", secret);
        return Response.ok(response).build();
    }

    @GET
    public Response list(@PathParam("project") String project,
            @QueryParam("serviceAccountEmail") String serviceAccountEmail,
            @QueryParam("showDeletedKeys") @DefaultValue("false") boolean showDeletedKeys) {
        List<GcsHmacKey> items = new ArrayList<>();
        for (GcsHmacKey key : keys.values()) {
            if (!key.getProjectId().equals(project)) {
                continue;
            }
            if (serviceAccountEmail != null && !serviceAccountEmail.isBlank()
                    && !serviceAccountEmail.equals(key.getServiceAccountEmail())) {
                continue;
            }
            if (!showDeletedKeys && "DELETED".equals(key.getState())) {
                continue;
            }
            items.add(key);
        }
        items.sort((a, b) -> a.getAccessId().compareTo(b.getAccessId()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "storage#hmacKeysMetadata");
        response.put("items", items);
        return Response.ok(response).build();
    }

    @GET
    @Path("/{accessId}")
    public Response get(@PathParam("project") String project, @PathParam("accessId") String accessId) {
        return Response.ok(require(project, accessId)).build();
    }

    @PUT
    @Path("/{accessId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("project") String project, @PathParam("accessId") String accessId,
            Map<String, Object> body) {
        GcsHmacKey key = require(project, accessId);
        Object state = body != null ? body.get("state") : null;
        if (!(state instanceof String requested)) {
            throw GcpException.invalidArgument("state is required");
        }
        if (!requested.equals("ACTIVE") && !requested.equals("INACTIVE")) {
            throw GcpException.invalidArgument("state must be ACTIVE or INACTIVE, got: " + requested);
        }
        key.setState(requested);
        key.setUpdated(now());
        return Response.ok(key).build();
    }

    @DELETE
    @Path("/{accessId}")
    public Response delete(@PathParam("project") String project, @PathParam("accessId") String accessId) {
        GcsHmacKey key = require(project, accessId);
        if ("ACTIVE".equals(key.getState())) {
            throw GcpException.invalidArgument(
                    "This key must be in the INACTIVE state before it can be deleted.");
        }
        keys.remove(accessId);
        secrets.remove(accessId);
        return Response.noContent().build();
    }

    private GcsHmacKey require(String project, String accessId) {
        GcsHmacKey key = keys.get(accessId);
        if (key == null || !key.getProjectId().equals(project)) {
            throw GcpException.notFound("HMAC key not found: " + accessId);
        }
        return key;
    }

    // GCS access ids look like a GOOG-prefixed uppercase alphanumeric string.
    private static String randomAccessId() {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder sb = new StringBuilder("GOOG1E");
        for (int i = 0; i < 55; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}

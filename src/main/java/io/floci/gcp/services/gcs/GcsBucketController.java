package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.gcs.model.StoredAcl;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/storage/v1/b")
@Produces(MediaType.APPLICATION_JSON)
public class GcsBucketController {

    private final GcsService service;
    private final EmulatorConfig config;
    private final IamService iamService;
    private final ObjectMapper objectMapper;

    @Inject
    public GcsBucketController(GcsService service, EmulatorConfig config, IamService iamService,
            ObjectMapper objectMapper) {
        this.service = service;
        this.config = config;
        this.iamService = iamService;
        this.objectMapper = objectMapper;
    }

    @OPTIONS
    public Response optionsRoot() {
        return Response.ok().build();
    }

    @OPTIONS
    @Path("/{anyPath: .*}")
    public Response options() {
        return Response.ok().build();
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response createBucket(@QueryParam("project") String project,
            @Context HttpHeaders headers, byte[] body) {
        Map<String, Object> parsed = parseJsonBody(body);
        String name = parsed != null ? (String) parsed.get("name") : null;
        if (name == null || name.isBlank()) {
            throw GcpException.invalidArgument("bucket name is required");
        }
        GcsBucket bucket = service.createBucket(name, project, requestBaseUrl(headers), parsed);
        return Response.ok(bucket).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonBody(byte[] body) {
        if (body == null || body.length == 0) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            throw GcpException.invalidArgument("invalid JSON body");
        }
    }

    @GET
    public Response listBuckets(@QueryParam("project") String project,
            @QueryParam("maxResults") @DefaultValue("0") int maxResults,
            @QueryParam("pageToken") String pageToken) {
        List<GcsBucket> all = service.listBuckets(project);
        PageToken.Page<GcsBucket> page = PageToken.paginate(all, maxResults, pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "storage#buckets");
        if (!page.items().isEmpty()) {
            response.put("items", page.items());
        }
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/{bucket}")
    public Response getBucket(@PathParam("bucket") String bucket) {
        return Response.ok(service.getBucket(bucket)).build();
    }

    @PATCH
    @Path("/{bucket}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response patchBucket(@PathParam("bucket") String bucket, Map<String, Object> body) {
        return Response.ok(service.updateBucket(bucket, body)).build();
    }

    @POST
    @Path("/{bucket}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response postBucketMethodOverride(@PathParam("bucket") String bucket,
            @HeaderParam("X-HTTP-Method-Override") String methodOverride,
            Map<String, Object> body) {
        if ("PATCH".equalsIgnoreCase(methodOverride)) {
            return Response.ok(service.updateBucket(bucket, body)).build();
        }
        throw GcpException.invalidArgument("unsupported method override: " + methodOverride);
    }

    @DELETE
    @Path("/{bucket}")
    public Response deleteBucket(@PathParam("bucket") String bucket) {
        service.deleteBucket(bucket);
        return Response.noContent().build();
    }

    @POST
    @Path("/{bucket}/lockRetentionPolicy")
    public Response lockRetentionPolicy(@PathParam("bucket") String bucket,
            @QueryParam("ifMetagenerationMatch") Long ifMetagenerationMatch) {
        return Response.ok(service.lockRetentionPolicy(bucket, ifMetagenerationMatch)).build();
    }

    @GET
    @Path("/{bucket}/storageLayout")
    public Response getStorageLayout(@PathParam("bucket") String bucket) {
        GcsBucket b = service.getBucket(bucket);
        String location = b.getLocation() != null ? b.getLocation() : "US";
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "storage#storageLayout");
        response.put("bucket", bucket);
        response.put("location", location);
        response.put("locationType", locationType(location));
        response.put("hierarchicalNamespace", Map.of("enabled", false));
        return Response.ok(response).build();
    }

    private static String locationType(String location) {
        return switch (location.toUpperCase()) {
            case "US", "EU", "ASIA" -> "multi-region";
            default -> "region";
        };
    }

    // ── Bucket IAM ────────────────────────────────────────────────────────────

    @GET
    @Path("/{bucket}/iam")
    public Response getBucketIamPolicy(@PathParam("bucket") String bucket) {
        service.getBucket(bucket);
        StoredPolicy policy = iamService.getPolicy("buckets/" + bucket);
        return Response.ok(bucketIamResponse(bucket, policy)).build();
    }

    @PUT
    @Path("/{bucket}/iam")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setBucketIamPolicy(@PathParam("bucket") String bucket, Map<String, Object> body) {
        service.getBucket(bucket);
        StoredPolicy policy = parsePolicy(body);
        iamService.setPolicy("buckets/" + bucket, policy);
        return Response.ok(bucketIamResponse(bucket, policy)).build();
    }

    @POST
    @Path("/{bucket}/iam:testPermissions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response testBucketIamPermissions(@PathParam("bucket") String bucket,
            Map<String, Object> body) {
        service.getBucket(bucket);
        @SuppressWarnings("unchecked")
        List<String> requested = body != null ? (List<String>) body.get("permissions") : List.of();
        List<String> granted = iamService.testPermissions(requested != null ? requested : List.of());
        return Response.ok(Map.of("permissions", granted)).build();
    }

    // ── Bucket ACLs ───────────────────────────────────────────────────────────

    @GET
    @Path("/{bucket}/acl")
    public Response listBucketAcls(@PathParam("bucket") String bucket) {
        List<StoredAcl> items = service.listBucketAcls(bucket);
        return Response.ok(Map.of("kind", "storage#bucketAccessControls", "items", items)).build();
    }

    @POST
    @Path("/{bucket}/acl")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response insertBucketAcl(@PathParam("bucket") String bucket, Map<String, Object> body) {
        String entity = body != null ? (String) body.get("entity") : null;
        String role = body != null ? (String) body.get("role") : "READER";
        StoredAcl acl = service.upsertBucketAcl(bucket, entity, role);
        return Response.ok(acl).build();
    }

    @GET
    @Path("/{bucket}/acl/{entity}")
    public Response getBucketAcl(@PathParam("bucket") String bucket,
            @PathParam("entity") String entity) {
        return Response.ok(service.getBucketAcl(bucket, decode(entity))).build();
    }

    @PUT
    @Path("/{bucket}/acl/{entity}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateBucketAcl(@PathParam("bucket") String bucket,
            @PathParam("entity") String entity, Map<String, Object> body) {
        String role = body != null ? (String) body.get("role") : "READER";
        StoredAcl acl = service.upsertBucketAcl(bucket, decode(entity), role);
        return Response.ok(acl).build();
    }

    @DELETE
    @Path("/{bucket}/acl/{entity}")
    public Response deleteBucketAcl(@PathParam("bucket") String bucket,
            @PathParam("entity") String entity) {
        service.deleteBucketAcl(bucket, decode(entity));
        return Response.noContent().build();
    }

    // ── Default Object ACLs ───────────────────────────────────────────────────

    @GET
    @Path("/{bucket}/defaultObjectAcl")
    public Response listDefaultAcls(@PathParam("bucket") String bucket) {
        List<StoredAcl> items = service.listDefaultAcls(bucket);
        return Response.ok(Map.of("kind", "storage#objectAccessControls", "items", items)).build();
    }

    @POST
    @Path("/{bucket}/defaultObjectAcl")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response insertDefaultAcl(@PathParam("bucket") String bucket, Map<String, Object> body) {
        String entity = body != null ? (String) body.get("entity") : null;
        String role = body != null ? (String) body.get("role") : "READER";
        StoredAcl acl = service.upsertDefaultAcl(bucket, entity, role);
        return Response.ok(acl).build();
    }

    @GET
    @Path("/{bucket}/defaultObjectAcl/{entity}")
    public Response getDefaultAcl(@PathParam("bucket") String bucket,
            @PathParam("entity") String entity) {
        return Response.ok(service.getDefaultAcl(bucket, decode(entity))).build();
    }

    @PUT
    @Path("/{bucket}/defaultObjectAcl/{entity}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateDefaultAcl(@PathParam("bucket") String bucket,
            @PathParam("entity") String entity, Map<String, Object> body) {
        String role = body != null ? (String) body.get("role") : "READER";
        StoredAcl acl = service.upsertDefaultAcl(bucket, decode(entity), role);
        return Response.ok(acl).build();
    }

    @DELETE
    @Path("/{bucket}/defaultObjectAcl/{entity}")
    public Response deleteDefaultAcl(@PathParam("bucket") String bucket,
            @PathParam("entity") String entity) {
        service.deleteDefaultAcl(bucket, decode(entity));
        return Response.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Map<String, Object> bucketIamResponse(String bucket, StoredPolicy policy) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("kind", "storage#policy");
        resp.put("resourceId", "projects/_/buckets/" + bucket);
        resp.put("version", policy.getVersion() > 0 ? policy.getVersion() : 1);
        resp.put("bindings", policy.getBindings() != null ? policy.getBindings() : List.of());
        resp.put("etag", policy.getEtag() != null ? policy.getEtag() : "CAE=");
        return resp;
    }

    @SuppressWarnings("unchecked")
    private static StoredPolicy parsePolicy(Map<String, Object> body) {
        StoredPolicy policy = new StoredPolicy();
        if (body == null) {
            return policy;
        }
        if (body.containsKey("version")) {
            policy.setVersion(((Number) body.get("version")).intValue());
        }
        if (body.containsKey("bindings")) {
            policy.setBindings((List<Map<String, Object>>) body.get("bindings"));
        }
        if (body.containsKey("etag")) {
            policy.setEtag((String) body.get("etag"));
        }
        return policy;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private String requestBaseUrl(HttpHeaders headers) {
        String host = headers.getHeaderString("Host");
        return host != null ? "http://" + host : config.baseUrl();
    }
}

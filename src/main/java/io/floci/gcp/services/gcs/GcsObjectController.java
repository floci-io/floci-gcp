package io.floci.gcp.services.gcs;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.credentials.GcsAuthorizationService;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.floci.gcp.services.gcs.model.GcsObjectPreconditions;
import io.floci.gcp.services.gcs.model.StoredAcl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@ApplicationScoped
@Path("/storage/v1/b/{bucket}/o")
@Produces(MediaType.APPLICATION_JSON)
public class GcsObjectController {

    private final GcsService service;
    private final EmulatorConfig config;
	private final GcsAuthorizationService authorizationService;

    @Inject
	public GcsObjectController(GcsService service, EmulatorConfig config,
			GcsAuthorizationService authorizationService) {
        this.service = service;
        this.config = config;
		this.authorizationService = authorizationService;
    }

    @OPTIONS
    @Path("/{anyPath: .*}")
    public Response options() {
        return Response.ok().build();
    }

    @GET
    public Response listObjects(@PathParam("bucket") String bucket,
            @QueryParam("maxResults") @DefaultValue("0") int maxResults,
            @QueryParam("pageToken") String pageToken,
            @QueryParam("prefix") String prefix,
            @QueryParam("delimiter") String delimiter,
			@QueryParam("startOffset") String startOffset,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @QueryParam("versions") @DefaultValue("false") boolean includeVersions) {
		authorizationService.requireObjectList(authorization, bucket, prefix);
        List<GcsObjectMeta> all = includeVersions
                ? service.listObjectVersions(bucket, prefix)
                : service.listObjects(bucket);
        if (!includeVersions && prefix != null && !prefix.isBlank()) {
            all = all.stream().filter(o -> o.getName().startsWith(prefix)).toList();
        }
        all = all.stream()
                .sorted(Comparator.comparing(GcsObjectMeta::getName))
                .filter(o -> startOffset == null || o.getName().compareTo(startOffset) >= 0)
                .toList();
        Set<String> prefixes = new TreeSet<>();
        if (delimiter != null && !delimiter.isEmpty()) {
            String basePrefix = prefix != null ? prefix : "";
            List<GcsObjectMeta> rolledUp = new ArrayList<>();
            for (GcsObjectMeta meta : all) {
                String rest = meta.getName().substring(basePrefix.length());
                int idx = rest.indexOf(delimiter);
                if (idx >= 0) {
                    prefixes.add(basePrefix + rest.substring(0, idx + delimiter.length()));
                } else {
                    rolledUp.add(meta);
                }
            }
            all = rolledUp;
        }
        PageToken.Page<GcsObjectMeta> page = PageToken.paginate(all, maxResults, pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "storage#objects");
        if (!page.items().isEmpty()) {
            response.put("items", page.items());
        }
        if (!prefixes.isEmpty()) {
            response.put("prefixes", new ArrayList<>(prefixes));
        }
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/{object: .+}/acl")
    public Response listObjectAcls(@PathParam("bucket") String bucket,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @PathParam("object") String objectPath) {
        authorizationService.rejectDownscopedToken(authorization);
        List<StoredAcl> items = service.listObjectAcls(bucket, objectPath);
        return Response.ok(Map.of("kind", "storage#objectAccessControls", "items", items)).build();
    }

    @POST
    @Path("/{object: .+}/acl")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response insertObjectAcl(@PathParam("bucket") String bucket,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @PathParam("object") String objectPath, Map<String, Object> body) {
        authorizationService.rejectDownscopedToken(authorization);
        String entity = body != null ? (String) body.get("entity") : null;
        String role = body != null ? (String) body.get("role") : "READER";
        StoredAcl acl = service.upsertObjectAcl(bucket, objectPath, entity, role);
        return Response.ok(acl).build();
    }

    @GET
    @Path("/{object: .+}/acl/{entity}")
    public Response getObjectAcl(@PathParam("bucket") String bucket,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @PathParam("object") String objectPath,
            @PathParam("entity") String entity) {
        authorizationService.rejectDownscopedToken(authorization);
        return Response.ok(service.getObjectAcl(bucket, objectPath, entity)).build();
    }

    @PUT
    @Path("/{object: .+}/acl/{entity}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateObjectAcl(@PathParam("bucket") String bucket,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @PathParam("object") String objectPath,
            @PathParam("entity") String entity, Map<String, Object> body) {
        authorizationService.rejectDownscopedToken(authorization);
        String role = body != null ? (String) body.get("role") : "READER";
        StoredAcl acl = service.upsertObjectAcl(bucket, objectPath, entity, role);
        return Response.ok(acl).build();
    }

    @DELETE
    @Path("/{object: .+}/acl/{entity}")
    public Response deleteObjectAcl(@PathParam("bucket") String bucket,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @PathParam("object") String objectPath,
            @PathParam("entity") String entity) {
        authorizationService.rejectDownscopedToken(authorization);
        service.deleteObjectAcl(bucket, objectPath, entity);
        return Response.noContent().build();
    }

    @GET
    @Path("/{object: .+}")
    public Response getObject(@PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @QueryParam("alt") String alt,
            @QueryParam("generation") String generation,
            @QueryParam("ifGenerationMatch") Long ifGenerationMatch,
            @QueryParam("ifGenerationNotMatch") Long ifGenerationNotMatch,
            @QueryParam("ifMetagenerationMatch") Long ifMetagenerationMatch,
            @QueryParam("ifMetagenerationNotMatch") Long ifMetagenerationNotMatch,
            @HeaderParam("x-goog-encryption-key-sha256") String customerEncryptionKeySha256,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @HeaderParam("Range") String rangeHeader) {
        authorizationService.requireObjectRead(authorization, bucket, objectPath);
        GcsCustomerEncryption customerEncryption = GcsCustomerEncryption.fromKeySha256(customerEncryptionKeySha256);
        if ("media".equals(alt)) {
            var download = service.getObjectForDownload(bucket, objectPath, generation, customerEncryption);
            if (readPreconditionsFail(download.meta(), ifGenerationMatch, ifGenerationNotMatch,
                    ifMetagenerationMatch, ifMetagenerationNotMatch)) {
                return notModified(download.meta());
            }
            return GcsMediaResponses.mediaResponse(download.data(), download.meta(), rangeHeader);
        }
        GcsObjectMeta meta = generation != null
                ? service.getObjectMeta(bucket, objectPath, generation)
                : service.getObjectMeta(bucket, objectPath);
        if (readPreconditionsFail(meta, ifGenerationMatch, ifGenerationNotMatch,
                ifMetagenerationMatch, ifMetagenerationNotMatch)) {
            return notModified(meta);
        }
        return Response.ok(meta).build();
    }

    /**
     * A 304 carries the selected object's validators. RFC 7232 requires a 304 to generate the
     * header fields that a 200 for the same request would have sent, ETag among them, and the
     * media path does send ETag and the x-goog generation headers. A bare 304 would leave a
     * cache-aware client without the validators it needs to revalidate what it already holds.
     */
    private static Response notModified(GcsObjectMeta meta) {
        Response.ResponseBuilder builder = Response.notModified();
        if (meta.getEtag() != null) {
            builder.header("ETag", meta.getEtag());
        }
        if (meta.getGeneration() != null) {
            builder.header("x-goog-generation", meta.getGeneration());
        }
        if (meta.getMetageneration() != null) {
            builder.header("x-goog-metageneration", meta.getMetageneration());
        }
        return builder.build();
    }

    /**
     * Evaluates conditional-read preconditions.
     *
     * <p>The two families fail differently, per the documented precondition table: a
     * {@code *Match} that does not hold is {@code 412 Precondition Failed}, while a
     * {@code *NotMatch} that does not hold is {@code 304 Not Modified}, the request would have
     * succeeded, so the point is to skip transferring a body the caller already has. Returns true
     * when the caller should answer 304; throws for the 412 cases.
     */
    private static boolean readPreconditionsFail(GcsObjectMeta meta, Long ifGenerationMatch,
            Long ifGenerationNotMatch, Long ifMetagenerationMatch, Long ifMetagenerationNotMatch) {
        long generation = parseOrZero(meta.getGeneration());
        long metageneration = parseOrZero(meta.getMetageneration());

        if (ifGenerationMatch != null && generation != ifGenerationMatch) {
            throw GcpException.conditionNotMet(
                    "ifGenerationMatch: " + generation + " != " + ifGenerationMatch);
        }
        if (ifMetagenerationMatch != null && metageneration != ifMetagenerationMatch) {
            throw GcpException.conditionNotMet(
                    "ifMetagenerationMatch: " + metageneration + " != " + ifMetagenerationMatch);
        }
        if (ifGenerationNotMatch != null && generation == ifGenerationNotMatch) {
            return true;
        }
        return ifMetagenerationNotMatch != null && metageneration == ifMetagenerationNotMatch;
    }

    private static long parseOrZero(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @PATCH
    @Path("/{object: .+}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response patchObject(@PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @QueryParam("ifGenerationMatch") Long ifGenerationMatch,
            @QueryParam("ifGenerationNotMatch") Long ifGenerationNotMatch,
            @QueryParam("ifMetagenerationMatch") Long ifMetagenerationMatch,
			@QueryParam("ifMetagenerationNotMatch") Long ifMetagenerationNotMatch,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            Map<String, Object> body) {
        authorizationService.requireObjectWrite(authorization, bucket, objectPath);
        GcsObjectPreconditions preconditions = new GcsObjectPreconditions(ifGenerationMatch, ifGenerationNotMatch,
                ifMetagenerationMatch, ifMetagenerationNotMatch);
        return Response.ok(service.patchObject(bucket, objectPath, body, preconditions)).build();
    }

    @PUT
    @Path("/{object: .+}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateObject(@PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @QueryParam("ifGenerationMatch") Long ifGenerationMatch,
            @QueryParam("ifGenerationNotMatch") Long ifGenerationNotMatch,
            @QueryParam("ifMetagenerationMatch") Long ifMetagenerationMatch,
			@QueryParam("ifMetagenerationNotMatch") Long ifMetagenerationNotMatch,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            Map<String, Object> body) {
        authorizationService.requireObjectWrite(authorization, bucket, objectPath);
        GcsObjectPreconditions preconditions = new GcsObjectPreconditions(ifGenerationMatch, ifGenerationNotMatch,
                ifMetagenerationMatch, ifMetagenerationNotMatch);
        return Response.ok(service.patchObject(bucket, objectPath, body, preconditions)).build();
    }

    @POST
    @Path("/{object: .+}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response postObjectMethodOverride(@PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @HeaderParam("X-HTTP-Method-Override") String methodOverride,
            @QueryParam("ifGenerationMatch") Long ifGenerationMatch,
            @QueryParam("ifGenerationNotMatch") Long ifGenerationNotMatch,
            @QueryParam("ifMetagenerationMatch") Long ifMetagenerationMatch,
            @QueryParam("ifMetagenerationNotMatch") Long ifMetagenerationNotMatch,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            Map<String, Object> body) {
        if ("PATCH".equalsIgnoreCase(methodOverride)) {
            authorizationService.requireObjectWrite(authorization, bucket, objectPath);
            GcsObjectPreconditions preconditions = new GcsObjectPreconditions(ifGenerationMatch, ifGenerationNotMatch,
                    ifMetagenerationMatch, ifMetagenerationNotMatch);
            return Response.ok(service.patchObject(bucket, objectPath, body, preconditions)).build();
        }
        throw GcpException.invalidArgument("Unsupported method override: " + methodOverride);
    }

    @DELETE
    @Path("/{object: .+}")
    public Response deleteObject(@PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @QueryParam("generation") String generation,
            @QueryParam("ifGenerationMatch") Long ifGenerationMatch,
            @QueryParam("ifGenerationNotMatch") Long ifGenerationNotMatch,
            @QueryParam("ifMetagenerationMatch") Long ifMetagenerationMatch,
            @QueryParam("ifMetagenerationNotMatch") Long ifMetagenerationNotMatch) {
        authorizationService.requireObjectDelete(authorization, bucket, objectPath);
        GcsObjectPreconditions preconditions = new GcsObjectPreconditions(ifGenerationMatch, ifGenerationNotMatch,
                ifMetagenerationMatch, ifMetagenerationNotMatch);
        if (generation != null) {
            service.deleteObjectVersion(bucket, objectPath, generation, preconditions);
            return Response.noContent().build();
        }
        if (!service.deleteObject(bucket, objectPath, preconditions)) {
            throw GcpException.notFound("Object not found: " + objectPath);
        }
        return Response.noContent().build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{destObject: .+}/compose")
    public Response composeObject(@PathParam("bucket") String bucket,
            @PathParam("destObject") String destObjectPath,
            @QueryParam("ifGenerationMatch") Long ifGenerationMatch,
            @QueryParam("ifMetagenerationMatch") Long ifMetagenerationMatch,
            @Context HttpHeaders headers, Map<String, Object> body) {
        String authorization = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceObjects = body != null
                ? (List<Map<String, Object>>) body.get("sourceObjects") : List.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> destReq = body != null
                ? (Map<String, Object>) body.get("destination") : Map.of();
        String contentType = destReq != null ? (String) destReq.get("contentType") : null;
        List<String> sourceNames = sourceObjects == null ? List.of()
                : sourceObjects.stream().map(s -> (String) s.get("name")).toList();
        for (String sourceName : sourceNames) {
            authorizationService.requireObjectRead(authorization, bucket, sourceName);
        }
        authorizationService.requireObjectWrite(authorization, bucket, destObjectPath);
        GcsObjectPreconditions preconditions = new GcsObjectPreconditions(ifGenerationMatch, null,
                ifMetagenerationMatch, null);
        GcsObjectMeta meta = service.composeObject(bucket, destObjectPath, sourceNames, contentType,
                preconditions, requestBaseUrl(headers));
        return Response.ok(meta).build();
    }

    @POST
    @Path("/{srcObject: .+}/copyTo/b/{dstBucket}/o/{dstObject: .+}")
    public Response copyObject(@PathParam("bucket") String srcBucket,
            @PathParam("srcObject") String srcObjectPath,
            @PathParam("dstBucket") String dstBucket,
            @PathParam("dstObject") String dstObjectPath,
            @QueryParam("ifGenerationMatch") Long ifGenerationMatch,
            @QueryParam("ifGenerationNotMatch") Long ifGenerationNotMatch,
            @QueryParam("ifMetagenerationMatch") Long ifMetagenerationMatch,
            @QueryParam("ifMetagenerationNotMatch") Long ifMetagenerationNotMatch,
            @Context HttpHeaders headers) {
        authorizationService.requireSourceReadAndDestinationWrite(
                headers.getHeaderString(HttpHeaders.AUTHORIZATION),
                srcBucket, srcObjectPath, dstBucket, dstObjectPath);
        GcsObjectPreconditions preconditions = new GcsObjectPreconditions(ifGenerationMatch, ifGenerationNotMatch,
                ifMetagenerationMatch, ifMetagenerationNotMatch);
        GcsObjectMeta meta = service.copyObject(srcBucket, srcObjectPath, dstBucket, dstObjectPath,
                preconditions, requestBaseUrl(headers));
        return Response.ok(meta).build();
    }

    @POST
    @Path("/{srcObject: .+}/moveTo/o/{dstObject: .+}")
    public Response moveObject(@PathParam("bucket") String bucket,
            @PathParam("srcObject") String srcObjectPath,
            @PathParam("dstObject") String dstObjectPath,
            @QueryParam("ifGenerationMatch") Long ifGenerationMatch,
            @QueryParam("ifGenerationNotMatch") Long ifGenerationNotMatch,
            @QueryParam("ifMetagenerationMatch") Long ifMetagenerationMatch,
            @QueryParam("ifMetagenerationNotMatch") Long ifMetagenerationNotMatch,
            @QueryParam("ifSourceGenerationMatch") Long ifSourceGenerationMatch,
            @QueryParam("ifSourceGenerationNotMatch") Long ifSourceGenerationNotMatch,
            @QueryParam("ifSourceMetagenerationMatch") Long ifSourceMetagenerationMatch,
            @QueryParam("ifSourceMetagenerationNotMatch") Long ifSourceMetagenerationNotMatch,
            @Context HttpHeaders headers) {
        String authorization = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
        authorizationService.requireObjectRead(authorization, bucket, srcObjectPath);
        authorizationService.requireObjectDelete(authorization, bucket, srcObjectPath);
        authorizationService.requireObjectWrite(authorization, bucket, dstObjectPath);
        GcsObjectPreconditions sourcePreconditions = new GcsObjectPreconditions(ifSourceGenerationMatch,
                ifSourceGenerationNotMatch, ifSourceMetagenerationMatch, ifSourceMetagenerationNotMatch);
        GcsObjectPreconditions destinationPreconditions = new GcsObjectPreconditions(ifGenerationMatch,
                ifGenerationNotMatch, ifMetagenerationMatch, ifMetagenerationNotMatch);
        GcsObjectMeta meta = service.moveObject(bucket, srcObjectPath, dstObjectPath,
                sourcePreconditions, destinationPreconditions, requestBaseUrl(headers));
        return Response.ok(meta).build();
    }

    @POST
    @Path("/{srcObject: .+}/rewriteTo/b/{dstBucket}/o/{dstObject: .+}")
    public Response rewriteObject(@PathParam("bucket") String srcBucket,
            @PathParam("srcObject") String srcObjectPath,
            @PathParam("dstBucket") String dstBucket,
            @PathParam("dstObject") String dstObjectPath,
            @QueryParam("ifGenerationMatch") Long ifGenerationMatch,
            @QueryParam("ifGenerationNotMatch") Long ifGenerationNotMatch,
            @QueryParam("ifMetagenerationMatch") Long ifMetagenerationMatch,
            @QueryParam("ifMetagenerationNotMatch") Long ifMetagenerationNotMatch,
            @Context HttpHeaders headers) {
        authorizationService.requireSourceReadAndDestinationWrite(
                headers.getHeaderString(HttpHeaders.AUTHORIZATION),
                srcBucket, srcObjectPath, dstBucket, dstObjectPath);
        GcsObjectPreconditions preconditions = new GcsObjectPreconditions(ifGenerationMatch, ifGenerationNotMatch,
                ifMetagenerationMatch, ifMetagenerationNotMatch);
        GcsObjectMeta meta = service.copyObject(srcBucket, srcObjectPath, dstBucket, dstObjectPath,
                preconditions, requestBaseUrl(headers));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "storage#rewriteResponse");
        response.put("totalBytesRewritten", meta.getSize());
        response.put("objectSize", meta.getSize());
        response.put("done", true);
        response.put("resource", meta);
        return Response.ok(response).build();
    }

    private String requestBaseUrl(HttpHeaders headers) {
        String host = headers.getHeaderString("Host");
        return host != null ? "http://" + host : config.baseUrl();
    }
}

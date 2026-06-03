package io.floci.gcp.services.gcs;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.floci.gcp.services.gcs.model.StoredAcl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    @Inject
    public GcsObjectController(GcsService service, EmulatorConfig config) {
        this.service = service;
        this.config = config;
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
            @QueryParam("versions") @DefaultValue("false") boolean includeVersions) {
        List<GcsObjectMeta> all = includeVersions
                ? service.listObjectVersions(bucket, prefix)
                : service.listObjects(bucket);
        if (!includeVersions && prefix != null && !prefix.isBlank()) {
            all = all.stream().filter(o -> o.getName().startsWith(prefix)).toList();
        }
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
            @PathParam("object") String objectPath) {
        String objectName = decode(objectPath);
        List<StoredAcl> items = service.listObjectAcls(bucket, objectName);
        return Response.ok(Map.of("kind", "storage#objectAccessControls", "items", items)).build();
    }

    @POST
    @Path("/{object: .+}/acl")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response insertObjectAcl(@PathParam("bucket") String bucket,
            @PathParam("object") String objectPath, Map<String, Object> body) {
        String objectName = decode(objectPath);
        String entity = body != null ? (String) body.get("entity") : null;
        String role = body != null ? (String) body.get("role") : "READER";
        StoredAcl acl = service.upsertObjectAcl(bucket, objectName, entity, role);
        return Response.ok(acl).build();
    }

    @GET
    @Path("/{object: .+}/acl/{entity}")
    public Response getObjectAcl(@PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @PathParam("entity") String entity) {
        String objectName = decode(objectPath);
        return Response.ok(service.getObjectAcl(bucket, objectName, decode(entity))).build();
    }

    @PUT
    @Path("/{object: .+}/acl/{entity}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateObjectAcl(@PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @PathParam("entity") String entity, Map<String, Object> body) {
        String objectName = decode(objectPath);
        String role = body != null ? (String) body.get("role") : "READER";
        StoredAcl acl = service.upsertObjectAcl(bucket, objectName, decode(entity), role);
        return Response.ok(acl).build();
    }

    @DELETE
    @Path("/{object: .+}/acl/{entity}")
    public Response deleteObjectAcl(@PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @PathParam("entity") String entity) {
        String objectName = decode(objectPath);
        service.deleteObjectAcl(bucket, objectName, decode(entity));
        return Response.noContent().build();
    }

    @GET
    @Path("/{object: .+}")
    public Response getObject(@PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @QueryParam("alt") String alt,
            @QueryParam("generation") String generation) {
        String objectName = decode(objectPath);
        if (generation != null) {
            if ("media".equals(alt)) {
                byte[] data = service.getObjectData(bucket, objectName, generation);
                GcsObjectMeta meta = service.getObjectMeta(bucket, objectName, generation);
                return Response.ok(data).type(meta.getContentType()).build();
            }
            return Response.ok(service.getObjectMeta(bucket, objectName, generation)).build();
        }
        if ("media".equals(alt)) {
            byte[] data = service.getObjectData(bucket, objectName);
            GcsObjectMeta meta = service.getObjectMeta(bucket, objectName);
            return Response.ok(data).type(meta.getContentType()).build();
        }
        return Response.ok(service.getObjectMeta(bucket, objectName)).build();
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
            Map<String, Object> body) {
        String objectName = decode(objectPath);
        service.checkPreconditions(bucket, objectName, ifGenerationMatch, ifGenerationNotMatch,
                ifMetagenerationMatch, ifMetagenerationNotMatch);
        return Response.ok(service.patchObject(bucket, objectName, body)).build();
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
            Map<String, Object> body) {
        String objectName = decode(objectPath);
        service.checkPreconditions(bucket, objectName, ifGenerationMatch, ifGenerationNotMatch,
                ifMetagenerationMatch, ifMetagenerationNotMatch);
        return Response.ok(service.patchObject(bucket, objectName, body)).build();
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
            Map<String, Object> body) {
        if ("PATCH".equalsIgnoreCase(methodOverride)) {
            String objectName = decode(objectPath);
            service.checkPreconditions(bucket, objectName, ifGenerationMatch, ifGenerationNotMatch,
                    ifMetagenerationMatch, ifMetagenerationNotMatch);
            return Response.ok(service.patchObject(bucket, objectName, body)).build();
        }
        throw GcpException.invalidArgument("Unsupported method override: " + methodOverride);
    }

    @DELETE
    @Path("/{object: .+}")
    public Response deleteObject(@PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @QueryParam("generation") String generation) {
        String objectName = decode(objectPath);
        if (generation != null) {
            service.deleteObjectVersion(bucket, objectName, generation);
            return Response.noContent().build();
        }
        if (!service.deleteObject(bucket, objectName)) {
            throw GcpException.notFound("Object not found: " + objectName);
        }
        return Response.noContent().build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{destObject: .+}/compose")
    public Response composeObject(@PathParam("bucket") String bucket,
            @PathParam("destObject") String destObjectPath,
            @Context HttpHeaders headers, Map<String, Object> body) {
        String destObject = decode(destObjectPath);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceObjects = body != null
                ? (List<Map<String, Object>>) body.get("sourceObjects") : List.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> destReq = body != null
                ? (Map<String, Object>) body.get("destination") : Map.of();
        String contentType = destReq != null ? (String) destReq.get("contentType") : null;
        List<String> sourceNames = sourceObjects == null ? List.of()
                : sourceObjects.stream().map(s -> (String) s.get("name")).toList();
        GcsObjectMeta meta = service.composeObject(bucket, destObject, sourceNames, contentType,
                requestBaseUrl(headers));
        return Response.ok(meta).build();
    }

    @POST
    @Path("/{srcObject: .+}/copyTo/b/{dstBucket}/o/{dstObject: .+}")
    public Response copyObject(@PathParam("bucket") String srcBucket,
            @PathParam("srcObject") String srcObjectPath,
            @PathParam("dstBucket") String dstBucket,
            @PathParam("dstObject") String dstObjectPath,
            @Context HttpHeaders headers) {
        String srcObject = decode(srcObjectPath);
        String dstObject = decode(dstObjectPath);
        GcsObjectMeta meta = service.copyObject(srcBucket, srcObject, dstBucket, dstObject,
                requestBaseUrl(headers));
        return Response.ok(meta).build();
    }

    @POST
    @Path("/{srcObject: .+}/rewriteTo/b/{dstBucket}/o/{dstObject: .+}")
    public Response rewriteObject(@PathParam("bucket") String srcBucket,
            @PathParam("srcObject") String srcObjectPath,
            @PathParam("dstBucket") String dstBucket,
            @PathParam("dstObject") String dstObjectPath,
            @Context HttpHeaders headers) {
        String srcObject = decode(srcObjectPath);
        String dstObject = decode(dstObjectPath);
        GcsObjectMeta meta = service.copyObject(srcBucket, srcObject, dstBucket, dstObject,
                requestBaseUrl(headers));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "storage#rewriteResponse");
        response.put("totalBytesRewritten", meta.getSize());
        response.put("objectSize", meta.getSize());
        response.put("done", true);
        response.put("resource", meta);
        return Response.ok(response).build();
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private String requestBaseUrl(HttpHeaders headers) {
        String host = headers.getHeaderString("Host");
        return host != null ? "http://" + host : config.baseUrl();
    }
}

package io.floci.gcp.services.gcs;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.XmlBuilder;
import io.floci.gcp.services.credentials.GcsAuthorizationService;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Handles GCS XML API requests: GET on a bucket (list objects) and GET/PUT/DELETE on an object.
 * Used by the Go SDK (STORAGE_EMULATOR_HOST), for signed-URL access, and by S3-shaped tooling
 * that predates the JSON API.
 */
@ApplicationScoped
@Path("/{bucket: [a-z0-9._-]+}")
public class GcsXmlDownloadController {

    private final GcsService service;
    private final EmulatorConfig config;
	private final GcsAuthorizationService authorizationService;

    @Inject
	public GcsXmlDownloadController(GcsService service, EmulatorConfig config,
			GcsAuthorizationService authorizationService) {
        this.service = service;
        this.config = config;
		this.authorizationService = authorizationService;
    }

    @OPTIONS
    @Path("/{object: .*}")
    public Response options() {
        return Response.ok().build();
    }

    @OPTIONS
    public Response optionsBucket() {
        return Response.ok().build();
    }

    @GET
    @Path("/{object: .+}")
    public Response download(
            @PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @Context UriInfo uriInfo,
            @QueryParam("generation") String generation,
            @HeaderParam("x-goog-encryption-key-sha256") String customerEncryptionKeySha256,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @HeaderParam("Range") String rangeHeader,
            @HeaderParam("Accept-Encoding") String acceptEncoding) {
        GcsSignedUrl.checkNotExpired(uriInfo);
        authorizationService.requireObjectRead(authorization, bucket, objectPath);
        GcsCustomerEncryption customerEncryption = GcsCustomerEncryption.fromKeySha256(customerEncryptionKeySha256);
        var download = service.getObjectForDownload(bucket, objectPath, generation, customerEncryption);
        return GcsMediaResponses.mediaResponse(download.data(), download.meta(), rangeHeader, acceptEncoding);
    }

    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{object: .+}")
    public Response upload(
            @PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @Context UriInfo uriInfo,
            @Context HttpHeaders headers,
            byte[] body) {
        GcsSignedUrl.checkNotExpired(uriInfo);
        authorizationService.requireObjectWrite(
                headers.getHeaderString(HttpHeaders.AUTHORIZATION), bucket, objectPath);
        String contentType = headers.getHeaderString(HttpHeaders.CONTENT_TYPE);
        String host = headers.getHeaderString("Host");
        String baseUrl = host != null ? "http://" + host : config.baseUrl();
        GcsObjectMeta meta = service.putObject(bucket, objectPath, contentType, body != null ? body : new byte[0],
                GcsCustomerEncryption.fromHeaders(headers), googMetaHeaders(headers), baseUrl);
        return Response.ok(meta).build();
    }

    /**
     * XML API DELETE. The JSON API equivalent is {@code DELETE /storage/v1/b/{b}/o/{o}}; a client
     * speaking the XML API expects a bare 204 here rather than a 405.
     */
    @DELETE
    @Path("/{object: .+}")
    public Response delete(
            @PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @Context UriInfo uriInfo,
            @QueryParam("generation") String generation,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization) {
        GcsSignedUrl.checkNotExpired(uriInfo);
        authorizationService.requireObjectDelete(authorization, bucket, objectPath);
        if (generation != null && !generation.isBlank()) {
            service.deleteObjectVersion(bucket, objectPath, generation);
        } else if (!service.deleteObject(bucket, objectPath)) {
            throw io.floci.gcp.core.common.GcpException.notFound("Object not found: " + objectPath);
        }
        return Response.noContent().build();
    }

    /**
     * XML API ListObjects: {@code GET /{bucket}}, answering with {@code ListBucketResult}.
     *
     * <p>Emits the v1 shape (Contents/CommonPrefixes/Marker), which is what GCS serves by default
     * and what S3-compatible clients expect.
     */
    @GET
    @Produces(MediaType.APPLICATION_XML)
    public Response listObjects(
            @PathParam("bucket") String bucket,
            @Context UriInfo uriInfo,
            @QueryParam("prefix") String prefix,
            @QueryParam("delimiter") String delimiter,
            @QueryParam("max-keys") Integer maxKeys,
            @QueryParam("marker") String marker,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization) {
        GcsSignedUrl.checkNotExpired(uriInfo);
        authorizationService.requireObjectList(authorization, bucket, prefix);
        service.getBucket(bucket);

        // "The object name after which you want to start listing objects. Objects whose names
        // are lexicographically greater than the marker are returned in the list of objects."
        // Without this a client that sees IsTruncated and re-requests gets the same first page
        // forever, so pagination never terminates.
        List<GcsObjectMeta> all = service.listObjects(bucket).stream()
                .filter(o -> prefix == null || prefix.isEmpty() || o.getName().startsWith(prefix))
                .sorted(Comparator.comparing(GcsObjectMeta::getName))
                .toList();

        // A page is a single lexicographic run over both objects and rolled-up prefixes, so
        // max-keys bounds the combined result and NextMarker names whichever entry ended the
        // page. Limiting only the objects would emit oversized pages and, worse, repeat every
        // prefix on the next page because the marker would skip past them.
        String basePrefix = prefix != null ? prefix : "";
        List<String> entryKeys = new ArrayList<>();
        Map<String, GcsObjectMeta> objectsByKey = new LinkedHashMap<>();
        Set<String> seenPrefixes = new LinkedHashSet<>();
        for (GcsObjectMeta meta : all) {
            if (delimiter != null && !delimiter.isEmpty()) {
                String rest = meta.getName().substring(basePrefix.length());
                int idx = rest.indexOf(delimiter);
                if (idx >= 0) {
                    String common = basePrefix + rest.substring(0, idx + delimiter.length());
                    if (seenPrefixes.add(common)) {
                        entryKeys.add(common);
                    }
                    continue;
                }
            }
            objectsByKey.put(meta.getName(), meta);
            entryKeys.add(meta.getName());
        }
        // The marker is compared against the rolled-up entry key, not the raw object name. A
        // NextMarker naming a common prefix would otherwise never advance: every object under
        // "d1/" sorts after "d1/", so they would survive the filter and roll up to "d1/" again,
        // handing the client the same page forever.
        if (marker != null && !marker.isEmpty()) {
            String from = marker;
            entryKeys.removeIf(key -> key.compareTo(from) <= 0);
        }
        entryKeys.sort(Comparator.naturalOrder());

        int limit = maxKeys != null && maxKeys > 0 ? maxKeys : entryKeys.size();
        boolean truncated = entryKeys.size() > limit;
        List<String> page = truncated ? entryKeys.subList(0, limit) : entryKeys;

        XmlBuilder xml = new XmlBuilder()
                .start("ListBucketResult", "http://doc.s3.amazonaws.com/2006-03-01")
                .elem("Name", bucket)
                .elem("Prefix", basePrefix)
                .elem("Marker", marker != null ? marker : "")
                .elem("MaxKeys", limit);
        if (delimiter != null && !delimiter.isEmpty()) {
            xml.elem("Delimiter", delimiter);
        }
        xml.elem("IsTruncated", truncated);
        if (truncated && !page.isEmpty()) {
            // The key a follow-up request passes back as `marker`.
            xml.elem("NextMarker", page.get(page.size() - 1));
        }
        for (String key : page) {
            GcsObjectMeta meta = objectsByKey.get(key);
            if (meta == null) {
                xml.start("CommonPrefixes").elem("Prefix", key).end("CommonPrefixes");
                continue;
            }
            xml.start("Contents")
                    .elem("Key", meta.getName())
                    .elem("Generation", meta.getGeneration())
                    .elem("MetaGeneration", meta.getMetageneration())
                    .elem("LastModified", meta.getUpdated())
                    .elem("ETag", meta.getEtag())
                    .elem("Size", meta.getSize())
                    .elem("StorageClass", meta.getStorageClass())
                    .end("Contents");
        }
        xml.end("ListBucketResult");
        return Response.ok(xml.build(), MediaType.APPLICATION_XML).build();
    }

    private static void appendTag(StringBuilder xml, String tag, String value) {
        if (value == null) {
            return;
        }
        xml.append('<').append(tag).append('>').append(escapeXml(value)).append("</").append(tag).append('>');
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static Map<String, String> googMetaHeaders(HttpHeaders headers) {
        var prefix = GcsMediaResponses.META_HEADER_PREFIX;
        var metadata = new LinkedHashMap<String, String>();
        for (var headerName : headers.getRequestHeaders().keySet()) {
            var lower = headerName.toLowerCase(Locale.ROOT);
            if (lower.startsWith(prefix) && lower.length() > prefix.length()) {
                metadata.put(lower.substring(prefix.length()), headers.getHeaderString(headerName));
            }
        }
        return metadata;
    }
}

package io.floci.gcp.services.gcs;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.*;
import io.floci.gcp.services.credentials.GcsAuthorizationService;
import io.floci.gcp.services.gcs.model.GcsMultipartUpload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shares the XML object routes with ordinary transfers, dispatched by native query parameters. */
@ApplicationScoped
public class GcsXmlMultipartHandler {
    private static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";
    private final GcsMultipartService service;
    private final GcsAuthorizationService auth;
    private final EmulatorConfig config;
    @Inject
    public GcsXmlMultipartHandler(GcsMultipartService service, GcsAuthorizationService auth, EmulatorConfig config) {
        this.service = service; this.auth = auth; this.config = config;
    }
    public static boolean matches(UriInfo uri) { return uri.getQueryParameters().containsKey("uploads") || uri.getQueryParameters().containsKey("uploadId"); }
    public Response handle(String method, String bucket, String object, UriInfo uri, HttpHeaders headers, byte[] bytes) {
        try {
            GcsSignedUrl.checkNotExpired(uri);
            String authorization = headers.getHeaderString(HttpHeaders.AUTHORIZATION);
            if (object == null) { auth.requireObjectList(authorization, bucket, uri.getQueryParameters().getFirst("prefix")); }
            else if (method.equals("GET")) { auth.requireObjectRead(authorization, bucket, object); }
            else { auth.requireObjectWrite(authorization, bucket, object); }
            for (String header : headers.getRequestHeaders().keySet()) {
                String lower = header.toLowerCase(Locale.ROOT);
                if (lower.startsWith("x-goog-if-") || lower.startsWith("x-goog-encryption-") || lower.startsWith("if-")) {
                    throw GcpException.invalidArgument("Preconditions and customer encryption are unsupported for XML multipart uploads");
                }
            }
            var query = uri.getQueryParameters();
            String id = query.getFirst("uploadId");
            if (object == null && method.equals("GET") && query.containsKey("uploads")) { return list(bucket, query); }
            if (object == null) { throw GcpException.invalidArgument("Object name is required"); }
            if (method.equals("POST") && query.containsKey("uploads") && id == null) {
                var metadata = new LinkedHashMap<String,String>();
                headers.getRequestHeaders().forEach((key, values) -> {
                    if (key.toLowerCase(Locale.ROOT).startsWith("x-goog-meta-")) { metadata.put(key.substring(12), values.getFirst()); }
                });
                GcsMultipartUpload upload = service.initiate(bucket, object, headers.getHeaderString("Content-Type"), metadata);
                return xml(new XmlBuilder().start("InitiateMultipartUploadResult", NS).elem("Bucket", bucket).elem("Key", object).elem("UploadId", upload.id).end("InitiateMultipartUploadResult"));
            }
            if (id == null || id.isBlank()) { throw GcpException.invalidArgument("uploadId is required"); }
            switch (method) {
                case "PUT" -> {
                    int number = number(query.getFirst("partNumber"), 1, 10000);
                    var part = service.putPart(bucket, object, id, number, bytes == null ? new byte[0] : bytes, headers.getHeaderString("Content-MD5"));
                    return Response.ok().header("ETag", part.etag()).build();
                }
                case "POST" -> {
                    var requested = XmlParser.parseRecords(new String(bytes == null ? new byte[0] : bytes, StandardCharsets.UTF_8), "CompleteMultipartUpload", "Part");
                    String base = RequestBaseUrl.resolve(uri, headers, config.baseUrl(), config.port());
                    var meta = service.complete(bucket, object, id, requested, base);
                    return Response.ok(new XmlBuilder().start("CompleteMultipartUploadResult", NS).elem("Location", meta.getMediaLink())
                            .elem("Bucket", bucket).elem("Key", object).elem("ETag", "\"" + meta.getEtag() + "\"").end("CompleteMultipartUploadResult").build(), MediaType.APPLICATION_XML)
                            .header("ETag", "\"" + meta.getEtag() + "\"").header("x-goog-generation", meta.getGeneration()).build();
                }
                case "DELETE" -> { service.abort(bucket, object, id); return Response.noContent().build(); }
                case "GET" -> {
                    var upload = service.get(bucket, object, id);
                    int marker = number(Optional.ofNullable(query.getFirst("part-number-marker")).orElse("0"), 0, 10000);
                    int max = number(Optional.ofNullable(query.getFirst("max-parts")).orElse("1000"), 1, 1000);
                    var parts = upload.parts.values().stream().filter(p -> p.number() > marker).toList();
                    var page = parts.subList(0, Math.min(parts.size(), max));
                    XmlBuilder response = new XmlBuilder().start("ListPartsResult", NS).elem("Bucket", bucket).elem("Key", object).elem("UploadId", id)
                            .elem("PartNumberMarker", marker).elem("MaxParts", max).elem("IsTruncated", parts.size() > max);
                    if (parts.size() > max) { response.elem("NextPartNumberMarker", page.getLast().number()); }
                    for (var part : page) { response.start("Part").elem("PartNumber", part.number()).elem("ETag", part.etag()).elem("Size", part.data().length).elem("LastModified", part.modified()).end("Part"); }
                    return xml(response.end("ListPartsResult"));
                }
                default -> throw GcpException.unimplemented("Unsupported multipart method");
            }
        } catch (GcpException error) {
            String code = error.getReason() != null ? error.getReason() : switch (error.getHttpStatus()) {
                case 403 -> "AccessDenied"; case 404 -> "NoSuchBucket"; default -> "InvalidArgument";
            };
            return Response.status(error.getHttpStatus()).type(MediaType.APPLICATION_XML)
                    .entity(new XmlBuilder().start("Error").elem("Code", code).elem("Message", error.getMessage()).end("Error").build()).build();
        }
    }
    private Response list(String bucket, MultivaluedMap<String,String> query) {
        String prefix = Optional.ofNullable(query.getFirst("prefix")).orElse("");
        String delimiter = query.getFirst("delimiter");
        if (delimiter != null && !delimiter.isEmpty()) { throw GcpException.unimplemented("Multipart upload delimiter grouping is not implemented"); }
        String keyMarker = Optional.ofNullable(query.getFirst("key-marker")).orElse("");
        String idMarker = query.getFirst("upload-id-marker");
        int max = number(Optional.ofNullable(query.getFirst("max-uploads")).orElse("1000"), 1, 1000);
        List<GcsMultipartUpload> all = service.list(bucket, prefix).stream().filter(u -> u.object.compareTo(keyMarker) > 0
                || u.object.equals(keyMarker) && idMarker != null && u.id.compareTo(idMarker) > 0).toList();
        var page = all.subList(0, Math.min(all.size(), max));
        XmlBuilder xml = new XmlBuilder().start("ListMultipartUploadsResult", NS).elem("Bucket", bucket).elem("Prefix", prefix)
                .elem("KeyMarker", keyMarker).elem("UploadIdMarker", idMarker).elem("MaxUploads", max).elem("IsTruncated", all.size() > max);
        if (all.size() > max) { xml.elem("NextKeyMarker", page.getLast().object).elem("NextUploadIdMarker", page.getLast().id); }
        for (var upload : page) {
            xml.start("Upload").elem("Key", upload.object).elem("UploadId", upload.id).elem("Initiated", upload.initiated).elem("StorageClass", "STANDARD").end("Upload");
        }
        return xml(xml.end("ListMultipartUploadsResult"));
    }
    private static Response xml(XmlBuilder xml) { return Response.ok(xml.build(), MediaType.APPLICATION_XML).build(); }
    private static int number(String text, int min, int max) {
        try { int value = Integer.parseInt(text); if (value >= min && value <= max) { return value; } }
        catch (NumberFormatException ignored) {}
        throw GcpException.invalidArgument("Invalid multipart numeric parameter");
    }
}

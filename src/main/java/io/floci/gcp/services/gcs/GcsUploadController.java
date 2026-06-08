package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@ApplicationScoped
@Path("/upload/storage/v1/b/{bucket}/o")
@Produces(MediaType.APPLICATION_JSON)
public class GcsUploadController {

    private static final Charset ISO = StandardCharsets.ISO_8859_1;

    private final GcsService service;
    private final EmulatorConfig config;
    private final ObjectMapper objectMapper;

    @Inject
    public GcsUploadController(GcsService service, EmulatorConfig config, ObjectMapper objectMapper) {
        this.service = service;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response upload(
            @PathParam("bucket") String bucket,
            @QueryParam("uploadType") String uploadType,
            @QueryParam("name") String nameParam,
            @QueryParam("ifGenerationMatch") Long ifGenerationMatch,
            @QueryParam("ifGenerationNotMatch") Long ifGenerationNotMatch,
            @QueryParam("ifMetagenerationMatch") Long ifMetagenerationMatch,
            @QueryParam("ifMetagenerationNotMatch") Long ifMetagenerationNotMatch,
            @jakarta.ws.rs.core.Context HttpHeaders headers,
            byte[] body) {
        Preconditions preconditions = new Preconditions(ifGenerationMatch, ifGenerationNotMatch,
                ifMetagenerationMatch, ifMetagenerationNotMatch);
        if ("multipart".equals(uploadType)) {
            return handleMultipart(bucket, nameParam, headers, body, preconditions);
        } else if ("resumable".equals(uploadType)) {
            return handleStartResumable(bucket, nameParam, headers, body, preconditions);
        } else if ("media".equals(uploadType)) {
            return handleMedia(bucket, nameParam, headers, body, preconditions);
        }
        throw GcpException.invalidArgument("unsupported uploadType: " + uploadType);
    }

    private record Preconditions(Long ifGenerationMatch, Long ifGenerationNotMatch,
            Long ifMetagenerationMatch, Long ifMetagenerationNotMatch) {
        void check(GcsService service, String bucket, String objectName) {
            service.checkPreconditions(bucket, objectName, ifGenerationMatch, ifGenerationNotMatch,
                    ifMetagenerationMatch, ifMetagenerationNotMatch);
        }
    }

    @PUT
    @Consumes(MediaType.WILDCARD)
    public Response resumablePut(
            @PathParam("bucket") String bucket,
            @QueryParam("upload_id") String uploadId,
            @jakarta.ws.rs.core.Context HttpHeaders headers,
            byte[] body) {
        if (uploadId == null) {
            throw GcpException.invalidArgument("missing upload_id query parameter");
        }
        String contentRange = headers.getHeaderString("Content-Range");
        if (contentRange != null && !contentRange.isBlank()) {
            ContentRange range = parseContentRange(contentRange, body.length);
            if (range.statusQuery()) {
                long uploadedLength = service.resumableUploadLength(uploadId);
                if (range.totalSize() != null && uploadedLength == range.totalSize()) {
                    GcsObjectMeta meta = service.completeBufferedResumableUpload(
                            uploadId, range.totalSize(), requestBaseUrl(headers));
                    return Response.ok(meta).build();
                }
                Response.ResponseBuilder response = Response.status(308);
                if (uploadedLength > 0) {
                    response.header("Range", "bytes=0-" + (uploadedLength - 1));
                }
                return response.build();
            }
            if (range.totalSize() == null) {
                long end = service.appendResumableUpload(uploadId, range.start(), body);
                return Response.status(308).header("Range", "bytes=0-" + end).build();
            }
            GcsObjectMeta meta = service.completeResumableUpload(
                    uploadId, range.start(), body, range.totalSize(), requestBaseUrl(headers));
            return Response.ok(meta).build();
        }
        GcsObjectMeta meta = service.completeResumableUpload(uploadId, body, requestBaseUrl(headers));
        return Response.ok(meta).build();
    }

    private record ContentRange(long start, long end, Long totalSize, boolean statusQuery) {}

    private static ContentRange parseContentRange(String header, int bodyLength) {
        if (!header.startsWith("bytes ")) {
            throw GcpException.invalidArgument("invalid Content-Range header: " + header);
        }
        String value = header.substring("bytes ".length());
        if (value.startsWith("*/")) {
            if (bodyLength != 0) {
                throw GcpException.invalidArgument("invalid Content-Range header: " + header);
            }
            String totalValue = value.substring(2);
            Long totalSize = "*".equals(totalValue) ? null : parseLong(totalValue, header);
            return new ContentRange(0, -1, totalSize, true);
        }
        int dash = value.indexOf('-');
        int slash = value.indexOf('/');
        if (dash < 0 || slash < 0 || slash < dash) {
            throw GcpException.invalidArgument("invalid Content-Range header: " + header);
        }

        long start = parseLong(value.substring(0, dash), header);
        String endValue = value.substring(dash + 1, slash);
        String totalValue = value.substring(slash + 1);
        Long totalSize = "*".equals(totalValue) ? null : parseLong(totalValue, header);
        long end;
        if ("*".equals(endValue)) {
            if (totalSize != null || bodyLength == 0) {
                throw GcpException.invalidArgument("invalid Content-Range header: " + header);
            }
            end = start + bodyLength - 1L;
            totalSize = end + 1L;
        } else {
            end = parseLong(endValue, header);
        }
        if (start < 0 || end < start || bodyLength != end - start + 1) {
            throw GcpException.invalidArgument("invalid Content-Range header: " + header);
        }
        return new ContentRange(start, end, totalSize, false);
    }

    private static long parseLong(String value, String header) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw GcpException.invalidArgument("invalid Content-Range header: " + header);
        }
    }

    private Response handleMultipart(String bucket, String nameParam, HttpHeaders headers, byte[] body,
            Preconditions preconditions) {
        String contentType = headers.getHeaderString(HttpHeaders.CONTENT_TYPE);
        String[] rawParts = parseMultipartRaw(contentType, new String(body, ISO));

        Map<?, ?> metadata;
        try {
            metadata = objectMapper.readValue(extractPartBody(rawParts[0]).getBytes(ISO), Map.class);
        } catch (Exception e) {
            throw GcpException.invalidArgument("invalid JSON metadata in multipart upload");
        }

        String objectName = (String) metadata.get("name");
        if (objectName == null) {
            objectName = nameParam;
        }
        String objectContentType = (String) metadata.get("contentType");
        if (objectContentType == null) {
            objectContentType = extractPartHeader(rawParts[1], "content-type");
        }
        preconditions.check(service, bucket, objectName);
        byte[] dataBytes = extractPartBody(rawParts[1]).getBytes(ISO);
        GcsObjectMeta meta = service.putObject(bucket, objectName, objectContentType, dataBytes,
                GcsCustomerEncryption.fromHeaders(headers), requestBaseUrl(headers));
        return Response.ok(meta).build();
    }

    @SuppressWarnings("unchecked")
    private Response handleStartResumable(String bucket, String nameParam, HttpHeaders headers, byte[] body,
            Preconditions preconditions) {
        String contentType = headers.getHeaderString("X-Upload-Content-Type");
        String name = nameParam;

        if (body != null && body.length > 0) {
            try {
                Map<String, Object> meta = objectMapper.readValue(body, Map.class);
                if (name == null) {
                    name = (String) meta.get("name");
                }
                if (contentType == null) {
                    contentType = (String) meta.get("contentType");
                }
            } catch (Exception ignored) {
            }
        }

        if (name == null) {
            name = "unknown";
        }
        if (contentType == null) {
            contentType = "application/octet-stream";
        }

        preconditions.check(service, bucket, name);
        String uploadId = service.startResumableUpload(bucket, name, contentType,
                GcsCustomerEncryption.fromHeaders(headers));
        String location = requestBaseUrl(headers) + "/upload/storage/v1/b/" + bucket
                + "/o?uploadType=resumable&upload_id=" + uploadId;

        return Response.ok().header("Location", location).build();
    }

    private Response handleMedia(String bucket, String name, HttpHeaders headers, byte[] body,
            Preconditions preconditions) {
        String contentType = headers.getHeaderString(HttpHeaders.CONTENT_TYPE);
        preconditions.check(service, bucket, name);
        GcsObjectMeta meta = service.putObject(bucket, name, contentType, body,
                GcsCustomerEncryption.fromHeaders(headers), requestBaseUrl(headers));
        return Response.ok(meta).build();
    }

    private String requestBaseUrl(HttpHeaders headers) {
        String host = headers.getHeaderString("Host");
        return host != null ? "http://" + host : config.baseUrl();
    }

    private static String[] parseMultipartRaw(String contentType, String bodyStr) {
        String boundary = "--" + extractBoundary(contentType);

        int first = bodyStr.indexOf(boundary);
        if (first < 0) {
            throw GcpException.invalidArgument("multipart boundary not found in body");
        }
        int second = bodyStr.indexOf(boundary, first + boundary.length());
        if (second < 0) {
            throw GcpException.invalidArgument("only one multipart part found");
        }
        int third = bodyStr.indexOf(boundary, second + boundary.length());
        if (third < 0) {
            third = bodyStr.length();
        }

        return new String[]{
                bodyStr.substring(first + boundary.length(), second),
                bodyStr.substring(second + boundary.length(), third)
        };
    }

    private static String extractPartHeader(String part, String headerName) {
        int headersEnd = part.indexOf("\r\n\r\n");
        String sep = "\r\n";
        if (headersEnd < 0) {
            headersEnd = part.indexOf("\n\n");
            sep = "\n";
        }
        if (headersEnd < 0) {
            return null;
        }
        String headerSection = part.substring(0, headersEnd);
        for (String line : headerSection.split(sep)) {
            if (line.toLowerCase().startsWith(headerName + ":")) {
                String value = line.substring(headerName.length() + 1).trim();
                int semi = value.indexOf(';');
                return semi >= 0 ? value.substring(0, semi).trim() : value;
            }
        }
        return null;
    }

    private static String extractBoundary(String contentType) {
        if (contentType == null) {
            throw GcpException.invalidArgument("missing Content-Type header for multipart upload");
        }
        for (String segment : contentType.split(";")) {
            String trimmed = segment.trim();
            if (trimmed.startsWith("boundary=")) {
                String boundary = trimmed.substring("boundary=".length());
                if (boundary.length() >= 2
                        && ((boundary.startsWith("\"") && boundary.endsWith("\""))
                        || (boundary.startsWith("'") && boundary.endsWith("'")))) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        throw GcpException.invalidArgument("missing boundary in Content-Type: " + contentType);
    }

    private static String extractPartBody(String part) {
        int idx = part.indexOf("\r\n\r\n");
        if (idx >= 0) {
            String partBody = part.substring(idx + 4);
            if (partBody.endsWith("\r\n")) {
                partBody = partBody.substring(0, partBody.length() - 2);
            }
            return partBody;
        }
        idx = part.indexOf("\n\n");
        if (idx >= 0) {
            String partBody = part.substring(idx + 2);
            if (partBody.endsWith("\n")) {
                partBody = partBody.substring(0, partBody.length() - 1);
            }
            return partBody;
        }
        return part;
    }
}

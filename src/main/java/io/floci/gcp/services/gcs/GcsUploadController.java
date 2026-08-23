package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.credentials.GcsAuthorizationService;
import io.floci.gcp.services.gcs.model.CompletedResumableUpload;
import io.floci.gcp.services.gcs.model.GcsContentRange;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.floci.gcp.services.gcs.model.GcsObjectPreconditions;
import io.floci.gcp.services.gcs.model.ResumableChunkOutcome;
import io.floci.gcp.services.gcs.model.ResumableUpload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
@Path("/upload/storage/v1/b/{bucket}/o")
@Produces(MediaType.APPLICATION_JSON)
public class GcsUploadController {

    private static final Charset ISO = StandardCharsets.ISO_8859_1;

    private final GcsService service;
    private final EmulatorConfig config;
    private final ObjectMapper objectMapper;
	private final GcsAuthorizationService authorizationService;

    @Inject
	public GcsUploadController(GcsService service, EmulatorConfig config, ObjectMapper objectMapper,
			GcsAuthorizationService authorizationService) {
        this.service = service;
        this.config = config;
        this.objectMapper = objectMapper;
		this.authorizationService = authorizationService;
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response upload(
            @PathParam("bucket") String bucket,
            @QueryParam("uploadType") String uploadType,
            @QueryParam("upload_id") String uploadId,
            @QueryParam("name") String nameParam,
            @QueryParam("ifGenerationMatch") Long ifGenerationMatch,
            @QueryParam("ifGenerationNotMatch") Long ifGenerationNotMatch,
            @QueryParam("ifMetagenerationMatch") Long ifMetagenerationMatch,
            @QueryParam("ifMetagenerationNotMatch") Long ifMetagenerationNotMatch,
            @jakarta.ws.rs.core.Context HttpHeaders headers,
            @jakarta.ws.rs.core.Context UriInfo uriInfo,
            byte[] body) {
        if (uploadId != null && !uploadId.isBlank()) {
            return resumableChunk(uploadId, headers, uriInfo, body);
        }
        GcsObjectPreconditions preconditions = new GcsObjectPreconditions(ifGenerationMatch, ifGenerationNotMatch,
                ifMetagenerationMatch, ifMetagenerationNotMatch);
        if ("multipart".equals(uploadType)) {
            return handleMultipart(bucket, nameParam, headers, uriInfo, body, preconditions);
        } else if ("resumable".equals(uploadType)) {
            return handleStartResumable(bucket, nameParam, headers, uriInfo, body, preconditions);
        } else if ("media".equals(uploadType)) {
            return handleMedia(bucket, nameParam, headers, uriInfo, body, preconditions);
        }
        throw GcpException.invalidArgument("unsupported uploadType: " + uploadType);
    }

    @PUT
    @Consumes(MediaType.WILDCARD)
    public Response resumablePut(
            @PathParam("bucket") String bucket,
            @QueryParam("upload_id") String uploadId,
            @jakarta.ws.rs.core.Context HttpHeaders headers,
            @jakarta.ws.rs.core.Context UriInfo uriInfo,
            byte[] body) {
        if (uploadId == null || uploadId.isBlank()) {
            throw GcpException.invalidArgument("missing upload_id query parameter");
        }
        return resumableChunk(uploadId, headers, uriInfo, body);
    }

    private Response resumableChunk(String uploadId, HttpHeaders headers, UriInfo uriInfo, byte[] body) {
        ResumableUpload upload = service.findResumableUpload(uploadId);
        String bucket;
        String objectName;
        if (upload != null) {
            bucket = upload.bucket();
            objectName = upload.objectName();
        } else {
            // The active session is dropped only after the completed one is recorded, so
            // reading the completed map after this miss cannot land in a gap.
            CompletedResumableUpload completed = service.completedResumableUpload(uploadId);
            if (completed == null) {
                throw GcpException.notFound("Resumable upload not found: " + uploadId);
            }
            bucket = completed.bucket();
            objectName = completed.objectName();
        }
        authorizationService.requireObjectWrite(
                headers.getHeaderString(HttpHeaders.AUTHORIZATION), bucket, objectName);

        String contentRange = headers.getHeaderString("Content-Range");
        GcsContentRange range = contentRange != null && !contentRange.isBlank()
                ? parseContentRange(contentRange, body.length)
                : null;
        ResumableChunkOutcome outcome = service.applyResumableChunk(
                uploadId, range, body, requestBaseUrl(headers, uriInfo));
        if (outcome.completed() != null) {
            return Response.ok(outcome.completed()).build();
        }
        Response.ResponseBuilder response = resumeIncomplete(headers);
        if (outcome.receivedLength() > 0) {
            response.header("Range", "bytes=0-" + (outcome.receivedLength() - 1));
        }
        return response.build();
    }

    // The Go SDK sets X-GUploader-No-308 because 308 collides with the RFC 7238
    // "Permanent Redirect" semantics, and expects 200 with the override header instead.
    private static Response.ResponseBuilder resumeIncomplete(HttpHeaders headers) {
        if ("yes".equalsIgnoreCase(headers.getHeaderString("X-GUploader-No-308"))) {
            return Response.ok().header("X-HTTP-Status-Code-Override", "308");
        }
        return Response.status(308);
    }

    private static GcsContentRange parseContentRange(String header, int bodyLength) {
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
            return new GcsContentRange(0, -1, totalSize, true);
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
        if (totalSize != null && end >= totalSize) {
            throw GcpException.invalidArgument("invalid Content-Range header: " + header);
        }
        return new GcsContentRange(start, end, totalSize, false);
    }

    private static long parseLong(String value, String header) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw GcpException.invalidArgument("invalid Content-Range header: " + header);
        }
    }

    private Response handleMultipart(String bucket, String nameParam, HttpHeaders headers, UriInfo uriInfo, byte[] body,
            GcsObjectPreconditions preconditions) {
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
        authorizationService.requireObjectWrite(
                headers.getHeaderString(HttpHeaders.AUTHORIZATION), bucket, objectName);
        var userMetadata = extractUserMetadata(metadata);
        byte[] dataBytes = extractPartBody(rawParts[1]).getBytes(ISO);
        GcsObjectMeta meta = service.putObject(bucket, objectName, objectContentType, dataBytes,
                GcsCustomerEncryption.fromHeaders(headers), userMetadata, preconditions, requestBaseUrl(headers, uriInfo));
        return Response.ok(meta).build();
    }

    private Response handleStartResumable(String bucket, String nameParam, HttpHeaders headers, UriInfo uriInfo, byte[] body,
            GcsObjectPreconditions preconditions) {
        String requestContentType = headers.getHeaderString(HttpHeaders.CONTENT_TYPE);
        if (body != null && body.length > 0 && !isJsonContentType(requestContentType)) {
            throw GcpException.invalidArgument("Unsupported content with type: " + mediaType(requestContentType));
        }
        String contentType = headers.getHeaderString("X-Upload-Content-Type");
        String name = nameParam;
        Map<String, String> userMetadata = null;

        if (body != null && body.length > 0) {
            Map<?, ?> meta = null;
            try {
                meta = objectMapper.readValue(body, Map.class);
            } catch (Exception ignored) {
            }
            if (meta != null) {
                if (name == null && meta.get("name") instanceof String bodyName) {
                    name = bodyName;
                }
                if (contentType == null && meta.get("contentType") instanceof String bodyContentType) {
                    contentType = bodyContentType;
                }
                userMetadata = extractUserMetadata(meta);
            }
        }

        if (name == null) {
            name = "unknown";
        }
        if (contentType == null) {
            contentType = "application/octet-stream";
        }

        authorizationService.requireObjectWrite(
                headers.getHeaderString(HttpHeaders.AUTHORIZATION), bucket, name);
        String uploadId = service.startResumableUpload(bucket, name, contentType,
                GcsCustomerEncryption.fromHeaders(headers), userMetadata, preconditions);
        String location = requestBaseUrl(headers, uriInfo) + "/upload/storage/v1/b/" + bucket
                + "/o?uploadType=resumable&upload_id=" + uploadId;

        return Response.ok().header("Location", location).build();
    }

    private Response handleMedia(String bucket, String name, HttpHeaders headers, UriInfo uriInfo, byte[] body,
            GcsObjectPreconditions preconditions) {
        String contentType = headers.getHeaderString(HttpHeaders.CONTENT_TYPE);
        authorizationService.requireObjectWrite(
                headers.getHeaderString(HttpHeaders.AUTHORIZATION), bucket, name);
        GcsObjectMeta meta = service.putObject(bucket, name, contentType, body,
                GcsCustomerEncryption.fromHeaders(headers), preconditions, requestBaseUrl(headers, uriInfo));
        return Response.ok(meta).build();
    }

    private static String mediaType(String contentType) {
        int parameters = contentType.indexOf(';');
        return parameters < 0 ? contentType.trim() : contentType.substring(0, parameters).trim();
    }

    private static boolean isJsonContentType(String contentType) {
        return contentType == null || contentType.isBlank() || contentType.toLowerCase().contains("json");
    }

    private String requestBaseUrl(HttpHeaders headers, UriInfo uriInfo) {
        String host = headers.getHeaderString("Host");
        if (host == null) {
            return config.baseUrl();
        }
        if (hasPort(host)) {
            return "http://" + host;
        }
        URI baseUrl = URI.create(config.baseUrl());
        int port = baseUrl.getPort() >= 0 ? baseUrl.getPort() : config.port();
        String scheme = baseUrl.getScheme() != null ? baseUrl.getScheme() : uriInfo.getBaseUri().getScheme();
        return scheme + "://" + host + ":" + port;
    }

    private static boolean hasPort(String host) {
        if (host.startsWith("[")) {
            return host.indexOf("]:") > 0;
        }
        return host.indexOf(':') >= 0;
    }

    private static Map<String, String> extractUserMetadata(Map<?, ?> requestMetadata) {
        var value = requestMetadata.get("metadata");
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> entries)) {
            throw GcpException.invalidArgument("metadata must be an object with string values");
        }
        var userMetadata = new LinkedHashMap<String, String>();
        for (var entry : entries.entrySet()) {
            var entryValue = entry.getValue();
            if (entryValue == null) {
                continue;
            }
            if (!(entryValue instanceof String stringValue)) {
                throw GcpException.invalidArgument(
                        "metadata value for key " + entry.getKey() + " must be a string");
            }
            userMetadata.put(String.valueOf(entry.getKey()), stringValue);
        }
        return userMetadata;
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

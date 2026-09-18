package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.RequestBaseUrl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Media uploads for load jobs ({@code jobs.insert} with {@code supportsMediaUpload}): the
 * {@code multipart} protocol (job resource and data in one {@code multipart/related} request) and
 * the {@code resumable} protocol (a session opened with the job resource, then {@code PUT}s with
 * {@code Content-Range}, answered 308 until the last byte arrives). The SDKs use
 * {@code /upload/...?uploadType=resumable}; the alternative {@code /resumable/upload/...} path is
 * not served.
 */
@ApplicationScoped
@Path("/upload/bigquery/v2/projects/{projectId}/jobs")
@Produces(MediaType.APPLICATION_JSON)
public class BigQueryUploadController {

    private static final int RESUME_INCOMPLETE = 308;

    private record Session(String projectId, Map<String, Object> job, ByteArrayOutputStream data) {}

    private static Response incomplete(long received) {
        Response.ResponseBuilder builder = Response.status(RESUME_INCOMPLETE);
        if (received > 0) {
            builder.header("Range", "bytes=0-" + (received - 1));
        }
        return builder.build();
    }

    private final BigQueryService service;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Inject
    public BigQueryUploadController(BigQueryService service, ObjectMapper mapper, EmulatorConfig config) {
        this.service = service;
        this.mapper = mapper;
        this.config = config;
    }

    /** Cancelling a resumable session, which is how the SDK aborts an upload. */
    @DELETE
    public Response cancel(@QueryParam("upload_id") String uploadId) {
        if (uploadId == null || sessions.remove(uploadId) == null) {
            throw GcpException.notFound("Not found: upload session " + uploadId);
        }
        return Response.noContent().build();
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response upload(@PathParam("projectId") String projectId, @QueryParam("uploadType") String uploadType,
                           @HeaderParam("Content-Type") String contentType, byte[] body,
                           @Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return start(projectId, uploadType, contentType, body, uriInfo, headers);
    }

    @PUT
    @Consumes(MediaType.WILDCARD)
    public Response putChunk(@QueryParam("upload_id") String uploadId,
                             @HeaderParam("Content-Range") String contentRange, byte[] body) {
        return chunk(uploadId, contentRange, body);
    }

    private Response start(String projectId, String uploadType, String contentType, byte[] body, UriInfo uriInfo,
                           HttpHeaders headers) {
        if ("multipart".equalsIgnoreCase(uploadType)) {
            Multipart parts = Multipart.parse(contentType, body);
            return Response.ok(runJob(projectId, readJob(parts.metadata()), parts.media())).build();
        }
        if ("resumable".equalsIgnoreCase(uploadType)) {
            String id = UUID.randomUUID().toString().replace("-", "");
            sessions.put(id, new Session(projectId, readJob(body), new ByteArrayOutputStream()));
            String location = RequestBaseUrl.resolve(uriInfo, headers, config.baseUrl(), config.port())
                    + "/upload/bigquery/v2/projects/" + projectId + "/jobs"
                    + "?uploadType=resumable&upload_id=" + id;
            return Response.ok().header("Location", location).build();
        }
        throw GcpException.invalidArgument("Unsupported uploadType " + uploadType
                + "; use multipart or resumable").withReason("invalid");
    }

    private Response chunk(String uploadId, String contentRange, byte[] body) {
        Session session = uploadId != null ? sessions.get(uploadId) : null;
        if (session == null) {
            throw GcpException.notFound("Not found: upload session " + uploadId);
        }
        byte[] data = body != null ? body : new byte[0];
        long total = -1;
        synchronized (session) {
            if (contentRange != null && contentRange.startsWith("bytes ")) {
                String[] rangeAndTotal = contentRange.substring("bytes ".length()).trim().split("/", 2);
                if (rangeAndTotal.length == 2 && !rangeAndTotal[1].equals("*")) {
                    total = Long.parseLong(rangeAndTotal[1].trim());
                }
                if (!rangeAndTotal[0].equals("*")) {
                    long start = Long.parseLong(rangeAndTotal[0].split("-", 2)[0].trim());
                    if (start > session.data().size()) {
                        // A gap: the client resumed from an offset we never received. Appending
                        // here would corrupt the payload, so report what we actually hold.
                        return incomplete(session.data().size());
                    }
                    if (start < session.data().size()) {
                        // Retransmission after a lost response: keep only the bytes before start.
                        byte[] kept = Arrays.copyOf(session.data().toByteArray(), (int) start);
                        session.data().reset();
                        session.data().writeBytes(kept);
                    }
                    session.data().writeBytes(data);
                }
            } else {
                session.data().writeBytes(data);
                total = session.data().size();
            }
            long received = session.data().size();
            if (total < 0 || received < total) {
                return incomplete(received);
            }
            sessions.remove(uploadId);
            return Response.ok(runJob(session.projectId(), session.job(), session.data().toByteArray())).build();
        }
    }

    @SuppressWarnings("unchecked")
    private Object runJob(String projectId, Map<String, Object> job, byte[] data) {
        Map<String, Object> configuration = job.get("configuration") instanceof Map<?, ?> c
                ? (Map<String, Object>) c : Map.of();
        if (!(configuration.get("load") instanceof Map<?, ?> load)) {
            throw GcpException.invalidArgument("Media uploads are only supported for load jobs").withReason("invalid");
        }
        Map<String, Object> reference = job.get("jobReference") instanceof Map<?, ?> r
                ? (Map<String, Object>) r : Map.of();
        return BigQueryController.buildJob(service.load(projectId, (String) reference.get("location"),
                (String) reference.get("jobId"), (Map<String, Object>) load, data));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJob(byte[] json) {
        if (json == null || json.length == 0) {
            throw GcpException.invalidArgument("Missing job resource in upload request").withReason("invalid");
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (IOException e) {
            throw GcpException.invalidArgument("Invalid job resource: " + e.getMessage()).withReason("invalid");
        }
    }

    /** A {@code multipart/related} body: the JSON job resource followed by the media. */
    record Multipart(byte[] metadata, byte[] media) {

        static Multipart parse(String contentType, byte[] body) {
            String boundary = boundary(contentType);
            byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            java.util.List<byte[]> parts = new java.util.ArrayList<>();
            int position = indexOf(body, delimiter, 0);
            while (position >= 0) {
                int start = position + delimiter.length;
                if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') {
                    break; // closing delimiter
                }
                int next = indexOf(body, delimiter, start);
                if (next < 0) {
                    break;
                }
                parts.add(partContent(Arrays.copyOfRange(body, start, next)));
                position = next;
            }
            if (parts.size() < 2) {
                throw GcpException.invalidArgument("A multipart upload needs the job resource and the data")
                        .withReason("invalid");
            }
            return new Multipart(parts.get(0), parts.get(1));
        }

        private static String boundary(String contentType) {
            if (contentType != null) {
                for (String param : contentType.split(";")) {
                    String trimmed = param.trim();
                    if (trimmed.toLowerCase().startsWith("boundary=")) {
                        String value = trimmed.substring("boundary=".length());
                        return value.startsWith("\"") && value.endsWith("\"")
                                ? value.substring(1, value.length() - 1) : value;
                    }
                }
            }
            throw GcpException.invalidArgument("multipart upload without a boundary").withReason("invalid");
        }

        /** Strips the part headers and the CRLF that precedes the next delimiter. */
        private static byte[] partContent(byte[] part) {
            int headersEnd = indexOf(part, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 0);
            int start = headersEnd >= 0 ? headersEnd + 4 : 0;
            int end = part.length;
            if (end - 2 >= start && part[end - 2] == '\r' && part[end - 1] == '\n') {
                end -= 2;
            } else if (end - 1 >= start && part[end - 1] == '\n') {
                end -= 1;
            }
            return Arrays.copyOfRange(part, start, end);
        }

        private static int indexOf(byte[] data, byte[] pattern, int from) {
            outer:
            for (int i = from; i <= data.length - pattern.length; i++) {
                for (int j = 0; j < pattern.length; j++) {
                    if (data[i + j] != pattern[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }
}

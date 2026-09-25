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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (?:(\\*)|(\\d+)-(\\d+))/(\\*|\\d+)");

    /** An open resumable upload. Mutated only while holding its own monitor. */
    private static final class Session {
        private final String projectId;
        private final Map<String, Object> job;
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private long total = -1;
        private volatile long lastWriteMillis = System.currentTimeMillis();

        private Session(String projectId, Map<String, Object> job) {
            this.projectId = projectId;
            this.job = job;
        }
    }

    /** A parsed {@code Content-Range}: {@code start}/{@code end} are -1 for a {@code bytes *} status query. */
    private record ContentRange(long start, long end, long total) {}

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

    /**
     * Cancelling a resumable session, which is how the SDK aborts an upload. It takes the session's
     * monitor, like the chunk that completes an upload, so a 204 here means the load job will not
     * run: either the cancel wins and the final chunk finds no session, or the final chunk already
     * claimed the upload and the cancel reports the session gone. The final chunk releases the
     * monitor before running the job, so a cancel never waits on a load.
     */
    @DELETE
    public Response cancel(@QueryParam("upload_id") String uploadId) {
        Session session = uploadId != null ? sessions.get(uploadId) : null;
        if (session == null) {
            throw GcpException.notFound("Not found: upload session " + uploadId);
        }
        synchronized (session) {
            if (!sessions.remove(uploadId, session)) {
                throw GcpException.notFound("Not found: upload session " + uploadId);
            }
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
            sessions.put(id, new Session(projectId, readJob(body)));
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
        byte[] payload;
        synchronized (session) {
            if (sessions.get(uploadId) != session) {
                throw GcpException.notFound("Not found: upload session " + uploadId);
            }
            session.lastWriteMillis = System.currentTimeMillis();
            if (contentRange == null) {
                session.data.writeBytes(data);
                session.total = session.data.size();
            } else {
                // Everything is validated before the session changes, so a rejected chunk leaves
                // the bytes already held untouched.
                ContentRange range = parseContentRange(contentRange, data.length);
                long held = session.data.size();
                if (range.total() >= 0) {
                    if (session.total >= 0 && session.total != range.total()) {
                        throw GcpException.invalidArgument("Content-Range total " + range.total()
                                + " does not match the declared total " + session.total).withReason("invalid");
                    }
                    if (range.start() < 0 && range.total() < held) {
                        throw GcpException.invalidArgument("Content-Range total " + range.total()
                                + " is smaller than the " + held + " bytes already received").withReason("invalid");
                    }
                }
                if (range.start() > held) {
                    // A gap: the client resumed from an offset we never received. Appending
                    // here would corrupt the payload, so report what we actually hold.
                    return incomplete(held);
                }
                if (range.total() >= 0) {
                    session.total = range.total();
                }
                if (range.start() >= 0) {
                    if (range.start() < held) {
                        // Retransmission after a lost response: keep only the bytes before start.
                        byte[] kept = Arrays.copyOf(session.data.toByteArray(), (int) range.start());
                        session.data.reset();
                        session.data.writeBytes(kept);
                    }
                    session.data.writeBytes(data);
                }
            }
            long received = session.data.size();
            if (session.total < 0 || received < session.total) {
                return incomplete(received);
            }
            if (received > session.total) {
                throw GcpException.invalidArgument("Received " + received + " bytes, more than the declared total "
                        + session.total).withReason("invalid");
            }
            if (!sessions.remove(uploadId, session)) {
                throw GcpException.notFound("Not found: upload session " + uploadId);
            }
            payload = session.data.toByteArray();
        }
        return Response.ok(runJob(session.projectId, session.job, payload)).build();
    }

    /**
     * {@code bytes <first>-<last>/<total|*>} for a chunk, or {@code bytes *}{@code /<total|*>} for a
     * status query. A malformed header is a 400 rather than a NumberFormatException, and a chunk's
     * range has to describe exactly the bytes in the request body.
     */
    private static ContentRange parseContentRange(String header, int bodyLength) {
        Matcher m = CONTENT_RANGE.matcher(header.trim());
        if (!m.matches()) {
            throw GcpException.invalidArgument("Malformed Content-Range: " + header).withReason("invalid");
        }
        long total;
        long start;
        long end;
        try {
            total = m.group(4).equals("*") ? -1 : Long.parseLong(m.group(4));
            start = m.group(1) != null ? -1 : Long.parseLong(m.group(2));
            end = m.group(1) != null ? -1 : Long.parseLong(m.group(3));
        } catch (NumberFormatException e) {
            throw GcpException.invalidArgument("Malformed Content-Range: " + header).withReason("invalid");
        }
        if (start < 0) {
            if (bodyLength > 0) {
                throw GcpException.invalidArgument("A Content-Range of bytes */" + m.group(4)
                        + " cannot carry data").withReason("invalid");
            }
            return new ContentRange(-1, -1, total);
        }
        if (end < start || end - start + 1 != bodyLength) {
            throw GcpException.invalidArgument("Content-Range " + header + " does not match the " + bodyLength
                    + " bytes in the request").withReason("invalid");
        }
        if (total >= 0 && end >= total) {
            throw GcpException.invalidArgument("Content-Range " + header + " ends past the declared total")
                    .withReason("invalid");
        }
        return new ContentRange(start, end, total);
    }

    /**
     * Drops resumable sessions whose last write is older than {@code idleMillis}, with the bytes
     * they buffered. The sweep takes each session's monitor, so a chunk being written is never
     * evicted mid-write. Returns the number of sessions dropped.
     */
    int evictExpiredSessions(long nowMillis, long idleMillis) {
        int evicted = 0;
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Session> entry = it.next();
            Session session = entry.getValue();
            synchronized (session) {
                if (nowMillis - session.lastWriteMillis > idleMillis && sessions.remove(entry.getKey(), session)) {
                    evicted++;
                }
            }
        }
        return evicted;
    }

    int openSessions() {
        return sessions.size();
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
            List<byte[]> parts = new ArrayList<>();
            int position = delimiterAt(body, delimiter, 0);
            while (position >= 0) {
                int start = position + delimiter.length;
                if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') {
                    break; // closing delimiter
                }
                int next = delimiterAt(body, delimiter, start);
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

        /**
         * The next boundary delimiter, per RFC 2046 section 5.1.1: at the very start of the body or
         * right after a line break, and followed by a line break, the closing {@code --}, or
         * transport padding. The same bytes inside the media (a Parquet string, say) are data.
         */
        private static int delimiterAt(byte[] body, byte[] delimiter, int from) {
            int candidate = indexOf(body, delimiter, from);
            while (candidate >= 0) {
                boolean lineStart = candidate == 0 || body[candidate - 1] == '\n';
                int after = candidate + delimiter.length;
                boolean lineEnd = after >= body.length || body[after] == '\r' || body[after] == '\n'
                        || body[after] == ' ' || body[after] == '\t'
                        || (body[after] == '-' && after + 1 < body.length && body[after + 1] == '-');
                if (lineStart && lineEnd) {
                    return candidate;
                }
                candidate = indexOf(body, delimiter, candidate + 1);
            }
            return -1;
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

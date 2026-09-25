package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Floci-internal route the DuckDB SQL engine reads table rows from while staging a query.
 * Not a GCP API: it lives under the {@code /_floci-gcp} prefix and serves the stored rows as
 * newline-delimited JSON.
 */
@ApplicationScoped
@Path("/_floci-gcp/bigquery")
public class BigQueryInternalController {

    private static final Pattern SINGLE_RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private final BigQueryService service;
    private final BigQueryLoadFiles loadFiles;
    private final ObjectMapper mapper;

    @Inject
    public BigQueryInternalController(BigQueryService service, BigQueryLoadFiles loadFiles, ObjectMapper mapper) {
        this.service = service;
        this.loadFiles = loadFiles;
        this.mapper = mapper;
    }

    /**
     * DuckDB's httpfs probes a URL with HEAD before reading it. Without this method JAX-RS
     * derives HEAD from the GET below, which answers with a StreamingOutput whose entity is then
     * discarded; that combination left the probe waiting until httpfs timed out, which showed up
     * as an intermittent "IO Error: Timeout was reached error for HTTP HEAD" in the native
     * compatibility run. Answering headers only, with no content length, keeps the probe cheap
     * and tells httpfs to fetch the whole body rather than attempt ranged reads.
     */
    @HEAD
    @Path("/projects/{projectId}/datasets/{datasetId}/tables/{tableId}/rows.ndjson")
    @Produces("application/x-ndjson")
    public Response rowsHead(@PathParam("projectId") String projectId,
                             @PathParam("datasetId") String datasetId,
                             @PathParam("tableId") String tableId) {
        service.storedRows(projectId, datasetId, tableId);
        return Response.ok().type("application/x-ndjson").header("Accept-Ranges", "none").build();
    }

    /**
     * A load job's source file. Parquet is read with ranged requests, so HEAD and single
     * {@code Range: bytes=a-b} requests are answered. A Range header that is not a single valid
     * byte range is ignored and the whole file is returned, as RFC 9110 section 14.2 allows.
     */
    @GET
    @Path("/projects/{projectId}/load-files/{fileId}")
    @Produces("application/octet-stream")
    public Response loadFile(@PathParam("fileId") String fileId, @HeaderParam("Range") String range) {
        byte[] data = loadFiles.get(fileId).orElseThrow(() -> GcpException.notFound("Not found: load file " + fileId));
        Matcher m = range != null ? SINGLE_RANGE.matcher(range.trim()) : null;
        if (m == null || !m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) {
            return Response.ok(data).header("Accept-Ranges", "bytes").build();
        }
        long start;
        long end;
        try {
            if (m.group(1).isEmpty()) {
                long suffix = Long.parseLong(m.group(2));
                start = Math.max(0, data.length - suffix);
                end = data.length - 1;
            } else {
                start = Long.parseLong(m.group(1));
                end = m.group(2).isEmpty() ? data.length - 1 : Math.min(Long.parseLong(m.group(2)), data.length - 1);
            }
        } catch (NumberFormatException e) {
            return Response.ok(data).header("Accept-Ranges", "bytes").build();
        }
        if (start >= data.length || start > end) {
            return Response.status(416).header("Content-Range", "bytes */" + data.length).build();
        }
        byte[] slice = Arrays.copyOfRange(data, (int) start, (int) end + 1);
        return Response.status(206).entity(slice)
                .header("Accept-Ranges", "bytes")
                .header("Content-Range", "bytes " + start + "-" + end + "/" + data.length)
                .build();
    }

    @HEAD
    @Path("/projects/{projectId}/load-files/{fileId}")
    public Response loadFileHead(@PathParam("fileId") String fileId) {
        byte[] data = loadFiles.get(fileId).orElseThrow(() -> GcpException.notFound("Not found: load file " + fileId));
        return Response.ok().header("Content-Length", data.length).header("Accept-Ranges", "bytes")
                .type("application/octet-stream").build();
    }

    @GET
    @Path("/projects/{projectId}/datasets/{datasetId}/tables/{tableId}/rows.ndjson")
    @Produces("application/x-ndjson")
    public Response rows(@PathParam("projectId") String projectId,
                         @PathParam("datasetId") String datasetId,
                         @PathParam("tableId") String tableId) {
        List<Map<String, Object>> rows = service.storedRows(projectId, datasetId, tableId);
        StreamingOutput body = out -> {
            for (Map<String, Object> row : rows) {
                out.write(mapper.writeValueAsBytes(row));
                out.write('\n');
            }
        };
        return Response.ok(body).build();
    }
}

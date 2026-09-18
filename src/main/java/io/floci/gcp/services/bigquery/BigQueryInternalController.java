package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.util.List;
import java.util.Map;

/**
 * Floci-internal route the DuckDB SQL engine reads table rows from while staging a query.
 * Not a GCP API: it lives under the {@code /_floci-gcp} prefix and serves the stored rows as
 * newline-delimited JSON.
 */
@ApplicationScoped
@Path("/_floci-gcp/bigquery")
public class BigQueryInternalController {

    private final BigQueryService service;
    private final ObjectMapper mapper;

    @Inject
    public BigQueryInternalController(BigQueryService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
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

package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.bigquery.model.Dataset;
import io.floci.gcp.services.bigquery.model.UpdateMode;
import io.floci.gcp.services.bigquery.model.ErrorProto;
import io.floci.gcp.services.bigquery.model.Job;
import io.floci.gcp.services.bigquery.model.JobReference;
import io.floci.gcp.services.bigquery.model.JobStatus;
import io.floci.gcp.services.bigquery.model.QueryResponse;
import io.floci.gcp.services.bigquery.model.StoredJob;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BigQuery REST (Discovery) control plane, served under {@code /bigquery/v2/projects}.
 * Mirrors the GCS REST controller pattern: thin methods delegating to {@link BigQueryService}.
 */
@ApplicationScoped
@Path("/bigquery/v2/projects")
@Produces(MediaType.APPLICATION_JSON)
public class BigQueryController {

    private final BigQueryService service;

    @Inject
    public BigQueryController(BigQueryService service) {
        this.service = service;
    }

    // ── Datasets ─────────────────────────────────────────────────────────────────

    @POST
    @Path("/{projectId}/datasets")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response insertDataset(@PathParam("projectId") String projectId, Dataset body) {
        return Response.ok(service.createDataset(projectId, body != null ? body : new Dataset())).build();
    }

    @GET
    @Path("/{projectId}/datasets")
    public Response listDatasets(@PathParam("projectId") String projectId,
            @QueryParam("maxResults") @DefaultValue("0") int maxResults,
            @QueryParam("pageToken") String pageToken) {
        List<Dataset> all = service.listDatasets(projectId);
        PageToken.Page<Dataset> page = PageToken.paginate(all, maxResults, pageToken);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("kind", "bigquery#datasetList");
        if (!page.items().isEmpty()) {
            resp.put("datasets", page.items());
        }
        if (page.nextPageToken() != null) {
            resp.put("nextPageToken", page.nextPageToken());
        }
        return Response.ok(resp).build();
    }

    @GET
    @Path("/{projectId}/datasets/{datasetId}")
    public Response getDataset(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId) {
        return Response.ok(service.getDataset(projectId, datasetId)).build();
    }

    @PATCH
    @Path("/{projectId}/datasets/{datasetId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response patchDataset(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId,
            @QueryParam("updateMode") String updateMode, Dataset body) {
        return Response.ok(service.patchDataset(projectId, datasetId,
                body != null ? body : new Dataset(), UpdateMode.from(updateMode))).build();
    }

    @PUT
    @Path("/{projectId}/datasets/{datasetId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateDataset(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId,
            @QueryParam("updateMode") String updateMode, Dataset body) {
        return Response.ok(service.updateDataset(projectId, datasetId,
                body != null ? body : new Dataset(), UpdateMode.from(updateMode))).build();
    }

    @POST
    @Path("/{projectId}/datasets/{datasetId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response postDatasetMethodOverride(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId,
            @HeaderParam("X-HTTP-Method-Override") String methodOverride,
            @QueryParam("updateMode") String updateMode, Dataset body) {
        if ("PATCH".equalsIgnoreCase(methodOverride)) {
            return Response.ok(service.patchDataset(projectId, datasetId,
                    body != null ? body : new Dataset(), UpdateMode.from(updateMode))).build();
        }
        throw GcpException.invalidArgument("unsupported method override: " + methodOverride);
    }

    @DELETE
    @Path("/{projectId}/datasets/{datasetId}")
    public Response deleteDataset(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId,
            @QueryParam("deleteContents") @DefaultValue("false") boolean deleteContents) {
        service.deleteDataset(projectId, datasetId, deleteContents);
        return Response.noContent().build();
    }

    // ── Tables ───────────────────────────────────────────────────────────────────

    @POST
    @Path("/{projectId}/datasets/{datasetId}/tables")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response insertTable(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId, Table body) {
        return Response.ok(service.createTable(projectId, datasetId, body != null ? body : new Table())).build();
    }

    @GET
    @Path("/{projectId}/datasets/{datasetId}/tables")
    public Response listTables(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId,
            @QueryParam("maxResults") @DefaultValue("0") int maxResults,
            @QueryParam("pageToken") String pageToken) {
        List<Table> all = service.listTables(projectId, datasetId);
        PageToken.Page<Table> page = PageToken.paginate(all, maxResults, pageToken);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("kind", "bigquery#tableList");
        if (!page.items().isEmpty()) {
            resp.put("tables", page.items());
        }
        resp.put("totalItems", all.size());
        if (page.nextPageToken() != null) {
            resp.put("nextPageToken", page.nextPageToken());
        }
        return Response.ok(resp).build();
    }

    @GET
    @Path("/{projectId}/datasets/{datasetId}/tables/{tableId}")
    public Response getTable(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId, @PathParam("tableId") String tableId) {
        return Response.ok(service.getTable(projectId, datasetId, tableId)).build();
    }

    @PATCH
    @Path("/{projectId}/datasets/{datasetId}/tables/{tableId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response patchTable(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId, @PathParam("tableId") String tableId, Table body) {
        return Response.ok(service.patchTable(projectId, datasetId, tableId, body != null ? body : new Table())).build();
    }

    @PUT
    @Path("/{projectId}/datasets/{datasetId}/tables/{tableId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateTable(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId, @PathParam("tableId") String tableId, Table body) {
        return Response.ok(service.updateTable(projectId, datasetId, tableId, body != null ? body : new Table())).build();
    }

    @POST
    @Path("/{projectId}/datasets/{datasetId}/tables/{tableId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response postTableMethodOverride(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId, @PathParam("tableId") String tableId,
            @HeaderParam("X-HTTP-Method-Override") String methodOverride, Table body) {
        if ("PATCH".equalsIgnoreCase(methodOverride)) {
            return Response.ok(service.patchTable(projectId, datasetId, tableId, body != null ? body : new Table())).build();
        }
        throw GcpException.invalidArgument("unsupported method override: " + methodOverride);
    }

    @DELETE
    @Path("/{projectId}/datasets/{datasetId}/tables/{tableId}")
    public Response deleteTable(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId, @PathParam("tableId") String tableId) {
        service.deleteTable(projectId, datasetId, tableId);
        return Response.noContent().build();
    }

    // ── Table data ───────────────────────────────────────────────────────────────

    @POST
    @Path("/{projectId}/datasets/{datasetId}/tables/{tableId}/insertAll")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response insertAll(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId, @PathParam("tableId") String tableId,
            Map<String, Object> body) {
        boolean skipInvalidRows = boolValue(body, "skipInvalidRows");
        boolean ignoreUnknownValues = boolValue(body, "ignoreUnknownValues");
        List<Map<String, Object>> insertErrors = service.insertAll(projectId, datasetId, tableId,
                extractInsertRows(body), skipInvalidRows, ignoreUnknownValues);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("kind", "bigquery#tableDataInsertAllResponse");
        if (!insertErrors.isEmpty()) {
            resp.put("insertErrors", insertErrors);
        }
        return Response.ok(resp).build();
    }

    @GET
    @Path("/{projectId}/datasets/{datasetId}/tables/{tableId}/data")
    public Response listTableData(@PathParam("projectId") String projectId,
            @PathParam("datasetId") String datasetId, @PathParam("tableId") String tableId,
            @QueryParam("maxResults") Long maxResults,
            @QueryParam("pageToken") String pageToken,
            @QueryParam("startIndex") Long startIndex,
            @QueryParam("formatOptions.useInt64Timestamp") Boolean useInt64Timestamp,
            @QueryParam("formatOptions.timestampOutputFormat") String timestampOutputFormat) {
        BigQueryService.TableData data = service.listTableData(projectId, datasetId, tableId,
                RowCodec.TimestampFormat.of(useInt64Timestamp, timestampOutputFormat));
        PageToken.Page<TableRow> page = pageRows(data.rows(), maxResults, pageToken, startIndex);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("kind", "bigquery#tableDataList");
        resp.put("totalRows", String.valueOf(data.rows().size()));
        if (!page.items().isEmpty()) {
            resp.put("rows", page.items());
        }
        if (page.nextPageToken() != null) {
            resp.put("pageToken", page.nextPageToken());
        }
        return Response.ok(resp).build();
    }

    // ── Queries / Jobs ─────────────────────────────────────────────────────────────

    @POST
    @Path("/{projectId}/queries")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response query(@PathParam("projectId") String projectId, Map<String, Object> body) {
        Map<String, Object> request = body != null ? body : Map.of();
        String location = (String) request.get("location");
        BigQueryService.QueryOptions options = queryOptions(request, Boolean.TRUE.equals(request.get("dryRun")));
        StoredJob job = service.query(projectId, location, null, options);

        Map<String, Object> formatOptions = asMap(request.get("formatOptions"));
        RowCodec.TimestampFormat format = formatOptions != null
                ? RowCodec.TimestampFormat.of(formatOptions.get("useInt64Timestamp") instanceof Boolean b ? b : null,
                        (String) formatOptions.get("timestampOutputFormat"))
                : RowCodec.TimestampFormat.FLOAT64;
        Long maxResults = request.get("maxResults") instanceof Number n ? n.longValue() : null;
        QueryResponse resp = job.isDryRun() ? buildDryRunResponse(job)
                : buildQueryResponse(job, maxResults, null, null, format);
        resp.setKind("bigquery#queryResponse");
        return Response.ok(resp).build();
    }

    @GET
    @Path("/{projectId}/queries/{jobId}")
    public Response getQueryResults(@PathParam("projectId") String projectId,
            @PathParam("jobId") String jobId,
            @QueryParam("maxResults") Long maxResults,
            @QueryParam("pageToken") String pageToken,
            @QueryParam("startIndex") Long startIndex,
            @QueryParam("formatOptions.useInt64Timestamp") Boolean useInt64Timestamp,
            @QueryParam("formatOptions.timestampOutputFormat") String timestampOutputFormat) {
        StoredJob job = service.getJob(projectId, jobId);
        QueryResponse resp = buildQueryResponse(job, maxResults, pageToken, startIndex,
                RowCodec.TimestampFormat.of(useInt64Timestamp, timestampOutputFormat));
        resp.setKind("bigquery#getQueryResultsResponse");
        return Response.ok(resp).build();
    }

    @POST
    @Path("/{projectId}/jobs")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response insertJob(@PathParam("projectId") String projectId, Job body) {
        Map<String, Object> configuration = body != null ? body.getConfiguration() : null;
        Map<String, Object> queryConfig = configuration != null ? asMap(configuration.get("query")) : null;
        if (queryConfig == null || queryConfig.get("query") == null) {
            throw GcpException.invalidArgument(
                    "Only QUERY jobs are supported by the floci BigQuery emulator");
        }
        String location = body.getJobReference() != null ? body.getJobReference().getLocation() : null;
        String jobId = body.getJobReference() != null ? body.getJobReference().getJobId() : null;
        boolean dryRun = Boolean.TRUE.equals(configuration.get("dryRun"));
        BigQueryService.QueryOptions options = queryOptions(queryConfig, dryRun);

        StoredJob job;
        try {
            job = service.query(projectId, location, jobId, options);
        } catch (GcpException e) {
            // A dry run reports an invalid query as an HTTP error; a real job records it
            // in its status instead.
            if (e.getHttpStatus() == 409 || dryRun) {
                throw e;
            }
            job = service.failedJob(projectId, location, jobId, options.sql(), e);
        }
        return Response.ok(buildJob(job)).build();
    }

    @GET
    @Path("/{projectId}/jobs/{jobId}")
    public Response getJob(@PathParam("projectId") String projectId, @PathParam("jobId") String jobId) {
        return Response.ok(buildJob(service.getJob(projectId, jobId))).build();
    }

    @GET
    @Path("/{projectId}/jobs")
    public Response listJobs(@PathParam("projectId") String projectId,
            @QueryParam("maxResults") @DefaultValue("0") int maxResults,
            @QueryParam("pageToken") String pageToken) {
        List<StoredJob> all = service.listJobs(projectId);
        PageToken.Page<StoredJob> page = PageToken.paginate(all, maxResults, pageToken);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("kind", "bigquery#jobList");
        if (!page.items().isEmpty()) {
            resp.put("jobs", page.items().stream().map(BigQueryController::buildJob).toList());
        }
        if (page.nextPageToken() != null) {
            resp.put("nextPageToken", page.nextPageToken());
        }
        return Response.ok(resp).build();
    }

    @POST
    @Path("/{projectId}/jobs/{jobId}/cancel")
    public Response cancelJob(@PathParam("projectId") String projectId, @PathParam("jobId") String jobId) {
        // Jobs complete synchronously, so cancel just echoes the (already DONE) job.
        StoredJob job = service.getJob(projectId, jobId);
        return Response.ok(Map.of(
                "kind", "bigquery#jobCancelResponse",
                "job", buildJob(job))).build();
    }

    @DELETE
    @Path("/{projectId}/jobs/{jobId}/delete")
    public Response deleteJob(@PathParam("projectId") String projectId, @PathParam("jobId") String jobId) {
        service.deleteJob(projectId, jobId);
        return Response.noContent().build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static BigQueryService.QueryOptions queryOptions(Map<String, Object> config, boolean dryRun) {
        Map<String, Object> defaultDataset = asMap(config.get("defaultDataset"));
        String defaultDatasetId = defaultDataset != null ? (String) defaultDataset.get("datasetId") : null;
        List<Map<String, Object>> parameters = config.get("queryParameters") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        Boolean useLegacySql = config.get("useLegacySql") instanceof Boolean b ? b : null;
        return new BigQueryService.QueryOptions((String) config.get("query"), defaultDatasetId, parameters,
                (String) config.get("parameterMode"), dryRun, useLegacySql);
    }

    private static QueryResponse buildDryRunResponse(StoredJob job) {
        QueryResponse resp = new QueryResponse();
        resp.setJobReference(new JobReference(job.getProjectId(), null, job.getLocation()));
        resp.setJobComplete(true);
        resp.setCacheHit(false);
        resp.setSchema(job.getSchema());
        resp.setTotalBytesProcessed(job.getTotalBytesProcessed());
        return resp;
    }

    private QueryResponse buildQueryResponse(StoredJob job, Long maxResults, String pageToken,
            Long startIndex, RowCodec.TimestampFormat format) {
        QueryResponse resp = new QueryResponse();
        resp.setJobReference(new JobReference(job.getProjectId(), job.getJobId(), job.getLocation()));
        resp.setJobComplete(true);
        if (job.failed()) {
            // Failed jobs surface as a completed 200 response with embedded errors; the
            // Java SDK's Job.getQueryResults() then reads the failure from job status.
            resp.setErrors(List.of(new ErrorProto(job.getErrorReason(), null, job.getErrorMessage())));
            return resp;
        }
        resp.setCacheHit(false);
        resp.setTotalBytesProcessed(job.getTotalBytesProcessed() != null ? job.getTotalBytesProcessed() : "0");

        BigQueryService.TableData data = service.queryResults(job.getProjectId(), job, format);
        resp.setSchema(data.schema());
        resp.setTotalRows(String.valueOf(data.rows().size()));
        PageToken.Page<TableRow> page = pageRows(data.rows(), maxResults, pageToken, startIndex);
        if (!page.items().isEmpty()) {
            resp.setRows(page.items());
        }
        resp.setPageToken(page.nextPageToken());
        return resp;
    }

    private static Job buildJob(StoredJob sj) {
        Job job = new Job();
        job.setId(sj.getProjectId() + ":" + sj.getLocation() + "." + sj.getJobId());
        job.setJobReference(new JobReference(sj.getProjectId(), sj.getJobId(), sj.getLocation()));

        Map<String, Object> queryConfig = new LinkedHashMap<>();
        queryConfig.put("query", sj.getQuery());
        queryConfig.put("useLegacySql", false);
        if (sj.getDestinationTableId() != null) {
            queryConfig.put("destinationTable", Map.of(
                    "projectId", sj.getProjectId(),
                    "datasetId", sj.getDestinationDatasetId(),
                    "tableId", sj.getDestinationTableId()));
        }
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("jobType", "QUERY");
        configuration.put("query", queryConfig);
        if (sj.isDryRun()) {
            configuration.put("dryRun", true);
        }
        job.setConfiguration(configuration);

        JobStatus status = new JobStatus();
        status.setState(sj.getState());
        if (sj.failed()) {
            ErrorProto error = new ErrorProto(sj.getErrorReason(), "query", sj.getErrorMessage());
            status.setErrorResult(error);
            status.setErrors(List.of(error));
        }
        job.setStatus(status);

        if (sj.getCreationTime() != null) {
            Map<String, Object> statistics = new LinkedHashMap<>();
            statistics.put("creationTime", sj.getCreationTime());
            statistics.put("startTime", sj.getCreationTime());
            statistics.put("endTime", sj.getCreationTime());
            Map<String, Object> queryStatistics = new LinkedHashMap<>();
            String bytes = sj.getTotalBytesProcessed() != null ? sj.getTotalBytesProcessed() : "0";
            queryStatistics.put("totalBytesProcessed", bytes);
            statistics.put("totalBytesProcessed", bytes);
            queryStatistics.put("statementType", sj.getStatementType() != null ? sj.getStatementType() : "SELECT");
            if (sj.isDryRun() && sj.getSchema() != null) {
                queryStatistics.put("schema", sj.getSchema());
            }
            statistics.put("query", queryStatistics);
            job.setStatistics(statistics);
        }
        return job;
    }

    /**
     * Row paging for tabledata.list / query results: {@code maxResults} is nullable —
     * {@code 0} means zero rows (the SDK's {@code Job.waitFor} sends 0), absent means all.
     * A non-empty {@code pageToken} wins over {@code startIndex}: the SDK's auto-paging
     * sends the token together with a default {@code startIndex=0}.
     */
    private static PageToken.Page<TableRow> pageRows(List<TableRow> all, Long maxResults,
            String pageToken, Long startIndex) {
        if (maxResults != null && maxResults == 0) {
            return new PageToken.Page<>(List.of(), null);
        }
        int offset = pageToken != null && !pageToken.isBlank()
                ? PageToken.decode(pageToken)
                : startIndex != null ? (int) Math.min(startIndex, Integer.MAX_VALUE) : 0;
        if (offset < 0 || offset >= all.size()) {
            return new PageToken.Page<>(List.of(), null);
        }
        int size = maxResults != null && maxResults > 0
                ? (int) Math.min(maxResults, Integer.MAX_VALUE) : all.size();
        int end = Math.min(offset + size, all.size());
        List<TableRow> items = List.copyOf(all.subList(offset, end));
        String next = end < all.size() ? PageToken.encode(end) : null;
        return new PageToken.Page<>(items, next);
    }

    @SuppressWarnings("unchecked")
    private static List<BigQueryService.InsertRow> extractInsertRows(Map<String, Object> body) {
        List<BigQueryService.InsertRow> result = new ArrayList<>();
        if (body == null) {
            return result;
        }
        Object rows = body.get("rows");
        if (!(rows instanceof List<?> list)) {
            return result;
        }
        for (int i = 0; i < list.size(); i++) {
            Object json = list.get(i) instanceof Map<?, ?> rowMap
                    ? ((Map<String, Object>) rowMap).get("json") : null;
            if (!(json instanceof Map)) {
                throw GcpException.invalidArgument(
                        "Insert entry at index " + i + " is missing the required json row payload")
                        .withReason("invalid");
            }
            result.add(new BigQueryService.InsertRow(i, (Map<String, Object>) json));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static boolean boolValue(Map<String, Object> body, String key) {
        return body != null && Boolean.TRUE.equals(body.get(key));
    }
}

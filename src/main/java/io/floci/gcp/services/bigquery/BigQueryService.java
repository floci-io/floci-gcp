package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.bigquery.model.Dataset;
import io.floci.gcp.services.bigquery.model.DatasetReference;
import io.floci.gcp.services.bigquery.model.ErrorProto;
import io.floci.gcp.services.bigquery.model.StoredJob;
import io.floci.gcp.services.bigquery.model.StoredTableData;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.floci.gcp.services.bigquery.model.TableReference;
import io.floci.gcp.services.bigquery.model.TableRow;
import io.floci.gcp.services.bigquery.model.TableSchema;
import io.floci.gcp.services.bigquery.model.UpdateMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BigQuery: datasets/tables metadata, streaming inserts ({@code insertAll}), row reads and
 * query jobs. Queries run on a {@link BigQuerySqlEngine}: GoogleSQL on the DuckDB sidecar, or
 * the built-in SQL subset in mock mode. Storage is project-namespaced via
 * {@link StorageFactory#create}.
 */
@ApplicationScoped
public class BigQueryService {

    private static final Logger LOG = Logger.getLogger(BigQueryService.class);

    private final StorageBackend<String, Dataset> datasetStore;
    private final StorageBackend<String, Table> tableStore;
    private final StorageBackend<String, StoredTableData> dataStore;
    private final StorageBackend<String, StoredJob> jobStore;

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final BigQuerySqlEngine engine;

    @Inject
    public BigQueryService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, DuckSqlEngine duckEngine) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.engine = config.services().bigquery().mock() ? new InMemorySqlEngine() : duckEngine;
        this.datasetStore = storageFactory.create("bigquery-datasets", "bigquery-datasets.json",
                new TypeReference<Map<String, Dataset>>() {});
        this.tableStore = storageFactory.create("bigquery-tables", "bigquery-tables.json",
                new TypeReference<Map<String, Table>>() {});
        this.dataStore = storageFactory.create("bigquery-tabledata", "bigquery-tabledata.json",
                new TypeReference<Map<String, StoredTableData>>() {});
        this.jobStore = storageFactory.create("bigquery-jobs", "bigquery-jobs.json",
                new TypeReference<Map<String, StoredJob>>() {});
    }

    BigQueryService(StorageBackend<String, Dataset> datasetStore,
            StorageBackend<String, Table> tableStore,
            StorageBackend<String, StoredTableData> dataStore,
            StorageBackend<String, StoredJob> jobStore) {
        this(datasetStore, tableStore, dataStore, jobStore, new InMemorySqlEngine());
    }

    BigQueryService(StorageBackend<String, Dataset> datasetStore,
            StorageBackend<String, Table> tableStore,
            StorageBackend<String, StoredTableData> dataStore,
            StorageBackend<String, StoredJob> jobStore,
            BigQuerySqlEngine engine) {
        this.engine = engine;
        this.datasetStore = datasetStore;
        this.tableStore = tableStore;
        this.dataStore = dataStore;
        this.jobStore = jobStore;
        this.serviceRegistry = null;
        this.config = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("bigquery")
                .enabled(config.services().bigquery().enabled())
                .storageKey("bigquery")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(BigQueryController.class, BigQueryInternalController.class)
                .build());
    }

    // ── Datasets ─────────────────────────────────────────────────────────────────

    public Dataset createDataset(String projectId, Dataset body) {
        String datasetId = body.getDatasetReference() != null
                ? body.getDatasetReference().getDatasetId() : null;
        if (datasetId == null || datasetId.isBlank()) {
            throw GcpException.invalidArgument("datasetReference.datasetId is required");
        }
        if (datasetStore.get(datasetId).isPresent()) {
            throw GcpException.alreadyExists("Already Exists: Dataset " + projectId + ":" + datasetId)
                    .withReason("duplicate");
        }
        String now = nowMillis();
        Map<String, Object> extra = BigQueryMetadata.writable(body.getExtra(), BigQueryMetadata.DATASET_FIELDS);
        BigQueryMetadata.validateDataset(extra);
        body.getExtra().clear();
        body.getExtra().putAll(extra);
        BigQueryMetadata.fillDatasetOutputs(body);
        body.setDatasetReference(new DatasetReference(projectId, datasetId));
        body.setId(projectId + ":" + datasetId);
        body.setSelfLink(selfLink(projectId, datasetId, null));
        body.setEtag(etag());
        body.setCreationTime(now);
        body.setLastModifiedTime(now);
        datasetStore.put(datasetId, body);
        LOG.debugf("createDataset project=%s dataset=%s", projectId, datasetId);
        return body;
    }

    public Dataset getDataset(String projectId, String datasetId) {
        return datasetStore.get(datasetId)
                .orElseThrow(() -> GcpException.notFound("Not found: Dataset " + projectId + ":" + datasetId));
    }

    public List<Dataset> listDatasets(String projectId) {
        return datasetStore.scan(k -> true);
    }

    public Dataset patchDataset(String projectId, String datasetId, Dataset patch) {
        return patchDataset(projectId, datasetId, patch, UpdateMode.UPDATE_FULL);
    }

    /** datasets.patch, honouring {@code updateMode}. */
    public Dataset patchDataset(String projectId, String datasetId, Dataset patch, UpdateMode mode) {
        Dataset existing = getDataset(projectId, datasetId);
        if (mode.touchesMetadata()) {
            Map<String, Object> candidate = new LinkedHashMap<>(existing.getExtra());
            BigQueryMetadata.patch(candidate, patch.getExtra(), BigQueryMetadata.DATASET_FIELDS);
            clearZeroDefaultExpiration(candidate);
            BigQueryMetadata.validateDataset(candidate);
            if (patch.getFriendlyName() != null) {
                existing.setFriendlyName(patch.getFriendlyName());
            }
            if (patch.getDescription() != null) {
                existing.setDescription(patch.getDescription());
            }
            if (patch.getLabels() != null) {
                existing.setLabels(patch.getLabels());
            }
            replaceExtra(existing.getExtra(), candidate);
            BigQueryMetadata.fillDatasetOutputs(existing);
        }
        if (mode.touchesAcl() && patch.getAccess() != null) {
            existing.setAccess(patch.getAccess());
        }
        existing.setLastModifiedTime(nowMillis());
        existing.setEtag(etag());
        datasetStore.put(datasetId, existing);
        return existing;
    }

    /** "To clear an existing default expiration with a PATCH request, set to 0." */
    private static void clearZeroDefaultExpiration(Map<String, Object> extra) {
        Object value = extra.get("defaultTableExpirationMs");
        if (value != null && "0".equals(String.valueOf(value).trim())) {
            extra.remove("defaultTableExpirationMs");
        }
    }

    /**
     * Writable metadata is validated on a detached copy and only then committed, so a request that
     * fails validation leaves the stored resource untouched: the storage backends hand out the live
     * object, so mutating it before validating would publish a rejected update.
     */
    private static void replaceExtra(Map<String, Object> target, Map<String, Object> candidate) {
        target.clear();
        target.putAll(candidate);
    }

    /** datasets.update (PUT): full replacement — mutable fields absent from the body are cleared. */
    public Dataset updateDataset(String projectId, String datasetId, Dataset update) {
        return updateDataset(projectId, datasetId, update, UpdateMode.UPDATE_FULL);
    }

    /**
     * datasets.update, honouring {@code updateMode}.
     *
     * <p>PUT replaces, so a field absent from the body is a cleared field. That
     * is only safe for the half of the resource the caller addressed:
     * UPDATE_METADATA leaves the ACL exactly as it was, UPDATE_ACL leaves the
     * metadata alone, and UPDATE_FULL (the default) replaces both.
     */
    public Dataset updateDataset(
            String projectId, String datasetId, Dataset update, UpdateMode mode) {
        Dataset existing = getDataset(projectId, datasetId);
        if (mode.touchesMetadata()) {
            Map<String, Object> candidate = new LinkedHashMap<>(existing.getExtra());
            BigQueryMetadata.replace(candidate, update.getExtra(), BigQueryMetadata.DATASET_FIELDS);
            BigQueryMetadata.validateDataset(candidate);
            existing.setFriendlyName(update.getFriendlyName());
            existing.setDescription(update.getDescription());
            existing.setLabels(update.getLabels());
            replaceExtra(existing.getExtra(), candidate);
            BigQueryMetadata.fillDatasetOutputs(existing);
        }
        if (mode.touchesAcl()) {
            existing.setAccess(update.getAccess());
        }
        existing.setLastModifiedTime(nowMillis());
        existing.setEtag(etag());
        datasetStore.put(datasetId, existing);
        return existing;
    }

    public void deleteDataset(String projectId, String datasetId, boolean deleteContents) {
        getDataset(projectId, datasetId);
        List<Table> tables = listTables(projectId, datasetId);
        if (!tables.isEmpty() && !deleteContents) {
            throw GcpException.invalidArgument(
                    "Dataset " + projectId + ":" + datasetId + " is still in use")
                    .withReason("resourceInUse");
        }
        for (Table t : tables) {
            String tableId = t.getTableReference().getTableId();
            tableStore.delete(tableKey(datasetId, tableId));
            dataStore.delete(tableKey(datasetId, tableId));
        }
        datasetStore.delete(datasetId);
        LOG.debugf("deleteDataset project=%s dataset=%s deleteContents=%s", projectId, datasetId, deleteContents);
    }

    // ── Tables ───────────────────────────────────────────────────────────────────

    public Table createTable(String projectId, String datasetId, Table body) {
        Dataset dataset = getDataset(projectId, datasetId);
        String tableId = body.getTableReference() != null
                ? body.getTableReference().getTableId() : null;
        if (tableId == null || tableId.isBlank()) {
            throw GcpException.invalidArgument("tableReference.tableId is required");
        }
        String key = tableKey(datasetId, tableId);
        if (tableStore.get(key).isPresent()) {
            throw GcpException.alreadyExists(
                    "Already Exists: Table " + projectId + ":" + datasetId + "." + tableId)
                    .withReason("duplicate");
        }
        String now = nowMillis();
        body.setSchema(RowCodec.normalizeSchema(body.getSchema()));
        Map<String, Object> extra = BigQueryMetadata.writable(body.getExtra(), BigQueryMetadata.TABLE_FIELDS);
        body.getExtra().clear();
        body.getExtra().putAll(extra);
        BigQueryMetadata.validateTable(extra, schemaFields(body));
        BigQueryMetadata.applyDatasetDefaults(body, dataset, Long.parseLong(now));
        if (body.getType() == null && extra.containsKey("view")) {
            body.setType("VIEW");
        } else if (body.getType() == null && extra.containsKey("materializedView")) {
            body.setType("MATERIALIZED_VIEW");
        } else if (body.getType() == null && extra.containsKey("externalDataConfiguration")) {
            body.setType("EXTERNAL");
        }
        body.setLocation(dataset.getLocation());
        // numRows is maintained on insert, but nothing tracks a byte size, and a table with rows
        // reporting numBytes "0" is worse than one that omits the field. numLongTermBytes stays
        // "0" because nothing here ever ages into long-term storage.
        body.setNumLongTermBytes("0");
        body.setSelfLink(selfLink(projectId, datasetId, tableId));
        body.setTableReference(new TableReference(projectId, datasetId, tableId));
        body.setId(projectId + ":" + datasetId + "." + tableId);
        body.setType(body.getType() != null ? body.getType() : "TABLE");
        body.setEtag(etag());
        body.setCreationTime(now);
        body.setLastModifiedTime(now);
        body.setNumRows("0");
        tableStore.put(key, body);
        LOG.debugf("createTable project=%s dataset=%s table=%s", projectId, datasetId, tableId);
        return body;
    }

    public Table getTable(String projectId, String datasetId, String tableId) {
        return tableStore.get(tableKey(datasetId, tableId))
                .filter(table -> !expire(datasetId, tableId, table))
                .orElseThrow(() -> GcpException.notFound(
                        "Not found: Table " + projectId + ":" + datasetId + "." + tableId));
    }

    public List<Table> listTables(String projectId, String datasetId) {
        String prefix = datasetId + "/";
        return tableStore.scan(k -> k.startsWith(prefix)).stream()
                .filter(table -> !expire(datasetId, table.getTableReference().getTableId(), table))
                .toList();
    }

    /** "Expired tables will be deleted": removes the table (and its rows) once past expirationTime. */
    private boolean expire(String datasetId, String tableId, Table table) {
        if (!BigQueryMetadata.expired(table, System.currentTimeMillis())) {
            return false;
        }
        tableStore.delete(tableKey(datasetId, tableId));
        dataStore.delete(tableKey(datasetId, tableId));
        LOG.debugf("table expired dataset=%s table=%s", datasetId, tableId);
        return true;
    }

    private static List<TableFieldSchema> schemaFields(Table table) {
        return schemaFields(table.getSchema());
    }

    private static List<TableFieldSchema> schemaFields(TableSchema schema) {
        return schema != null && schema.getFields() != null ? schema.getFields() : List.of();
    }

    private String selfLink(String projectId, String datasetId, String tableId) {
        String base = config != null ? config.effectiveBaseUrl() : "";
        return base + "/bigquery/v2/projects/" + projectId + "/datasets/" + datasetId
                + (tableId != null ? "/tables/" + tableId : "");
    }

    public Table patchTable(String projectId, String datasetId, String tableId, Table patch) {
        Table existing = getTable(projectId, datasetId, tableId);
        TableSchema schema = patch.getSchema() != null
                ? RowCodec.normalizeSchema(patch.getSchema()) : existing.getSchema();
        Map<String, Object> candidate = new LinkedHashMap<>(existing.getExtra());
        BigQueryMetadata.patch(candidate, patch.getExtra(), BigQueryMetadata.TABLE_FIELDS);
        BigQueryMetadata.validateTable(candidate, schemaFields(schema));
        if (patch.getFriendlyName() != null) {
            existing.setFriendlyName(patch.getFriendlyName());
        }
        if (patch.getDescription() != null) {
            existing.setDescription(patch.getDescription());
        }
        if (patch.getSchema() != null) {
            existing.setSchema(schema);
        }
        if (patch.getLabels() != null) {
            existing.setLabels(patch.getLabels());
        }
        replaceExtra(existing.getExtra(), candidate);
        existing.setLastModifiedTime(nowMillis());
        existing.setEtag(etag());
        tableStore.put(tableKey(datasetId, tableId), existing);
        return existing;
    }

    /** tables.update (PUT): full replacement — mutable fields absent from the body are cleared. */
    public Table updateTable(String projectId, String datasetId, String tableId, Table update) {
        Table existing = getTable(projectId, datasetId, tableId);
        TableSchema schema = update.getSchema() != null ? RowCodec.normalizeSchema(update.getSchema()) : null;
        Map<String, Object> candidate = new LinkedHashMap<>(existing.getExtra());
        BigQueryMetadata.replace(candidate, update.getExtra(), BigQueryMetadata.TABLE_FIELDS);
        BigQueryMetadata.validateTable(candidate, schemaFields(schema));
        existing.setFriendlyName(update.getFriendlyName());
        existing.setDescription(update.getDescription());
        existing.setSchema(schema);
        existing.setLabels(update.getLabels());
        replaceExtra(existing.getExtra(), candidate);
        existing.setLastModifiedTime(nowMillis());
        existing.setEtag(etag());
        tableStore.put(tableKey(datasetId, tableId), existing);
        return existing;
    }

    public void deleteTable(String projectId, String datasetId, String tableId) {
        getTable(projectId, datasetId, tableId);
        tableStore.delete(tableKey(datasetId, tableId));
        dataStore.delete(tableKey(datasetId, tableId));
        LOG.debugf("deleteTable project=%s dataset=%s table=%s", projectId, datasetId, tableId);
    }

    // ── Table data ───────────────────────────────────────────────────────────────

    /** One {@code insertAll} row: the JSON payload plus its request index. */
    public record InsertRow(int index, Map<String, Object> json) {}

    /**
     * Validates and appends rows from {@code insertAll}. Returns the per-row insert
     * errors (empty = all accepted); the HTTP response is 200 either way.
     */
    public List<Map<String, Object>> insertAll(String projectId, String datasetId, String tableId,
            List<InsertRow> rows, boolean skipInvalidRows, boolean ignoreUnknownValues) {
        Table table = getTable(projectId, datasetId, tableId);
        String key = tableKey(datasetId, tableId);

        List<Map<String, Object>> insertErrors = new ArrayList<>();
        List<Map<String, Object>> accepted = new ArrayList<>();
        List<Integer> acceptedIndexes = new ArrayList<>();
        for (InsertRow row : rows) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            List<ErrorProto> rowErrors = RowCodec.normalizeRow(
                    table.getSchema(), row.json(), ignoreUnknownValues, normalized);
            if (rowErrors.isEmpty()) {
                accepted.add(normalized);
                acceptedIndexes.add(row.index());
            } else {
                insertErrors.add(Map.of("index", row.index(), "errors", rowErrors));
            }
        }

        if (!insertErrors.isEmpty() && !skipInvalidRows) {
            // Real BigQuery inserts nothing and marks the valid rows as "stopped".
            for (Integer index : acceptedIndexes) {
                insertErrors.add(Map.of("index", index,
                        "errors", List.of(new ErrorProto("stopped", null, null))));
            }
            return insertErrors;
        }

        if (!accepted.isEmpty()) {
            StoredTableData data = dataStore.get(key).orElseGet(StoredTableData::new);
            data.getRows().addAll(accepted);
            dataStore.put(key, data);
            table.setNumRows(String.valueOf(data.getRows().size()));
            table.setLastModifiedTime(nowMillis());
            tableStore.put(key, table);
        }
        LOG.debugf("insertAll project=%s dataset=%s table=%s accepted=%d rejected=%d",
                projectId, datasetId, tableId, accepted.size(), insertErrors.size());
        return insertErrors;
    }

    /** Encoded rows plus totals for {@code tabledata.list}. */
    public record TableData(TableSchema schema, List<TableRow> rows) {}

    public TableData listTableData(String projectId, String datasetId, String tableId) {
        return listTableData(projectId, datasetId, tableId, RowCodec.TimestampFormat.FLOAT64);
    }

    public TableData listTableData(String projectId, String datasetId, String tableId,
            RowCodec.TimestampFormat format) {
        Table table = getTable(projectId, datasetId, tableId);
        StoredTableData data = dataStore.get(tableKey(datasetId, tableId)).orElseGet(StoredTableData::new);
        return new TableData(table.getSchema(), RowCodec.encodeRows(table.getSchema(), data.getRows(), format));
    }

    /** Stored (normalized) rows of a table, as the SQL engine stages them. */
    public List<Map<String, Object>> storedRows(String projectId, String datasetId, String tableId) {
        getTable(projectId, datasetId, tableId);
        return dataStore.get(tableKey(datasetId, tableId)).orElseGet(StoredTableData::new).getRows();
    }

    // ── Query ────────────────────────────────────────────────────────────────────

    /** Hidden dataset holding materialized query results; never listed, has no dataset record. */
    static final String ANON_DATASET = "_floci_anon";

    /** Request-level options of {@code jobs.query} / {@code jobs.insert} that shape execution. */
    public record QueryOptions(String sql, String defaultDatasetId, List<Map<String, Object>> queryParameters,
                               String parameterMode, boolean dryRun, Boolean useLegacySql) {

        public static QueryOptions of(String sql, String defaultDatasetId) {
            return new QueryOptions(sql, defaultDatasetId, List.of(), null, false, null);
        }
    }

    public StoredJob query(String projectId, String location, String jobId, String sql, String defaultDatasetId) {
        return query(projectId, location, jobId, QueryOptions.of(sql, defaultDatasetId));
    }

    /**
     * Executes the query and materializes its results into a hidden anonymous table
     * referenced as the job's {@code configuration.query.destinationTable} (the SDK's
     * {@code Job.getQueryResults()} reads rows from there via {@code tabledata.list}).
     * SQL errors propagate as {@link GcpException}: {@code jobs.query} maps them to HTTP
     * errors while {@code jobs.insert} converts them into a DONE job with an error status.
     * Dry runs return an unpersisted job carrying the result schema.
     */
    public StoredJob query(String projectId, String location, String jobId, QueryOptions options) {
        // Reserve the ID before any parsing/lookup so a duplicate explicit jobId always
        // 409s, even when the query would otherwise fail, matching failedJob's path.
        String resolvedJobId = options.dryRun() ? null : reserveJobId(projectId, jobId);
        if (Boolean.TRUE.equals(options.useLegacySql())) {
            throw QueryEngine.invalidQuery("Legacy SQL is not supported by the floci BigQuery emulator;"
                    + " set useLegacySql to false to run GoogleSQL.");
        }

        BigQuerySqlEngine.Result result = engine.execute(new BigQuerySqlEngine.Request(projectId, options.sql(),
                options.defaultDatasetId(), options.queryParameters(), options.parameterMode(), options.dryRun()),
                tables(projectId));

        StoredJob job = new StoredJob();
        job.setJobId(resolvedJobId);
        job.setProjectId(projectId);
        job.setLocation(location != null && !location.isBlank() ? location : "US");
        job.setQuery(options.sql());
        job.setState("DONE");
        job.setCreationTime(nowMillis());
        job.setStatementType(result.statementType());
        job.setTotalBytesProcessed(String.valueOf(result.totalBytesProcessed()));
        if (options.dryRun()) {
            job.setDryRun(true);
            job.setSchema(result.schema());
            return job;
        }
        job.setTotalRows(result.rows().size());
        job.setDestinationDatasetId(ANON_DATASET);
        job.setDestinationTableId("anon_" + job.getJobId());

        materializeResult(projectId, job, result.schema(), result.rows());
        jobStore.put(job.getJobId(), job);
        return job;
    }

    private BigQuerySqlEngine.Tables tables(String projectId) {
        return new BigQuerySqlEngine.Tables() {
            @Override
            public Table table(String datasetId, String tableId) {
                return getTable(projectId, datasetId, tableId);
            }

            @Override
            public List<Map<String, Object>> rows(String datasetId, String tableId) {
                return dataStore.get(tableKey(datasetId, tableId)).orElseGet(StoredTableData::new).getRows();
            }
        };
    }

    /** Persists a failed query job (used by {@code jobs.insert}, which must not throw for SQL errors). */
    public StoredJob failedJob(String projectId, String location, String jobId,
            String sql, GcpException cause) {
        String resolvedJobId = reserveJobId(projectId, jobId);
        StoredJob job = new StoredJob();
        job.setJobId(resolvedJobId);
        job.setProjectId(projectId);
        job.setLocation(location != null && !location.isBlank() ? location : "US");
        job.setQuery(sql);
        job.setState("DONE");
        job.setCreationTime(nowMillis());
        job.setErrorReason(cause.getReason() != null ? cause.getReason() : "invalidQuery");
        job.setErrorMessage(cause.getMessage());
        jobStore.put(job.getJobId(), job);
        return job;
    }

    /** Resolves a job ID (generating one when blank) and rejects an already-used explicit ID with 409. */
    private String reserveJobId(String projectId, String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return "job_" + UUID.randomUUID().toString().replace("-", "");
        }
        if (jobStore.get(jobId).isPresent()) {
            throw GcpException.alreadyExists("Already Exists: Job " + projectId + ":" + jobId)
                    .withReason("duplicate");
        }
        return jobId;
    }

    private void materializeResult(String projectId, StoredJob job, TableSchema schema,
            List<Map<String, Object>> resultRows) {
        String key = tableKey(job.getDestinationDatasetId(), job.getDestinationTableId());
        Table anon = new Table();
        anon.setTableReference(new TableReference(projectId,
                job.getDestinationDatasetId(), job.getDestinationTableId()));
        anon.setId(projectId + ":" + job.getDestinationDatasetId() + "." + job.getDestinationTableId());
        anon.setType("TABLE");
        anon.setSchema(schema);
        anon.setCreationTime(job.getCreationTime());
        anon.setLastModifiedTime(job.getCreationTime());
        anon.setNumRows(String.valueOf(resultRows.size()));
        tableStore.put(key, anon);

        StoredTableData rows = new StoredTableData();
        rows.setRows(new ArrayList<>(resultRows));
        dataStore.put(key, rows);
    }

    public StoredJob getJob(String projectId, String jobId) {
        return jobStore.get(jobId)
                .orElseThrow(() -> GcpException.notFound("Not found: Job " + projectId + ":" + jobId));
    }

    public List<StoredJob> listJobs(String projectId) {
        return jobStore.scan(k -> true).stream()
                .sorted((a, b) -> nullSafe(b.getCreationTime()).compareTo(nullSafe(a.getCreationTime())))
                .toList();
    }

    public void deleteJob(String projectId, String jobId) {
        StoredJob job = getJob(projectId, jobId);
        if (job.getDestinationTableId() != null) {
            String key = tableKey(job.getDestinationDatasetId(), job.getDestinationTableId());
            tableStore.delete(key);
            dataStore.delete(key);
        }
        jobStore.delete(jobId);
    }

    /** Encoded result rows for {@code getQueryResults}, read from the job's anonymous table. */
    public TableData queryResults(String projectId, StoredJob job) {
        return queryResults(projectId, job, RowCodec.TimestampFormat.FLOAT64);
    }

    public TableData queryResults(String projectId, StoredJob job, RowCodec.TimestampFormat format) {
        if (job.failed()) {
            throw GcpException.invalidArgument(job.getErrorMessage()).withReason(job.getErrorReason());
        }
        return listTableData(projectId, job.getDestinationDatasetId(), job.getDestinationTableId(), format);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    private static String tableKey(String datasetId, String tableId) {
        return datasetId + "/" + tableId;
    }

    private static String nowMillis() {
        return String.valueOf(Instant.now().toEpochMilli());
    }

    private static String etag() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

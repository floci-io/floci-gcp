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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
        if (body.viewQuery() != null) {
            body.setType(body.isMaterializedView() ? "MATERIALIZED_VIEW" : "VIEW");
            if (!(engine instanceof InMemorySqlEngine)) {
                // BigQuery validates the view query and derives the view's schema from it.
                body.setSchema(engine.execute(new BigQuerySqlEngine.Request(projectId, body.viewQuery(), datasetId,
                        List.of(), null, true), tables(projectId)).schema());
            }
        }
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
            synchronized (writeLock) {
                StoredTableData data = dataStore.get(key).orElseGet(StoredTableData::new);
                List<Map<String, Object>> stored = data.getRows();
                synchronized (stored) {
                    stored.addAll(accepted);
                }
                dataStore.put(key, data);
                table.setNumRows(String.valueOf(data.getRows().size()));
                table.setLastModifiedTime(nowMillis());
                tableStore.put(key, table);
            }
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
        // A snapshot, not the stored list. Callers stream these rows to the SQL engine from a
        // StreamingOutput, so the list would otherwise be iterated after the request method has
        // returned, while insertAll appends to that same list.
        // Deliberately not under writeLock: DML holds that lock across the sidecar round trip,
        // and the sidecar fetches this route over HTTP to stage the table, so taking it here
        // deadlocks until the read times out. The row list has its own monitor instead, which
        // insertAll also takes to append, so copying cannot see a half-written ArrayList. Nothing
        // holds that monitor across the sidecar call, so it cannot deadlock the way writeLock does.
        List<Map<String, Object>> stored = dataStore.get(tableKey(datasetId, tableId))
                .orElseGet(StoredTableData::new).getRows();
        synchronized (stored) {
            return List.copyOf(stored);
        }
    }

    // ── Query ────────────────────────────────────────────────────────────────────

    /** Hidden dataset holding materialized query results; never listed, has no dataset record. */
    static final String ANON_DATASET = "_floci_anon";

    /** Request-level options of {@code jobs.query} / {@code jobs.insert} that shape execution. */
    public record QueryOptions(String sql, String defaultDatasetId, List<Map<String, Object>> queryParameters,
                               String parameterMode, boolean dryRun, Boolean useLegacySql,
                               TableReference destinationTable, String writeDisposition,
                               String createDisposition, List<String> schemaUpdateOptions) {

        public QueryOptions {
            schemaUpdateOptions = schemaUpdateOptions != null ? schemaUpdateOptions : List.of();
        }

        public QueryOptions(String sql, String defaultDatasetId, List<Map<String, Object>> queryParameters,
                            String parameterMode, boolean dryRun, Boolean useLegacySql,
                            TableReference destinationTable, String writeDisposition, String createDisposition) {
            this(sql, defaultDatasetId, queryParameters, parameterMode, dryRun, useLegacySql, destinationTable,
                    writeDisposition, createDisposition, List.of());
        }

        public QueryOptions(String sql, String defaultDatasetId, List<Map<String, Object>> queryParameters,
                            String parameterMode, boolean dryRun, Boolean useLegacySql) {
            this(sql, defaultDatasetId, queryParameters, parameterMode, dryRun, useLegacySql, null, null, null);
        }

        public static QueryOptions of(String sql, String defaultDatasetId) {
            return new QueryOptions(sql, defaultDatasetId, List.of(), null, false, null);
        }
    }

    /** True when {@code e} is the 409 for an already-used explicit job ID, not a statement error. */
    static boolean isDuplicateJobId(GcpException e) {
        return e.getHttpStatus() == 409 && e.getMessage() != null && e.getMessage().startsWith("Already Exists: Job ");
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
        SqlDialectTranslator.Statement statement = SqlDialectTranslator.parseStatement(options.sql(), projectId,
                options.defaultDatasetId());

        StoredJob job = new StoredJob();
        job.setJobId(resolvedJobId);
        job.setProjectId(projectId);
        job.setLocation(location != null && !location.isBlank() ? location : "US");
        job.setQuery(options.sql());
        job.setState("DONE");
        job.setCreationTime(nowMillis());
        job.setStatementType(statement.statementType());
        job.setTotalBytesProcessed("0");

        if (statement.kind() != SqlDialectTranslator.StatementKind.QUERY) {
            if (options.dryRun()) {
                // "an invalid query will return the same error it would if it wasn't a dry run", so
                // a dry run has to resolve what the statement touches rather than only classify it.
                validateStatement(projectId, statement, options);
                job.setDryRun(true);
                return job;
            }
            synchronized (writeLock) {
                executeStatement(projectId, statement, options, job);
            }
            jobStore.put(job.getJobId(), job);
            return job;
        }

        BigQuerySqlEngine.Result result = engine.execute(request(projectId, options.sql(), options,
                options.dryRun()), tables(projectId));
        job.setStatementType(result.statementType());
        job.setTotalBytesProcessed(String.valueOf(result.totalBytesProcessed()));
        if (options.dryRun()) {
            job.setDryRun(true);
            job.setSchema(result.schema());
            return job;
        }
        job.setTotalRows(result.rows().size());
        if (options.destinationTable() != null) {
            synchronized (writeLock) {
                writeDestination(projectId, options, result);
            }
            job.setDestinationDatasetId(options.destinationTable().getDatasetId());
            job.setDestinationTableId(options.destinationTable().getTableId());
        } else {
            job.setDestinationDatasetId(ANON_DATASET);
            job.setDestinationTableId("anon_" + job.getJobId());
            materializeResult(projectId, job, result.schema(), result.rows());
        }
        jobStore.put(job.getJobId(), job);
        return job;
    }

    private BigQuerySqlEngine.Request request(String projectId, String sql, QueryOptions options, boolean dryRun) {
        return new BigQuerySqlEngine.Request(projectId, sql, options.defaultDatasetId(), options.queryParameters(),
                options.parameterMode(), dryRun);
    }

    // ── DML / DDL ────────────────────────────────────────────────────────────────

    private final Object writeLock = new Object();

    /**
     * The resource checks {@link #executeStatement} makes, without any of its writes. A dry run
     * reports the same error a real run would, so a DML statement against a missing table or a
     * CREATE VIEW over a missing one fails here instead of reporting success.
     */
    private void validateStatement(String projectId, SqlDialectTranslator.Statement statement,
                                   QueryOptions options) {
        SqlDialectTranslator.TableRef target = statement.target();
        switch (statement.kind()) {
            case INSERT, UPDATE, DELETE, MERGE, TRUNCATE ->
                    getTable(projectId, target.datasetId(), target.tableId());
            case DROP_TABLE, DROP_VIEW -> {
                if (tableStore.get(tableKey(target.datasetId(), target.tableId())).isEmpty()
                        && !statement.ifExists()) {
                    throw GcpException.notFound("Not found: Table " + qualified(projectId, target));
                }
            }
            case CREATE_TABLE, CREATE_VIEW -> {
                boolean exists = tableStore.get(tableKey(target.datasetId(), target.tableId())).isPresent();
                if (exists && !statement.orReplace() && !statement.ifNotExists()) {
                    throw GcpException.alreadyExists("Already Exists: Table " + qualified(projectId, target))
                            .withReason("duplicate");
                }
                getDataset(projectId, target.datasetId());
                if (statement.querySql() != null) {
                    engine.execute(request(projectId, statement.querySql(), options, true), tables(projectId));
                }
            }
            case CREATE_SCHEMA -> {
                if (datasetStore.get(statement.datasetTarget()).isPresent() && !statement.ifNotExists()) {
                    throw GcpException.alreadyExists(
                            "Already Exists: Dataset " + projectId + ":" + statement.datasetTarget())
                            .withReason("duplicate");
                }
            }
            case DROP_SCHEMA -> {
                if (datasetStore.get(statement.datasetTarget()).isEmpty() && !statement.ifExists()) {
                    throw GcpException.notFound(
                            "Not found: Dataset " + projectId + ":" + statement.datasetTarget());
                }
            }
            default -> { }
        }
    }

    private void executeStatement(String projectId, SqlDialectTranslator.Statement statement, QueryOptions options,
                                  StoredJob job) {
        SqlDialectTranslator.TableRef target = statement.target();
        switch (statement.kind()) {
            case INSERT, UPDATE, DELETE, MERGE -> {
                BigQuerySqlEngine.DmlResult result = engine.executeDml(request(projectId, options.sql(), options,
                        false), tables(projectId));
                replaceRows(projectId, target, result.tableRows());
                job.setTotalBytesProcessed(String.valueOf(result.totalBytesProcessed()));
                job.setNumDmlAffectedRows(String.valueOf(result.affectedRows()));
                Map<String, String> stats = new LinkedHashMap<>();
                if (statement.kind() == SqlDialectTranslator.StatementKind.INSERT
                        || statement.kind() == SqlDialectTranslator.StatementKind.MERGE) {
                    stats.put("insertedRowCount", String.valueOf(result.insertedRows()));
                }
                if (statement.kind() == SqlDialectTranslator.StatementKind.UPDATE
                        || statement.kind() == SqlDialectTranslator.StatementKind.MERGE) {
                    stats.put("updatedRowCount", String.valueOf(result.updatedRows()));
                }
                if (statement.kind() == SqlDialectTranslator.StatementKind.DELETE
                        || statement.kind() == SqlDialectTranslator.StatementKind.MERGE) {
                    stats.put("deletedRowCount", String.valueOf(result.deletedRows()));
                }
                job.setDmlStats(stats);
            }
            case TRUNCATE -> {
                Table table = getTable(projectId, target.datasetId(), target.tableId());
                if (table.viewQuery() != null) {
                    throw QueryEngine.invalidQuery("Cannot truncate view " + qualified(projectId, target));
                }
                int removed = storedRows(projectId, target.datasetId(), target.tableId()).size();
                replaceRows(projectId, target, List.of());
                job.setDmlStats(Map.of("deletedRowCount", String.valueOf(removed)));
            }
            case CREATE_TABLE, CREATE_VIEW -> createFromStatement(projectId, statement, options, job);
            case DROP_TABLE, DROP_VIEW -> {
                job.setDdlTargetTable(new TableReference(projectId, target.datasetId(), target.tableId()));
                Optional<Table> existing = tableStore.get(tableKey(target.datasetId(), target.tableId()));
                if (existing.isEmpty()) {
                    if (!statement.ifExists()) {
                        throw GcpException.notFound("Not found: Table " + qualified(projectId, target));
                    }
                    job.setDdlOperationPerformed("SKIP");
                    return;
                }
                boolean isView = existing.get().viewQuery() != null;
                if (isView != (statement.kind() == SqlDialectTranslator.StatementKind.DROP_VIEW)) {
                    throw QueryEngine.invalidQuery(qualified(projectId, target) + " is " + (isView
                            ? "a view; use DROP VIEW" : "a table; use DROP TABLE"));
                }
                deleteTable(projectId, target.datasetId(), target.tableId());
                job.setDdlOperationPerformed("DROP");
            }
            case CREATE_SCHEMA -> {
                String datasetId = statement.datasetTarget();
                job.setDdlTargetDataset(new DatasetReference(projectId, datasetId));
                if (datasetStore.get(datasetId).isPresent()) {
                    if (statement.ifNotExists()) {
                        job.setDdlOperationPerformed("SKIP");
                        return;
                    }
                    throw GcpException.alreadyExists("Already Exists: Dataset " + projectId + ":" + datasetId)
                            .withReason("duplicate");
                }
                Dataset dataset = new Dataset();
                dataset.setDatasetReference(new DatasetReference(projectId, datasetId));
                dataset.setLocation(job.getLocation());
                createDataset(projectId, dataset);
                job.setDdlOperationPerformed("CREATE");
            }
            case DROP_SCHEMA -> {
                String datasetId = statement.datasetTarget();
                job.setDdlTargetDataset(new DatasetReference(projectId, datasetId));
                if (datasetStore.get(datasetId).isEmpty()) {
                    if (!statement.ifExists()) {
                        throw GcpException.notFound("Not found: Dataset " + projectId + ":" + datasetId);
                    }
                    job.setDdlOperationPerformed("SKIP");
                    return;
                }
                deleteDataset(projectId, datasetId, statement.cascade());
                job.setDdlOperationPerformed("DROP");
            }
            default -> throw QueryEngine.invalidQuery("Unsupported statement " + statement.statementType());
        }
    }

    private void createFromStatement(String projectId, SqlDialectTranslator.Statement statement, QueryOptions options,
                                     StoredJob job) {
        SqlDialectTranslator.TableRef target = statement.target();
        getDataset(projectId, target.datasetId());
        job.setDdlTargetTable(new TableReference(projectId, target.datasetId(), target.tableId()));
        boolean exists = tableStore.get(tableKey(target.datasetId(), target.tableId())).isPresent();
        if (exists && statement.ifNotExists()) {
            job.setDdlOperationPerformed("SKIP");
            return;
        }
        if (exists && !statement.orReplace()) {
            throw GcpException.alreadyExists("Already Exists: Table " + qualified(projectId, target))
                    .withReason("duplicate");
        }

        Table table = new Table();
        List<Map<String, Object>> rows = List.of();
        if (statement.kind() == SqlDialectTranslator.StatementKind.CREATE_VIEW) {
            BigQuerySqlEngine.Result described = engine.execute(request(projectId, statement.querySql(), options, true),
                    tables(projectId));
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("query", statement.querySql());
            if (statement.materialized()) {
                table.setType("MATERIALIZED_VIEW");
                table.setMaterializedViewDefinition(definition);
            } else {
                definition.put("useLegacySql", false);
                table.setType("VIEW");
                table.setViewDefinition(definition);
            }
            table.setSchema(described.schema());
        } else if (statement.querySql() != null) {
            BigQuerySqlEngine.Result result = engine.execute(request(projectId, statement.querySql(), options, false),
                    tables(projectId));
            table.setSchema(result.schema());
            rows = result.rows();
            job.setTotalBytesProcessed(String.valueOf(result.totalBytesProcessed()));
        } else {
            table.setSchema(RowCodec.normalizeSchema(new TableSchema(statement.columns())));
        }
        if (exists) {
            deleteTable(projectId, target.datasetId(), target.tableId());
        }
        putTable(projectId, target, table, rows);
        job.setDdlOperationPerformed(exists ? "REPLACE" : "CREATE");
    }

    /**
     * Writes SELECT results into a user destination table following BigQuery's dispositions:
     * CREATE_IF_NEEDED (default) / CREATE_NEVER, and WRITE_EMPTY (default) / WRITE_TRUNCATE /
     * WRITE_TRUNCATE_DATA / WRITE_APPEND.
     */
    private void writeDestination(String projectId, QueryOptions options, BigQuerySqlEngine.Result result) {
        TableReference destination = options.destinationTable();
        if (destination.getProjectId() != null && !destination.getProjectId().equals(projectId)) {
            throw QueryEngine.invalidQuery("Cross-project destination tables are not supported by the floci"
                    + " BigQuery emulator");
        }
        if (destination.getDatasetId() == null || destination.getDatasetId().isBlank()) {
            throw GcpException.invalidArgument("destinationTable.datasetId is required").withReason("invalid");
        }
        if (destination.getTableId() == null || destination.getTableId().isBlank()) {
            throw GcpException.invalidArgument("destinationTable.tableId is required").withReason("invalid");
        }
        SqlDialectTranslator.TableRef target = new SqlDialectTranslator.TableRef(destination.getDatasetId(),
                destination.getTableId());
        getDataset(projectId, target.datasetId());
        Optional<Table> existing = tableStore.get(tableKey(target.datasetId(), target.tableId()));
        String write = options.writeDisposition() != null ? options.writeDisposition() : "WRITE_EMPTY";
        String create = options.createDisposition() != null ? options.createDisposition() : "CREATE_IF_NEEDED";

        if (existing.isEmpty()) {
            if ("CREATE_NEVER".equals(create)) {
                throw GcpException.notFound("Not found: Table " + qualified(projectId, target));
            }
            Table table = new Table();
            table.setSchema(result.schema());
            putTable(projectId, target, table, result.rows());
            return;
        }
        Table table = existing.get();
        if (table.viewQuery() != null) {
            throw QueryEngine.invalidQuery("Cannot write query results to view " + qualified(projectId, target));
        }
        List<Map<String, Object>> current = storedRows(projectId, target.datasetId(), target.tableId());
        boolean relaxed = options.schemaUpdateOptions().stream()
                .anyMatch(option -> "ALLOW_FIELD_RELAXATION".equalsIgnoreCase(option));
        switch (write) {
            case "WRITE_TRUNCATE" -> {
                table.setSchema(result.schema());
                tableStore.put(tableKey(target.datasetId(), target.tableId()), table);
                replaceRows(projectId, target, result.rows());
            }
            case "WRITE_TRUNCATE_DATA" -> {
                List<Map<String, Object>> rows = alignRows(projectId, target, table, result, relaxed);
                relaxSchemaIfRequested(projectId, target, table, relaxed);
                replaceRows(projectId, target, rows);
            }
            case "WRITE_APPEND" -> {
                List<Map<String, Object>> rows = new ArrayList<>(current);
                rows.addAll(alignRows(projectId, target, table, result, relaxed));
                relaxSchemaIfRequested(projectId, target, table, relaxed);
                replaceRows(projectId, target, rows);
            }
            default -> {
                if (!current.isEmpty()) {
                    throw GcpException.alreadyExists("Already Exists: Table " + qualified(projectId, target))
                            .withReason("duplicate");
                }
                table.setSchema(result.schema());
                tableStore.put(tableKey(target.datasetId(), target.tableId()), table);
                replaceRows(projectId, target, result.rows());
            }
        }
    }

    /** Result rows keyed by the existing table's field names; every result column must exist there. */
    private static List<Map<String, Object>> alignRows(String projectId, SqlDialectTranslator.TableRef target,
                                                       Table table, BigQuerySqlEngine.Result result,
                                                       boolean relaxed) {
        List<TableFieldSchema> fields = table.getSchema() != null && table.getSchema().getFields() != null
                ? table.getSchema().getFields() : List.of();
        Map<String, String> byLower = new LinkedHashMap<>();
        fields.forEach(f -> byLower.put(f.getName().toLowerCase(Locale.ROOT), f.getName()));
        for (TableFieldSchema column : result.schema().getFields()) {
            if (!byLower.containsKey(column.getName().toLowerCase(Locale.ROOT))) {
                throw QueryEngine.invalidQuery("Provided Schema does not match Table " + qualified(projectId, target)
                        + ". Cannot add fields (field: " + column.getName() + ")");
            }
        }
        List<Map<String, Object>> aligned = new ArrayList<>(result.rows().size());
        for (Map<String, Object> row : result.rows()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (TableFieldSchema field : fields) {
                out.put(field.getName(), null);
            }
            row.forEach((k, v) -> out.put(byLower.get(k.toLowerCase(Locale.ROOT)), v));
            aligned.add(enforceSchema(projectId, target, fields, out, relaxed));
        }
        return aligned;
    }

    /**
     * WRITE_APPEND and WRITE_TRUNCATE_DATA keep "the constraints and schema of the existing table",
     * so the declared schema decides what may be stored, exactly as it does for insertAll. Matching
     * only column names let a value of the wrong type land in a column: the write reported success,
     * tabledata.list then returned a cell the SDK cannot parse, and the next query over the table
     * failed while staging it. Relaxing a REQUIRED field needs ALLOW_FIELD_RELAXATION.
     */
    private static Map<String, Object> enforceSchema(String projectId, SqlDialectTranslator.TableRef target,
                                                     List<TableFieldSchema> fields, Map<String, Object> row,
                                                     boolean relaxed) {
        for (TableFieldSchema field : fields) {
            Object value = row.get(field.getName());
            if (value == null) {
                if ("REQUIRED".equals(field.getMode()) && !relaxed) {
                    throw QueryEngine.invalidQuery("Missing required field: " + field.getName()
                            + " in table " + qualified(projectId, target)
                            + ". Use schemaUpdateOptions ALLOW_FIELD_RELAXATION to write null into it.");
                }
                continue;
            }
            try {
                row.put(field.getName(), RowCodec.coerceValue(field, value));
            } catch (IllegalArgumentException e) {
                throw QueryEngine.invalidQuery("Provided Schema does not match Table "
                        + qualified(projectId, target) + ". Field " + field.getName() + ": " + e.getMessage());
            }
        }
        return row;
    }

    private void relaxSchemaIfRequested(String projectId, SqlDialectTranslator.TableRef target,
                                        Table table, boolean relaxed) {
        if (!relaxed) {
            return;
        }
        relaxRequiredFields(table);
        tableStore.put(tableKey(target.datasetId(), target.tableId()), table);
    }

    /** "ALLOW_FIELD_RELAXATION: allow relaxing a required field in the original schema to nullable." */
    private static void relaxRequiredFields(Table table) {
        if (table.getSchema() == null || table.getSchema().getFields() == null) {
            return;
        }
        table.getSchema().getFields().forEach(field -> {
            if ("REQUIRED".equals(field.getMode())) {
                field.setMode("NULLABLE");
            }
        });
    }

    private void putTable(String projectId, SqlDialectTranslator.TableRef target, Table table,
                          List<Map<String, Object>> rows) {
        String now = nowMillis();
        table.setTableReference(new TableReference(projectId, target.datasetId(), target.tableId()));
        table.setId(projectId + ":" + target.datasetId() + "." + target.tableId());
        if (table.getType() == null) {
            table.setType("TABLE");
        }
        table.setEtag(etag());
        table.setCreationTime(now);
        table.setLastModifiedTime(now);
        table.setNumRows(String.valueOf(rows.size()));
        tableStore.put(tableKey(target.datasetId(), target.tableId()), table);
        StoredTableData data = new StoredTableData();
        data.setRows(new ArrayList<>(rows));
        dataStore.put(tableKey(target.datasetId(), target.tableId()), data);
    }

    private void replaceRows(String projectId, SqlDialectTranslator.TableRef target, List<Map<String, Object>> rows) {
        String key = tableKey(target.datasetId(), target.tableId());
        Table table = getTable(projectId, target.datasetId(), target.tableId());
        StoredTableData data = new StoredTableData();
        data.setRows(new ArrayList<>(rows));
        dataStore.put(key, data);
        table.setNumRows(String.valueOf(rows.size()));
        table.setLastModifiedTime(nowMillis());
        table.setEtag(etag());
        tableStore.put(key, table);
    }

    private static String qualified(String projectId, SqlDialectTranslator.TableRef ref) {
        return projectId + ":" + ref.datasetId() + "." + ref.tableId();
    }

    BigQuerySqlEngine.Tables tables(String projectId) {
        return new BigQuerySqlEngine.Tables() {
            @Override
            public Table table(String datasetId, String tableId) {
                return getTable(projectId, datasetId, tableId);
            }

            @Override
            public List<Map<String, Object>> rows(String datasetId, String tableId) {
                // A snapshot for the same reason storedRows takes one: the engines iterate these
                // rows (to estimate bytes, and to evaluate in mock mode) while insertAll can be
                // appending to the very same list.
                return storedRows(projectId, datasetId, tableId);
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
        job.setErrorReason(cause.getReason() != null ? cause.getReason() : switch (cause.getHttpStatus()) {
            case 404 -> "notFound";
            case 409 -> "duplicate";
            default -> "invalidQuery";
        });
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
        // "Requests the deletion of the metadata of a job", so only the anonymous result table this
        // emulator created goes with it. A destination table the caller named is their resource and
        // outlives the job.
        if (job.getDestinationTableId() != null && ANON_DATASET.equals(job.getDestinationDatasetId())) {
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
        if (job.getDestinationTableId() == null) {
            return new TableData(null, List.of()); // DML and DDL produce no result rows
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

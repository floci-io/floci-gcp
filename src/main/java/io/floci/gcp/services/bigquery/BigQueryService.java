package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
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

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final GcsService gcsService;
    private final BigQueryLoadFiles loadFiles;

    @Inject
    public BigQueryService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, DuckSqlEngine duckEngine, GcsService gcsService,
            BigQueryLoadFiles loadFiles) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.gcsService = gcsService;
        this.loadFiles = loadFiles;
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
        this.gcsService = null;
        this.loadFiles = new BigQueryLoadFiles();
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
                .resourceClasses(BigQueryController.class, BigQueryInternalController.class,
                        BigQueryUploadController.class, BigQueryReadController.class,
                        BigQueryWriteController.class)
                .api("bigquery.googleapis.com", "BigQuery API")
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

    private static List<Map<String, Object>> alignedPartitionRows(String projectId,
                                                                  SqlDialectTranslator.TableRef target,
                                                                  Table table, String partitionId,
                                                                  BigQuerySqlEngine.Result result,
                                                                  boolean relaxed) {
        List<Map<String, Object>> rows = alignRows(projectId, target, table, result, relaxed);
        validateRowsBelongToPartition(table, partitionId, rows);
        return rows;
    }

    private void writePartition(String projectId, SqlDialectTranslator.TableRef target, Table table,
                                String partitionId, String write, List<Map<String, Object>> rows) {
        StoredTableData data = dataStore.get(tableKey(target.datasetId(), target.tableId()))
                .orElseGet(StoredTableData::new);
        synchronized (data) {
            List<Map<String, Object>> selected = selectedPartitionRows(table, data, partitionId);
            switch (write) {
                case "WRITE_TRUNCATE", "WRITE_TRUNCATE_DATA" -> {
                    data.getRows().removeIf(row -> partitionId.equals(partitionIdForRow(table, row)));
                    data.getPartitionRows().put(partitionId, new ArrayList<>(rows));
                }
                case "WRITE_APPEND" -> data.getPartitionRows()
                        .computeIfAbsent(partitionId, ignored -> new ArrayList<>()).addAll(rows);
                default -> {
                    if (!selected.isEmpty()) {
                        throw GcpException.alreadyExists("Already Exists: Table " + qualified(projectId, target))
                                .withReason("duplicate");
                    }
                    data.getPartitionRows().put(partitionId, new ArrayList<>(rows));
                }
            }
            dataStore.put(tableKey(target.datasetId(), target.tableId()), data);
            table.setNumRows(String.valueOf(allRows(data).size()));
        }
        table.setLastModifiedTime(nowMillis());
        table.setEtag(etag());
        tableStore.put(tableKey(target.datasetId(), target.tableId()), table);
    }

    private DecoratedDestination decoratedDestination(TableReference destination) {
        String tableId = destination.getTableId();
        if (tableId == null || !tableId.contains("$")) {
            return null;
        }
        Matcher matcher = PARTITION_DECORATOR.matcher(tableId);
        if (!matcher.matches()) {
            throw GcpException.invalidArgument("Invalid partition decorator: " + tableId).withReason("invalid");
        }
        SqlDialectTranslator.TableRef target = new SqlDialectTranslator.TableRef(destination.getDatasetId(),
                matcher.group(1));
        return new DecoratedDestination(target, matcher.group(2));
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
        validateUnchangedPartitioning(existing.getExtra(), candidate);
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
        validateUnchangedPartitioning(existing.getExtra(), candidate);
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
        DecoratedDestination decorated = decoratedDestination(new TableReference(projectId, datasetId, tableId));
        if (decorated != null) {
            String key = tableKey(datasetId, decorated.target().tableId());
            synchronized (writeLock) {
                Table table = getTable(projectId, datasetId, decorated.target().tableId());
                validatePartitionDecorator(table, decorated.partitionId());
                StoredTableData data = dataStore.get(key).orElseGet(StoredTableData::new);
                synchronized (data) {
                    data.getPartitionRows().remove(decorated.partitionId());
                    synchronized (data.getRows()) {
                        data.getRows().removeIf(row -> decorated.partitionId().equals(partitionIdForRow(table, row)));
                    }
                }
                dataStore.put(key, data);
                table.setNumRows(String.valueOf(allRows(data).size()));
                table.setLastModifiedTime(nowMillis());
                table.setEtag(etag());
                tableStore.put(key, table);
            }
            LOG.debugf("deleteTable partition project=%s dataset=%s table=%s partition=%s",
                    projectId, datasetId, decorated.target().tableId(), decorated.partitionId());
            return;
        }
        synchronized (writeLock) {
            getTable(projectId, datasetId, tableId);
            tableStore.delete(tableKey(datasetId, tableId));
            dataStore.delete(tableKey(datasetId, tableId));
        }
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
        DecoratedDestination decorated = decoratedDestination(new TableReference(projectId, datasetId, tableId));
        String baseTableId = decorated != null ? decorated.target().tableId() : tableId;
        Table table = getTable(projectId, datasetId, baseTableId);
        if (decorated != null) {
            validateStreamingPartitionDecorator(table, decorated.partitionId());
        }

        List<Map<String, Object>> insertErrors = new ArrayList<>();
        List<Map<String, Object>> accepted = new ArrayList<>();
        List<Integer> acceptedIndexes = new ArrayList<>();
        for (InsertRow row : rows) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            List<ErrorProto> rowErrors = RowCodec.normalizeRow(
                    table.getSchema(), row.json(), ignoreUnknownValues, normalized);
            if (rowErrors.isEmpty()) {
                try {
                    validateStreamingPartitionValue(table, normalized);
                } catch (GcpException e) {
                    rowErrors = List.of(new ErrorProto("invalid", null, e.getMessage()));
                }
            }
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

        if (decorated != null) {
            appendPartitionRows(projectId, datasetId, baseTableId, decorated.partitionId(), accepted);
        } else {
            appendRows(projectId, datasetId, baseTableId, accepted);
        }
        LOG.debugf("insertAll project=%s dataset=%s table=%s accepted=%d rejected=%d",
                projectId, datasetId, tableId, accepted.size(), insertErrors.size());
        return insertErrors;
    }

    /** Appends rows already normalized against the table schema. */
    void appendRows(String projectId, String datasetId, String tableId, List<Map<String, Object>> rows) {
        appendRows(projectId, datasetId, tableId, rows, Map.of());
    }

    /**
     * Appends rows and, in the same write, records {@code streamRows}: for each Storage Write
     * stream, how many of its rows the table now holds in total.
     */
    void appendRows(String projectId, String datasetId, String tableId, List<Map<String, Object>> rows,
                    Map<String, Long> streamRows) {
        if (rows.isEmpty() && streamRows.isEmpty()) {
            return;
        }
        String key = tableKey(datasetId, tableId);
        synchronized (writeLock) {
            Table table = getTable(projectId, datasetId, tableId);
            StoredTableData data = dataStore.get(key).orElseGet(StoredTableData::new);
            synchronized (data) {
                if (!rows.isEmpty()) {
                    List<Map<String, Object>> stored = isIngestionTimePartitioned(table)
                            ? data.getPartitionRows().computeIfAbsent(currentIngestionPartitionId(table),
                                    ignored -> new ArrayList<>())
                            : data.getRows();
                    stored.addAll(rows);
                }
                data.getStreamRows().putAll(streamRows);
            }
            dataStore.put(key, data);
            table.setNumRows(String.valueOf(allRows(data).size()));
            table.setLastModifiedTime(nowMillis());
            tableStore.put(key, table);
        }
    }

    /**
     * How many of a Storage Write stream's rows the table holds, or empty when no write of that
     * stream ever reached the table. Present with 0 means a stream committed with no rows.
     */
    OptionalLong streamRowsApplied(String projectId, String datasetId, String tableId, String streamName) {
        getTable(projectId, datasetId, tableId);
        Long applied = dataStore.get(tableKey(datasetId, tableId))
                .map(data -> data.getStreamRows().get(streamName)).orElse(null);
        return applied != null ? OptionalLong.of(applied) : OptionalLong.empty();
    }

    /** Encoded rows plus totals for {@code tabledata.list}. */
    public record TableData(TableSchema schema, List<TableRow> rows) {}

    public TableData listTableData(String projectId, String datasetId, String tableId) {
        return listTableData(projectId, datasetId, tableId, RowCodec.TimestampFormat.FLOAT64);
    }

    public TableData listTableData(String projectId, String datasetId, String tableId,
            RowCodec.TimestampFormat format) {
        DecoratedDestination decorated = decoratedDestination(new TableReference(projectId, datasetId, tableId));
        if (decorated != null) {
            Table table = getTable(projectId, decorated.target().datasetId(), decorated.target().tableId());
            validatePartitionDecorator(table, decorated.partitionId());
            StoredTableData data = dataStore.get(tableKey(decorated.target().datasetId(), decorated.target().tableId()))
                    .orElseGet(StoredTableData::new);
            List<Map<String, Object>> rows;
            synchronized (data) {
                rows = selectedPartitionRows(table, data, decorated.partitionId());
            }
            return new TableData(table.getSchema(), RowCodec.encodeRows(table.getSchema(), rows, format));
        }
        Table table = getTable(projectId, datasetId, tableId);
        StoredTableData data = dataStore.get(tableKey(datasetId, tableId)).orElseGet(StoredTableData::new);
        return new TableData(table.getSchema(), RowCodec.encodeRows(table.getSchema(), allRows(data), format));
    }

    /** Stored (normalized) rows of a table, as the SQL engine stages them. */
    public List<Map<String, Object>> storedRows(String projectId, String datasetId, String tableId) {
        getTable(projectId, datasetId, tableId);
        // A snapshot, not the stored list. Callers stream these rows to the SQL engine from a
        // StreamingOutput, so the list would otherwise be iterated after the request method has
        // returned, while insertAll appends to that same list.
        // Deliberately not under writeLock: DML holds that lock across the sidecar round trip,
        // and the sidecar fetches this route over HTTP to stage the table, so taking it here
        // deadlocks until the read times out. The table-data object has its own monitor instead,
        // which append paths also take, so a snapshot cannot observe a partial row or partition
        // mutation. Nothing holds that monitor across the sidecar call, so it cannot deadlock the
        // way writeLock does.
        StoredTableData data = dataStore.get(tableKey(datasetId, tableId)).orElseGet(StoredTableData::new);
        return allRows(data);
    }

    // ── Query ────────────────────────────────────────────────────────────────────

    /** Hidden dataset holding materialized query results; never listed, has no dataset record. */
    static final String ANON_DATASET = "_floci_anon";
    private static final Pattern PARTITION_DECORATOR = Pattern.compile("^(.+)\\$(-?\\d+)$");
    private static final DateTimeFormatter HOUR_PARTITION = DateTimeFormatter.ofPattern("uuuuMMddHH")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DAY_PARTITION = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter MONTH_PARTITION = DateTimeFormatter.ofPattern("uuuuMM")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter YEAR_PARTITION = DateTimeFormatter.ofPattern("uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final LocalDate MIN_COLUMN_PARTITION_DATE = LocalDate.of(1960, 1, 1);
    private static final LocalDate MAX_COLUMN_PARTITION_DATE = LocalDate.of(2159, 12, 31);
    private static final LocalDate MIN_DML_PARTITION_DATE = LocalDate.of(1970, 1, 1);
    private static final LocalDate MAX_DML_PARTITION_DATE = LocalDate.of(2159, 12, 31);
    static final String PARTITION_DATE = "_PARTITIONDATE";
    static final String PARTITION_TIME = "_PARTITIONTIME";
    static final String DML_ORIGINAL_PARTITION_TIME = "$floci_original_partition_time";
    static final String DML_ORIGINAL_UNPARTITIONED = "$floci_original_unpartitioned";

    /** Request-level options of {@code jobs.query} / {@code jobs.insert} that shape execution. */
    public record QueryOptions(String sql, String defaultDatasetId, List<Map<String, Object>> queryParameters,
                               String parameterMode, boolean dryRun, Boolean useLegacySql,
                               TableReference destinationTable, String writeDisposition,
                               String createDisposition, List<String> schemaUpdateOptions,
                               Map<String, Object> timePartitioning, Map<String, Object> rangePartitioning) {

        public QueryOptions {
            schemaUpdateOptions = schemaUpdateOptions != null ? schemaUpdateOptions : List.of();
        }

        public QueryOptions(String sql, String defaultDatasetId, List<Map<String, Object>> queryParameters,
                            String parameterMode, boolean dryRun, Boolean useLegacySql,
                            TableReference destinationTable, String writeDisposition, String createDisposition,
                            List<String> schemaUpdateOptions) {
            this(sql, defaultDatasetId, queryParameters, parameterMode, dryRun, useLegacySql, destinationTable,
                    writeDisposition, createDisposition, schemaUpdateOptions, null, null);
        }

        public QueryOptions(String sql, String defaultDatasetId, List<Map<String, Object>> queryParameters,
                            String parameterMode, boolean dryRun, Boolean useLegacySql,
                            TableReference destinationTable, String writeDisposition, String createDisposition) {
            this(sql, defaultDatasetId, queryParameters, parameterMode, dryRun, useLegacySql, destinationTable,
                    writeDisposition, createDisposition, List.of(), null, null);
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
        return query(projectId, new JobLocation(projectId, location), jobId, options);
    }

    /**
     * jobs.insert for a query: an invalid query is not an HTTP error but a DONE job whose status
     * carries the error, recorded in the location the job had resolved to when it failed.
     */
    public StoredJob insertQuery(String projectId, String location, String jobId, QueryOptions options) {
        JobLocation jobLocation = new JobLocation(projectId, location);
        try {
            return query(projectId, jobLocation, jobId, options);
        } catch (GcpException e) {
            // A dry run reports an invalid query as an HTTP error, as does a duplicate job ID.
            if (isDuplicateJobId(e) || options.dryRun()) {
                throw e;
            }
            return failedJob(projectId, jobLocation.resolved(), jobId, options.sql(), e);
        }
    }

    private StoredJob query(String projectId, JobLocation jobLocation, String jobId, QueryOptions options) {
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
        job.setLocation(jobLocation.resolved());
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
                options.dryRun()), tables(projectId, jobLocation));
        job.setLocation(jobLocation.resolved());
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

    /**
     * An int32 job option. JSON clients send it as a number or, like the Python client for
     * {@code skipLeadingRows}, as a decimal string; BigQuery rejects any other string, an empty or
     * padded one included, with a 400 that names the field's path in the request.
     */
    private static Integer integerOption(Object value, String fieldPath) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.valueOf(s);
            } catch (NumberFormatException e) {
                throw GcpException.invalidArgument("Invalid value at '" + fieldPath + "' (TYPE_INT32), \"" + s + "\"")
                        .withReason("invalid");
            }
        }
        return null;
    }

    private BigQuerySqlEngine.Request request(String projectId, String sql, QueryOptions options, boolean dryRun) {
        return new BigQuerySqlEngine.Request(projectId, sql, options.defaultDatasetId(), options.queryParameters(),
                options.parameterMode(), dryRun);
    }

    // ── DML / DDL ────────────────────────────────────────────────────────────────

    private final Object writeLock = new Object();

    /**
     * The resource checks {@link #executeStatement} makes, without any of its writes. A dry run
     * reports the same error a real run would, so a DML statement against a missing target or
     * source table, or a CREATE VIEW over a missing one, fails here instead of reporting success.
     */
    private void validateStatement(String projectId, SqlDialectTranslator.Statement statement,
                                   QueryOptions options) {
        SqlDialectTranslator.TableRef target = statement.target();
        switch (statement.kind()) {
            case INSERT, UPDATE, DELETE, MERGE -> {
                getTable(projectId, target.datasetId(), target.tableId());
                engine.executeDml(request(projectId, options.sql(), options, true), tables(projectId));
            }
            case TRUNCATE -> getTable(projectId, target.datasetId(), target.tableId());
            case DROP_TABLE, DROP_VIEW -> {
                if (tableStore.get(tableKey(target.datasetId(), target.tableId())).isEmpty()
                        && !statement.ifExists()) {
                    throw GcpException.notFound("Not found: Table " + qualified(projectId, target));
                }
            }
            case CREATE_TABLE, CREATE_VIEW -> {
                Optional<Table> existing = tableStore.get(tableKey(target.datasetId(), target.tableId()));
                boolean exists = existing.isPresent();
                if (exists && !statement.orReplace() && !statement.ifNotExists()) {
                    throw GcpException.alreadyExists("Already Exists: Table " + qualified(projectId, target))
                            .withReason("duplicate");
                }
                getDataset(projectId, target.datasetId());
                BigQuerySqlEngine.Result described = null;
                if (statement.querySql() != null) {
                    described = engine.execute(request(projectId, statement.querySql(), options, true),
                            tables(projectId));
                }
                if (statement.kind() == SqlDialectTranslator.StatementKind.CREATE_TABLE) {
                    Table candidate = createStatementTable(statement,
                            described != null ? described.schema() : null);
                    if (existing.isPresent() && statement.orReplace()
                            && !samePartitioning(existing.get(), candidate)) {
                        throw QueryEngine.invalidQuery(
                                "CREATE OR REPLACE TABLE cannot change the partitioning specification");
                    }
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
            case ALTER_TABLE, ALTER_VIEW -> alterTarget(projectId, statement);
            default -> { }
        }
    }

    /**
     * The table an {@code ALTER TABLE/VIEW ... SET OPTIONS} changes, or empty when {@code IF EXISTS}
     * skips a missing one. Each form applies only to its own kind of table.
     */
    private Optional<Table> alterTarget(String projectId, SqlDialectTranslator.Statement statement) {
        SqlDialectTranslator.TableRef target = statement.target();
        Optional<Table> existing = tableStore.get(tableKey(target.datasetId(), target.tableId()))
                .filter(table -> !expire(target.datasetId(), target.tableId(), table));
        if (existing.isEmpty()) {
            if (statement.ifExists()) {
                return existing;
            }
            throw GcpException.notFound("Not found: Table " + qualified(projectId, target));
        }
        boolean view = existing.get().viewQuery() != null;
        if (view != (statement.kind() == SqlDialectTranslator.StatementKind.ALTER_VIEW)) {
            String form = view ? "TABLE" : "VIEW";
            throw QueryEngine.invalidQuery("ALTER " + form + " SET OPTIONS only supports setting options for "
                    + (view ? "tables" : "views") + ", while " + target.tableId() + " is a(n) "
                    + (view ? "view" : "table"));
        }
        return existing;
    }

    /** Applies SET OPTIONS: each listed option is set, or cleared by NULL; labels replace the old ones. */
    private void alterOptions(String projectId, SqlDialectTranslator.Statement statement, StoredJob job) {
        SqlDialectTranslator.TableRef target = statement.target();
        job.setDdlTargetTable(new TableReference(projectId, target.datasetId(), target.tableId()));
        Optional<Table> found = alterTarget(projectId, statement);
        if (found.isEmpty()) {
            job.setDdlOperationPerformed("SKIP");
            return;
        }
        Table table = found.get();
        Map<String, Object> options = statement.options();
        if (options.containsKey("description")) {
            table.setDescription((String) options.get("description"));
        }
        if (options.containsKey("friendly_name")) {
            table.setFriendlyName((String) options.get("friendly_name"));
        }
        if (options.containsKey("labels")) {
            Map<String, String> labels = new LinkedHashMap<>();
            if (options.get("labels") instanceof Map<?, ?> given) {
                given.forEach((key, value) -> labels.put(String.valueOf(key), String.valueOf(value)));
            }
            table.setLabels(labels.isEmpty() ? null : labels);
        }
        if (options.containsKey("expiration_timestamp")) {
            Object expiration = options.get("expiration_timestamp");
            if (expiration == null) {
                table.getExtra().remove("expirationTime");
            } else {
                table.getExtra().put("expirationTime", String.valueOf(expiration));
            }
        }
        table.setLastModifiedTime(nowMillis());
        table.setEtag(etag());
        tableStore.put(tableKey(target.datasetId(), target.tableId()), table);
        job.setDdlOperationPerformed("ALTER");
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
            case ALTER_TABLE, ALTER_VIEW -> alterOptions(projectId, statement, job);
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
        Optional<Table> existingTable = tableStore.get(tableKey(target.datasetId(), target.tableId()));
        boolean exists = existingTable.isPresent();
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
        if (statement.timePartitioning() != null) {
            table.getExtra().put("timePartitioning", new LinkedHashMap<>(statement.timePartitioning()));
        }
        if (statement.rangePartitioning() != null) {
            table.getExtra().put("rangePartitioning", new LinkedHashMap<>(statement.rangePartitioning()));
        }
        BigQueryMetadata.validateTable(table.getExtra(), schemaFields(table));
        validateCreateTablePartitioning(statement, table);
        if (statement.kind() == SqlDialectTranslator.StatementKind.CREATE_TABLE && existingTable.isPresent()
                && !samePartitioning(existingTable.get(), table)) {
            throw QueryEngine.invalidQuery("CREATE OR REPLACE TABLE cannot change the partitioning specification");
        }
        if (exists) {
            deleteTable(projectId, target.datasetId(), target.tableId());
        }
        putTable(projectId, target, table, rows);
        job.setDdlOperationPerformed(exists ? "REPLACE" : "CREATE");
    }

    private static Table createStatementTable(SqlDialectTranslator.Statement statement, TableSchema describedSchema) {
        Table table = new Table();
        table.setSchema(describedSchema != null ? describedSchema
                : RowCodec.normalizeSchema(new TableSchema(statement.columns())));
        if (statement.timePartitioning() != null) {
            table.getExtra().put("timePartitioning", new LinkedHashMap<>(statement.timePartitioning()));
        }
        if (statement.rangePartitioning() != null) {
            table.getExtra().put("rangePartitioning", new LinkedHashMap<>(statement.rangePartitioning()));
        }
        BigQueryMetadata.validateTable(table.getExtra(), schemaFields(table));
        validateCreateTablePartitioning(statement, table);
        return table;
    }

    private static void validateCreateTablePartitioning(SqlDialectTranslator.Statement statement, Table table) {
        if (statement.partitionExpression() == null
                || statement.partitionExpression() == SqlDialectTranslator.PartitionExpression.INGESTION_TIME
                || statement.partitionExpression() == SqlDialectTranslator.PartitionExpression.RANGE_BUCKET) {
            return;
        }
        Map<String, Object> partitioning = timePartitioning(table);
        String fieldName = partitioning != null && partitioning.get("field") instanceof String field ? field : null;
        TableFieldSchema field = schemaFields(table).stream()
                .filter(candidate -> candidate.getName() != null
                        && candidate.getName().equalsIgnoreCase(fieldName != null ? fieldName : ""))
                .findFirst()
                .orElseThrow(() -> QueryEngine.invalidQuery("Partition field " + fieldName
                        + " is not present in the table schema"));
        String actualType = field.getType();
        boolean valid = switch (statement.partitionExpression()) {
            case BARE_DATE, DATE_TRUNC -> "DATE".equals(actualType);
            case DATE -> "TIMESTAMP".equals(actualType) || "DATETIME".equals(actualType);
            case TIMESTAMP_TRUNC -> "TIMESTAMP".equals(actualType);
            case DATETIME_TRUNC -> "DATETIME".equals(actualType);
            case INGESTION_TIME, RANGE_BUCKET -> true;
        };
        if (!valid) {
            throw QueryEngine.invalidQuery("PARTITION BY " + statement.partitionExpression()
                    + " is not valid for field " + fieldName + " of type " + actualType);
        }
    }

    private static boolean samePartitioning(Table existing, Table replacement) {
        return samePartitioning(existing.getExtra(), replacement.getExtra());
    }

    @SuppressWarnings("unchecked")
    private static boolean samePartitioning(Map<String, Object> existing, Map<String, Object> replacement) {
        Object rawExistingTime = existing.get("timePartitioning");
        Object rawReplacementTime = replacement.get("timePartitioning");
        Map<String, Object> existingTime = rawExistingTime instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
        Map<String, Object> replacementTime = rawReplacementTime instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
        if (existingTime != null || replacementTime != null) {
            return existingTime != null && replacementTime != null
                    && String.valueOf(existingTime.get("type"))
                            .equalsIgnoreCase(String.valueOf(replacementTime.get("type")))
                    && equalPartitionFields(existingTime.get("field"), replacementTime.get("field"));
        }
        Object rawExistingRange = existing.get("rangePartitioning");
        Object rawReplacementRange = replacement.get("rangePartitioning");
        Map<String, Object> existingRange = rawExistingRange instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
        Map<String, Object> replacementRange = rawReplacementRange instanceof Map<?, ?> map
                ? (Map<String, Object>) map : null;
        return existingRange == null && replacementRange == null
                || existingRange != null && replacementRange != null
                && equalPartitionFields(existingRange.get("field"), replacementRange.get("field"))
                && rangeSpec(existingRange).equals(rangeSpec(replacementRange));
    }

    private static boolean equalPartitionFields(Object left, Object right) {
        return left == null && right == null || left != null && right != null
                && String.valueOf(left).equalsIgnoreCase(String.valueOf(right));
    }

    private static void validateUnchangedPartitioning(Map<String, Object> existing, Map<String, Object> candidate) {
        if (!samePartitioning(existing, candidate)) {
            throw GcpException.invalidArgument("The table partitioning specification cannot be changed")
                    .withReason("invalid");
        }
    }

    /**
     * Writes SELECT results into a user destination table following BigQuery's dispositions:
     * CREATE_IF_NEEDED (default) / CREATE_NEVER, and WRITE_EMPTY (default) / WRITE_TRUNCATE /
     * WRITE_TRUNCATE_DATA / WRITE_APPEND.
     */
    private void writeDestination(String projectId, QueryOptions options, BigQuerySqlEngine.Result result) {
        writeRows(projectId, options.destinationTable(),
                options.writeDisposition() != null ? options.writeDisposition() : "WRITE_EMPTY",
                options.createDisposition(), options.schemaUpdateOptions(), options.timePartitioning(),
                options.rangePartitioning(), result);
    }

    /** Writes rows into a destination table following BigQuery's create and write dispositions. */
    private void writeRows(String projectId, TableReference destination, String write, String createDisposition,
                           List<String> schemaUpdateOptions, BigQuerySqlEngine.Result result) {
        writeRows(projectId, destination, write, createDisposition, schemaUpdateOptions, null, null, result);
    }

    private void writeRows(String projectId, TableReference destination, String write, String createDisposition,
                           List<String> schemaUpdateOptions, Map<String, Object> requestedTimePartitioning,
                           Map<String, Object> requestedRangePartitioning, BigQuerySqlEngine.Result result) {
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
        DecoratedDestination decorated = decoratedDestination(destination);
        SqlDialectTranslator.TableRef target = decorated != null ? decorated.target()
                : new SqlDialectTranslator.TableRef(destination.getDatasetId(), destination.getTableId());
        getDataset(projectId, target.datasetId());
        Optional<Table> existing = tableStore.get(tableKey(target.datasetId(), target.tableId()));
        String create = createDisposition != null ? createDisposition : "CREATE_IF_NEEDED";

        if (existing.isEmpty()) {
            if ("CREATE_NEVER".equals(create)) {
                throw GcpException.notFound("Not found: Table " + qualified(projectId, target));
            }
            if (requestedTimePartitioning != null && requestedRangePartitioning != null) {
                throw GcpException.invalidArgument("timePartitioning and rangePartitioning are mutually"
                        + " exclusive").withReason("invalid");
            }
            Table table = new Table();
            table.setSchema(result.schema());
            Map<String, Object> requestedPartitioning = new LinkedHashMap<>();
            if (requestedTimePartitioning != null) {
                requestedPartitioning.put("timePartitioning", requestedTimePartitioning);
            }
            if (requestedRangePartitioning != null) {
                requestedPartitioning.put("rangePartitioning", requestedRangePartitioning);
            }
            table.getExtra().putAll(BigQueryMetadata.writable(requestedPartitioning,
                    BigQueryMetadata.TABLE_FIELDS));
            BigQueryMetadata.validateTable(table.getExtra(), schemaFields(table.getSchema()));
            if (decorated != null) {
                if (requestedTimePartitioning == null && requestedRangePartitioning == null) {
                    throw GcpException.invalidArgument("Creating a decorated destination table requires"
                            + " timePartitioning or rangePartitioning").withReason("invalid");
                }
                validatePartitionDecorator(table, decorated.partitionId());
                List<Map<String, Object>> rows = alignedPartitionRows(projectId, target, table,
                        decorated.partitionId(), result, false);
                putTable(projectId, target, table, List.of());
                writePartition(projectId, target, table, decorated.partitionId(), write, rows);
                return;
            }
            putTable(projectId, target, table, result.rows());
            return;
        }
        Table table = existing.get();
        if (table.viewQuery() != null) {
            throw QueryEngine.invalidQuery("Cannot write query results to view " + qualified(projectId, target));
        }
        if (requestedTimePartitioning != null || requestedRangePartitioning != null) {
            Map<String, Object> requested = new LinkedHashMap<>();
            if (requestedTimePartitioning != null) {
                requested.put("timePartitioning", requestedTimePartitioning);
            }
            if (requestedRangePartitioning != null) {
                requested.put("rangePartitioning", requestedRangePartitioning);
            }
            validateUnchangedPartitioning(table.getExtra(),
                    BigQueryMetadata.writable(requested, BigQueryMetadata.TABLE_FIELDS));
        }
        if (decorated != null) {
            validatePartitionDecorator(table, decorated.partitionId());
            boolean relaxed = schemaUpdateOptions.stream()
                    .anyMatch(option -> "ALLOW_FIELD_RELAXATION".equalsIgnoreCase(option));
            boolean relaxPartitionSchema = relaxed && ("WRITE_APPEND".equals(write)
                    || "WRITE_TRUNCATE".equals(write) || "WRITE_TRUNCATE_DATA".equals(write));
            List<Map<String, Object>> rows = alignedPartitionRows(projectId, target, table,
                    decorated.partitionId(), result, relaxPartitionSchema);
            writePartition(projectId, target, table, decorated.partitionId(), write, rows);
            relaxSchemaIfRequested(projectId, target, table, relaxPartitionSchema);
            return;
        }
        List<Map<String, Object>> current = storedRows(projectId, target.datasetId(), target.tableId());
        boolean relaxed = schemaUpdateOptions.stream()
                .anyMatch(option -> "ALLOW_FIELD_RELAXATION".equalsIgnoreCase(option));
        switch (write) {
            case "WRITE_TRUNCATE" -> {
                BigQueryMetadata.validateTable(table.getExtra(), schemaFields(result.schema()));
                replaceRowsAndSchema(target, table, result.schema(), result.rows());
            }
            case "WRITE_TRUNCATE_DATA" -> {
                List<Map<String, Object>> rows = alignRows(projectId, target, table, result, relaxed);
                relaxSchemaIfRequested(projectId, target, table, relaxed);
                replaceRows(projectId, target, rows);
            }
            case "WRITE_APPEND" -> {
                List<Map<String, Object>> rows = alignRows(projectId, target, table, result, relaxed);
                relaxSchemaIfRequested(projectId, target, table, relaxed);
                appendRows(projectId, target.datasetId(), target.tableId(), rows);
            }
            default -> {
                if (!current.isEmpty()) {
                    throw GcpException.alreadyExists("Already Exists: Table " + qualified(projectId, target))
                            .withReason("duplicate");
                }
                List<Map<String, Object>> rows = alignRows(projectId, target, table, result, false);
                replaceRows(projectId, target, rows);
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
        if (isIngestionTimePartitioned(table) && !rows.isEmpty()) {
            data.getPartitionRows().put(currentIngestionPartitionId(table), new ArrayList<>(rows));
        } else {
            data.setRows(new ArrayList<>(rows));
        }
        dataStore.put(tableKey(target.datasetId(), target.tableId()), data);
    }

    private void replaceRows(String projectId, SqlDialectTranslator.TableRef target, List<Map<String, Object>> rows) {
        String key = tableKey(target.datasetId(), target.tableId());
        Table table = getTable(projectId, target.datasetId(), target.tableId());
        StoredTableData previous = dataStore.get(key).orElse(null);
        StoredTableData data = replacementData(table, rows, previous);
        dataStore.put(key, data);
        table.setNumRows(String.valueOf(rows.size()));
        table.setLastModifiedTime(nowMillis());
        table.setEtag(etag());
        tableStore.put(key, table);
    }

    private void replaceRowsAndSchema(SqlDialectTranslator.TableRef target, Table table, TableSchema schema,
                                      List<Map<String, Object>> rows) {
        String key = tableKey(target.datasetId(), target.tableId());
        StoredTableData previous = dataStore.get(key).orElse(null);
        StoredTableData data = replacementData(table, rows, previous);
        table.setSchema(schema);
        table.setNumRows(String.valueOf(rows.size()));
        table.setLastModifiedTime(nowMillis());
        table.setEtag(etag());
        dataStore.put(key, data);
        tableStore.put(key, table);
    }

    private static StoredTableData replacementData(Table table, List<Map<String, Object>> rows,
                                                    StoredTableData previous) {
        StoredTableData data = new StoredTableData();
        if (isIngestionTimePartitioned(table)) {
            for (Map<String, Object> row : rows) {
                Map<String, Object> stored = new LinkedHashMap<>(row);
                Object partitionDate = stored.remove(PARTITION_DATE);
                Object partitionTime = stored.remove(PARTITION_TIME);
                Object originalPartitionTime = stored.remove(DML_ORIGINAL_PARTITION_TIME);
                Object originalUnpartitioned = stored.remove(DML_ORIGINAL_UNPARTITIONED);
                Object partitionValue = partitionTime != null ? partitionTime : partitionDate;
                DmlPartition partition = partitionValue != null
                        ? dmlPartition(table, partitionValue) : null;
                String partitionId = partition != null ? partition.id() : null;
                DmlPartition original = originalPartitionTime != null
                        ? dmlPartition(table, originalPartitionTime) : null;
                if (partition != null && (original == null || !partition.id().equals(original.id()))) {
                    validateDmlPartitionRange(partition.date());
                }
                if (partitionId == null && Boolean.TRUE.equals(originalUnpartitioned)) {
                    data.getRows().add(stored);
                } else {
                    data.getPartitionRows().computeIfAbsent(
                            partitionId != null ? partitionId : currentIngestionPartitionId(table),
                            ignored -> new ArrayList<>()).add(stored);
                }
            }
        } else {
            data.setRows(new ArrayList<>(rows));
        }
        // DML rewrites the rows but not what Storage Write streams already delivered.
        if (previous != null) {
            data.setStreamRows(previous.getStreamRows());
        }
        return data;
    }

    static boolean isIngestionTimePartitioned(Table table) {
        Object raw = table.getExtra().get("timePartitioning");
        return raw instanceof Map<?, ?> partitioning && !partitioning.containsKey("field");
    }

    static boolean hasPartitionDatePseudocolumn(Table table) {
        Map<String, Object> partitioning = timePartitioning(table);
        return isIngestionTimePartitioned(table) && partitioning != null
                && "DAY".equalsIgnoreCase(String.valueOf(partitioning.get("type")));
    }

    private record DmlPartition(String id, LocalDate date) {}

    private static DmlPartition dmlPartition(Table table, Object value) {
        Map<String, Object> partitioning = timePartitioning(table);
        if (partitioning == null) {
            return null;
        }
        String type = String.valueOf(partitioning.get("type"));
        LocalDateTime dateTime = partitionDateTime(String.valueOf(value));
        String partitionId = timePartitionId(value, type);
        if (!dateTime.equals(timePartitionBoundary(table, partitionId))) {
            throw QueryEngine.invalidQuery("DML partition values must match the exact partition boundary");
        }
        return new DmlPartition(partitionId, dateTime.toLocalDate());
    }

    private static void validateDmlPartitionRange(LocalDate date) {
        if (date.isBefore(MIN_DML_PARTITION_DATE) || date.isAfter(MAX_DML_PARTITION_DATE)) {
            throw QueryEngine.invalidQuery("DML partition values must be between 1970-01-01 and 2159-12-31");
        }
    }

    private static String partitionIdForRow(Table table, Map<String, Object> row) {
        Map<String, Object> time = timePartitioning(table);
        if (time != null && time.get("field") instanceof String field) {
            Object value = row.get(BigQueryMetadata.actualFieldName(table, field));
            return value != null ? timeFieldPartitionId(value, String.valueOf(time.get("type"))) : "__NULL__";
        }
        Map<String, Object> range = rangePartitioning(table);
        if (range != null && range.get("field") instanceof String field) {
            Object value = row.get(BigQueryMetadata.actualFieldName(table, field));
            if (value == null) {
                return "__NULL__";
            }
            BigInteger number;
            try {
                number = new BigInteger(String.valueOf(value));
            } catch (NumberFormatException expected) {
                // Legacy rows with malformed range values remain readable in __UNPARTITIONED__.
                return "__UNPARTITIONED__";
            }
            RangeSpec spec = rangeSpec(range);
            if (number.compareTo(spec.start()) < 0 || number.compareTo(spec.end()) >= 0) {
                return "__UNPARTITIONED__";
            }
            return spec.start().add(number.subtract(spec.start()).divide(spec.interval()).multiply(spec.interval()))
                    .toString();
        }
        return null;
    }

    static String timeFieldPartitionId(Object value, String type) {
        LocalDateTime dateTime = partitionDateTime(String.valueOf(value));
        LocalDate date = dateTime.toLocalDate();
        if (date.isBefore(MIN_COLUMN_PARTITION_DATE) || date.isAfter(MAX_COLUMN_PARTITION_DATE)) {
            return "__UNPARTITIONED__";
        }
        return timePartitionId(value, type);
    }

    static void validateStreamingPartitionValue(Table table, Map<String, Object> row) {
        Map<String, Object> partitioning = timePartitioning(table);
        if (partitioning == null || !(partitioning.get("field") instanceof String field)) {
            return;
        }
        Object value = row.get(BigQueryMetadata.actualFieldName(table, field));
        if (value == null) {
            return;
        }
        LocalDate partitionDate = partitionDateTime(String.valueOf(value)).toLocalDate();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (partitionDate.isBefore(today.minusYears(10)) || partitionDate.isAfter(today.plusYears(1))) {
            throw GcpException.invalidArgument("Streaming time partition value must be between 10 years in the past"
                    + " and 1 year in the future").withReason("invalid");
        }
    }

    private static List<Map<String, Object>> selectedPartitionRows(Table table, StoredTableData data,
                                                                   String partitionId) {
        List<Map<String, Object>> selected = new ArrayList<>(
                data.getPartitionRows().getOrDefault(partitionId, List.of()));
        synchronized (data.getRows()) {
            data.getRows().stream().filter(row -> partitionId.equals(partitionIdForRow(table, row)))
                    .forEach(selected::add);
        }
        return List.copyOf(selected);
    }

    private static void validateRowsBelongToPartition(Table table, String partitionId,
                                                       List<Map<String, Object>> rows) {
        if (isIngestionTimePartitioned(table)) {
            return;
        }
        if (rows.stream().anyMatch(row -> !partitionId.equals(partitionIdForRow(table, row)))) {
            throw GcpException.invalidArgument("Rows do not belong to destination partition " + partitionId)
                    .withReason("invalid");
        }
    }

    private static List<Map<String, Object>> allRows(StoredTableData data) {
        synchronized (data) {
            List<Map<String, Object>> rows;
            synchronized (data.getRows()) {
                rows = new ArrayList<>(data.getRows());
            }
            data.getPartitionRows().values().forEach(rows::addAll);
            return List.copyOf(rows);
        }
    }

    /** Rows for DuckDB staging, including ingestion-time partition pseudocolumns. */
    List<Map<String, Object>> stagingRows(String projectId, String datasetId, String tableId) {
        Table table = getTable(projectId, datasetId, tableId);
        StoredTableData data = dataStore.get(tableKey(datasetId, tableId)).orElseGet(StoredTableData::new);
        if (!isIngestionTimePartitioned(table)) {
            return allRows(data);
        }
        synchronized (data) {
            List<Map<String, Object>> rows = new ArrayList<>();
            synchronized (data.getRows()) {
                data.getRows().forEach(row -> rows.add(new LinkedHashMap<>(row)));
            }
            data.getPartitionRows().forEach((partitionId, partitionRows) -> {
                LocalDateTime boundary = timePartitionBoundary(table, partitionId);
                String time = DateTimeFormatter.ISO_INSTANT.format(boundary.toInstant(ZoneOffset.UTC));
                partitionRows.forEach(row -> {
                    Map<String, Object> staged = new LinkedHashMap<>(row);
                    if ("DAY".equalsIgnoreCase(String.valueOf(timePartitioning(table).get("type")))) {
                        staged.put(PARTITION_DATE, boundary.toLocalDate().toString());
                    }
                    staged.put(PARTITION_TIME, time);
                    rows.add(staged);
                });
            });
            return List.copyOf(rows);
        }
    }

    private void appendPartitionRows(String projectId, String datasetId, String tableId, String partitionId,
                                     List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        String key = tableKey(datasetId, tableId);
        synchronized (writeLock) {
            Table table = getTable(projectId, datasetId, tableId);
            StoredTableData data = dataStore.get(key).orElseGet(StoredTableData::new);
            synchronized (data) {
                data.getPartitionRows().computeIfAbsent(partitionId, ignored -> new ArrayList<>()).addAll(rows);
            }
            dataStore.put(key, data);
            table.setNumRows(String.valueOf(allRows(data).size()));
            table.setLastModifiedTime(nowMillis());
            table.setEtag(etag());
            tableStore.put(key, table);
        }
    }

    private static void validateStreamingPartitionDecorator(Table table, String partitionId) {
        Map<String, Object> partitioning = timePartitioning(table);
        if (!isIngestionTimePartitioned(table) || partitioning == null
                || !"DAY".equalsIgnoreCase(String.valueOf(partitioning.get("type")))) {
            throw GcpException.invalidArgument("tabledata.insertAll partition decorators require a daily"
                    + " ingestion-time partitioned table").withReason("invalid");
        }
        validateTimePartitionId(partitionId, "DAY");
        LocalDate partition = LocalDate.parse(partitionId, DAY_PARTITION);
        long days = ChronoUnit.DAYS.between(LocalDate.now(ZoneOffset.UTC), partition);
        if (days < -31 || days > 16) {
            throw GcpException.invalidArgument("Streaming partition decorator is outside the supported range: "
                    + "31 days in the past through 16 days in the future").withReason("invalid");
        }
    }

    private static String currentIngestionPartitionId(Table table) {
        return timePartitionId(Instant.now().toString(), String.valueOf(timePartitioning(table).get("type")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> timePartitioning(Table table) {
        Object raw = table.getExtra().get("timePartitioning");
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rangePartitioning(Table table) {
        Object raw = table.getExtra().get("rangePartitioning");
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static void validatePartitionDecorator(Table table, String partitionId) {
        Map<String, Object> time = timePartitioning(table);
        if (time != null) {
            validateTimePartitionId(partitionId, String.valueOf(time.get("type")));
            return;
        }
        Map<String, Object> range = rangePartitioning(table);
        if (range != null) {
            BigInteger id;
            try {
                id = new BigInteger(partitionId);
            } catch (NumberFormatException e) {
                throw invalidPartitionDecorator(partitionId, "an integer range boundary");
            }
            RangeSpec spec = rangeSpec(range);
            if (id.compareTo(spec.start()) < 0 || id.compareTo(spec.end()) >= 0
                    || !id.subtract(spec.start()).mod(spec.interval()).equals(BigInteger.ZERO)) {
                throw invalidPartitionDecorator(partitionId, "a configured integer range boundary");
            }
            return;
        }
        throw GcpException.invalidArgument("Partition decorator requires a partitioned table").withReason("invalid");
    }

    private static void validateTimePartitionId(String partitionId, String type) {
        try {
            switch (type.toUpperCase(Locale.ROOT)) {
                case "HOUR" -> LocalDateTime.parse(partitionId, HOUR_PARTITION);
                case "DAY" -> LocalDate.parse(partitionId, DAY_PARTITION);
                case "MONTH" -> YearMonth.parse(partitionId, MONTH_PARTITION);
                case "YEAR" -> Year.parse(partitionId, YEAR_PARTITION);
                default -> throw GcpException.invalidArgument("Unsupported time partitioning type: " + type)
                        .withReason("invalid");
            }
        } catch (DateTimeParseException e) {
            String expected = switch (type.toUpperCase(Locale.ROOT)) {
                case "HOUR" -> "YYYYMMDDHH";
                case "DAY" -> "YYYYMMDD";
                case "MONTH" -> "YYYYMM";
                case "YEAR" -> "YYYY";
                default -> type;
            };
            throw invalidPartitionDecorator(partitionId, expected);
        }
    }

    private static GcpException invalidPartitionDecorator(String partitionId, String expected) {
        return GcpException.invalidArgument("Invalid partition decorator " + partitionId + "; expected " + expected)
                .withReason("invalid");
    }

    private static String timePartitionId(Object value, String type) {
        LocalDateTime dateTime = partitionDateTime(String.valueOf(value));
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "HOUR" -> dateTime.format(HOUR_PARTITION);
            case "DAY" -> dateTime.format(DAY_PARTITION);
            case "MONTH" -> dateTime.format(MONTH_PARTITION);
            case "YEAR" -> dateTime.format(YEAR_PARTITION);
            default -> throw GcpException.invalidArgument("Unsupported time partitioning type: " + type)
                    .withReason("invalid");
        };
    }

    private static LocalDateTime partitionDateTime(String value) {
        try {
            return DuckTypes.storedDateTime(value);
        } catch (DateTimeException | ArithmeticException invalid) {
            throw GcpException.invalidArgument("Invalid partitioning value: " + value).withReason("invalid");
        }
    }

    private static LocalDateTime timePartitionBoundary(Table table, String partitionId) {
        Map<String, Object> partitioning = timePartitioning(table);
        String type = String.valueOf(partitioning.get("type"));
        validateTimePartitionId(partitionId, type);
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "HOUR" -> LocalDateTime.parse(partitionId, HOUR_PARTITION);
            case "DAY" -> LocalDate.parse(partitionId, DAY_PARTITION).atStartOfDay();
            case "MONTH" -> YearMonth.parse(partitionId, MONTH_PARTITION).atDay(1).atStartOfDay();
            case "YEAR" -> Year.parse(partitionId, YEAR_PARTITION).atDay(1).atStartOfDay();
            default -> throw new IllegalStateException("validated partition type " + type);
        };
    }

    @SuppressWarnings("unchecked")
    private static RangeSpec rangeSpec(Map<?, ?> partitioning) {
        if (!(partitioning.get("range") instanceof Map<?, ?> rawRange)) {
            throw GcpException.invalidArgument("rangePartitioning.range is required").withReason("invalid");
        }
        Map<String, Object> range = (Map<String, Object>) rawRange;
        try {
            BigInteger start = new BigInteger(String.valueOf(range.get("start")));
            BigInteger end = new BigInteger(String.valueOf(range.get("end")));
            BigInteger interval = new BigInteger(String.valueOf(range.get("interval")));
            if (interval.signum() <= 0 || end.compareTo(start) <= 0) {
                throw new NumberFormatException("invalid range");
            }
            return new RangeSpec(start, end, interval);
        } catch (NumberFormatException e) {
            throw GcpException.invalidArgument("rangePartitioning requires integer start, end, and positive interval")
                    .withReason("invalid");
        }
    }

    private record DecoratedDestination(SqlDialectTranslator.TableRef target, String partitionId) {}

    private record RangeSpec(BigInteger start, BigInteger end, BigInteger interval) {}

    private static String qualified(String projectId, SqlDialectTranslator.TableRef ref) {
        return projectId + ":" + ref.datasetId() + "." + ref.tableId();
    }

    BigQuerySqlEngine.Tables tables(String projectId) {
        return tables(projectId, null);
    }

    private BigQuerySqlEngine.Tables tables(String projectId, JobLocation jobLocation) {
        return new BigQuerySqlEngine.Tables() {
            @Override
            public Table table(String datasetId, String tableId) {
                if (jobLocation != null) {
                    datasetStore.get(datasetId).ifPresent(jobLocation::admit);
                }
                return getTable(projectId, datasetId, tableId);
            }

            @Override
            public List<Table> listTables(String datasetId) {
                return BigQueryService.this.listTables(projectId, datasetId);
            }

            @Override
            public List<Map<String, Object>> rows(String datasetId, String tableId) {
                // A snapshot for the same reason storedRows takes one: the engines iterate these
                // rows (to estimate bytes, and to evaluate in mock mode) while insertAll can be
                // appending to the very same list.
                return storedRows(projectId, datasetId, tableId);
            }

            @Override
            public List<Map<String, Object>> informationSchema(InformationSchema.Ref ref) {
                if (jobLocation != null) {
                    if (ref.dataset() != null) {
                        datasetStore.get(ref.dataset()).ifPresent(jobLocation::admit);
                    } else if (ref.region() != null) {
                        jobLocation.adoptRegion(ref.region());
                    }
                }
                return informationSchemaRows(projectId, ref.view(), ref.dataset(), ref.region());
            }
        };
    }

    /**
     * Rows of an INFORMATION_SCHEMA view over one dataset ({@code datasetId}) or every dataset in a
     * region; a dataset-qualified view of a missing dataset is a 404, as in BigQuery.
     */
    public List<Map<String, Object>> informationSchemaRows(String projectId, String view, String datasetId,
                                                           String region) {
        if (!InformationSchema.isView(view)) {
            throw GcpException.notFound("Not found: INFORMATION_SCHEMA." + view);
        }
        List<Dataset> datasets = datasetId != null
                ? List.of(getDataset(projectId, datasetId))
                : listDatasets(projectId).stream().filter(d -> InformationSchema.inRegion(d, region)).toList();
        Map<String, List<Table>> tables = new LinkedHashMap<>();
        Map<String, StoredTableData> tableData = new LinkedHashMap<>();
        if (!InformationSchema.regionOnly(view)) {
            for (Dataset dataset : datasets) {
                String id = dataset.getDatasetReference().getDatasetId();
                List<Table> datasetTables = listTables(projectId, id);
                tables.put(id, datasetTables);
                for (Table table : datasetTables) {
                    String tableId = table.getTableReference().getTableId();
                    dataStore.get(tableKey(id, tableId)).ifPresent(data -> tableData.put(tableKey(id, tableId), data));
                }
            }
        }
        return InformationSchema.rows(view, projectId, datasets, tables, tableData);
    }

    // ── Load jobs ────────────────────────────────────────────────────────────────

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> LOAD_FORMATS = Set.of("CSV", "NEWLINE_DELIMITED_JSON", "PARQUET");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw GcpException.invalidArgument(field + " must be an object").withReason("invalid");
        }
        return new LinkedHashMap<>((Map<String, Object>) map);
    }

    /**
     * Runs a load job synchronously. Data comes from {@code sourceUris} in floci's GCS or, for
     * media uploads, from {@code uploadedData}. Configuration errors are HTTP 400s; data and
     * destination errors are recorded in the DONE job's status, as BigQuery reports them.
     */
    @SuppressWarnings("unchecked")
    public StoredJob load(String projectId, String location, String jobId, Map<String, Object> loadConfig,
                          byte[] uploadedData) {
        Map<String, Object> config = loadConfig != null ? new LinkedHashMap<>(loadConfig) : new LinkedHashMap<>();
        if (!(config.get("destinationTable") instanceof Map<?, ?> destinationMap)) {
            throw GcpException.invalidArgument("Required parameter is missing: destinationTable").withReason("invalid");
        }
        Map<String, Object> destination = new LinkedHashMap<>((Map<String, Object>) destinationMap);
        destination.putIfAbsent("projectId", projectId);
        config.put("destinationTable", destination);
        List<String> sourceUris = config.get("sourceUris") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        if (uploadedData == null && sourceUris.isEmpty()) {
            throw GcpException.invalidArgument("Required parameter is missing: sourceUris").withReason("invalid");
        }
        Integer skipLeadingRows = integerOption(config.get("skipLeadingRows"),
                "job.configuration.load.skip_leading_rows.value");
        if (skipLeadingRows != null) {
            config.put("skipLeadingRows", skipLeadingRows);
        }

        StoredJob job = new StoredJob();
        job.setJobId(reserveJobId(projectId, jobId));
        job.setProjectId(projectId);
        job.setLocation(location != null && !location.isBlank() ? location : "US");
        job.setJobType("LOAD");
        job.setLoadConfiguration(config);
        job.setState("DONE");
        job.setCreationTime(nowMillis());

        List<String> fileIds = new ArrayList<>();
        try {
            synchronized (writeLock) {
                runLoad(projectId, config, destination, sourceUris, uploadedData, fileIds, job);
            }
        } catch (GcpException e) {
            job.setErrorReason(loadErrorReason(e));
            job.setErrorMessage(e.getMessage());
        } catch (RuntimeException e) {
            // The job id is already reserved, so the job has to be stored whatever went wrong.
            // Returning 500 here would leave jobs.get answering 404 for a job the client holds.
            LOG.warnf(e, "load job %s failed", job.getJobId());
            job.setErrorReason("invalid");
            job.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            loadFiles.release(fileIds);
        }
        jobStore.put(job.getJobId(), job);
        return job;
    }

    /**
     * A load job never reports {@code invalidQuery}: that reason is for "an invalid query", while
     * {@code invalid} covers "any type of invalid input other than an invalid query, such as ...
     * an invalid table schema". The destination checks the load path shares with query
     * destination tables raise {@code invalidQuery}, so it is mapped here.
     */
    private static String loadErrorReason(GcpException e) {
        if (e.getReason() == null) {
            return errorReason(e);
        }
        return "invalidQuery".equals(e.getReason()) ? "invalid" : e.getReason();
    }

    private static String errorReason(GcpException e) {
        return switch (e.getHttpStatus()) {
            case 404 -> "notFound";
            case 409 -> "duplicate";
            default -> "invalid";
        };
    }

    @SuppressWarnings("unchecked")
    private void runLoad(String projectId, Map<String, Object> config, Map<String, Object> destination,
                         List<String> sourceUris, byte[] uploadedData, List<String> fileIds, StoredJob job) {
        String format = config.get("sourceFormat") instanceof String f ? f.toUpperCase(Locale.ROOT) : "CSV";
        if (!LOAD_FORMATS.contains(format)) {
            throw GcpException.invalidArgument("Source format " + format + " is not supported by the floci BigQuery"
                    + " emulator; use CSV, NEWLINE_DELIMITED_JSON or PARQUET").withReason("invalid");
        }
        TableReference destinationRef = new TableReference((String) destination.get("projectId"),
                (String) destination.get("datasetId"), (String) destination.get("tableId"));
        if (destinationRef.getProjectId() != null && !destinationRef.getProjectId().equals(projectId)) {
            throw GcpException.invalidArgument("Cross-project load destinations are not supported by the floci"
                    + " BigQuery emulator").withReason("invalid");
        }
        getDataset(projectId, destinationRef.getDatasetId());

        List<byte[]> files = uploadedData != null ? List.of(uploadedData) : readSourceUris(sourceUris);
        long inputBytes = 0;
        for (byte[] file : files) {
            inputBytes += file.length;
            fileIds.add(loadFiles.register(file));
        }

        DecoratedDestination decorated = decoratedDestination(destinationRef);
        SqlDialectTranslator.TableRef schemaTarget = decorated != null ? decorated.target()
                : new SqlDialectTranslator.TableRef(destinationRef.getDatasetId(), destinationRef.getTableId());
        Optional<Table> existing = tableStore.get(tableKey(schemaTarget.datasetId(), schemaTarget.tableId()));
        boolean autodetect = Boolean.TRUE.equals(config.get("autodetect"));
        List<TableFieldSchema> schema = null;
        if (config.get("schema") instanceof Map<?, ?> schemaMap) {
            TableSchema explicit = JSON.convertValue(schemaMap, TableSchema.class);
            schema = RowCodec.normalizeSchema(explicit).getFields();
        } else if (!autodetect && existing.isPresent() && existing.get().getSchema() != null
                && existing.get().getSchema().getFields() != null && !existing.get().getSchema().getFields().isEmpty()) {
            schema = existing.get().getSchema().getFields();
        } else if (!autodetect && !format.equals("PARQUET")) {
            throw GcpException.invalidArgument("No schema specified on job or table.").withReason("invalid");
        }

        int maxBadRecords = config.get("maxBadRecords") instanceof Number n ? n.intValue() : 0;
        boolean ignoreUnknownValues = Boolean.TRUE.equals(config.get("ignoreUnknownValues"));
        BigQuerySqlEngine.Result result;
        long badRecords = 0;
        if (format.equals("NEWLINE_DELIMITED_JSON") && schema != null) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (byte[] file : files) {
                for (String line : new String(file, StandardCharsets.UTF_8).split("\\r?\\n")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    try {
                        Map<String, Object> json = JSON.readValue(line, Map.class);
                        if (!RowCodec.normalizeRow(new TableSchema(schema), json, ignoreUnknownValues, true, row)
                                .isEmpty()) {
                            badRecords++;
                            continue;
                        }
                    } catch (IOException e) {
                        badRecords++;
                        continue;
                    }
                    rows.add(row);
                }
            }
            if (badRecords > maxBadRecords) {
                throw GcpException.invalidArgument("Error while reading data, error message: JSON table encountered"
                        + " too many errors, giving up. Rows: " + (rows.size() + badRecords) + "; errors: " + badRecords
                        + ".").withReason("invalid");
            }
            result = new BigQuerySqlEngine.Result(new TableSchema(schema), rows, "LOAD", 0);
        } else {
            Integer skip = config.get("skipLeadingRows") instanceof Number n ? n.intValue() : null;
            result = engine.readFiles(new BigQuerySqlEngine.LoadSource(projectId, format, fileIds,
                    format.equals("PARQUET") && config.get("schema") == null ? null : schema, skip,
                    (String) config.get("fieldDelimiter"), (String) config.get("quote"),
                    Boolean.TRUE.equals(config.get("allowJaggedRows")), maxBadRecords > 0,
                    (String) config.get("nullMarker"), (String) config.get("encoding")));
        }

        String write = config.get("writeDisposition") instanceof String w ? w : "WRITE_APPEND";
        List<String> schemaUpdateOptions = config.get("schemaUpdateOptions") instanceof List<?> list
                ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                : List.of();
        writeRows(projectId, destinationRef, write, (String) config.get("createDisposition"), schemaUpdateOptions,
                mapValue(config.get("timePartitioning"), "timePartitioning"),
                mapValue(config.get("rangePartitioning"), "rangePartitioning"), result);

        long outputBytes = 0;
        for (Map<String, Object> row : result.rows()) {
            try {
                outputBytes += JSON.writeValueAsBytes(row).length;
            } catch (IOException e) {
                throw GcpException.internal("Could not size loaded rows: " + e.getMessage());
            }
        }
        Map<String, String> stats = new LinkedHashMap<>();
        stats.put("inputFiles", String.valueOf(files.size()));
        stats.put("inputFileBytes", String.valueOf(inputBytes));
        stats.put("outputRows", String.valueOf(result.rows().size()));
        stats.put("outputBytes", String.valueOf(outputBytes));
        stats.put("badRecords", String.valueOf(badRecords));
        job.setLoadStatistics(stats);
    }

    /** Reads {@code gs://bucket/object} URIs from floci's GCS; one {@code *} wildcard after the bucket. */
    private List<byte[]> readSourceUris(List<String> uris) {
        if (gcsService == null || (serviceRegistry != null && !serviceRegistry.isEnabled("gcs"))) {
            throw GcpException.invalidArgument("Loading from gs:// URIs needs the Cloud Storage service to be enabled")
                    .withReason("invalid");
        }
        List<byte[]> files = new ArrayList<>();
        for (String uri : uris) {
            if (!uri.startsWith("gs://") || uri.indexOf('/', 5) < 0) {
                throw GcpException.invalidArgument("Invalid source URI " + uri
                        + "; the floci BigQuery emulator loads from gs://bucket/object").withReason("invalid");
            }
            String bucket = uri.substring(5, uri.indexOf('/', 5));
            String object = uri.substring(uri.indexOf('/', 5) + 1);
            if (object.chars().filter(c -> c == '*').count() > 1) {
                throw GcpException.invalidArgument("Source URI " + uri + " may contain only one '*' wildcard")
                        .withReason("invalid");
            }
            List<String> names;
            if (object.contains("*")) {
                Pattern pattern = Pattern.compile(
                        Pattern.quote(object).replace("*", "\\E.*\\Q"));
                names = gcsService.listObjects(bucket).stream().map(GcsObjectMeta::getName)
                        .filter(name -> pattern.matcher(name).matches()).sorted().toList();
            } else {
                names = List.of(object);
            }
            if (names.isEmpty()) {
                throw GcpException.notFound("Not found: URI " + uri);
            }
            for (String name : names) {
                try {
                    files.add(gcsService.getObjectData(bucket, name));
                } catch (GcpException e) {
                    if (e.getHttpStatus() != 404) {
                        throw e;
                    }
                    throw GcpException.notFound("Not found: URI gs://" + bucket + "/" + name);
                }
            }
        }
        return files;
    }

    // ── Storage Read API ─────────────────────────────────────────────────────────

    /** Rows (stored representation) of a Storage Read session's snapshot query. */
    List<Map<String, Object>> readRows(String projectId, String sql) {
        return engine.execute(new BigQuerySqlEngine.Request(projectId, sql, null, List.of(), null, false),
                tables(projectId)).rows();
    }

    /** Arrow IPC messages of a Storage Read session's snapshot query. */
    DuckClient.ArrowIpc readArrow(String projectId, String sql) {
        return engine.executeArrow(new BigQuerySqlEngine.Request(projectId, sql, null, List.of(), null, false),
                tables(projectId));
    }

    /**
     * The location a query job runs in. An explicit {@code jobReference.location} /
     * {@code QueryRequest.location} is kept; otherwise the job takes the location of the first
     * dataset the query references ("the query is used for determining the processing location"),
     * falling back to {@code US}. Every referenced dataset must then live in that location, compared
     * case-insensitively, or the dataset is reported as not found there.
     */
    private static final class JobLocation {

        private final String projectId;
        private String location;

        JobLocation(String projectId, String requested) {
            this.projectId = projectId;
            this.location = requested != null && !requested.isBlank() ? requested : null;
        }

        void admit(Dataset dataset) {
            String datasetLocation = dataset.getLocation() != null ? dataset.getLocation() : "US";
            if (location == null) {
                location = datasetLocation;
                return;
            }
            if (!location.equalsIgnoreCase(datasetLocation)) {
                throw GcpException.notFound("Not found: Dataset " + projectId + ":"
                        + dataset.getDatasetReference().getDatasetId() + " was not found in location " + location)
                        .withReason("notFound");
            }
        }

        /**
         * A region-qualified INFORMATION_SCHEMA view ({@code region-eu}) runs in that region when no
         * location is known yet. A conflicting region is not rejected: the references do not say
         * which error BigQuery returns for it.
         */
        void adoptRegion(String region) {
            if (location == null) {
                location = region.indexOf('-') < 0 ? region.toUpperCase(Locale.ROOT) : region;
            }
        }

        String resolved() {
            return location != null ? location : "US";
        }
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
        return getJob(projectId, jobId, null);
    }

    /**
     * jobs.get / getQueryResults / cancel / delete. A {@code location} that is given must match the
     * job's location (case-insensitively); a job in another location is not found there.
     */
    public StoredJob getJob(String projectId, String jobId, String location) {
        boolean located = location != null && !location.isBlank();
        return jobStore.get(jobId)
                .filter(job -> !located || location.equalsIgnoreCase(job.getLocation()))
                .orElseThrow(() -> GcpException.notFound("Not found: Job " + projectId + ":"
                        + (located ? location + "." : "") + jobId).withReason("notFound"));
    }

    public List<StoredJob> listJobs(String projectId) {
        return jobStore.scan(k -> true).stream()
                .sorted((a, b) -> nullSafe(b.getCreationTime()).compareTo(nullSafe(a.getCreationTime())))
                .toList();
    }

    public void deleteJob(String projectId, String jobId) {
        deleteJob(projectId, jobId, null);
    }

    public void deleteJob(String projectId, String jobId, String location) {
        StoredJob job = getJob(projectId, jobId, location);
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

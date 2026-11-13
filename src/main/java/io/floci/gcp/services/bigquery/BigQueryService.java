package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.RequestScopes;
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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    private static final Pattern SCRIPT_DECLARE = Pattern.compile(
            "(?is)^DECLARE\\s+((?:[A-Za-z_][A-Za-z0-9_]*)(?:\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*)*)"
                    + "(?:\\s+(.*))?$");
    private static final Pattern SCRIPT_SET = Pattern.compile(
            "(?is)^SET\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$");
    private static final Pattern SCRIPT_SET_TUPLE = Pattern.compile(
            "(?is)^SET\\s*\\((.*)\\)\\s*=\\s*\\((.*)\\)$");
    /** A typed array literal, whose element type BigQuery keeps instead of inferring one. */
    private static final Pattern SCRIPT_TYPED_ARRAY_LITERAL = Pattern.compile(
            "(?is)^ARRAY\\s*<\\s*([^<>]+?)\\s*>\\s*\\[(.*)]$");
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

    /**
     * Request-level options of {@code jobs.query} / {@code jobs.insert} that shape execution.
     * {@code defaultProjectId} is {@code defaultDataset.projectId}, null when the request leaves it out.
     */
    public record QueryOptions(String sql, String defaultDatasetId, List<Map<String, Object>> queryParameters,
                               String parameterMode, boolean dryRun, Boolean useLegacySql,
                               TableReference destinationTable, String writeDisposition,
                               String createDisposition, List<String> schemaUpdateOptions,
                               Map<String, Object> timePartitioning, Map<String, Object> rangePartitioning,
                               String defaultProjectId, Set<String> scriptIncompatibleOptions) {

        public QueryOptions {
            schemaUpdateOptions = schemaUpdateOptions != null ? schemaUpdateOptions : List.of();
            scriptIncompatibleOptions = scriptIncompatibleOptions != null ? scriptIncompatibleOptions : Set.of();
        }

        public QueryOptions(String sql, String defaultDatasetId, List<Map<String, Object>> queryParameters,
                            String parameterMode, boolean dryRun, Boolean useLegacySql,
                            TableReference destinationTable, String writeDisposition,
                            String createDisposition, List<String> schemaUpdateOptions,
                            Map<String, Object> timePartitioning, Map<String, Object> rangePartitioning) {
            this(sql, defaultDatasetId, queryParameters, parameterMode, dryRun, useLegacySql, destinationTable,
                    writeDisposition, createDisposition, schemaUpdateOptions, timePartitioning, rangePartitioning,
                    null, Set.of());
        }

        public QueryOptions(String sql, String defaultDatasetId, List<Map<String, Object>> queryParameters,
                            String parameterMode, boolean dryRun, Boolean useLegacySql,
                            TableReference destinationTable, String writeDisposition, String createDisposition,
                            List<String> schemaUpdateOptions) {
            this(sql, defaultDatasetId, queryParameters, parameterMode, dryRun, useLegacySql, destinationTable,
                    writeDisposition, createDisposition, schemaUpdateOptions, null, null, null, Set.of());
        }

        public QueryOptions(String sql, String defaultDatasetId, List<Map<String, Object>> queryParameters,
                            String parameterMode, boolean dryRun, Boolean useLegacySql,
                            TableReference destinationTable, String writeDisposition, String createDisposition) {
            this(sql, defaultDatasetId, queryParameters, parameterMode, dryRun, useLegacySql, destinationTable,
                    writeDisposition, createDisposition, List.of(), null, null, null, Set.of());
        }

        public QueryOptions(String sql, String defaultDatasetId, List<Map<String, Object>> queryParameters,
                            String parameterMode, boolean dryRun, Boolean useLegacySql) {
            this(sql, defaultDatasetId, queryParameters, parameterMode, dryRun, useLegacySql, null, null, null);
        }

        public static QueryOptions of(String sql, String defaultDatasetId) {
            return new QueryOptions(sql, defaultDatasetId, List.of(), null, false, null);
        }

        /**
         * The project holding the default dataset: unqualified table names resolve there, whatever
         * project the job runs in.
         */
        String dataProjectId(String jobProjectId) {
            return defaultDatasetId != null && defaultProjectId != null && !defaultProjectId.isBlank()
                    ? defaultProjectId : jobProjectId;
        }

        /**
         * True when the parameters bind by position: an explicit {@code POSITIONAL} mode or, as the
         * SDKs send them, a list without names and without a mode, the same rule
         * {@link SqlDialectTranslator.QueryParameters} follows.
         */
        boolean positionalParameters() {
            return "POSITIONAL".equalsIgnoreCase(parameterMode)
                    || queryParameters != null && !queryParameters.isEmpty()
                    && queryParameters.getFirst().get("name") == null;
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
        String dataProjectId = options.dataProjectId(projectId);
        boolean script = SqlDialectTranslator.multiStatementScript(options.sql())
                || SqlDialectTranslator.singleStatementScript(options.sql())
                || isExecuteImmediateStatement(options.sql());
        SqlDialectTranslator.ProceduralScript procedural = script
                ? SqlDialectTranslator.proceduralScript(options.sql()) : null;
        if (script) {
            validateScriptConfiguration(options);
        }
        if (script && options.positionalParameters()) {
            throw QueryEngine.invalidQuery(
                    "Positional query parameters are not supported in multi-statement scripts.");
        }
        if (procedural != null) {
            validateProceduralStatements(dataProjectId, projectId, procedural, options.defaultDatasetId(),
                    new SqlDialectTranslator.QueryParameters(options.queryParameters(), options.parameterMode()));
        }
        SqlDialectTranslator.Statement statement = procedural == null
                ? SqlDialectTranslator.scriptFinalStatement(options.sql(), dataProjectId, projectId,
                        options.defaultDatasetId())
                : null;

        StoredJob job = new StoredJob();
        job.setJobId(resolvedJobId);
        job.setProjectId(projectId);
        job.setLocation(jobLocation.resolved());
        job.setQuery(options.sql());
        job.setState("DONE");
        job.setCreationTime(nowMillis());
        job.setStatementType(procedural == null ? statement.statementType() : "SCRIPT");
        job.setTotalBytesProcessed("0");

        if (procedural != null) {
            if (options.dryRun()) {
                // The default dataset may name another project, so resolve temporary objects and tables
                // while the stores see the project the statement reads from.
                ProceduralContext context = inProject(projectId, dataProjectId, () -> validateProceduralScript(
                        dataProjectId, projectId, procedural, options, jobLocation));
                BigQuerySqlEngine.Result result = context.lastQueryResult();
                if (result != null) {
                    job.setSchema(result.schema());
                }
                job.setTotalBytesProcessed(String.valueOf(context.totalBytesProcessed()));
                job.setLocation(jobLocation.resolved());
                job.setDryRun(true);
                return job;
            }
            synchronized (writeLock) {
                executeProceduralScript(dataProjectId, projectId, procedural, options, job, jobLocation);
            }
            job.setLocation(jobLocation.resolved());
            jobStore.put(job.getJobId(), job);
            return job;
        }

        if (statement.kind() != SqlDialectTranslator.StatementKind.QUERY) {
            // The default dataset only qualifies table names, so a schema statement stays in the job's project.
            String targetProjectId = statement.datasetTarget() != null ? projectId : dataProjectId;
            if (options.dryRun()) {
                // "an invalid query will return the same error it would if it wasn't a dry run", so
                // a dry run has to resolve what the statement touches rather than only classify it.
                long validatedBytes = inProject(projectId, targetProjectId, () -> {
                    admitWrittenDataset(statement, jobLocation);
                    return validateStatement(targetProjectId, projectId, statement, options, jobLocation);
                });
                job.setTotalBytesProcessed(String.valueOf(validatedBytes));
                job.setLocation(jobLocation.resolved());
                job.setDryRun(true);
                return job;
            }
            synchronized (writeLock) {
                inProject(projectId, targetProjectId, () -> {
                    admitWrittenDataset(statement, jobLocation);
                    executeStatement(targetProjectId, projectId, statement, options, job, jobLocation);
                    return null;
                });
            }
            job.setLocation(jobLocation.resolved());
            jobStore.put(job.getJobId(), job);
            return job;
        }

        BigQuerySqlEngine.Result result = inProject(projectId, dataProjectId, () -> engine.execute(
                request(dataProjectId, projectId, options.sql(), options, options.dryRun()),
                tables(dataProjectId, jobLocation)));
        admitDestinationDataset(projectId, options, jobLocation);
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
     * "The location of every dataset involved in the request, including those read from and those
     * written to, must match the location of the job as inferred or specified." A missing dataset
     * keeps the statement's own error, and CREATE SCHEMA creates its dataset where the job runs.
     */
    private void admitWrittenDataset(SqlDialectTranslator.Statement statement, JobLocation jobLocation) {
        String datasetId = null;
        if (statement.target() != null) {
            datasetId = statement.target().datasetId();
        } else if (statement.kind() == SqlDialectTranslator.StatementKind.DROP_SCHEMA) {
            datasetId = statement.datasetTarget();
        }
        if (datasetId != null) {
            datasetStore.get(datasetId).ifPresent(jobLocation::admit);
        }
    }

    /** A cross-project or incomplete destination is left for {@link #writeRows} to reject. */
    private void admitDestinationDataset(String projectId, QueryOptions options, JobLocation jobLocation) {
        TableReference destination = options.destinationTable();
        if (destination == null || destination.getDatasetId() == null
                || (destination.getProjectId() != null && !destination.getProjectId().equals(projectId))) {
            return;
        }
        datasetStore.get(destination.getDatasetId()).ifPresent(jobLocation::admit);
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

    private BigQuerySqlEngine.Request request(String projectId, String jobProjectId, String sql, QueryOptions options,
                                              boolean dryRun) {
        return new BigQuerySqlEngine.Request(projectId, sql, options.defaultDatasetId(), options.queryParameters(),
                options.parameterMode(), dryRun, jobProjectId);
    }

    /**
     * Runs {@code action} against {@code dataProjectId}'s stores: they are namespaced by the request's
     * project, which is the job's, so a default dataset in another project needs the switch.
     */
    private static <T> T inProject(String jobProjectId, String dataProjectId, Supplier<T> action) {
        return dataProjectId.equals(jobProjectId) ? action.get() : RequestScopes.callAs(dataProjectId, action);
    }

    // ── DML / DDL ────────────────────────────────────────────────────────────────

    private final Object writeLock = new Object();

    /**
     * The resource checks {@link #executeStatement} makes, without any of its writes. A dry run
     * reports the same error a real run would, so a DML statement against a missing target or
     * source table, or a CREATE VIEW over a missing one, fails here instead of reporting success.
     */
    private long validateStatement(String projectId, String jobProjectId, SqlDialectTranslator.Statement statement,
                                   QueryOptions options, JobLocation jobLocation) {
        SqlDialectTranslator.TableRef target = statement.target();
        long totalBytesProcessed = 0;
        switch (statement.kind()) {
            case INSERT, UPDATE, DELETE, MERGE -> {
                getTable(projectId, target.datasetId(), target.tableId());
                BigQuerySqlEngine.DmlResult result = engine.executeDml(
                        request(projectId, jobProjectId, options.sql(), options, true),
                        tables(projectId, jobLocation));
                totalBytesProcessed = result.totalBytesProcessed();
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
                Dataset destinationDataset = getDataset(projectId, target.datasetId());
                BigQuerySqlEngine.Result described = null;
                if (statement.copySource() != null) {
                    jobLocation.admit(getDataset(projectId, statement.copySource().datasetId()));
                    Table source = getTable(projectId, statement.copySource().datasetId(),
                            statement.copySource().tableId());
                    if (source.viewQuery() != null) {
                        throw QueryEngine.invalidQuery("CREATE TABLE COPY source must be a table, clone, or snapshot");
                    }
                    Table candidate = copiedTableCandidate(statement, source);
                    if (existing.isPresent() && statement.orReplace()
                            && !samePartitioning(existing.get(), candidate)) {
                        throw QueryEngine.invalidQuery(
                                "CREATE OR REPLACE TABLE cannot change the partitioning specification");
                    }
                    if (statement.options() != null) {
                        evaluateOptions(projectId, jobProjectId, statement.options(), options, jobLocation);
                    }
                } else if (statement.querySql() != null) {
                    described = engine.execute(request(projectId, jobProjectId, statement.querySql(),
                            options, true),
                            tables(projectId, jobLocation));
                    rejectDuplicateQueryColumns(statement, described);
                    totalBytesProcessed = described.totalBytesProcessed();
                }
                if (statement.kind() == SqlDialectTranslator.StatementKind.CREATE_TABLE
                        && statement.copySource() == null) {
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
            case ALTER_TABLE, ALTER_VIEW -> {
                if (alterTarget(projectId, statement).isPresent()) {
                    evaluateOptions(projectId, jobProjectId, statement.options(), options, jobLocation);
                }
            }
            default -> { }
        }
        return totalBytesProcessed;
    }

    private ProceduralContext validateProceduralScript(String projectId, String jobProjectId,
                                                        SqlDialectTranslator.ProceduralScript script,
                                                        QueryOptions options, JobLocation jobLocation) {
        ProceduralContext context = new ProceduralContext();
        validateProceduralItemsForDryRun(projectId, jobProjectId, script.items(), options, context, jobLocation);
        return context;
    }

    private boolean validateProceduralItemsForDryRun(String projectId, String jobProjectId,
                                                       List<SqlDialectTranslator.ScriptItem> items,
                                                       QueryOptions options, ProceduralContext context,
                                                       JobLocation jobLocation) {
        for (SqlDialectTranslator.ScriptItem item : items) {
            if (item instanceof SqlDialectTranslator.SqlItem sql
                    && (SCRIPT_DECLARE.matcher(sql.sql().trim()).matches()
                            || SCRIPT_SET.matcher(sql.sql().trim()).matches()
                            || SCRIPT_SET_TUPLE.matcher(sql.sql().trim()).matches()
                            || isExecuteImmediateStatement(sql.sql()))) {
                return false;
            }
            if (item instanceof SqlDialectTranslator.IfBlock || item instanceof SqlDialectTranslator.CaseBlock
                    || item instanceof SqlDialectTranslator.WhileBlock
                    || item instanceof SqlDialectTranslator.LoopBlock || item instanceof SqlDialectTranslator.RepeatBlock
                    || item instanceof SqlDialectTranslator.ForBlock || item instanceof SqlDialectTranslator.LoopControl) {
                return false;
            }
            if (item instanceof SqlDialectTranslator.BeginBlock block) {
                if (!validateProceduralItemsForDryRun(projectId, jobProjectId, block.items(), options, context,
                        jobLocation)) {
                    return false;
                }
            } else if (!validateProceduralItem(projectId, jobProjectId, item, options, context, jobLocation)) {
                return false;
            }
        }
        return true;
    }

    private static void validateProceduralStatements(String projectId, String jobProjectId,
                                                     SqlDialectTranslator.ProceduralScript script,
                                                     String defaultDatasetId,
                                                     SqlDialectTranslator.QueryParameters parameters) {
        validateProceduralItems(projectId, jobProjectId, script.items(), defaultDatasetId,
                new LinkedHashSet<>(), parameters, true);
        validateLoopControlTargets(script.items(), new LinkedHashMap<>(), 0);
    }

    private static void validateLoopControlTargets(List<SqlDialectTranslator.ScriptItem> items,
                                                   Map<String, Boolean> labels, int loopDepth) {
        for (SqlDialectTranslator.ScriptItem item : items) {
            if (item instanceof SqlDialectTranslator.LoopControl control) {
                if (control.label() == null) {
                    if (loopDepth == 0) {
                        throw QueryEngine.invalidQuery(control.continueLoop()
                                ? "CONTINUE is only allowed inside of a loop body"
                                : "BREAK without label is only allowed inside of a loop body");
                    }
                    continue;
                }
                Boolean loopLabel = labels.get(control.label().toLowerCase(Locale.ROOT));
                if (loopLabel == null) {
                    throw QueryEngine.invalidQuery("Loop control label " + control.label()
                            + " does not name an enclosing block or loop.");
                }
                if (control.continueLoop() && !loopLabel) {
                    throw QueryEngine.invalidQuery("CONTINUE cannot target a block label.");
                }
                continue;
            }
            if (item instanceof SqlDialectTranslator.BeginBlock block) {
                validateLabeledScriptBody(block.label(), false, block.items(), labels, loopDepth);
            } else if (item instanceof SqlDialectTranslator.WhileBlock loop) {
                validateLabeledScriptBody(loop.label(), true, loop.items(), labels, loopDepth);
            } else if (item instanceof SqlDialectTranslator.LoopBlock loop) {
                validateLabeledScriptBody(loop.label(), true, loop.items(), labels, loopDepth);
            } else if (item instanceof SqlDialectTranslator.RepeatBlock loop) {
                validateLabeledScriptBody(loop.label(), true, loop.items(), labels, loopDepth);
            } else if (item instanceof SqlDialectTranslator.ForBlock loop) {
                validateLabeledScriptBody(loop.label(), true, loop.items(), labels, loopDepth);
            } else if (item instanceof SqlDialectTranslator.IfBlock conditional) {
                validateLoopControlTargets(conditional.whenTrue(), labels, loopDepth);
                validateLoopControlTargets(conditional.whenFalse(), labels, loopDepth);
            } else if (item instanceof SqlDialectTranslator.CaseBlock caseBlock) {
                for (SqlDialectTranslator.CaseWhen branch : caseBlock.branches()) {
                    validateLoopControlTargets(branch.items(), labels, loopDepth);
                }
                validateLoopControlTargets(caseBlock.otherwise(), labels, loopDepth);
            }
        }
    }

    private static void validateLabeledScriptBody(String label, boolean loop,
                                                  List<SqlDialectTranslator.ScriptItem> items,
                                                  Map<String, Boolean> labels, int loopDepth) {
        String normalized = label != null ? label.toLowerCase(Locale.ROOT) : null;
        if (normalized != null && labels.containsKey(normalized)) {
            throw QueryEngine.invalidQuery("Label " + label + " is already used in an enclosing block or loop.");
        }
        if (normalized != null) {
            labels.put(normalized, loop);
        }
        try {
            validateLoopControlTargets(items, labels, loopDepth + (loop ? 1 : 0));
        } finally {
            if (normalized != null) {
                labels.remove(normalized);
            }
        }
    }

    private static Set<String> validateProceduralItems(String projectId, String jobProjectId,
                                                       List<SqlDialectTranslator.ScriptItem> items,
                                                       String defaultDatasetId, Set<String> temporaryTables,
                                                       SqlDialectTranslator.QueryParameters parameters,
                                                       boolean declarationsAllowed) {
        Set<String> current = new LinkedHashSet<>(temporaryTables);
        boolean proceduralStatementSeen = false;
        for (SqlDialectTranslator.ScriptItem item : items) {
            if (item instanceof SqlDialectTranslator.SqlItem sql) {
                if (SCRIPT_DECLARE.matcher(sql.sql().trim()).matches()) {
                    if (!declarationsAllowed) {
                        throw QueryEngine.invalidQuery(
                                "Variable declarations are allowed only at the start of a script or BEGIN block.");
                    }
                    if (proceduralStatementSeen) {
                        throw QueryEngine.invalidQuery(
                                "Variable declarations must appear at the start of a block.");
                    }
                    continue;
                }
                proceduralStatementSeen = true;
                if (isExecuteImmediateStatement(sql.sql())) {
                    continue;
                }
                if (SCRIPT_SET.matcher(sql.sql().trim()).matches()
                        || SCRIPT_SET_TUPLE.matcher(sql.sql().trim()).matches()) {
                    continue;
                }
                SqlDialectTranslator.validateProceduralStatement(
                        sql.sql(), projectId, jobProjectId, defaultDatasetId, current, parameters);
                SqlDialectTranslator.TemporaryTableDefinition declaration =
                        SqlDialectTranslator.temporaryTableDefinition(sql.sql());
                if (declaration != null) {
                    current.add(declaration.name());
                    continue;
                }
                SqlDialectTranslator.TemporaryTableDrop drop =
                        SqlDialectTranslator.temporaryTableDrop(sql.sql());
                if (drop != null && current.contains(drop.name())) {
                    current.remove(drop.name());
                }
                continue;
            }
            proceduralStatementSeen = true;
            if (item instanceof SqlDialectTranslator.BeginBlock block) {
                Set<String> nested = validateProceduralItems(projectId, jobProjectId, block.items(), defaultDatasetId,
                        current, parameters, true);
                current.clear();
                current.addAll(nested);
                continue;
            }
            if (item instanceof SqlDialectTranslator.LoopControl) {
                continue;
            }
            if (item instanceof SqlDialectTranslator.LoopBlock loop) {
                validateProceduralItems(projectId, jobProjectId, loop.items(), defaultDatasetId, current, parameters,
                        false);
                continue;
            }
            if (item instanceof SqlDialectTranslator.ForBlock loop) {
                SqlDialectTranslator.validateProceduralStatement(loop.query(), projectId, jobProjectId,
                        defaultDatasetId, current, parameters);
                validateProceduralItems(projectId, jobProjectId, loop.items(), defaultDatasetId, current, parameters,
                        false);
                continue;
            }
            if (item instanceof SqlDialectTranslator.RepeatBlock loop) {
                if (!loop.condition().matches("(?i)\\s*[A-Za-z_][A-Za-z0-9_]*\\s*(<=|>=|!=|<>|=|<|>)\\s*"
                        + "([A-Za-z_][A-Za-z0-9_]*|[-+]?[0-9]+)\\s*")) {
                    SqlDialectTranslator.validateProceduralStatement(
                            "SELECT " + loop.condition(), projectId, jobProjectId, defaultDatasetId, current,
                            parameters);
                }
                validateProceduralItems(projectId, jobProjectId, loop.items(), defaultDatasetId, current, parameters,
                        false);
                continue;
            }
            if (item instanceof SqlDialectTranslator.CaseBlock caseBlock) {
                List<Set<String>> branchTemporaryTables = new ArrayList<>();
                for (SqlDialectTranslator.CaseWhen branch : caseBlock.branches()) {
                    SqlDialectTranslator.validateProceduralStatement(
                            "SELECT " + caseBlock.condition(branch), projectId, jobProjectId, defaultDatasetId, current,
                            parameters);
                    branchTemporaryTables.add(validateProceduralItems(
                            projectId, jobProjectId, branch.items(), defaultDatasetId, current, parameters, false));
                }
                branchTemporaryTables.add(validateProceduralItems(
                            projectId, jobProjectId, caseBlock.otherwise(), defaultDatasetId, current, parameters,
                            false));
                current.clear();
                branchTemporaryTables.forEach(current::addAll);
                continue;
            }
            if (item instanceof SqlDialectTranslator.WhileBlock loop) {
                if (!loop.condition().matches("(?i)\\s*[A-Za-z_][A-Za-z0-9_]*\\s*(<=|>=|!=|<>|=|<|>)\\s*"
                        + "([A-Za-z_][A-Za-z0-9_]*|[-+]?[0-9]+)\\s*")) {
                    SqlDialectTranslator.validateProceduralStatement(
                            "SELECT " + loop.condition(), projectId, jobProjectId, defaultDatasetId, current,
                            parameters);
                }
                validateProceduralItems(projectId, jobProjectId, loop.items(), defaultDatasetId, current, parameters,
                        false);
                continue;
            }
            SqlDialectTranslator.IfBlock conditional = (SqlDialectTranslator.IfBlock) item;
            SqlDialectTranslator.validateProceduralStatement(
                    "SELECT " + conditional.condition(), projectId, jobProjectId, defaultDatasetId, current,
                    parameters);
            Set<String> whenTrue = validateProceduralItems(
                    projectId, jobProjectId, conditional.whenTrue(), defaultDatasetId, current, parameters, false);
            Set<String> whenFalse = validateProceduralItems(
                    projectId, jobProjectId, conditional.whenFalse(), defaultDatasetId, current, parameters, false);
            current.clear();
            current.addAll(whenTrue);
            current.addAll(whenFalse);
        }
        return current;
    }

    private boolean validateProceduralItem(String projectId, String jobProjectId, SqlDialectTranslator.ScriptItem item,
                                           QueryOptions options, ProceduralContext context,
                                           JobLocation jobLocation) {
        if (item instanceof SqlDialectTranslator.SqlItem sql) {
            SqlDialectTranslator.TemporaryTableDefinition temporaryTable =
                    SqlDialectTranslator.temporaryTableDefinition(sql.sql());
            if (temporaryTable != null) {
                if (temporaryTable.ifNotExists() && context.temporaryTables().contains(temporaryTable.name())) {
                    context.clearQueryResult();
                    return true;
                }
                // A dry run describes the definition instead of storing it, so a later statement of the
                // same script still resolves the temporary table by name.
                materializeTemporaryTable(projectId, jobProjectId, sql.sql(), temporaryTable.name(),
                        temporaryTable.fields(), options, context, jobLocation, true);
                return true;
            }
            String truncatedTemporaryTable = SqlDialectTranslator.temporaryTruncateTarget(
                    sql.sql(), context.temporaryTables());
            if (truncatedTemporaryTable != null) {
                context.truncateTemporaryTable(truncatedTemporaryTable);
                return true;
            }
            String temporaryTarget = SqlDialectTranslator.temporaryDmlTarget(sql.sql(), context.temporaryTables());
            if (temporaryTarget != null) {
                materializeTemporaryTable(projectId, jobProjectId, sql.sql(), temporaryTarget, List.of(), options,
                        context, jobLocation, true);
                return true;
            }
            validateTemporaryFunctionDeclaration(projectId, jobProjectId, sql.sql(), options, context);
            if (context.applyTemporaryObjectChange(sql.sql(), projectId)) {
                return true;
            }
            SqlDialectTranslator.Statement statement = SqlDialectTranslator.parseStatement(sql.sql(), projectId,
                    jobProjectId, options.defaultDatasetId());
            String effectiveSql = context.prependTo(sql.sql());
            statement = withTemporarySetup(statement, context);
            if (statement.kind() == SqlDialectTranslator.StatementKind.QUERY) {
                BigQuerySqlEngine.Result result = engine.execute(
                        request(projectId, jobProjectId, effectiveSql, options, true), tables(projectId, jobLocation));
                context.recordQueryResult(result);
            } else {
                admitWrittenDataset(statement, jobLocation);
                context.recordBytes(validateStatement(
                        projectId, jobProjectId, statement, leafOptions(effectiveSql, options), jobLocation));
                context.clearQueryResult();
            }
            return statement.kind() == SqlDialectTranslator.StatementKind.QUERY || statement.isDml();
        }
        throw new IllegalStateException("Procedural dry-run validation must stop before control flow");
    }

    private void executeProceduralScript(String projectId, String jobProjectId,
                                         SqlDialectTranslator.ProceduralScript script,
                                         QueryOptions options, StoredJob job, JobLocation jobLocation) {
        ProceduralContext context = new ProceduralContext();
        try {
            // A script's default dataset may name another project, so the stores must see the project
            // its statements read from. Saved results stay in the job's project below.
            inProject(jobProjectId, projectId, () -> {
                for (SqlDialectTranslator.ScriptItem item : script.items()) {
                    executeProceduralItem(projectId, jobProjectId, item, options, job, context, jobLocation);
                }
                return null;
            });
        } catch (LoopControlSignal signal) {
            throw QueryEngine.invalidQuery(signal.label != null
                    ? "Loop control label " + signal.label + " does not name an enclosing block or loop."
                    : signal.continueLoop ? "CONTINUE is only allowed inside of a loop body"
                    : "BREAK without label is only allowed inside of a loop body");
        }
        BigQuerySqlEngine.Result result = context.lastQueryResult();
        job.setTotalBytesProcessed(String.valueOf(context.totalBytesProcessed()));
        // A script reports only its final result. BigQuery runs a script's statements as child jobs, so the
        // parent job carries no DML counts, even when the last statement is DML.
        job.setNumDmlAffectedRows(null);
        job.setDmlStats(null);
        if (result != null) {
            job.setTotalRows(result.rows().size());
            job.setDestinationDatasetId(ANON_DATASET);
            job.setDestinationTableId("anon_" + job.getJobId());
            materializeResult(jobProjectId, job, result.schema(), result.rows());
        }
    }

    private record ExecuteImmediateStatement(String sqlExpression, List<String> intoVariables,
                                             List<ScriptUsingValue> usingValues) {}

    private record ScriptUsingValue(String expression, String alias, boolean aliasSpecified) {}

    private static boolean isExecuteImmediateStatement(String sql) {
        return executeImmediateRemainder(sql) != null;
    }

    private static String executeImmediateRemainder(String sql) {
        if (sql == null) {
            return null;
        }
        int index = skipScriptSqlTrivia(sql, 0);
        index = consumeScriptSqlKeyword(sql, index, "EXECUTE");
        if (index < 0) {
            return null;
        }
        index = skipScriptSqlTrivia(sql, index);
        index = consumeScriptSqlKeyword(sql, index, "IMMEDIATE");
        if (index < 0) {
            return null;
        }
        String expression = sql.substring(skipScriptSqlTrivia(sql, index)).trim();
        return expression.isEmpty() ? null : expression;
    }

    private static int skipScriptSqlTrivia(String sql, int from) {
        int index = from;
        while (index < sql.length()) {
            if (Character.isWhitespace(sql.charAt(index))) {
                index++;
            } else if (sql.charAt(index) == '#' || sql.startsWith("--", index)) {
                while (index < sql.length() && sql.charAt(index) != '\n') {
                    index++;
                }
            } else if (sql.startsWith("/*", index)) {
                int end = sql.indexOf("*/", index + 2);
                if (end < 0) {
                    return sql.length();
                }
                index = end + 2;
            } else {
                break;
            }
        }
        return index;
    }

    private static int consumeScriptSqlKeyword(String sql, int from, String keyword) {
        int end = from + keyword.length();
        if (end > sql.length() || !sql.regionMatches(true, from, keyword, 0, keyword.length())) {
            return -1;
        }
        if (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
            return -1;
        }
        return end;
    }

    private static ExecuteImmediateStatement parseExecuteImmediate(String sql) {
        String remainder = executeImmediateRemainder(sql);
        if (remainder == null) {
            throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE requires a SQL expression.");
        }
        int intoPosition = topLevelScriptKeyword(remainder, "INTO");
        int usingPosition = topLevelScriptKeyword(remainder, "USING");
        if (intoPosition >= 0 && usingPosition >= 0 && usingPosition < intoPosition) {
            throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE clauses must appear in INTO then USING order.");
        }
        int expressionEnd = remainder.length();
        if (intoPosition >= 0) {
            expressionEnd = Math.min(expressionEnd, intoPosition);
        }
        if (usingPosition >= 0) {
            expressionEnd = Math.min(expressionEnd, usingPosition);
        }
        String sqlExpression = remainder.substring(0, expressionEnd).trim();
        if (sqlExpression.isEmpty()) {
            throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE requires a SQL expression.");
        }
        List<String> intoVariables = List.of();
        if (intoPosition >= 0) {
            int intoEnd = usingPosition >= 0 ? usingPosition : remainder.length();
            String intoClause = remainder.substring(intoPosition + "INTO".length(), intoEnd).trim();
            intoVariables = splitScriptExpressions(intoClause).stream()
                    .map(BigQueryService::scriptIdentifier).toList();
            if (intoVariables.isEmpty()) {
                throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE INTO requires at least one variable.");
            }
        }
        List<ScriptUsingValue> usingValues = List.of();
        if (usingPosition >= 0) {
            usingValues = parseScriptUsingValues(
                    remainder.substring(usingPosition + "USING".length()).trim());
        }
        return new ExecuteImmediateStatement(sqlExpression, intoVariables, usingValues);
    }

    private static String scriptIdentifier(String expression) {
        List<SqlDialectTranslator.Token> tokens = new SqlDialectTranslator.Lexer(expression).tokenize().stream()
                .filter(token -> token.kind != SqlDialectTranslator.Kind.SPACE).toList();
        if (tokens.size() != 1 || (tokens.getFirst().kind != SqlDialectTranslator.Kind.IDENT
                && tokens.getFirst().kind != SqlDialectTranslator.Kind.QIDENT)) {
            throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE variable names must be identifiers.");
        }
        return tokens.getFirst().identifierText();
    }

    private static List<ScriptUsingValue> parseScriptUsingValues(String clause) {
        if (clause.isBlank()) {
            throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE USING requires at least one value.");
        }
        List<ScriptUsingValue> values = new ArrayList<>();
        for (String value : splitScriptExpressions(clause)) {
            int aliasPosition = topLevelScriptKeyword(value, "AS");
            String expression = aliasPosition >= 0 ? value.substring(0, aliasPosition).trim() : value.trim();
            String alias = aliasPosition >= 0
                    ? scriptIdentifier(value.substring(aliasPosition + "AS".length()).trim()) : null;
            if (expression.isEmpty()) {
                throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE USING values cannot be empty.");
            }
            values.add(new ScriptUsingValue(expression, alias, aliasPosition >= 0));
        }
        return values;
    }

    private void executeImmediate(String projectId, String jobProjectId, String sql, QueryOptions options,
                                  StoredJob job, ProceduralContext context, JobLocation jobLocation) {
        if (context.dynamicSqlExecution()) {
            throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE cannot be nested.");
        }
        ExecuteImmediateStatement statement = parseExecuteImmediate(sql);
        for (String variable : statement.intoVariables()) {
            if (!context.variables().containsKey(variable.toLowerCase(Locale.ROOT))) {
                throw QueryEngine.invalidQuery("Variable " + variable + " has not been declared.");
            }
        }
        String dynamicSql = evaluateImmediateSqlExpression(projectId, jobProjectId, statement.sqlExpression(),
                options, context, jobLocation);
        List<Map<String, Object>> parameters = executeImmediateQueryParameters(
                statement.usingValues(), projectId, jobProjectId, options, context, jobLocation);
        String parameterMode = statement.usingValues().isEmpty() ? null
                : statement.usingValues().stream().allMatch(ScriptUsingValue::aliasSpecified) ? "NAMED" : "POSITIONAL";
        QueryOptions dynamicOptions = new QueryOptions(dynamicSql, options.defaultDatasetId(), parameters,
                parameterMode, false, options.useLegacySql());
        SqlDialectTranslator.ProceduralScript dynamicScript = SqlDialectTranslator.proceduralScript(dynamicSql);
        if (dynamicScript.items().size() != 1
                || !(dynamicScript.items().getFirst() instanceof SqlDialectTranslator.SqlItem
                || dynamicScript.items().getFirst() instanceof SqlDialectTranslator.BeginBlock)) {
            throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE requires a single query, DDL, DML, or BEGIN block.");
        }
        // BigQuery validates the SQL a dynamic statement supplies before running it, so an invalid block
        // reports its error instead of writing the part of it that came first.
        validateProceduralStatements(projectId, jobProjectId, dynamicScript, options.defaultDatasetId(),
                new SqlDialectTranslator.QueryParameters(parameters, parameterMode));
        ProceduralContext dynamicContext = context.dynamicSqlChild();
        try {
            executeProceduralItem(projectId, jobProjectId, dynamicScript.items().getFirst(), dynamicOptions, job,
                    dynamicContext, jobLocation);
        } catch (LoopControlSignal signal) {
            throw QueryEngine.invalidQuery(signal.label != null
                    ? "Loop control label " + signal.label + " does not name an enclosing block or loop."
                    : signal.continueLoop ? "CONTINUE is only allowed inside of a loop body"
                    : "BREAK without label is only allowed inside of a loop body");
        }
        context.mergeDynamicSqlContext(dynamicContext);
        if (!statement.intoVariables().isEmpty()) {
            assignImmediateResults(statement.intoVariables(), dynamicContext.lastQueryResult(), context);
            context.clearQueryResult();
        }
    }

    private String evaluateImmediateSqlExpression(String projectId, String jobProjectId, String expression,
                                                  QueryOptions options, ProceduralContext context,
                                                  JobLocation jobLocation) {
        Object value;
        try {
            value = scriptOperand(expression, context);
        } catch (GcpException e) {
            value = evaluateScriptSqlExpression(expression, projectId, jobProjectId, options, context, jobLocation)
                    .value();
        }
        if (!(value instanceof String sql) || sql.isBlank()) {
            throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE SQL expression must produce a non-empty STRING.");
        }
        return sql;
    }

    private List<Map<String, Object>> executeImmediateQueryParameters(List<ScriptUsingValue> values, String projectId,
                                                                     String jobProjectId, QueryOptions options,
                                                                     ProceduralContext context,
                                                                     JobLocation jobLocation) {
        List<Map<String, Object>> parameters = new ArrayList<>(values.size());
        Set<String> names = new LinkedHashSet<>();
        for (ScriptUsingValue usingValue : values) {
            ScriptExpressionValue evaluated = evaluateScriptUsingValue(
                    usingValue.expression(), projectId, jobProjectId, options, context, jobLocation);
            String name = usingValue.alias();
            if (name == null) {
                String variable = simpleScriptVariableName(usingValue.expression());
                name = variable != null && context.variables().containsKey(variable.toLowerCase(Locale.ROOT))
                        ? variable : null;
            }
            if (name != null && !names.add(name.toLowerCase(Locale.ROOT))) {
                throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE USING aliases must be unique.");
            }
            parameters.add(scriptQueryParameter(name, evaluated.type(), evaluated.value()));
        }
        return parameters;
    }

    private ScriptExpressionValue evaluateScriptUsingValue(String expression, String projectId, String jobProjectId,
                                                           QueryOptions options, ProceduralContext context,
                                                           JobLocation jobLocation) {
        String variableName = simpleScriptVariableName(expression);
        if (variableName != null && context.variables().containsKey(variableName.toLowerCase(Locale.ROOT))) {
            String type = context.variableTypes().get(variableName.toLowerCase(Locale.ROOT));
            ScriptVariableType variableType = new ScriptVariableTypeParser(type).parse();
            Object value = coerceScriptVariableValue(context.variables().get(variableName.toLowerCase(Locale.ROOT)),
                    variableType);
            return new ScriptExpressionValue(value, scriptVariableTypeSql(variableType));
        }
        Object value;
        String type;
        if (isScriptIntegerLiteral(expression)) {
            value = scriptOperand(expression.trim(), context);
            type = inferredScriptVariableType(value);
        } else {
            try {
                value = scriptOperand(expression, context);
                String literalType = scriptLiteralType(expression);
                type = literalType != null ? literalType
                        : value == null ? "STRING" : inferredScriptVariableType(value);
            } catch (GcpException e) {
                ScriptExpressionValue evaluated = evaluateScriptSqlExpression(
                        expression, projectId, jobProjectId, options, context, jobLocation);
                value = evaluated.value();
                type = evaluated.type() != null ? evaluated.type()
                        : value == null ? "STRING" : inferredScriptVariableType(value);
            }
        }
        ScriptVariableType variableType = new ScriptVariableTypeParser(type).parse();
        return new ScriptExpressionValue(coerceScriptVariableValue(value, variableType),
                scriptVariableTypeSql(variableType));
    }

    private static String simpleScriptVariableName(String expression) {
        List<SqlDialectTranslator.Token> tokens = new SqlDialectTranslator.Lexer(expression).tokenize().stream()
                .filter(token -> token.kind != SqlDialectTranslator.Kind.SPACE).toList();
        return tokens.size() == 1 && (tokens.getFirst().kind == SqlDialectTranslator.Kind.IDENT
                || tokens.getFirst().kind == SqlDialectTranslator.Kind.QIDENT)
                ? tokens.getFirst().identifierText() : null;
    }

    private static Map<String, Object> scriptQueryParameter(String name, String type, Object value) {
        ScriptVariableType variableType = new ScriptVariableTypeParser(type).parse();
        Map<String, Object> parameter = new LinkedHashMap<>();
        if (name != null) {
            parameter.put("name", name);
        }
        parameter.put("parameterType", scriptQueryParameterType(variableType));
        parameter.put("parameterValue", scriptQueryParameterValue(variableType, value));
        return parameter;
    }

    private static Map<String, Object> scriptQueryParameterType(ScriptVariableType type) {
        Map<String, Object> parameterType = new LinkedHashMap<>();
        if (type.isArray()) {
            parameterType.put("type", "ARRAY");
            parameterType.put("arrayType", scriptQueryParameterType(type.elementType()));
        } else if (type.isStruct()) {
            parameterType.put("type", "STRUCT");
            List<Map<String, Object>> structTypes = new ArrayList<>(type.fields().size());
            for (int index = 0; index < type.fields().size(); index++) {
                ScriptVariableField field = type.fields().get(index);
                Map<String, Object> structFieldType = new LinkedHashMap<>();
                structFieldType.put("name", field.name() != null ? field.name() : "_field_" + (index + 1));
                structFieldType.put("type", scriptQueryParameterType(field.type()));
                structTypes.add(structFieldType);
            }
            parameterType.put("structTypes", structTypes);
        } else {
            parameterType.put("type", type.scalarType());
        }
        return parameterType;
    }

    private static Map<String, Object> scriptQueryParameterValue(ScriptVariableType type, Object value) {
        Map<String, Object> parameterValue = new LinkedHashMap<>();
        if (value == null) {
            parameterValue.put("value", null);
            return parameterValue;
        }
        if (type.isArray()) {
            if (!(value instanceof List<?> values)) {
                throw QueryEngine.invalidQuery("USING value cannot be coerced to " + scriptVariableTypeSql(type));
            }
            List<Map<String, Object>> arrayValues = new ArrayList<>(values.size());
            for (Object element : values) {
                arrayValues.add(scriptQueryParameterValue(type.elementType(), element));
            }
            parameterValue.put("arrayValues", arrayValues);
            return parameterValue;
        }
        if (type.isStruct()) {
            if (!(value instanceof Map<?, ?> values)) {
                throw QueryEngine.invalidQuery("USING value cannot be coerced to " + scriptVariableTypeSql(type));
            }
            Map<String, Object> structValues = new LinkedHashMap<>();
            List<Map.Entry<?, ?>> entries = new ArrayList<>(values.entrySet());
            for (int index = 0; index < type.fields().size(); index++) {
                ScriptVariableField field = type.fields().get(index);
                Map.Entry<?, ?> entry = structFieldEntry(entries, field.name(), index);
                if (entry == null) {
                    throw QueryEngine.invalidQuery("USING STRUCT value does not match its type.");
                }
                String fieldName = field.name() != null ? field.name() : "_field_" + (index + 1);
                structValues.put(fieldName, scriptQueryParameterValue(field.type(), entry.getValue()));
            }
            parameterValue.put("structValues", structValues);
            return parameterValue;
        }
        String literal = switch (type.scalarType()) {
            case "DATE" -> coerceScriptDate(value).toString();
            case "TIMESTAMP" -> coerceScriptTimestamp(value).toString();
            case "DATETIME" -> coerceScriptDateTime(value).toString().replace('T', ' ');
            case "TIME" -> coerceScriptTime(value).toString();
            case "BYTES" -> coerceScriptBytes(value);
            case "JSON" -> coerceScriptJson(value);
            default -> value.toString();
        };
        parameterValue.put("value", literal);
        return parameterValue;
    }

    private static void assignImmediateResults(List<String> targets, BigQuerySqlEngine.Result result,
                                               ProceduralContext context) {
        if (result == null || result.schema() == null || result.schema().getFields() == null) {
            throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE INTO requires a query result.");
        }
        List<TableFieldSchema> fields = result.schema().getFields();
        if (fields.size() != targets.size()) {
            throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE INTO variable count must match query result columns.");
        }
        if (result.rows().size() > 1) {
            throw QueryEngine.invalidQuery("EXECUTE IMMEDIATE INTO query must return at most one row.");
        }
        Map<String, Object> assigned = new LinkedHashMap<>();
        Map<String, Object> row = result.rows().isEmpty() ? null : result.rows().getFirst();
        for (int index = 0; index < targets.size(); index++) {
            String target = targets.get(index).toLowerCase(Locale.ROOT);
            if (!context.variables().containsKey(target)) {
                throw QueryEngine.invalidQuery("Variable " + targets.get(index) + " has not been declared.");
            }
            Object value = row == null ? null : scriptResultValue(row, fields.get(index), index);
            assigned.put(target, coerceScriptVariableValue(value, context.variableTypes().get(target)));
        }
        context.variables().putAll(assigned);
    }

    private static Object scriptResultValue(Map<String, Object> row, TableFieldSchema field, int index) {
        Object value = null;
        if (row.containsKey(field.getName())) {
            value = row.get(field.getName());
        } else if (index < row.size()) {
            value = new ArrayList<>(row.values()).get(index);
        }
        return scriptResultValue(value, field);
    }

    /** A result's value as a script holds it: a TIMESTAMP result is decimal epoch seconds. */
    private static Object scriptResultValue(Object value, TableFieldSchema field) {
        if (value == null || field == null) {
            return value;
        }
        if ("TIMESTAMP".equals(field.getType()) && !"REPEATED".equals(field.getMode())) {
            return resultTimestamp(value);
        }
        if ("REPEATED".equals(field.getMode()) && value instanceof List<?> values) {
            TableFieldSchema element = new TableFieldSchema();
            element.setName(field.getName());
            element.setType(field.getType());
            element.setMode("NULLABLE");
            element.setFields(field.getFields());
            List<Object> converted = new ArrayList<>(values.size());
            for (Object item : values) {
                converted.add(scriptResultValue(item, element));
            }
            return converted;
        }
        if ("RECORD".equals(field.getType()) && value instanceof Map<?, ?> record) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (TableFieldSchema child : field.getFields() != null ? field.getFields() : List.<TableFieldSchema>of()) {
                converted.put(child.getName(), scriptResultValue(record.get(child.getName()), child));
            }
            return converted;
        }
        return value;
    }

    /** A result row as a script holds it, converting every field a script represents differently. */
    private static Map<String, Object> scriptResultRow(Map<String, Object> row, TableSchema schema) {
        List<TableFieldSchema> fields = schema != null && schema.getFields() != null
                ? schema.getFields() : List.of();
        Map<String, Object> converted = new LinkedHashMap<>();
        for (TableFieldSchema field : fields) {
            if (row.containsKey(field.getName())) {
                converted.put(field.getName(), scriptResultValue(row.get(field.getName()), field));
            }
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            converted.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return converted;
    }

    private void validateScriptConfiguration(QueryOptions options) {
        if (options.destinationTable() != null || options.writeDisposition() != null
                || options.createDisposition() != null || !options.schemaUpdateOptions().isEmpty()
                || options.timePartitioning() != null || options.rangePartitioning() != null
                || !options.scriptIncompatibleOptions().isEmpty()) {
            throw QueryEngine.invalidQuery("Destination, disposition, schema update, partitioning, clustering, "
                    + "encryption, and user-defined function resource options cannot be set for multi-statement "
                    + "queries.");
        }
    }

    private boolean applyScriptVariableStatement(String sql, String projectId, String jobProjectId,
                                                 QueryOptions options, ProceduralContext context,
                                                 JobLocation jobLocation) {
        Matcher declaration = SCRIPT_DECLARE.matcher(sql.trim());
        if (declaration.matches()) {
            List<String> names = splitScriptExpressions(declaration.group(1));
            String remainder = declaration.group(2) != null ? declaration.group(2).trim() : "";
            int defaultPosition = topLevelScriptKeyword(remainder, "DEFAULT");
            String typeExpression = defaultPosition >= 0 ? remainder.substring(0, defaultPosition).trim()
                    : remainder;
            String defaultExpression = defaultPosition >= 0
                    ? remainder.substring(defaultPosition + "DEFAULT".length()).trim() : null;
            String type = typeExpression.isBlank() ? null : normalizeScriptVariableType(typeExpression);
            if (type == null && defaultExpression == null) {
                throw QueryEngine.invalidQuery("A variable declaration without a type requires a DEFAULT expression.");
            }
            for (String name : names) {
                String normalized = name.trim().toLowerCase(Locale.ROOT);
                if (context.variables().containsKey(normalized)) {
                    throw QueryEngine.invalidQuery("Variable " + name.trim() + " has already been declared.");
                }
            }
            Object value = null;
            if (defaultExpression != null) {
                if (type != null) {
                    value = parseScriptVariableValue(defaultExpression, type, projectId, jobProjectId, options, context, jobLocation);
                } else {
                    try {
                        value = scriptOperand(defaultExpression, context);
                    } catch (GcpException e) {
                        ScriptExpressionValue evaluated = evaluateScriptSqlExpression(
                                defaultExpression, projectId, jobProjectId, options, context, jobLocation);
                        value = evaluated.value();
                        type = evaluated.type();
                    }
                }
            }
            if (type == null) {
                // A value cannot always convey its type: `DECLARE xs DEFAULT ARRAY<FLOAT64>[]` is an
                // empty list and `DECLARE b DEFAULT a` may be NULL, so keep the literal's element type
                // or the declared type of the variable the default names.
                String literalType = defaultExpression != null ? scriptLiteralType(defaultExpression) : null;
                String referenceType = literalType == null && defaultExpression != null
                        ? scriptVariableReferenceType(defaultExpression, context) : null;
                type = literalType != null ? literalType
                        : referenceType != null ? referenceType : inferredScriptVariableType(value);
            }
            value = coerceScriptVariableValue(value, type);
            for (String name : names) {
                String normalized = name.trim().toLowerCase(Locale.ROOT);
                context.variables().put(normalized, value);
                context.variableTypes().put(normalized, type);
            }
            return true;
        }
        Matcher tupleAssignment = SCRIPT_SET_TUPLE.matcher(sql.trim());
        if (tupleAssignment.matches()) {
            List<String> names = splitScriptExpressions(tupleAssignment.group(1));
            List<String> expressions = splitScriptExpressions(tupleAssignment.group(2));
            if (names.size() != expressions.size() || names.isEmpty()) {
                throw QueryEngine.invalidQuery("SET tuple assignment must have matching variable and expression counts.");
            }
            Map<String, Object> assigned = new LinkedHashMap<>();
            for (int index = 0; index < names.size(); index++) {
                String name = names.get(index).trim().toLowerCase(Locale.ROOT);
                if (!context.variables().containsKey(name)) {
                    throw QueryEngine.invalidQuery("Variable " + names.get(index).trim() + " has not been declared.");
                }
                assigned.put(name, parseScriptVariableValue(expressions.get(index), context.variableTypes().get(name),
                        projectId, jobProjectId, options, context, jobLocation));
            }
            context.variables().putAll(assigned);
            return true;
        }
        Matcher assignment = SCRIPT_SET.matcher(sql.trim());
        if (assignment.matches()) {
            String name = assignment.group(1).toLowerCase(Locale.ROOT);
            if (!context.variables().containsKey(name)) {
                throw QueryEngine.invalidQuery("Variable " + assignment.group(1) + " has not been declared.");
            }
            String type = context.variableTypes().get(name);
            Object value = parseScriptVariableValue(assignment.group(2), type,
                    projectId, jobProjectId, options, context, jobLocation);
            context.variables().put(name, value);
            return true;
        }
        return false;
    }

    private static int topLevelScriptKeyword(String expression, String keyword) {
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        int angles = 0;
        String previousWord = null;
        int index = 0;
        while (index < expression.length()) {
            char current = expression.charAt(index);
            if (current == '-' && index + 1 < expression.length() && expression.charAt(index + 1) == '-'
                    || current == '#') {
                while (index < expression.length() && expression.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < expression.length() && expression.charAt(index + 1) == '*') {
                int end = expression.indexOf("*/", index + 2);
                if (end < 0) {
                    return -1;
                }
                index = end + 2;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                char quote = current;
                boolean triple = quote != '`' && expression.startsWith(String.valueOf(quote).repeat(3), index);
                String delimiter = triple ? String.valueOf(quote).repeat(3) : String.valueOf(quote);
                index += delimiter.length();
                while (index < expression.length()) {
                    if (expression.startsWith(delimiter, index)) {
                        index += delimiter.length();
                        if (!triple && quote != '`' && index < expression.length()
                                && expression.charAt(index) == quote) {
                            index++;
                            continue;
                        }
                        break;
                    }
                    if (expression.charAt(index) == '\\' && quote != '`' && index + 1 < expression.length()) {
                        index += 2;
                    } else {
                        index++;
                    }
                }
                previousWord = null;
                continue;
            }
            if (parentheses == 0 && brackets == 0 && braces == 0 && angles == 0
                    && Character.isLetter(current)) {
                int wordEnd = index + 1;
                while (wordEnd < expression.length()
                        && (Character.isLetterOrDigit(expression.charAt(wordEnd))
                        || expression.charAt(wordEnd) == '_')) {
                    wordEnd++;
                }
                String word = expression.substring(index, wordEnd);
                if (word.equalsIgnoreCase(keyword)) {
                    return index;
                }
                previousWord = word.toUpperCase(Locale.ROOT);
                index = wordEnd;
                continue;
            }
            switch (current) {
                case '(' -> parentheses++;
                case ')' -> parentheses--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                case '{' -> braces++;
                case '}' -> braces--;
                case '<' -> {
                    if (startsTypeParameters(previousWord)) {
                        angles++;
                    }
                }
                case '>' -> angles = Math.max(0, angles - 1);
                default -> { }
            }
            previousWord = null;
            index++;
        }
        return -1;
    }

    private static boolean startsTypeParameters(String previousWord) {
        return "ARRAY".equals(previousWord) || "STRUCT".equals(previousWord);
    }

    private static List<String> splitScriptExpressions(String expression) {
        List<SqlDialectTranslator.Token> tokens = new SqlDialectTranslator.Lexer(expression).tokenize();
        List<String> expressions = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        int typeAngles = 0;
        String previousWord = null;
        for (SqlDialectTranslator.Token token : tokens) {
            if (token.isPunct("(")) {
                parentheses++;
            } else if (token.isPunct(")")) {
                parentheses--;
            } else if (token.isPunct("[")) {
                brackets++;
            } else if (token.isPunct("]")) {
                brackets--;
            } else if (token.isPunct("{")) {
                braces++;
            } else if (token.isPunct("}")) {
                braces--;
            } else if (token.isPunct("<") && ("ARRAY".equals(previousWord) || "STRUCT".equals(previousWord))) {
                typeAngles++;
            } else if (typeAngles > 0 && token.isPunct(">")) {
                typeAngles--;
            } else if (typeAngles > 0 && token.isPunct(">>")) {
                typeAngles = Math.max(0, typeAngles - 2);
            }
            if (token.isPunct(",") && parentheses == 0 && brackets == 0 && braces == 0 && typeAngles == 0) {
                expressions.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(token.text);
            }
            if (token.kind == SqlDialectTranslator.Kind.IDENT) {
                previousWord = token.upper();
            } else if (token.kind != SqlDialectTranslator.Kind.SPACE) {
                previousWord = null;
            }
        }
        expressions.add(current.toString().trim());
        return expressions;
    }

    private ScriptExpressionValue evaluateScriptSqlExpression(String expression, String projectId,
                                                               String jobProjectId, QueryOptions options,
                                                               ProceduralContext context,
                                                               JobLocation jobLocation) {
        String sql = context.prependTo("SELECT " + expandScriptVariables(expression, context, Set.of())
                + " AS _floci_script_value");
        BigQuerySqlEngine.Result result = engine.execute(request(projectId, jobProjectId, sql, options, false),
                tables(projectId, jobLocation));
        context.recordBytes(result.totalBytesProcessed());
        List<TableFieldSchema> fields = result.schema() != null && result.schema().getFields() != null
                ? result.schema().getFields() : List.of();
        if (result.rows().isEmpty()) {
            return new ScriptExpressionValue(null, fields.isEmpty() ? null : scriptVariableType(fields.getFirst()));
        }
        Map<String, Object> row = result.rows().getFirst();
        Object value;
        if (row.containsKey("_floci_script_value")) {
            value = row.get("_floci_script_value");
        } else if (row.size() == 1) {
            value = row.values().iterator().next();
        } else {
            throw QueryEngine.invalidQuery("Script expression did not produce exactly one value.");
        }
        value = scriptResultValue(value, fields.isEmpty() ? null : fields.getFirst());
        return new ScriptExpressionValue(value, fields.isEmpty() ? inferredScriptVariableType(value)
                : scriptVariableType(fields.getFirst()));
    }

    private record ScriptVariableField(String name, ScriptVariableType type) {}

    private record ScriptVariableType(String scalarType, ScriptVariableType elementType,
                                      List<ScriptVariableField> fields) {
        private boolean isArray() {
            return elementType != null;
        }

        private boolean isStruct() {
            return fields != null;
        }
    }

    private record ScriptExpressionValue(Object value, String type) {}

    private static final class ScriptVariableTypeParser {
        private static final Set<String> SCALAR_TYPES = Set.of("INT64", "INTEGER", "BOOL", "BOOLEAN", "STRING",
                "FLOAT64", "FLOAT", "NUMERIC", "DATE", "TIMESTAMP", "BYTES", "DATETIME", "TIME", "JSON");

        private final List<SqlDialectTranslator.Token> tokens;
        private int at;

        private ScriptVariableTypeParser(String type) {
            List<SqlDialectTranslator.Token> parsed = new ArrayList<>();
            for (SqlDialectTranslator.Token token : new SqlDialectTranslator.Lexer(type).tokenize()) {
                if (token.kind == SqlDialectTranslator.Kind.SPACE) {
                    continue;
                }
                if (token.isPunct(">>")) {
                    parsed.add(new SqlDialectTranslator.Token(SqlDialectTranslator.Kind.PUNCT, ">", null));
                    parsed.add(new SqlDialectTranslator.Token(SqlDialectTranslator.Kind.PUNCT, ">", null));
                } else {
                    parsed.add(token);
                }
            }
            tokens = parsed;
        }

        private ScriptVariableType parse() {
            ScriptVariableType type = parseType();
            if (at != tokens.size()) {
                throw invalidType();
            }
            return type;
        }

        private ScriptVariableType parseType() {
            if (at >= tokens.size()) {
                throw invalidType();
            }
            SqlDialectTranslator.Token head = tokens.get(at++);
            if (head.kind != SqlDialectTranslator.Kind.IDENT) {
                throw invalidType();
            }
            String name = head.upper();
            if ("ARRAY".equals(name)) {
                expect("<");
                ScriptVariableType elementType = parseType();
                expect(">");
                if (elementType.isArray()) {
                    throw QueryEngine.invalidQuery("BigQuery arrays cannot contain arrays.");
                }
                return new ScriptVariableType(null, elementType, null);
            }
            if ("STRUCT".equals(name)) {
                expect("<");
                List<ScriptVariableField> fields = new ArrayList<>();
                while (!peek(">")) {
                    if (at >= tokens.size()) {
                        throw invalidType();
                    }
                    SqlDialectTranslator.Token fieldOrType = tokens.get(at);
                    if (isUnnamedType()) {
                        fields.add(new ScriptVariableField(null, parseType()));
                    } else {
                        if (fieldOrType.kind != SqlDialectTranslator.Kind.IDENT
                                && fieldOrType.kind != SqlDialectTranslator.Kind.QIDENT) {
                            throw invalidType();
                        }
                        at++;
                        fields.add(new ScriptVariableField(fieldOrType.identifierText(), parseType()));
                    }
                    if (!peek(">")) {
                        expect(",");
                    }
                }
                expect(">");
                if (fields.isEmpty()) {
                    throw invalidType();
                }
                return new ScriptVariableType(null, null, List.copyOf(fields));
            }
            if (!SCALAR_TYPES.contains(name)) {
                throw invalidType();
            }
            return new ScriptVariableType(normalizeScalarType(name), null, null);
        }

        private boolean isUnnamedType() {
            SqlDialectTranslator.Token token = tokens.get(at);
            if (token.kind != SqlDialectTranslator.Kind.IDENT) {
                return false;
            }
            String name = token.upper();
            if ("ARRAY".equals(name) || "STRUCT".equals(name)) {
                return at + 1 < tokens.size() && tokens.get(at + 1).isPunct("<");
            }
            if (!SCALAR_TYPES.contains(name)) {
                return false;
            }
            int next = at + 1;
            if (next < tokens.size() && tokens.get(next).isPunct("(")) {
                int parentheses = 0;
                while (next < tokens.size()) {
                    if (tokens.get(next).isPunct("(")) {
                        parentheses++;
                    } else if (tokens.get(next).isPunct(")")) {
                        parentheses--;
                        if (parentheses == 0) {
                            next++;
                            break;
                        }
                    }
                    next++;
                }
            }
            return next >= tokens.size() || tokens.get(next).isPunct(",") || tokens.get(next).isPunct(">");
        }

        private boolean peek(String punctuation) {
            return at < tokens.size() && tokens.get(at).isPunct(punctuation);
        }

        private void expect(String punctuation) {
            if (!peek(punctuation)) {
                throw invalidType();
            }
            at++;
        }

        private GcpException invalidType() {
            return QueryEngine.invalidQuery("Variable type is not supported by the floci BigQuery emulator.");
        }
    }

    private static String normalizeScriptVariableType(String type) {
        return scriptVariableTypeSql(new ScriptVariableTypeParser(type).parse());
    }

    private static String normalizeScalarType(String type) {
        return switch (type) {
            case "INTEGER" -> "INT64";
            case "BOOLEAN" -> "BOOL";
            case "FLOAT" -> "FLOAT64";
            default -> type;
        };
    }

    private static String scriptVariableTypeSql(ScriptVariableType type) {
        if (type.isArray()) {
            return "ARRAY<" + scriptVariableTypeSql(type.elementType()) + ">";
        }
        if (type.isStruct()) {
            List<String> fields = new ArrayList<>(type.fields().size());
            for (ScriptVariableField field : type.fields()) {
                String fieldType = scriptVariableTypeSql(field.type());
                fields.add(field.name() == null ? fieldType
                        : quoteScriptTypeField(field.name()) + " " + fieldType);
            }
            return "STRUCT<" + String.join(", ", fields) + ">";
        }
        return type.scalarType();
    }

    private static String quoteScriptTypeField(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    private static String scriptVariableType(TableFieldSchema field) {
        return scriptVariableTypeSql(scriptVariableTypeNode(field));
    }

    private static ScriptVariableType scriptVariableTypeNode(TableFieldSchema field) {
        if ("RECORD".equalsIgnoreCase(field.getType())) {
            List<TableFieldSchema> children = field.getFields() != null ? field.getFields() : List.of();
            List<ScriptVariableField> fields = new ArrayList<>(children.size());
            for (TableFieldSchema child : children) {
                fields.add(new ScriptVariableField(child.getName(), scriptVariableTypeNode(child)));
            }
            if (fields.isEmpty()) {
                throw QueryEngine.invalidQuery("A STRUCT variable must have at least one field.");
            }
            ScriptVariableType struct = new ScriptVariableType(null, null, List.copyOf(fields));
            return "REPEATED".equalsIgnoreCase(field.getMode())
                    ? new ScriptVariableType(null, struct, null) : struct;
        }
        ScriptVariableType scalar = scalarVariableType(field.getType());
        return "REPEATED".equalsIgnoreCase(field.getMode())
                ? new ScriptVariableType(null, scalar, null) : scalar;
    }

    private static ScriptVariableType scalarVariableType(String type) {
        if (type == null) {
            throw QueryEngine.invalidQuery("Script expression has no supported result type.");
        }
        String normalized = switch (type.toUpperCase(Locale.ROOT)) {
            case "INTEGER" -> "INT64";
            case "FLOAT" -> "FLOAT64";
            case "BOOLEAN" -> "BOOL";
            default -> type.toUpperCase(Locale.ROOT);
        };
        if (!ScriptVariableTypeParser.SCALAR_TYPES.contains(normalized)) {
            throw QueryEngine.invalidQuery(
                    "Variable type " + type + " is not supported by the floci BigQuery emulator.");
        }
        return new ScriptVariableType(normalized, null, null);
    }

    private static String inferredScriptVariableType(Object value) {
        return scriptVariableTypeSql(inferredScriptVariableTypeNode(value));
    }

    private static ScriptVariableType inferredScriptVariableTypeNode(Object value) {
        if (value instanceof List<?> values) {
            ScriptVariableType elementType = null;
            for (Object element : values) {
                if (element == null) {
                    continue;
                }
                ScriptVariableType currentType = inferredScriptVariableTypeNode(element);
                if (currentType.isArray()) {
                    throw QueryEngine.invalidQuery("BigQuery arrays cannot contain arrays.");
                }
                if (elementType != null && !elementType.equals(currentType)) {
                    throw QueryEngine.invalidQuery("Array variable elements must have a common type.");
                }
                elementType = currentType;
            }
            if (elementType == null) {
                throw QueryEngine.invalidQuery("Variable type cannot be inferred from an empty or NULL array.");
            }
            return new ScriptVariableType(null, elementType, null);
        }
        if (value instanceof Map<?, ?> map) {
            List<ScriptVariableField> fields = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    throw QueryEngine.invalidQuery("STRUCT variable field names must be strings.");
                }
                fields.add(new ScriptVariableField(name, inferredScriptVariableTypeNode(entry.getValue())));
            }
            if (fields.isEmpty()) {
                throw QueryEngine.invalidQuery("A STRUCT variable must have at least one field.");
            }
            return new ScriptVariableType(null, null, List.copyOf(fields));
        }
        return inferredScalarVariableType(value);
    }

    private static ScriptVariableType inferredScalarVariableType(Object value) {
        if (value instanceof String) {
            return new ScriptVariableType("STRING", null, null);
        }
        if (value instanceof LocalDate) {
            return new ScriptVariableType("DATE", null, null);
        }
        if (value instanceof Instant) {
            return new ScriptVariableType("TIMESTAMP", null, null);
        }
        if (value instanceof LocalDateTime) {
            return new ScriptVariableType("DATETIME", null, null);
        }
        if (value instanceof LocalTime) {
            return new ScriptVariableType("TIME", null, null);
        }
        if (value instanceof byte[]) {
            return new ScriptVariableType("BYTES", null, null);
        }
        if (value instanceof Boolean) {
            return new ScriptVariableType("BOOL", null, null);
        }
        if (value instanceof Double || value instanceof Float) {
            return new ScriptVariableType("FLOAT64", null, null);
        }
        if (value instanceof BigDecimal) {
            return new ScriptVariableType("NUMERIC", null, null);
        }
        if (value instanceof Number) {
            return new ScriptVariableType("INT64", null, null);
        }
        throw QueryEngine.invalidQuery("Variable type cannot be inferred from a NULL expression.");
    }

    private Object parseScriptVariableValue(String expression, String type, String projectId, String jobProjectId,
                                            QueryOptions options, ProceduralContext context,
                                            JobLocation jobLocation) {
        String value = expression.trim();
        Matcher arithmetic = Pattern.compile("(?i)^([A-Za-z_][A-Za-z0-9_]*|[-+]?[0-9]+)\\s*([+*-])\\s*"
                + "([A-Za-z_][A-Za-z0-9_]*|[-+]?[0-9]+)$").matcher(value);
        Object evaluated;
        if (arithmetic.matches()) {
            Object left = scriptOperand(arithmetic.group(1), context);
            Object right = scriptOperand(arithmetic.group(3), context);
            // Arithmetic with NULL is NULL, so `DECLARE x INT64; SET x = x + 1` keeps x NULL.
            if (left == null || right == null) {
                evaluated = null;
            } else if (!(left instanceof Number leftNumber) || !(right instanceof Number rightNumber)) {
                throw QueryEngine.invalidQuery("Variable arithmetic requires numeric operands.");
            } else if ("FLOAT64".equals(type) || "FLOAT".equals(type)
                    || left instanceof Double || right instanceof Double) {
                evaluated = switch (arithmetic.group(2)) {
                    case "+" -> leftNumber.doubleValue() + rightNumber.doubleValue();
                    case "-" -> leftNumber.doubleValue() - rightNumber.doubleValue();
                    default -> leftNumber.doubleValue() * rightNumber.doubleValue();
                };
            } else if ("NUMERIC".equals(type) || leftNumber instanceof BigDecimal
                    || rightNumber instanceof BigDecimal) {
                BigDecimal leftDecimal = scriptDecimal(leftNumber);
                BigDecimal rightDecimal = scriptDecimal(rightNumber);
                evaluated = switch (arithmetic.group(2)) {
                    case "+" -> leftDecimal.add(rightDecimal);
                    case "-" -> leftDecimal.subtract(rightDecimal);
                    default -> leftDecimal.multiply(rightDecimal);
                };
            } else {
                try {
                    evaluated = switch (arithmetic.group(2)) {
                        case "+" -> Math.addExact(leftNumber.longValue(), rightNumber.longValue());
                        case "-" -> Math.subtractExact(leftNumber.longValue(), rightNumber.longValue());
                        default -> Math.multiplyExact(leftNumber.longValue(), rightNumber.longValue());
                    };
                } catch (ArithmeticException overflow) {
                    // BigQuery reports an out-of-range INT64 result as an invalid query.
                    throw QueryEngine.invalidQuery("Integer Overflow");
                }
            }
        } else if (isScriptIntegerLiteral(value)) {
            // An integer literal is a scalar operand, never an engine expression: an out-of-range one is
            // BigQuery's "Invalid integer literal" rather than a value some wider type could hold.
            evaluated = scriptOperand(value, context);
        } else {
            try {
                evaluated = scriptOperand(value, context);
            } catch (GcpException e) {
                evaluated = evaluateScriptSqlExpression(value, projectId, jobProjectId, options, context, jobLocation).value();
            }
        }
        return coerceScriptVariableValue(evaluated, type);
    }

    private static Object coerceScriptVariableValue(Object value, String type) {
        return coerceScriptVariableValue(value, new ScriptVariableTypeParser(type).parse());
    }

    private static Object coerceScriptVariableValue(Object value, ScriptVariableType type) {
        if (value == null) {
            return null;
        }
        if (type.isArray()) {
            if (!(value instanceof List<?> values)) {
                throw QueryEngine.invalidQuery("Value cannot be coerced to " + scriptVariableTypeSql(type) + ".");
            }
            List<Object> coerced = new ArrayList<>(values.size());
            for (Object element : values) {
                coerced.add(coerceScriptVariableValue(element, type.elementType()));
            }
            return coerced;
        }
        if (type.isStruct()) {
            if (!(value instanceof Map<?, ?> values) || values.size() != type.fields().size()) {
                throw QueryEngine.invalidQuery("Value cannot be coerced to " + scriptVariableTypeSql(type) + ".");
            }
            // BigQuery assigns a STRUCT by field position, whatever the source calls its fields, so
            // `STRUCT(1 AS b, 2 AS a)` reaches `STRUCT<a INT64, b INT64>` as a=1, b=2.
            List<Map.Entry<?, ?>> entries = new ArrayList<>(values.entrySet());
            Map<String, Object> coerced = new LinkedHashMap<>();
            for (int index = 0; index < type.fields().size(); index++) {
                ScriptVariableField field = type.fields().get(index);
                Map.Entry<?, ?> entry = entries.get(index);
                if (!(entry.getKey() instanceof String sourceName)) {
                    throw QueryEngine.invalidQuery("STRUCT variable fields must have string names.");
                }
                String targetName = field.name() != null ? field.name() : sourceName;
                coerced.put(targetName, coerceScriptVariableValue(entry.getValue(), field.type()));
            }
            return coerced;
        }
        return switch (type.scalarType()) {
            case "INT64" -> scriptInt64Assignment(value);
            case "FLOAT64" -> {
                if (value instanceof Number number) {
                    yield number.doubleValue();
                }
                throw QueryEngine.invalidQuery("Value cannot be coerced to FLOAT64.");
            }
            case "NUMERIC" -> coerceScriptNumeric(value);
            case "BOOL" -> {
                if (value instanceof Boolean) {
                    yield value;
                }
                throw QueryEngine.invalidQuery("Value cannot be coerced to BOOL.");
            }
            case "STRING" -> {
                if (value instanceof String) {
                    yield value;
                }
                throw QueryEngine.invalidQuery("Value cannot be coerced to STRING.");
            }
            case "DATE" -> coerceScriptDate(value);
            case "TIMESTAMP" -> coerceScriptTimestamp(value);
            case "BYTES" -> coerceScriptBytes(value);
            case "DATETIME" -> coerceScriptDateTime(value);
            case "TIME" -> coerceScriptTime(value);
            case "JSON" -> coerceScriptJson(value);
            default -> throw QueryEngine.invalidQuery(
                    "Variable type " + type.scalarType() + " is not supported.");
        };
    }

    /**
     * BigQuery assigns to an INT64 variable only a value that already is an exact INT64. A FLOAT64, a
     * NUMERIC, or an out-of-range value is rejected instead of truncated or wrapped: live BigQuery
     * answers "Cannot coerce expression 1.9 to type INT64" where the emulator names the target type.
     */
    private static long scriptInt64Assignment(Object value) {
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        throw QueryEngine.invalidQuery("Value cannot be coerced to INT64.");
    }

    /** A NUMERIC value: BigQuery keeps 38 digits with 9 after the point, and rejects a value that needs more. */
    private static BigDecimal coerceScriptNumeric(Object value) {
        BigDecimal decimal;
        try {
            if (value instanceof BigDecimal exact) {
                decimal = exact;
            } else if (value instanceof Double || value instanceof Float) {
                decimal = BigDecimal.valueOf(((Number) value).doubleValue());
            } else if (value instanceof Number number) {
                decimal = new BigDecimal(number.toString());
            } else if (value instanceof String text) {
                decimal = new BigDecimal(text.trim());
            } else {
                throw QueryEngine.invalidQuery("Value cannot be coerced to NUMERIC.");
            }
        } catch (NumberFormatException e) {
            throw QueryEngine.invalidQuery("Invalid NUMERIC value: " + value);
        }
        if (decimal.scale() > 9 && decimal.stripTrailingZeros().scale() > 9) {
            throw QueryEngine.invalidQuery("Invalid NUMERIC value: " + decimal.toPlainString());
        }
        if (decimal.precision() - decimal.scale() > 29) {
            throw QueryEngine.invalidQuery("Invalid NUMERIC value: " + decimal.toPlainString());
        }
        return decimal.setScale(Math.min(9, decimal.scale()));
    }

    /** An exact decimal for {@code value}: an integral operand keeps every digit it carries. */
    private static BigDecimal scriptDecimal(Number value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Double || value instanceof Float) {
            return BigDecimal.valueOf(value.doubleValue());
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        return BigDecimal.valueOf(value.longValue());
    }

    private static ScriptVariableField scriptStructField(ScriptVariableType type, String name) {
        if (!type.isStruct()) {
            return null;
        }
        return type.fields().stream().filter(field -> field.name() != null
                && field.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private static Map.Entry<?, ?> structFieldEntry(Map<?, ?> values, String name) {
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() instanceof String key && key.equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    private static Map.Entry<?, ?> structFieldEntry(List<Map.Entry<?, ?>> entries, String name, int index) {
        if (name != null) {
            for (Map.Entry<?, ?> entry : entries) {
                if (entry.getKey() instanceof String key && key.equalsIgnoreCase(name)) {
                    return entry;
                }
            }
        }
        return index < entries.size() ? entries.get(index) : null;
    }

    private static LocalDate coerceScriptDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof String string) {
            try {
                return LocalDate.parse(string);
            } catch (DateTimeParseException e) {
                throw QueryEngine.invalidQuery("Value cannot be coerced to DATE.");
            }
        }
        throw QueryEngine.invalidQuery("Value cannot be coerced to DATE.");
    }

    private static Instant coerceScriptTimestamp(Object value) {
        if (value instanceof Instant instant) {
            return instant.truncatedTo(ChronoUnit.MICROS);
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toInstant().truncatedTo(ChronoUnit.MICROS);
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        }
        if (value instanceof String string) {
            String normalized = string.trim().replaceFirst("(?i)\\s+UTC$", "Z").replace(' ', 'T');
            normalized = normalized.replaceFirst("([+-][0-9]{2})$", "$1:00");
            // Try the standard timestamp forms in order, falling back to UTC for an unzoned value.
            try {
                return Instant.parse(normalized).truncatedTo(ChronoUnit.MICROS);
            } catch (DateTimeParseException ignored) {
                try {
                    return OffsetDateTime.parse(normalized).toInstant().truncatedTo(ChronoUnit.MICROS);
                } catch (DateTimeParseException ignoredOffset) {
                    try {
                        return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC)
                                .truncatedTo(ChronoUnit.MICROS);
                    } catch (DateTimeParseException e) {
                        throw QueryEngine.invalidQuery("Value cannot be coerced to TIMESTAMP.");
                    }
                }
            }
        }
        throw QueryEngine.invalidQuery("Value cannot be coerced to TIMESTAMP.");
    }

    /** A TIMESTAMP result as a script holds it: decimal epoch seconds, or the engine's text form. */
    private static Instant resultTimestamp(Object value) {
        if (value instanceof Instant instant) {
            return instant.truncatedTo(ChronoUnit.MICROS);
        }
        String text = value.toString().trim();
        return text.matches("[-+]?[0-9]+(\\.[0-9]+)?") ? epochSeconds(text) : coerceScriptTimestamp(text);
    }

    /** A TIMESTAMP in BigQuery's decimal epoch seconds, e.g. {@code 1735689600.123456}. */
    private static Instant epochSeconds(String text) {
        long micros;
        try {
            micros = new BigDecimal(text.trim()).movePointRight(6)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw QueryEngine.invalidQuery("A TIMESTAMP result must be epoch seconds.");
        }
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L)
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static LocalDateTime coerceScriptDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof String string) {
            try {
                return LocalDateTime.parse(string.replace(' ', 'T'));
            } catch (DateTimeParseException e) {
                throw QueryEngine.invalidQuery("Value cannot be coerced to DATETIME.");
            }
        }
        throw QueryEngine.invalidQuery("Value cannot be coerced to DATETIME.");
    }

    private static LocalTime coerceScriptTime(Object value) {
        if (value instanceof LocalTime time) {
            return time;
        }
        if (value instanceof String string) {
            try {
                return LocalTime.parse(string);
            } catch (DateTimeParseException e) {
                throw QueryEngine.invalidQuery("Value cannot be coerced to TIME.");
            }
        }
        throw QueryEngine.invalidQuery("Value cannot be coerced to TIME.");
    }

    private static String coerceScriptBytes(Object value) {
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof String encoded) {
            try {
                return Base64.getEncoder().encodeToString(Base64.getDecoder().decode(encoded));
            } catch (IllegalArgumentException e) {
                throw QueryEngine.invalidQuery("Value cannot be coerced to BYTES.");
            }
        }
        throw QueryEngine.invalidQuery("Value cannot be coerced to BYTES.");
    }

    private static String coerceScriptJson(Object value) {
        try {
            if (value instanceof String json) {
                return JSON.writeValueAsString(JSON.readValue(json, Object.class));
            }
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw QueryEngine.invalidQuery("Value cannot be coerced to JSON.");
        }
    }

    private static Object scriptOperand(String expression, ProceduralContext context) {
        String value = expression.trim();
        if (value.matches("(?i)TRUE|FALSE")) {
            return Boolean.valueOf(value);
        }
        if (value.equalsIgnoreCase("NULL")) {
            return null;
        }
        Matcher dateLiteral = Pattern.compile("(?is)^DATE\\s+'((?:''|[^'])*)'\\s*$").matcher(value);
        if (dateLiteral.matches()) {
            return coerceScriptDate(dateLiteral.group(1).replace("''", "'"));
        }
        Matcher timestampLiteral = Pattern.compile("(?is)^TIMESTAMP\\s+'((?:''|[^'])*)'\\s*$").matcher(value);
        if (timestampLiteral.matches()) {
            return coerceScriptTimestamp(timestampLiteral.group(1).replace("''", "'"));
        }
        Matcher dateTimeLiteral = Pattern.compile("(?is)^DATETIME\\s+'((?:''|[^'])*)'\\s*$").matcher(value);
        if (dateTimeLiteral.matches()) {
            return coerceScriptDateTime(dateTimeLiteral.group(1).replace("''", "'"));
        }
        Matcher timeLiteral = Pattern.compile("(?is)^TIME\\s+'((?:''|[^'])*)'\\s*$").matcher(value);
        if (timeLiteral.matches()) {
            return coerceScriptTime(timeLiteral.group(1).replace("''", "'"));
        }
        List<SqlDialectTranslator.Token> tokens = new SqlDialectTranslator.Lexer(value).tokenize().stream()
                .filter(token -> token.kind != SqlDialectTranslator.Kind.SPACE).toList();
        if (tokens.size() == 1 && tokens.getFirst().kind == SqlDialectTranslator.Kind.STRING) {
            return tokens.getFirst().value;
        }
        if (tokens.size() == 1 && tokens.getFirst().kind == SqlDialectTranslator.Kind.BYTES) {
            return scriptBytesLiteral(tokens.getFirst().value);
        }
        Matcher typedArray = SCRIPT_TYPED_ARRAY_LITERAL.matcher(value);
        if (typedArray.matches()) {
            return scriptArrayLiteral(typedArray.group(2), context);
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            return scriptArrayLiteral(value.substring(1, value.length() - 1), context);
        }
        if (value.matches("[-+]?[0-9]+")) {
            return scriptIntegerLiteral(value);
        }
        if (value.matches("[-+]?[0-9]+\\.[0-9]+")) {
            return Double.valueOf(value);
        }
        Object variable = context.variables().get(value.toLowerCase(Locale.ROOT));
        if (variable == null && !context.variables().containsKey(value.toLowerCase(Locale.ROOT))) {
            throw QueryEngine.invalidQuery("Variable " + value + " has not been declared.");
        }
        return variable;
    }

    /** An integer literal operand, which BigQuery rejects as a whole when it is out of INT64 range. */
    private static boolean isScriptIntegerLiteral(String expression) {
        return expression.trim().matches("[-+]?[0-9]+");
    }

    private static long scriptIntegerLiteral(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw QueryEngine.invalidQuery("Invalid integer literal: " + value);
        }
    }

    private static byte[] scriptBytesLiteral(String bytes) {
        byte[] value = new byte[bytes.length()];
        for (int index = 0; index < bytes.length(); index++) {
            value[index] = (byte) bytes.charAt(index);
        }
        return value;
    }

    /** The declared type of a typed array literal, or null when the expression is anything else. */
    private static String scriptLiteralType(String expression) {
        Matcher literal = SCRIPT_TYPED_ARRAY_LITERAL.matcher(expression.trim());
        return literal.matches() ? "ARRAY<" + normalizeScriptVariableType(literal.group(1)) + ">" : null;
    }

    /** The declared type of the variable an expression names, or null when it names none. */
    private static String scriptVariableReferenceType(String expression, ProceduralContext context) {
        String name = simpleScriptVariableName(expression.trim());
        return name == null ? null : context.variableTypes().get(name.toLowerCase(Locale.ROOT));
    }

    private static List<Object> scriptArrayLiteral(String expression, ProceduralContext context) {
        List<String> elements = splitScriptArrayElements(expression);
        if (elements.size() == 1 && elements.getFirst().isEmpty()) {
            return List.of();
        }
        List<Object> values = new ArrayList<>(elements.size());
        for (String element : elements) {
            values.add(scriptOperand(element, context));
        }
        return values;
    }

    private static List<String> splitScriptArrayElements(String expression) {
        List<SqlDialectTranslator.Token> tokens = new SqlDialectTranslator.Lexer(expression).tokenize();
        List<String> elements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (SqlDialectTranslator.Token token : tokens) {
            if (token.isPunct("(") || token.isPunct("[") || token.isPunct("{")) {
                depth++;
            } else if (token.isPunct(")") || token.isPunct("]") || token.isPunct("}")) {
                depth--;
            }
            if (token.isPunct(",") && depth == 0) {
                elements.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(token.text);
            }
        }
        elements.add(current.toString().trim());
        return elements;
    }

    private String expandScriptVariables(String sql, String projectId, String jobProjectId, QueryOptions options,
                                         ProceduralContext context, JobLocation jobLocation) {
        Set<String> columnNames;
        Set<Integer> columnPositions = Set.of();
        try {
            SqlDialectTranslator.Translation translation = SqlDialectTranslator.translateStatement(sql, projectId,
                    jobProjectId, options.defaultDatasetId(),
                    new SqlDialectTranslator.QueryParameters(options.queryParameters(), options.parameterMode()),
                    context.temporaryTables());
            columnNames = temporaryColumnNames(sql, context);
            addTableColumnNames(translation.tables(), projectId, jobLocation, context, columnNames);
        } catch (GcpException e) {
            // Variable substitution runs before statement-specific handling. A DML statement writes and
            // reads tables, and a DDL statement declares or targets columns, that a query translation
            // cannot describe; their own handling reports errors.
            columnNames = new LinkedHashSet<>();
            columnPositions = statementColumnPositions(sql, projectId, jobProjectId, options, context, jobLocation,
                    columnNames);
        }
        return expandScriptVariables(sql, context, columnNames, columnPositions);
    }

    /**
     * Adds the lower-cased column names of {@code refs}: a temporary table has no entry in the store,
     * so its schema comes from the script context.
     */
    private void addTableColumnNames(Collection<SqlDialectTranslator.TableRef> refs, String projectId,
                                     JobLocation jobLocation, ProceduralContext context, Set<String> columnNames) {
        for (SqlDialectTranslator.TableRef ref : refs) {
            columnNames.addAll(tableColumnNames(ref, projectId, jobLocation, context));
        }
    }

    private Set<String> tableColumnNames(SqlDialectTranslator.TableRef ref, String projectId,
                                         JobLocation jobLocation, ProceduralContext context) {
        if (context.temporaryTables().contains(ref.tableId())) {
            return fieldNames(context.temporaryTableColumns(ref.tableId()));
        }
        Table table;
        try {
            table = tables(projectId, jobLocation).table(ref.datasetId(), ref.tableId());
        } catch (GcpException e) {
            if (e.getHttpStatus() == 404) {
                return Set.of();
            }
            throw e;
        }
        return table.getSchema() != null ? fieldNames(table.getSchema().getFields()) : Set.of();
    }

    private static Set<String> fieldNames(List<TableFieldSchema> fields) {
        Set<String> names = new LinkedHashSet<>();
        if (fields != null) {
            for (TableFieldSchema field : fields) {
                if (field.getName() != null) {
                    names.add(field.getName().toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    /**
     * The token positions where a statement that is not a query resolves a name to a column:
     * the columns a {@code CREATE TABLE} declares, and the {@code INSERT} column list of a statement
     * whose values or query is a scope of its own, where a variable still substitutes as in BigQuery.
     * The other DML statements read their target's columns wherever they name one, so those names join
     * {@code columnNames} instead. Returns the positions of a declaration's column list.
     */
    private Set<Integer> statementColumnPositions(String sql, String projectId, String jobProjectId,
                                                  QueryOptions options, ProceduralContext context,
                                                  JobLocation jobLocation, Set<String> columnNames) {
        Set<Integer> positions = new LinkedHashSet<>();
        List<SqlDialectTranslator.Token> tokens = new SqlDialectTranslator.Lexer(sql).tokenize();

        // A declared schema is a list of column names, whether the table lives in the session or not.
        SqlDialectTranslator.TemporaryTableDefinition declaration = SqlDialectTranslator.temporaryTableDefinition(sql);
        if (declaration != null) {
            positions.addAll(columnListPositions(tokens, declaration.name(), fieldNames(declaration.fields())));
            return positions;
        }

        SqlDialectTranslator.Statement statement;
        try {
            statement = SqlDialectTranslator.parseStatement(sql, projectId, jobProjectId,
                    options.defaultDatasetId(), context.temporaryTables());
        } catch (GcpException e) {
            return positions;
        }
        if (statement.target() == null && !statement.isDml()) {
            return positions;
        }
        Set<String> declared = fieldNames(statement.columns());
        if (!declared.isEmpty() && statement.target() != null) {
            positions.addAll(columnListPositions(tokens, statement.target().tableId(), declared));
        }
        if (!statement.isDml()) {
            return positions;
        }
        // A DML statement against a temporary table has no resolved target: the table is request-local,
        // so its name comes from the statement itself.
        String targetTable = statement.target() != null ? statement.target().tableId()
                : SqlDialectTranslator.temporaryDmlTarget(sql, context.temporaryTables());
        if (targetTable == null) {
            return positions;
        }
        Set<String> target = Set.of();
        if (context.temporaryTables().contains(targetTable)) {
            target = fieldNames(context.temporaryTableColumns(targetTable));
        } else if (statement.target() != null) {
            target = tableColumnNames(statement.target(), projectId, jobLocation, context);
        }
        if (statement.kind() == SqlDialectTranslator.StatementKind.INSERT) {
            positions.addAll(columnListPositions(tokens, targetTable, target));
            // Only the column list names the target's columns; the values or query an INSERT supplies
            // is a scope of its own, where a variable of the same name still substitutes.
            columnNames.addAll(temporarySourceColumnNames(sql, context));
            addDmlTableColumnNames(sql, projectId, jobProjectId, options, jobLocation, context,
                    targetTable, columnNames);
            if (readsTable(tokens, targetTable)) {
                // A self-referencing INSERT reads the columns of its own target.
                columnNames.addAll(target);
            }
            return positions;
        }
        // UPDATE, DELETE and MERGE read their target's columns wherever they name one.
        columnNames.addAll(target);
        if (statement.kind() == SqlDialectTranslator.StatementKind.MERGE) {
            columnNames.addAll(temporarySourceColumnNames(sql, context));
            addDmlTableColumnNames(sql, projectId, jobProjectId, options, jobLocation, context, null, columnNames);
        }
        return positions;
    }

    /**
     * Adds the columns of the tables a DML statement reads, leaving out the target {@code skip}. The
     * translation that names them rejects a shape it cannot translate; the statement's own handling
     * then reports that error.
     */
    private void addDmlTableColumnNames(String sql, String projectId, String jobProjectId, QueryOptions options,
                                        JobLocation jobLocation, ProceduralContext context,
                                        String skip, Set<String> columnNames) {
        SqlDialectTranslator.Translation translation;
        try {
            translation = SqlDialectTranslator.translateDml(sql, projectId, jobProjectId, options.defaultDatasetId(),
                    new SqlDialectTranslator.QueryParameters(options.queryParameters(), options.parameterMode()),
                    ref -> null);
        } catch (GcpException e) {
            return;
        }
        for (SqlDialectTranslator.TableRef ref : translation.tables()) {
            if (skip == null || !skip.equalsIgnoreCase(ref.tableId())) {
                columnNames.addAll(tableColumnNames(ref, projectId, jobLocation, context));
            }
        }
    }

    /**
     * True when {@code tableName} stands in a table position the statement reads: a self-referencing
     * {@code INSERT} reads the columns of the table it writes.
     */
    private static boolean readsTable(List<SqlDialectTranslator.Token> tokens, String tableName) {
        for (int index = 0; index < tokens.size(); index++) {
            SqlDialectTranslator.Token token = tokens.get(index);
            if (token.kind != SqlDialectTranslator.Kind.IDENT && token.kind != SqlDialectTranslator.Kind.QIDENT
                    || !tableName.equalsIgnoreCase(token.identifierText())) {
                continue;
            }
            if (tablePosition(tokens, index, Set.of("FROM", "JOIN", "USING"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Positions of the column names in the parenthesized list that follows {@code tableName}: the
     * columns a {@code CREATE TABLE} declares, or the column list of an {@code INSERT}. BigQuery reads
     * those names as columns, so a variable of the same name cannot replace them.
     */
    private static Set<Integer> columnListPositions(List<SqlDialectTranslator.Token> tokens, String tableName,
                                                    Set<String> columnNames) {
        Set<Integer> positions = new LinkedHashSet<>();
        if (tableName == null || columnNames.isEmpty()) {
            return positions;
        }
        int open = -1;
        for (int index = 0; index < tokens.size() && open < 0; index++) {
            SqlDialectTranslator.Token token = tokens.get(index);
            if (token.kind != SqlDialectTranslator.Kind.IDENT && token.kind != SqlDialectTranslator.Kind.QIDENT
                    || !tableName.equalsIgnoreCase(token.identifierText())) {
                continue;
            }
            int next = nextSignificantToken(tokens, index + 1);
            if (next >= 0 && tokens.get(next).isPunct("(")) {
                open = next;
            }
        }
        if (open < 0) {
            return positions;
        }
        int depth = 0;
        for (int index = open; index < tokens.size(); index++) {
            SqlDialectTranslator.Token token = tokens.get(index);
            if (token.isPunct("(")) {
                depth++;
                continue;
            }
            if (token.isPunct(")")) {
                depth--;
                if (depth == 0) {
                    break;
                }
                continue;
            }
            if (depth == 1 && (token.kind == SqlDialectTranslator.Kind.IDENT
                    || token.kind == SqlDialectTranslator.Kind.QIDENT)
                    && columnNames.contains(token.identifierText().toLowerCase(Locale.ROOT))) {
                positions.add(index);
            }
        }
        return positions;
    }

    /**
     * Columns of the request-local tables a statement names. A temporary table has no entry in the
     * store, so its schema comes from the script context; without these names a variable of the same
     * name would replace a column reference or a DML column list.
     */
    private static Set<String> temporaryColumnNames(String sql, ProceduralContext context) {
        Set<String> columnNames = new LinkedHashSet<>();
        if (context.temporaryTables().isEmpty()) {
            return columnNames;
        }
        List<SqlDialectTranslator.Token> tokens = new SqlDialectTranslator.Lexer(sql).tokenize();
        for (int index = 0; index < tokens.size(); index++) {
            SqlDialectTranslator.Token token = tokens.get(index);
            if (token.kind != SqlDialectTranslator.Kind.IDENT && token.kind != SqlDialectTranslator.Kind.QIDENT) {
                continue;
            }
            String name = token.identifierText();
            if (!context.temporaryTables().contains(name) || !temporaryTablePosition(tokens, index)) {
                continue;
            }
            for (TableFieldSchema field : context.temporaryTableColumns(name)) {
                columnNames.add(field.getName().toLowerCase(Locale.ROOT));
            }
        }
        return columnNames;
    }

    private static boolean temporaryTablePosition(List<SqlDialectTranslator.Token> tokens, int index) {
        return tablePosition(tokens, index, Set.of("FROM", "JOIN", "INTO", "UPDATE", "TABLE", "USING"));
    }

    /**
     * True when the dotted name ending at {@code index} stands in one of the {@code keywords} table
     * positions, so that {@code _SESSION.t} and {@code dataset.table} both count.
     */
    private static boolean tablePosition(List<SqlDialectTranslator.Token> tokens, int index,
                                         Set<String> keywords) {
        int previous = previousSignificantToken(tokens, index - 1);
        while (previous >= 0 && tokens.get(previous).isPunct(".")) {
            int qualifier = previousSignificantToken(tokens, previous - 1);
            if (qualifier < 0) {
                return false;
            }
            previous = previousSignificantToken(tokens, qualifier - 1);
        }
        return previous >= 0 && keywords.contains(tokens.get(previous).upper());
    }

    /** As {@link #temporaryColumnNames(String, ProceduralContext)}, for the tables a query reads. */
    private static Set<String> temporarySourceColumnNames(String sql, ProceduralContext context) {
        Set<String> columnNames = new LinkedHashSet<>();
        if (context.temporaryTables().isEmpty()) {
            return columnNames;
        }
        List<SqlDialectTranslator.Token> tokens = new SqlDialectTranslator.Lexer(sql).tokenize();
        for (int index = 0; index < tokens.size(); index++) {
            SqlDialectTranslator.Token token = tokens.get(index);
            if (token.kind != SqlDialectTranslator.Kind.IDENT && token.kind != SqlDialectTranslator.Kind.QIDENT) {
                continue;
            }
            String name = token.identifierText();
            if (!context.temporaryTables().contains(name)
                    || !tablePosition(tokens, index, Set.of("FROM", "JOIN", "USING"))) {
                continue;
            }
            for (TableFieldSchema field : context.temporaryTableColumns(name)) {
                columnNames.add(field.getName().toLowerCase(Locale.ROOT));
            }
        }
        return columnNames;
    }

    private static String expandScriptVariables(String sql, ProceduralContext context, Set<String> columnNames) {
        return expandScriptVariables(sql, context, columnNames, Set.of());
    }

    /**
     * Replaces every script variable a statement may name with its typed literal. {@code columnPositions}
     * are the token indexes a statement resolves as column names even when a variable has that name.
     */
    private static String expandScriptVariables(String sql, ProceduralContext context, Set<String> columnNames,
                                                Set<Integer> columnPositions) {
        List<SqlDialectTranslator.Token> tokens = new SqlDialectTranslator.Lexer(sql).tokenize();
        ScriptNameResolution resolution = scriptNameResolution(tokens);
        StringBuilder expanded = new StringBuilder();
        for (int index = 0; index < tokens.size(); index++) {
            SqlDialectTranslator.Token token = tokens.get(index);
            String name = token.text.toLowerCase(Locale.ROOT);
            int previous = previousSignificantToken(tokens, index - 1);
            int next = nextSignificantToken(tokens, index + 1);
            boolean declarationPosition = previous >= 0 && Set.of("FROM", "JOIN", "INTO", "UPDATE", "TABLE", "AS")
                    .contains(tokens.get(previous).upper());
            boolean functionName = next >= 0 && tokens.get(next).isPunct("(");
            boolean qualifiedField = previous >= 0 && tokens.get(previous).isPunct(".");
            boolean columnName = columnNames.contains(name) || columnPositions.contains(index)
                    || resolution.hides(name, index);
            if (token.kind != SqlDialectTranslator.Kind.IDENT || !context.variables().containsKey(name)
                    || declarationPosition || functionName || qualifiedField || columnName
                    || resolution.isAliasReference(name, index)) {
                expanded.append(token.text);
                continue;
            }
            Object value = context.variables().get(name);
            int dotIndex = nextSignificantToken(tokens, index + 1);
            int fieldIndex = dotIndex >= 0 && tokens.get(dotIndex).isPunct(".")
                    ? nextSignificantToken(tokens, dotIndex + 1) : -1;
            if (value instanceof Map<?, ?> fields && fieldIndex >= 0
                    && tokens.get(fieldIndex).kind == SqlDialectTranslator.Kind.IDENT) {
                ScriptVariableType valueType = null;
                String storedType = context.variableTypes().get(name);
                if (storedType != null && !"STRUCT".equals(storedType)) {
                    valueType = new ScriptVariableTypeParser(storedType).parse();
                }
                Object fieldValue = value;
                int finalFieldIndex = index;
                int memberIndex = fieldIndex;
                while (fieldValue instanceof Map<?, ?> structValues
                        && memberIndex >= 0
                        && tokens.get(memberIndex).kind == SqlDialectTranslator.Kind.IDENT) {
                    String fieldName = tokens.get(memberIndex).text;
                    Map.Entry<?, ?> fieldEntry = structFieldEntry(structValues, fieldName);
                    if (fieldEntry == null) {
                        break;
                    }
                    fieldValue = fieldEntry.getValue();
                    ScriptVariableField declaredField = valueType != null && valueType.isStruct()
                            ? scriptStructField(valueType, fieldName) : null;
                    valueType = declaredField != null ? declaredField.type() : null;
                    finalFieldIndex = memberIndex;
                    int nextDot = nextSignificantToken(tokens, memberIndex + 1);
                    int nestedField = nextDot >= 0 && tokens.get(nextDot).isPunct(".")
                            ? nextSignificantToken(tokens, nextDot + 1) : -1;
                    if (!(fieldValue instanceof Map<?, ?>) || nestedField < 0
                            || tokens.get(nestedField).kind != SqlDialectTranslator.Kind.IDENT) {
                        break;
                    }
                    memberIndex = nestedField;
                }
                if (finalFieldIndex != index) {
                    String literal = valueType != null ? sqlVariableLiteral(fieldValue, valueType)
                            : sqlVariableLiteral(fieldValue);
                    // BigQuery names the column after the field a whole select item ends with.
                    expanded.append(resolution.startsSelectItem(index)
                            && endsSelectListItem(tokens, finalFieldIndex)
                            ? literal + " AS " + tokens.get(finalFieldIndex).text
                            : literal);
                    index = finalFieldIndex;
                    continue;
                }
            }
            String literal = sqlVariableLiteral(value, context.variableTypes().get(name));
            // A variable that is a whole select item keeps its name as the column name, as BigQuery's
            // path expression does, where an expression over it is named f0_, f1_, ...
            expanded.append(resolution.startsSelectItem(index) && endsSelectListItem(tokens, index)
                    ? literal + " AS " + token.text
                    : literal);
        }
        return expanded.toString();
    }

    /**
     * A statement's own name resolution, which BigQuery applies before a script variable of the same name:
     * the token positions where a variable is a whole select item, whose column BigQuery names after it, and
     * the names the statement binds to something else: a CTE name, the alias of a FROM item, and the columns
     * a derived table exposes.
     */
    private record ScriptNameResolution(Set<Integer> selectItemStarts, List<HiddenName> hiddenNames,
                                        int[] depths, String[] clauses,
                                        Map<String, Set<Integer>> aliasDepths) {

        /**
         * {@code names} hide a script variable of that name outside {@code excludedFrom..excludedTo} and, when
         * {@code depth} is not negative, at that query depth and deeper only: a nested query's alias names a
         * column inside its own query, not in the query that encloses it.
         */
        private record HiddenName(Set<String> names, int excludedFrom, int excludedTo, int depth) {}

        private boolean startsSelectItem(int index) {
            return selectItemStarts.contains(index);
        }

        /**
         * Whether the name is an alias of the query at this token: an unqualified name there stands for the
         * select-list alias instead of a script variable, but only in the clauses that resolve aliases and
         * only where that query declares it. BigQuery resolves {@code ORDER BY id} against
         * {@code SELECT name AS id}, while a select-list item of the same name, and a query nested inside,
         * still name the variable: {@code DECLARE id INT64 DEFAULT 99; SELECT id AS id} selects 99.
         */
        private boolean isAliasReference(String name, int index) {
            String clause = clauses[index];
            if (clause == null || !ALIAS_CLAUSES.contains(clause)) {
                return false;
            }
            Set<Integer> declaredDepths = aliasDepths.get(name);
            return declaredDepths != null && declaredDepths.contains(depths[index]);
        }

        private boolean hides(String name, int index) {
            for (HiddenName hidden : hiddenNames) {
                if (hidden.names().contains(name)
                        && (hidden.depth() < 0 || depths[index] >= hidden.depth())
                        && (index < hidden.excludedFrom() || index > hidden.excludedTo())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static ScriptNameResolution scriptNameResolution(List<SqlDialectTranslator.Token> tokens) {
        Set<Integer> selectItemStarts = new LinkedHashSet<>();
        List<ScriptNameResolution.HiddenName> hiddenNames = new ArrayList<>();
        List<String> clauses = new ArrayList<>();
        clauses.add(null);
        List<Boolean> withClauses = new ArrayList<>();
        withClauses.add(false);
        int[] depths = new int[tokens.size()];
        String[] clauseByToken = new String[tokens.size()];
        Map<String, Set<Integer>> aliasDepths = new LinkedHashMap<>();
        List<Integer> queryDepths = new ArrayList<>();
        queryDepths.add(0);
        List<Boolean> queryScopes = new ArrayList<>();
        queryScopes.add(true);
        for (int index = 0; index < tokens.size(); index++) {
            SqlDialectTranslator.Token token = tokens.get(index);
            if (token.kind == SqlDialectTranslator.Kind.PUNCT) {
                if (token.isPunct("(") || token.isPunct("[") || token.isPunct("{")) {
                    // A parenthesis that opens a query is a scope of its own; one that groups an expression
                    // keeps the clause and the alias scope around it.
                    boolean query = token.isPunct("(") && startsQuery(tokens, index);
                    if (token.isPunct("(")) {
                        addDerivedTableColumns(tokens, index, clauses.getLast(), hiddenNames);
                    }
                    clauses.add(query ? null : clauses.getLast());
                    withClauses.add(false);
                    queryDepths.add(query ? queryDepths.getLast() + 1 : queryDepths.getLast());
                    queryScopes.add(query);
                } else if ((token.isPunct(")") || token.isPunct("]") || token.isPunct("}"))
                        && clauses.size() > 1) {
                    clauses.removeLast();
                    withClauses.removeLast();
                    queryDepths.removeLast();
                    queryScopes.removeLast();
                }
                depths[index] = queryDepths.getLast();
                continue;
            }
            depths[index] = queryDepths.getLast();
            if (token.kind != SqlDialectTranslator.Kind.IDENT) {
                continue;
            }
            int depth = clauses.size() - 1;
            boolean withClause = withClauses.get(depth);
            String upper = token.upper();
            int previous = previousSignificantToken(tokens, index - 1);
            int next = nextSignificantToken(tokens, index + 1);
            if ("WITH".equals(upper)) {
                withClauses.set(depth, true);
            } else if (SCRIPT_CLAUSE_KEYWORDS.contains(upper) || "SELECT".equals(upper)) {
                clauses.set(depth, upper);
                // A WITH clause collects CTE names; the query that follows it does not.
                withClauses.set(depth, false);
            }
            // Only a query's own select list names its columns. A function call's arguments or a
            // grouped expression inherit the clause they sit in, but declare nothing there.
            boolean selectList = queryScopes.get(depth) && "SELECT".equals(clauses.get(depth));
            String name = token.text.toLowerCase(Locale.ROOT);
            clauseByToken[index] = clauses.get(depth);
            if (previous >= 0 && tokens.get(previous).isKeyword("AS") && selectList) {
                aliasDepths.computeIfAbsent(name, key -> new LinkedHashSet<>()).add(depths[index]);
            }
            if (withClause && next >= 0 && tokens.get(next).isKeyword("AS")) {
                int open = nextSignificantToken(tokens, next + 1);
                if (open >= 0 && tokens.get(open).isPunct("(")) {
                    // The name a CTE declares is visible outside its own definition.
                    hiddenNames.add(new ScriptNameResolution.HiddenName(Set.of(name), open,
                            matchingParenthesis(tokens, open), depths[index]));
                    continue;
                }
            }
            if ("FROM".equals(clauses.get(depth)) && previous >= 0 && tokens.get(previous).isKeyword("AS")) {
                // The alias of a table, UNNEST or derived table names a column scope of its own, which is
                // visible in the query that declares it and in a query nested inside it.
                hiddenNames.add(new ScriptNameResolution.HiddenName(Set.of(name), -1, -1, depths[index]));
                continue;
            }
            if (selectList && startsSelectItem(tokens, previous)) {
                selectItemStarts.add(index);
            }
        }
        return new ScriptNameResolution(selectItemStarts, hiddenNames, depths, clauseByToken, aliasDepths);
    }

    /**
     * Adds the columns a derived table in a FROM clause exposes: the aliases of its select list and the
     * names its bare select items carry. A name hides a variable outside the item, where BigQuery
     * resolves the derived table's column first.
     */
    private static void addDerivedTableColumns(List<SqlDialectTranslator.Token> tokens, int open, String clause,
                                               List<ScriptNameResolution.HiddenName> hiddenNames) {
        int previous = previousSignificantToken(tokens, open - 1);
        boolean fromItem = "FROM".equals(clause) && previous >= 0
                && (tokens.get(previous).isKeyword("FROM") || tokens.get(previous).isKeyword("JOIN")
                        || tokens.get(previous).isPunct(","));
        if (!fromItem) {
            return;
        }
        int close = matchingParenthesis(tokens, open);
        Set<String> names = new LinkedHashSet<>();
        List<String> innerClauses = new ArrayList<>();
        innerClauses.add(null);
        for (int index = open + 1; index < close; index++) {
            SqlDialectTranslator.Token token = tokens.get(index);
            if (token.kind == SqlDialectTranslator.Kind.PUNCT) {
                if (token.isPunct("(") || token.isPunct("[") || token.isPunct("{")) {
                    innerClauses.add(null);
                } else if ((token.isPunct(")") || token.isPunct("]") || token.isPunct("}"))
                        && innerClauses.size() > 1) {
                    innerClauses.removeLast();
                }
                continue;
            }
            if (token.kind != SqlDialectTranslator.Kind.IDENT) {
                continue;
            }
            int depth = innerClauses.size() - 1;
            String upper = token.upper();
            if (SCRIPT_CLAUSE_KEYWORDS.contains(upper) || "SELECT".equals(upper) || "WITH".equals(upper)) {
                innerClauses.set(depth, upper);
                continue;
            }
            if (!"SELECT".equals(innerClauses.get(depth))) {
                continue;
            }
            int itemStart = previousSignificantToken(tokens, index - 1);
            if (itemStart >= 0 && tokens.get(itemStart).isKeyword("AS")
                    || startsSelectItem(tokens, itemStart) && endsSelectListItem(tokens, index)) {
                names.add(token.text.toLowerCase(Locale.ROOT));
            }
        }
        if (!names.isEmpty()) {
            // A derived table exposes its columns to the query that reads it, so any depth qualifies.
            hiddenNames.add(new ScriptNameResolution.HiddenName(names, open, close, -1));
        }
    }

    /** Whether the parenthesis at {@code open} opens a query rather than grouping an expression. */
    private static boolean startsQuery(List<SqlDialectTranslator.Token> tokens, int open) {
        int next = nextSignificantToken(tokens, open + 1);
        return next >= 0 && (tokens.get(next).isPunct("(") || tokens.get(next).isKeyword("SELECT")
                || tokens.get(next).isKeyword("WITH"));
    }

    /** The first token of a select item, skipping the {@code DISTINCT} or {@code ALL} that may precede it. */
    private static boolean startsSelectItem(List<SqlDialectTranslator.Token> tokens, int previous) {
        int at = previous;
        while (at >= 0 && (tokens.get(at).isKeyword("DISTINCT") || tokens.get(at).isKeyword("ALL"))) {
            at = previousSignificantToken(tokens, at - 1);
        }
        return at >= 0 && (tokens.get(at).isKeyword("SELECT") || tokens.get(at).isPunct(","));
    }

    /** Whether the select item that starts earlier ends at {@code index}. */
    private static boolean endsSelectListItem(List<SqlDialectTranslator.Token> tokens, int index) {
        int next = nextSignificantToken(tokens, index + 1);
        return next < 0 || tokens.get(next).isPunct(",") || tokens.get(next).isPunct(")")
                || tokens.get(next).isPunct(";")
                || tokens.get(next).kind == SqlDialectTranslator.Kind.IDENT
                        && SCRIPT_CLAUSE_KEYWORDS.contains(tokens.get(next).upper());
    }

    private static int matchingParenthesis(List<SqlDialectTranslator.Token> tokens, int open) {
        int depth = 0;
        for (int index = open; index < tokens.size(); index++) {
            SqlDialectTranslator.Token token = tokens.get(index);
            if (token.kind != SqlDialectTranslator.Kind.PUNCT) {
                continue;
            }
            if (token.isPunct("(") || token.isPunct("[") || token.isPunct("{")) {
                depth++;
            } else if (token.isPunct(")") || token.isPunct("]") || token.isPunct("}")) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return tokens.size() - 1;
    }


    private static int previousSignificantToken(List<SqlDialectTranslator.Token> tokens, int from) {
        for (int index = from; index >= 0; index--) {
            if (tokens.get(index).kind != SqlDialectTranslator.Kind.SPACE) {
                return index;
            }
        }
        return -1;
    }

    private static final Set<String> SCRIPT_CLAUSE_KEYWORDS = Set.of("FROM", "WHERE", "GROUP", "HAVING",
            "QUALIFY", "WINDOW", "ORDER", "LIMIT", "OFFSET", "UNION", "INTERSECT", "EXCEPT");

    /** The clauses where BigQuery resolves an unqualified name against the select-list aliases. */
    private static final Set<String> ALIAS_CLAUSES = Set.of("GROUP", "ORDER", "HAVING", "QUALIFY", "WINDOW");

    private static int nextSignificantToken(List<SqlDialectTranslator.Token> tokens, int from) {
        for (int index = from; index < tokens.size(); index++) {
            if (tokens.get(index).kind != SqlDialectTranslator.Kind.SPACE) {
                return index;
            }
        }
        return -1;
    }

    private static String sqlVariableLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Map<?, ?> fields) {
            List<String> values = new ArrayList<>(fields.size());
            for (Map.Entry<?, ?> entry : fields.entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    throw QueryEngine.invalidQuery("STRUCT variable field names must be strings.");
                }
                values.add(sqlVariableLiteral(entry.getValue()) + " AS " + quoteScriptTypeField(name));
            }
            return "STRUCT(" + String.join(", ", values) + ")";
        }
        return sqlVariableLiteral(value, inferredScriptVariableTypeNode(value));
    }

    private static String sqlVariableLiteral(Object value, String type) {
        if (value == null) {
            return "STRUCT".equalsIgnoreCase(type) ? "NULL"
                    : sqlVariableLiteral(null, new ScriptVariableTypeParser(type).parse());
        }
        if ("STRUCT".equalsIgnoreCase(type) && value instanceof Map<?, ?>) {
            return sqlVariableLiteral(value);
        }
        return sqlVariableLiteral(value, new ScriptVariableTypeParser(type).parse());
    }

    private static String sqlVariableLiteral(Object value, ScriptVariableType type) {
        if (value == null) {
            // A NULL variable keeps its declared type, so `DECLARE s STRING; SELECT s` reports STRING
            // and a null STRUCT still exposes its fields to `s.field`.
            return "CAST(NULL AS " + scriptVariableTypeSql(type) + ")";
        }
        if (type.isArray()) {
            if (!(value instanceof List<?> values)) {
                throw QueryEngine.invalidQuery("Value cannot be represented as " + scriptVariableTypeSql(type) + ".");
            }
            String elements = values.stream().map(element -> sqlVariableLiteral(element, type.elementType()))
                    .collect(Collectors.joining(", "));
            return "CAST([" + elements + "] AS " + scriptVariableTypeSql(type) + ")";
        }
        if (type.isStruct()) {
            if (!(value instanceof Map<?, ?> values)) {
                throw QueryEngine.invalidQuery("Value cannot be represented as " + scriptVariableTypeSql(type) + ".");
            }
            List<Map.Entry<?, ?>> entries = new ArrayList<>(values.entrySet());
            List<String> fields = new ArrayList<>(type.fields().size());
            for (int index = 0; index < type.fields().size(); index++) {
                ScriptVariableField field = type.fields().get(index);
                Map.Entry<?, ?> entry = structFieldEntry(entries, field.name(), index);
                if (entry == null) {
                    throw QueryEngine.invalidQuery("STRUCT value does not match its declared type.");
                }
                String expression = sqlVariableLiteral(entry.getValue(), field.type());
                fields.add(field.name() == null ? expression
                        : expression + " AS " + quoteScriptTypeField(field.name()));
            }
            return "CAST(STRUCT(" + String.join(", ", fields) + ") AS " + scriptVariableTypeSql(type) + ")";
        }
        return switch (type.scalarType()) {
            case "DATE" -> "DATE '" + coerceScriptDate(value) + "'";
            case "TIMESTAMP" -> "TIMESTAMP '" + coerceScriptTimestamp(value) + "'";
            case "DATETIME" -> "DATETIME '" + coerceScriptDateTime(value).toString().replace('T', ' ') + "'";
            case "TIME" -> "TIME '" + coerceScriptTime(value) + "'";
            case "BYTES" -> "FROM_BASE64('" + coerceScriptBytes(value) + "')";
            case "JSON" -> "CAST(" + scriptStringLiteral(coerceScriptJson(value)) + " AS JSON)";
            case "STRING" -> scriptStringLiteral(value.toString());
            case "BOOL" -> value.toString();
            case "INT64" -> value.toString();
            // A FLOAT64 literal is a DOUBLE, so arithmetic over it stays floating point: DuckDB reads a
            // bare decimal as NUMERIC, which would make `f + 0.2` a NUMERIC of a different value.
            case "FLOAT64" -> "CAST(" + value + " AS DOUBLE)";
            case "NUMERIC" -> "CAST('" + coerceScriptNumeric(value).toPlainString() + "' AS NUMERIC)";
            default -> throw QueryEngine.invalidQuery(
                    "Variable type " + type.scalarType() + " is not supported.");
        };
    }

    /**
     * A GoogleSQL string literal. The substitution result is lexed again for DuckDB, so a backslash
     * has to be escaped or it would be read as the start of a character escape.
     */
    private static String scriptStringLiteral(String value) {
        return '\'' + value.replace("\\", "\\\\").replace("'", "\\'") + '\'';
    }

    /** A directly evaluated condition: {@code resolved} is false when the SQL engine has to answer. */
    private record ScriptCondition(boolean resolved, Boolean value) {

        private static final ScriptCondition UNRESOLVED = new ScriptCondition(false, null);

        private static ScriptCondition of(Boolean value) {
            return new ScriptCondition(true, value);
        }
    }

    private static ScriptCondition evaluateScriptCondition(String condition, ProceduralContext context) {
        String expression = condition.trim();
        if (expression.matches("(?i)TRUE|FALSE")) {
            return ScriptCondition.of(Boolean.valueOf(expression));
        }
        if (expression.regionMatches(true, 0, "NOT ", 0, 4)) {
            ScriptCondition nested = evaluateScriptCondition(expression.substring(4), context);
            if (!nested.resolved()) {
                return ScriptCondition.UNRESOLVED;
            }
            // NOT NULL is NULL, not TRUE: an unknown condition keeps its unknown value through negation.
            return ScriptCondition.of(nested.value() == null ? null : !nested.value());
        }
        Matcher nullCheck = Pattern.compile("(?i)^(.+?)\\s+IS\\s+(NOT\\s+)?NULL$").matcher(expression);
        if (nullCheck.matches()) {
            try {
                boolean isNull = scriptOperand(nullCheck.group(1), context) == null;
                return ScriptCondition.of(nullCheck.group(2) != null ? !isNull : isNull);
            } catch (GcpException e) {
                return ScriptCondition.UNRESOLVED;
            }
        }
        try {
            Object value = scriptOperand(expression, context);
            if (value == null) {
                return ScriptCondition.of(null);
            }
            if (value instanceof Boolean booleanValue) {
                return ScriptCondition.of(booleanValue);
            }
        } catch (GcpException ignored) {
            // Non-scalar conditions are delegated to the configured SQL engine.
        }
        Matcher comparison = Pattern.compile("^(.+?)\\s*(<=|>=|!=|<>|=|<|>)\\s*(.+)$").matcher(expression);
        if (!comparison.matches()) {
            return ScriptCondition.UNRESOLVED;
        }
        Object left;
        Object right;
        try {
            left = scriptOperand(comparison.group(1), context);
            right = scriptOperand(comparison.group(3), context);
        } catch (GcpException e) {
            return ScriptCondition.UNRESOLVED;
        }
        if (left == null || right == null) {
            // A comparison with NULL is NULL, which the caller treats as false only when choosing a branch.
            return ScriptCondition.of(null);
        }
        int order;
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            order = compareScriptNumbers(leftNumber, rightNumber);
        } else if (left instanceof String leftString && right instanceof String rightString) {
            order = leftString.compareTo(rightString);
        } else if (left instanceof Boolean leftBoolean && right instanceof Boolean rightBoolean) {
            order = Boolean.compare(leftBoolean, rightBoolean);
        } else {
            return ScriptCondition.UNRESOLVED;
        }
        return ScriptCondition.of(switch (comparison.group(2)) {
            case "=" -> order == 0;
            case "!=", "<>" -> order != 0;
            case "<" -> order < 0;
            case "<=" -> order <= 0;
            case ">" -> order > 0;
            case ">=" -> order >= 0;
            default -> null;
        });
    }

    /**
     * Integral operands compare exactly: routing INT64 through a double would report
     * 9007199254740993 and 9007199254740992 as equal. A FLOAT64 operand makes BigQuery coerce both.
     */
    private static int compareScriptNumbers(Number left, Number right) {
        if (left instanceof BigDecimal || right instanceof BigDecimal) {
            return scriptDecimal(left).compareTo(scriptDecimal(right));
        }
        boolean integral = !(left instanceof Double || left instanceof Float)
                && !(right instanceof Double || right instanceof Float);
        return integral ? Long.compare(left.longValue(), right.longValue())
                : Double.compare(left.doubleValue(), right.doubleValue());
    }

    private void executeProceduralFor(String projectId, String jobProjectId, SqlDialectTranslator.ForBlock loop,
                                      QueryOptions options, StoredJob job, ProceduralContext context,
                                      JobLocation jobLocation) {
        String variable = loop.variable().toLowerCase(Locale.ROOT);
        if (context.variables().containsKey(variable)) {
            throw QueryEngine.invalidQuery("FOR loop variable " + loop.variable()
                    + " conflicts with a variable in the current scope.");
        }
        BigQuerySqlEngine.Result rows = engine.execute(request(projectId, jobProjectId,
                context.prependTo(expandScriptVariables(loop.query(), projectId, jobProjectId, options, context,
                        jobLocation)),
                options, false),
                tables(projectId, jobLocation));
        context.recordBytes(rows.totalBytesProcessed());
        context.clearQueryResult();
        // The row a FOR loop exposes is a struct of the query's fields, so a field keeps its type when
        // the loop substitutes it or binds it as a dynamic parameter.
        context.variableTypes().put(variable, scriptRowType(rows.schema()));
        try {
            for (int index = 0; index < Math.min(rows.rows().size(), 1000); index++) {
                context.variables().put(variable, scriptResultRow(rows.rows().get(index), rows.schema()));
                try {
                    for (SqlDialectTranslator.ScriptItem nested : loop.items()) {
                        executeProceduralItem(projectId, jobProjectId, nested, options, job, context, jobLocation);
                    }
                } catch (LoopControlSignal signal) {
                    if (!handlesLoopSignal(signal, loop.label())) {
                        throw signal;
                    }
                    if (!signal.continueLoop) {
                        return;
                    }
                }
            }
            if (rows.rows().size() > 1000) {
                throw QueryEngine.invalidQuery(
                        "FOR loop exceeded the floci BigQuery emulator iteration limit of 1000.");
            }
        } finally {
            context.variables().remove(variable);
            context.variableTypes().remove(variable);
        }
    }

    private void executeProceduralLoop(String projectId, String jobProjectId,
                                        List<SqlDialectTranslator.ScriptItem> items,
                                        String label, String condition, boolean postTest, QueryOptions options,
                                        StoredJob job,
                                        ProceduralContext context, JobLocation jobLocation) {
        for (int iteration = 0; iteration < 1000; iteration++) {
            if (condition != null && !postTest && !proceduralCondition(projectId, jobProjectId, condition, options, context,
                    jobLocation)) {
                return;
            }
            boolean broken = false;
            try {
                for (SqlDialectTranslator.ScriptItem nested : items) {
                    executeProceduralItem(projectId, jobProjectId, nested, options, job, context, jobLocation);
                }
            } catch (LoopControlSignal signal) {
                if (!handlesLoopSignal(signal, label)) {
                    throw signal;
                }
                if (!signal.continueLoop) {
                    broken = true;
                }
            }
            if (broken) {
                return;
            }
            if (condition != null && postTest && proceduralCondition(projectId, jobProjectId, condition, options, context,
                    jobLocation)) {
                return;
            }
        }
        throw QueryEngine.invalidQuery("Loop exceeded the floci BigQuery emulator iteration limit of 1000.");
    }

    private boolean proceduralCondition(String projectId, String jobProjectId, String condition,
                                        QueryOptions options, ProceduralContext context, JobLocation jobLocation) {
        ScriptCondition directCondition = evaluateScriptCondition(condition, context);
        if (directCondition.resolved()) {
            return Boolean.TRUE.equals(directCondition.value());
        }
        BigQuerySqlEngine.Result result = engine.execute(request(projectId, jobProjectId,
                context.prependTo("SELECT " + expandScriptVariables(condition, context, Set.of())
                        + " AS _floci_condition"),
                options, false), tables(projectId, jobLocation));
        context.recordBytes(result.totalBytesProcessed());
        List<TableFieldSchema> fields = result.schema() != null && result.schema().getFields() != null
                ? result.schema().getFields() : List.of();
        if (fields.size() != 1 || !"BOOLEAN".equalsIgnoreCase(fields.getFirst().getType())) {
            throw QueryEngine.invalidQuery("Loop condition must be a BOOL expression");
        }
        boolean matches = !result.rows().isEmpty()
                && Boolean.TRUE.equals(result.rows().getFirst().get("_floci_condition"));
        return matches;
    }

    private void executeProceduralWhile(String projectId, String jobProjectId, SqlDialectTranslator.WhileBlock loop,
                                         QueryOptions options, StoredJob job, ProceduralContext context,
                                         JobLocation jobLocation) {
        for (int iteration = 0; iteration < 1000; iteration++) {
            ScriptCondition evaluatedCondition = evaluateScriptCondition(loop.condition(), context);
            if (evaluatedCondition.resolved()) {
                if (!Boolean.TRUE.equals(evaluatedCondition.value())) {
                    return;
                }
                try {
                    for (SqlDialectTranslator.ScriptItem nested : loop.items()) {
                        executeProceduralItem(projectId, jobProjectId, nested, options, job, context, jobLocation);
                    }
                } catch (LoopControlSignal signal) {
                    if (!handlesLoopSignal(signal, loop.label())) {
                        throw signal;
                    }
                    if (!signal.continueLoop) {
                        return;
                    }
                }
                continue;
            }
            BigQuerySqlEngine.Result result = engine.execute(request(projectId, jobProjectId,
                    context.prependTo("SELECT " + expandScriptVariables(loop.condition(), context, Set.of())
                            + " AS _floci_condition"), options, false),
                    tables(projectId, jobLocation));
            context.recordBytes(result.totalBytesProcessed());
            List<TableFieldSchema> fields = result.schema() != null && result.schema().getFields() != null
                    ? result.schema().getFields() : List.of();
            if (fields.size() != 1 || !"BOOLEAN".equalsIgnoreCase(fields.getFirst().getType())) {
                throw QueryEngine.invalidQuery("WHILE condition must be a BOOL expression");
            }
            boolean matches = !result.rows().isEmpty()
                    && Boolean.TRUE.equals(result.rows().getFirst().get("_floci_condition"));
            if (!matches) {
                return;
            }
            try {
                for (SqlDialectTranslator.ScriptItem nested : loop.items()) {
                    executeProceduralItem(projectId, jobProjectId, nested, options, job, context, jobLocation);
                }
            } catch (LoopControlSignal signal) {
                if (!handlesLoopSignal(signal, loop.label())) {
                    throw signal;
                }
                if (!signal.continueLoop) {
                    return;
                }
            }
        }
        throw QueryEngine.invalidQuery("WHILE loop exceeded the floci BigQuery emulator iteration limit of 1000.");
    }

    private void executeProceduralItem(String projectId, String jobProjectId, SqlDialectTranslator.ScriptItem item,
                                       QueryOptions options, StoredJob job, ProceduralContext context,
                                       JobLocation jobLocation) {
        clearStatementStatistics(job);
        if (item instanceof SqlDialectTranslator.BeginBlock block) {
            Map<String, Object> variables = new LinkedHashMap<>(context.variables());
            Map<String, String> variableTypes = new LinkedHashMap<>(context.variableTypes());
            try {
                try {
                    for (SqlDialectTranslator.ScriptItem nested : block.items()) {
                        executeProceduralItem(projectId, jobProjectId, nested, options, job, context, jobLocation);
                    }
                } catch (LoopControlSignal signal) {
                    if (block.label() == null || !block.label().equalsIgnoreCase(signal.label)
                            || signal.continueLoop) {
                        throw signal;
                    }
                }
            } finally {
                // A block scopes only the variables it declares. An assignment to a variable declared
                // outside the block survives it, as it does in BigQuery.
                context.variables().keySet().removeIf(name -> !variables.containsKey(name));
                context.variableTypes().keySet().removeIf(name -> !variableTypes.containsKey(name));
            }
            return;
        }
        if (item instanceof SqlDialectTranslator.WhileBlock loop) {
            executeProceduralWhile(projectId, jobProjectId, loop, options, job, context, jobLocation);
            return;
        }
        if (item instanceof SqlDialectTranslator.LoopBlock loop) {
            executeProceduralLoop(projectId, jobProjectId,
                                        loop.items(), loop.label(), null, false,
                    options, job, context, jobLocation);
            return;
        }
        if (item instanceof SqlDialectTranslator.RepeatBlock loop) {
            executeProceduralLoop(projectId, jobProjectId,
                                        loop.items(), loop.label(), loop.condition(), true,
                    options, job, context, jobLocation);
            return;
        }
        if (item instanceof SqlDialectTranslator.ForBlock loop) {
            executeProceduralFor(projectId, jobProjectId, loop, options, job, context, jobLocation);
            return;
        }
        if (item instanceof SqlDialectTranslator.LoopControl control) {
            throw new LoopControlSignal(control.continueLoop(), control.label());
        }
        if (item instanceof SqlDialectTranslator.CaseBlock caseBlock) {
            for (SqlDialectTranslator.CaseWhen branch : caseBlock.branches()) {
                if (proceduralCondition(projectId, jobProjectId, caseBlock.condition(branch), options, context, jobLocation)) {
                    for (SqlDialectTranslator.ScriptItem nested : branch.items()) {
                        executeProceduralItem(projectId, jobProjectId, nested, options, job, context, jobLocation);
                    }
                    return;
                }
            }
            for (SqlDialectTranslator.ScriptItem nested : caseBlock.otherwise()) {
                executeProceduralItem(projectId, jobProjectId, nested, options, job, context, jobLocation);
            }
            return;
        }
        if (item instanceof SqlDialectTranslator.SqlItem sqlItem) {
            if (applyScriptVariableStatement(sqlItem.sql(), projectId, jobProjectId, options, context, jobLocation)) {
                return;
            }
            if (isExecuteImmediateStatement(sqlItem.sql())) {
                executeImmediate(projectId, jobProjectId, sqlItem.sql(), options, job, context, jobLocation);
                return;
            }
            SqlDialectTranslator.SqlItem sql = new SqlDialectTranslator.SqlItem(
                    expandScriptVariables(sqlItem.sql(), projectId, jobProjectId, options, context, jobLocation));
            SqlDialectTranslator.TemporaryTableDefinition temporaryTable =
                    SqlDialectTranslator.temporaryTableDefinition(sql.sql());
            if (temporaryTable != null) {
                if (temporaryTable.ifNotExists() && context.temporaryTables().contains(temporaryTable.name())) {
                    context.clearQueryResult();
                    return;
                }
                materializeTemporaryTable(projectId, jobProjectId, sql.sql(), temporaryTable.name(),
                        temporaryTable.fields(), options, context, jobLocation, false);
                return;
            }
            String truncatedTemporaryTable = SqlDialectTranslator.temporaryTruncateTarget(
                    sql.sql(), context.temporaryTables());
            if (truncatedTemporaryTable != null) {
                long removed = context.truncateTemporaryTable(truncatedTemporaryTable);
                job.setDmlStats(Map.of("deletedRowCount", String.valueOf(removed)));
                return;
            }
            String temporaryTarget = SqlDialectTranslator.temporaryDmlTarget(sql.sql(), context.temporaryTables());
            if (temporaryTarget != null) {
                materializeTemporaryDml(projectId, jobProjectId, sql.sql(), temporaryTarget, options, context,
                        jobLocation, job);
                return;
            }
            validateTemporaryFunctionDeclaration(projectId, jobProjectId, sql.sql(), options, context);
            if (context.applyTemporaryObjectChange(sql.sql(), projectId)) {
                return;
            }
            SqlDialectTranslator.Statement statement = SqlDialectTranslator.parseStatement(sql.sql(), projectId,
                    jobProjectId, options.defaultDatasetId());
            QueryOptions leaf = leafOptions(context.prependTo(sql.sql()), options);
            statement = withTemporarySetup(statement, context);
            if (statement.kind() == SqlDialectTranslator.StatementKind.QUERY) {
                BigQuerySqlEngine.Result result = engine.execute(
                        request(projectId, jobProjectId, leaf.sql(), leaf, false), tables(projectId, jobLocation));
                context.recordQueryResult(result);
            } else {
                job.setTotalBytesProcessed("0");
                // A script statement writes in the job's location just like a lone statement does.
                admitWrittenDataset(statement, jobLocation);
                executeStatement(projectId, jobProjectId, statement, leaf, job, jobLocation);
                context.recordBytes(Long.parseLong(job.getTotalBytesProcessed()));
                context.clearQueryResult();
            }
            return;
        }
        SqlDialectTranslator.IfBlock conditional = (SqlDialectTranslator.IfBlock) item;
        ScriptCondition directCondition = evaluateScriptCondition(conditional.condition(), context);
        boolean matches;
        if (directCondition.resolved()) {
            matches = Boolean.TRUE.equals(directCondition.value());
        } else {
            BigQuerySqlEngine.Result result = engine.execute(request(projectId, jobProjectId,
                    context.prependTo("SELECT " + expandScriptVariables(conditional.condition(), context, Set.of())
                            + " AS _floci_condition"), options, false), tables(projectId, jobLocation));
            context.recordBytes(result.totalBytesProcessed());
            List<TableFieldSchema> conditionFields = result.schema() != null && result.schema().getFields() != null
                    ? result.schema().getFields() : List.of();
            if (conditionFields.size() != 1
                    || !"BOOLEAN".equalsIgnoreCase(conditionFields.getFirst().getType())) {
                throw QueryEngine.invalidQuery("IF condition must be a BOOL expression");
            }
            matches = !result.rows().isEmpty()
                    && Boolean.TRUE.equals(result.rows().getFirst().get("_floci_condition"));
        }
        for (SqlDialectTranslator.ScriptItem branch : matches ? conditional.whenTrue() : conditional.whenFalse()) {
            executeProceduralItem(projectId, jobProjectId, branch, options, job, context, jobLocation);
        }
    }

    private static void validateTemporaryFunctionDeclaration(String projectId, String jobProjectId, String sql,
                                                               QueryOptions options, ProceduralContext context) {
        SqlDialectTranslator.validateTemporaryFunctionDeclaration(sql, projectId, jobProjectId,
                options.defaultDatasetId(),
                new SqlDialectTranslator.QueryParameters(options.queryParameters(), options.parameterMode()),
                context.temporaryTables());
    }

    private void materializeTemporaryTable(String projectId, String jobProjectId, String sql, String name,
                                           List<TableFieldSchema> declaredFields, QueryOptions options,
                                           ProceduralContext context, JobLocation jobLocation, boolean dryRun) {
        String snapshot = context.prependTo(sql + ";\nSELECT * FROM " + googleIdentifier(name));
        QueryOptions leaf = leafOptions(snapshot, options);
        BigQuerySqlEngine.Result result = engine.execute(request(projectId, jobProjectId, leaf.sql(), leaf, dryRun),
                tables(projectId, jobLocation));
        context.recordBytes(result.totalBytesProcessed());
        if (!declaredFields.isEmpty()) {
            result = new BigQuerySqlEngine.Result(new TableSchema(declaredFields), result.rows(),
                    result.statementType(), result.totalBytesProcessed());
        }
        // "A REQUIRED field cannot store a NULL value": creating the table rejects the values an insert
        // into it would reject.
        RequiredFields.check(result.schema() != null ? result.schema().getFields() : List.of(), result.rows());
        context.materializeTemporaryTable(name, result);
    }

    private void materializeTemporaryDml(String projectId, String jobProjectId, String sql, String name,
                                         QueryOptions options, ProceduralContext context, JobLocation jobLocation,
                                         StoredJob job) {
        QueryOptions leaf = leafOptions(context.prependTo(sql), options);
        BigQuerySqlEngine.Result existing = context.temporaryTableResult(name);
        List<TableFieldSchema> targetFields = existing.schema() != null && existing.schema().getFields() != null
                ? existing.schema().getFields() : List.of();
        BigQuerySqlEngine.TemporaryDmlResult result = engine.executeTemporaryDml(
                request(projectId, jobProjectId, leaf.sql(), leaf, false), tables(projectId, jobLocation), name,
                targetFields);
        BigQuerySqlEngine.DmlResult dml = result.dmlResult();
        context.recordBytes(dml.totalBytesProcessed());
        context.materializeTemporaryTable(name, new BigQuerySqlEngine.Result(result.schema(), dml.tableRows(),
                "SELECT", dml.totalBytesProcessed()));
        SqlDialectTranslator.Statement statement = SqlDialectTranslator.parseStatement(sql, projectId, jobProjectId,
                options.defaultDatasetId() != null ? options.defaultDatasetId() : "_floci_temporary",
                context.temporaryTables());
        recordDmlStatistics(job, statement.kind(), dml);
    }

    private static SqlDialectTranslator.Statement withTemporarySetup(SqlDialectTranslator.Statement statement,
                                                                     ProceduralContext context) {
        if (statement.kind() == SqlDialectTranslator.StatementKind.CREATE_TABLE && statement.querySql() != null) {
            return statement.withQuerySql(context.prependTo(statement.querySql()));
        }
        return statement;
    }

    private static String materializedTemporaryTable(String name, BigQuerySqlEngine.Result result) {
        List<TableFieldSchema> fields = result.schema() != null && result.schema().getFields() != null
                ? result.schema().getFields() : List.of();
        StringBuilder sql = new StringBuilder("CREATE TEMP TABLE ").append(googleIdentifier(name)).append(" (");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            TableFieldSchema field = fields.get(i);
            sql.append(googleIdentifier(field.getName())).append(' ').append(googleType(field));
            if ("REQUIRED".equals(field.getMode())) {
                sql.append(" NOT NULL");
            }
        }
        sql.append(')');
        if (result.rows().isEmpty()) {
            return sql.toString();
        }
        sql.append(";\nINSERT INTO ").append(googleIdentifier(name)).append(" SELECT * FROM UNNEST([");
        for (int rowIndex = 0; rowIndex < result.rows().size(); rowIndex++) {
            if (rowIndex > 0) {
                sql.append(", ");
            }
            Map<String, Object> row = result.rows().get(rowIndex);
            sql.append("STRUCT(");
            for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
                if (fieldIndex > 0) {
                    sql.append(", ");
                }
                TableFieldSchema field = fields.get(fieldIndex);
                sql.append(DuckTypes.storedLiteral(field, row.get(field.getName()))).append(" AS ")
                        .append(googleIdentifier(field.getName()));
            }
            sql.append(')');
        }
        return sql.append("])").toString();
    }

    /** The type of a FOR loop's row: its query's fields, nested structs and arrays included. */
    private static String scriptRowType(TableSchema schema) {
        List<TableFieldSchema> fields = schema != null && schema.getFields() != null
                ? schema.getFields() : List.of();
        List<String> members = new ArrayList<>(fields.size());
        try {
            for (TableFieldSchema field : fields) {
                members.add(quoteScriptTypeField(field.getName()) + " " + scriptVariableType(field));
            }
        } catch (GcpException e) {
            // A field type a script variable cannot hold, such as GEOGRAPHY: the row stays a struct of
            // values, which is what a loop over such a query resolved to before it kept types.
            return "STRUCT";
        }
        return members.isEmpty() ? "STRUCT" : "STRUCT<" + String.join(", ", members) + ">";
    }

    private static String googleType(TableFieldSchema field) {
        String scalar = switch (field.getType().toUpperCase(Locale.ROOT)) {
            case "INTEGER" -> "INT64";
            case "FLOAT" -> "FLOAT64";
            case "BOOLEAN" -> "BOOL";
            case "RECORD", "STRUCT" -> {
                List<TableFieldSchema> children = field.getFields() != null ? field.getFields() : List.of();
                List<String> members = new ArrayList<>();
                for (TableFieldSchema child : children) {
                    String member = googleIdentifier(child.getName()) + " " + googleType(child);
                    if ("REQUIRED".equals(child.getMode())) {
                        member += " NOT NULL";
                    }
                    members.add(member);
                }
                yield "STRUCT<" + String.join(", ", members) + ">";
            }
            default -> field.getType().toUpperCase(Locale.ROOT);
        };
        return "REPEATED".equals(field.getMode()) ? "ARRAY<" + scalar + ">" : scalar;
    }

    private static String googleIdentifier(String value) {
        return '`' + value.replace("`", "\\`") + '`';
    }

    private static boolean handlesLoopSignal(LoopControlSignal signal, String label) {
        return signal.label == null || label != null && label.equalsIgnoreCase(signal.label);
    }

    private static final class LoopControlSignal extends RuntimeException {
        private final boolean continueLoop;
        private final String label;

        private LoopControlSignal(boolean continueLoop, String label) {
            super(null, null, false, false);
            this.continueLoop = continueLoop;
            this.label = label;
        }
    }

    /** Request-local objects are replayed before each selected procedural leaf because every
     * sidecar call owns a fresh in-memory DuckDB connection. */
    private static final class ProceduralContext {
        private final Map<String, String> setupObjects = new LinkedHashMap<>();
        private final Map<String, Object> variables = new LinkedHashMap<>();
        private final Map<String, String> variableTypes = new LinkedHashMap<>();
        private final Set<String> temporaryTables = new LinkedHashSet<>();
        private final Set<String> temporaryFunctions = new LinkedHashSet<>();
        private final Map<String, BigQuerySqlEngine.Result> temporaryTableResults = new LinkedHashMap<>();
        private long totalBytesProcessed;
        private BigQuerySqlEngine.Result lastQueryResult;
        private boolean dynamicSqlExecution;

        private ProceduralContext dynamicSqlChild() {
            ProceduralContext child = new ProceduralContext();
            child.setupObjects.putAll(setupObjects);
            child.temporaryTables.addAll(temporaryTables);
            child.temporaryFunctions.addAll(temporaryFunctions);
            child.temporaryTableResults.putAll(temporaryTableResults);
            child.dynamicSqlExecution = true;
            return child;
        }

        private void mergeDynamicSqlContext(ProceduralContext child) {
            setupObjects.clear();
            setupObjects.putAll(child.setupObjects);
            temporaryTables.clear();
            temporaryTables.addAll(child.temporaryTables);
            temporaryFunctions.clear();
            temporaryFunctions.addAll(child.temporaryFunctions);
            temporaryTableResults.clear();
            temporaryTableResults.putAll(child.temporaryTableResults);
            totalBytesProcessed += child.totalBytesProcessed;
            lastQueryResult = child.lastQueryResult;
        }

        private boolean dynamicSqlExecution() {
            return dynamicSqlExecution;
        }

        private boolean applyTemporaryObjectChange(String sql, String projectId) {
            SqlDialectTranslator.TemporaryFunctionDefinition function =
                    SqlDialectTranslator.temporaryFunctionDefinition(sql);
            if (function != null) {
                String normalized = function.name().toLowerCase(Locale.ROOT);
                if (temporaryFunctions.contains(normalized)) {
                    if (function.ifNotExists()) {
                        clearQueryResult();
                        return true;
                    }
                    if (!function.orReplace()) {
                        throw QueryEngine.invalidQuery("Already Exists: Function " + function.name());
                    }
                    setupObjects.remove("function:" + normalized);
                }
                temporaryFunctions.add(normalized);
                setupObjects.put("function:" + normalized, sql);
                clearQueryResult();
                return true;
            }
            SqlDialectTranslator.TemporaryTableDrop drop = SqlDialectTranslator.temporaryTableDrop(sql);
            if (drop != null && temporaryTables.contains(drop.name())) {
                temporaryTables.remove(drop.name());
                setupObjects.remove("table:" + drop.name());
                temporaryTableResults.remove(drop.name());
                clearQueryResult();
                return true;
            }
            if (drop != null && drop.sessionQualified()) {
                if (!drop.ifExists()) {
                    throw GcpException.notFound("Not found: Dataset " + projectId
                            + ":_SESSION was not found");
                }
                clearQueryResult();
                return true;
            }
            return false;
        }

        private void materializeTemporaryTable(String name, BigQuerySqlEngine.Result result) {
            temporaryTables.add(name);
            temporaryTableResults.put(name, result);
            setupObjects.put("table:" + name, BigQueryService.materializedTemporaryTable(name, result));
            clearQueryResult();
        }

        private long truncateTemporaryTable(String name) {
            BigQuerySqlEngine.Result existing = temporaryTableResults.get(name);
            if (existing == null) {
                throw new IllegalStateException("Temporary table was not materialized: " + name);
            }
            long removed = existing.rows().size();
            materializeTemporaryTable(name, new BigQuerySqlEngine.Result(
                    existing.schema(), List.of(), existing.statementType(), existing.totalBytesProcessed()));
            return removed;
        }

        private void recordQueryResult(BigQuerySqlEngine.Result result) {
            recordBytes(result.totalBytesProcessed());
            lastQueryResult = result;
        }

        private void recordBytes(long bytes) {
            totalBytesProcessed += bytes;
        }

        private void clearQueryResult() {
            lastQueryResult = null;
        }

        private BigQuerySqlEngine.Result lastQueryResult() {
            return lastQueryResult;
        }

        private long totalBytesProcessed() {
            return totalBytesProcessed;
        }

        private Set<String> temporaryTables() {
            return temporaryTables;
        }

        private Map<String, Object> variables() {
            return variables;
        }

        private Map<String, String> variableTypes() {
            return variableTypes;
        }

        private BigQuerySqlEngine.Result temporaryTableResult(String name) {
            BigQuerySqlEngine.Result result = temporaryTableResults.get(name);
            if (result == null) {
                throw new IllegalStateException("Temporary table was not materialized: " + name);
            }
            return result;
        }

        private List<TableFieldSchema> temporaryTableColumns(String name) {
            BigQuerySqlEngine.Result result = temporaryTableResults.get(name);
            return result != null && result.schema() != null && result.schema().getFields() != null
                    ? result.schema().getFields() : List.of();
        }

        private String prependTo(String sql) {
            return setupObjects.isEmpty() ? sql : String.join(";\n", setupObjects.values()) + ";\n" + sql;
        }
    }

    private static QueryOptions leafOptions(String sql, QueryOptions parent) {
        return new QueryOptions(sql, parent.defaultDatasetId(), parent.queryParameters(), parent.parameterMode(),
                parent.dryRun(), parent.useLegacySql());
    }

    /** A CREATE TABLE or CREATE VIEW derives its schema, so BigQuery rejects a repeated select name. */
    private static void rejectDuplicateQueryColumns(SqlDialectTranslator.Statement statement,
                                                    BigQuerySqlEngine.Result result) {
        if (result == null || result.duplicateOutputNames().isEmpty()) {
            return;
        }
        String kind = switch (statement.kind()) {
            case CREATE_VIEW -> statement.materialized() ? "CREATE MATERIALIZED VIEW" : "CREATE VIEW";
            default -> "CREATE TABLE";
        };
        throw QueryEngine.invalidQuery(kind + " has columns with duplicate name "
                + result.duplicateOutputNames().getFirst());
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
    private void alterOptions(String projectId, String jobProjectId, SqlDialectTranslator.Statement statement,
                              QueryOptions options, StoredJob job, JobLocation jobLocation) {
        SqlDialectTranslator.TableRef target = statement.target();
        job.setDdlTargetTable(new TableReference(projectId, target.datasetId(), target.tableId()));
        Optional<Table> found = alterTarget(projectId, statement);
        if (found.isEmpty()) {
            job.setDdlOperationPerformed("SKIP");
            return;
        }
        Table table = found.get();
        applyTableOptions(table, evaluateOptions(projectId, jobProjectId, statement.options(), options, jobLocation));
        table.setLastModifiedTime(nowMillis());
        table.setEtag(etag());
        tableStore.put(tableKey(target.datasetId(), target.tableId()), table);
        job.setDdlOperationPerformed("ALTER");
    }

    /**
     * Evaluates the option values given as constant expressions with the SQL engine, as {@code SELECT expr}, for the
     * run and for a dry run, and checks each result's type as BigQuery does: a STRING for description and
     * friendly_name, a TIMESTAMP (stored as epoch milliseconds) for expiration_timestamp.
     */
    private Map<String, Object> evaluateOptions(String projectId, String jobProjectId, Map<String, Object> options,
                                                QueryOptions queryOptions, JobLocation jobLocation) {
        if (options.values().stream().noneMatch(v -> v instanceof SqlDialectTranslator.OptionExpression)) {
            return options;
        }
        Map<String, Object> evaluated = new LinkedHashMap<>(options);
        for (Map.Entry<String, Object> option : options.entrySet()) {
            if (!(option.getValue() instanceof SqlDialectTranslator.OptionExpression expression)) {
                continue;
            }
            BigQuerySqlEngine.Result result = engine.execute(request(projectId, jobProjectId,
                    "SELECT " + expression.sql(), expressionParameters(queryOptions, expression), false),
                    tables(projectId, jobLocation));
            TableFieldSchema field = result.schema().getFields().getFirst();
            Object value = result.rows().isEmpty() ? null : result.rows().getFirst().get(field.getName());
            String type = switch (field.getType()) {
                case "INTEGER" -> "INT64";
                case "FLOAT" -> "FLOAT64";
                case "BOOLEAN" -> "BOOL";
                case "RECORD" -> "STRUCT";
                default -> field.getType();
            };
            if ("REPEATED".equals(field.getMode())) {
                type = "ARRAY<" + type + ">";
            }
            String name = option.getKey();
            if (name.equals("expiration_timestamp")) {
                if (!type.equals("TIMESTAMP")) {
                    throw QueryEngine.invalidQuery("Expected type TIMESTAMP; found " + type
                            + "; failed to set 'expiration_timestamp' in OPTIONS()");
                }
                evaluated.put(name, value == null ? null
                        : new BigDecimal(value.toString()).movePointRight(3).longValue());
            } else {
                if (!type.equals("STRING")) {
                    throw QueryEngine.invalidQuery("Option " + name + " value has type " + type
                            + " which cannot be coerced to expected type STRING");
                }
                evaluated.put(name, value);
            }
        }
        return evaluated;
    }

    /**
     * The query options with only the positional parameters an option expression binds, in statement order.
     * Parameters bind positionally in {@code POSITIONAL} mode and, as the SDKs send them, when they have no name
     * and no mode is given, the same rule {@code QueryParameters} follows.
     */
    private static QueryOptions expressionParameters(QueryOptions options,
                                                     SqlDialectTranslator.OptionExpression expression) {
        List<Map<String, Object>> parameters = options.queryParameters();
        if (parameters == null || parameters.isEmpty()) {
            return options;
        }
        if (!options.positionalParameters()) {
            return options;
        }
        int from = Math.min(expression.firstParameter(), parameters.size());
        int to = Math.min(expression.firstParameter() + expression.parameterCount(), parameters.size());
        return new QueryOptions(options.sql(), options.defaultDatasetId(), parameters.subList(from, to),
                options.parameterMode(), options.dryRun(), options.useLegacySql(), options.destinationTable(),
                options.writeDisposition(), options.createDisposition(), options.schemaUpdateOptions(),
                options.timePartitioning(), options.rangePartitioning(), options.defaultProjectId(),
                options.scriptIncompatibleOptions());
    }

    private static void applyTableOptions(Table table, Map<String, Object> options) {
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
    }

    private void executeStatement(String projectId, String jobProjectId, SqlDialectTranslator.Statement statement,
                                  QueryOptions options, StoredJob job,
                                  JobLocation jobLocation) {
        SqlDialectTranslator.TableRef target = statement.target();
        switch (statement.kind()) {
            case INSERT, UPDATE, DELETE, MERGE -> {
                BigQuerySqlEngine.DmlResult result = engine.executeDml(request(projectId, jobProjectId,
                        options.sql(), options, false), tables(projectId, jobLocation));
                replaceRows(projectId, target, result.tableRows());
                recordDmlStatistics(job, statement.kind(), result);
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
            case CREATE_TABLE, CREATE_VIEW -> createFromStatement(projectId, jobProjectId, statement, options, job,
                    jobLocation);
            case ALTER_TABLE, ALTER_VIEW -> alterOptions(projectId, jobProjectId, statement, options, job, jobLocation);
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

    private static void clearStatementStatistics(StoredJob job) {
        job.setNumDmlAffectedRows(null);
        job.setDmlStats(null);
        job.setDdlOperationPerformed(null);
        job.setDdlTargetTable(null);
        job.setDdlTargetDataset(null);
    }

    private static void recordDmlStatistics(StoredJob job, SqlDialectTranslator.StatementKind kind,
                                            BigQuerySqlEngine.DmlResult result) {
        job.setTotalBytesProcessed(String.valueOf(result.totalBytesProcessed()));
        job.setNumDmlAffectedRows(String.valueOf(result.affectedRows()));
        Map<String, String> stats = new LinkedHashMap<>();
        if (kind == SqlDialectTranslator.StatementKind.INSERT || kind == SqlDialectTranslator.StatementKind.MERGE) {
            stats.put("insertedRowCount", String.valueOf(result.insertedRows()));
        }
        if (kind == SqlDialectTranslator.StatementKind.UPDATE || kind == SqlDialectTranslator.StatementKind.MERGE) {
            stats.put("updatedRowCount", String.valueOf(result.updatedRows()));
        }
        if (kind == SqlDialectTranslator.StatementKind.DELETE || kind == SqlDialectTranslator.StatementKind.MERGE) {
            stats.put("deletedRowCount", String.valueOf(result.deletedRows()));
        }
        job.setDmlStats(stats);
    }

    private void createFromStatement(String projectId, String jobProjectId, SqlDialectTranslator.Statement statement,
                                     QueryOptions options, StoredJob job,
                                     JobLocation jobLocation) {
        SqlDialectTranslator.TableRef target = statement.target();
        Dataset destinationDataset = getDataset(projectId, target.datasetId());
        if (statement.copySource() != null) {
            jobLocation.admit(getDataset(projectId, statement.copySource().datasetId()));
        }
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
        StoredTableData copiedData = null;
        if (statement.kind() == SqlDialectTranslator.StatementKind.CREATE_VIEW) {
            BigQuerySqlEngine.Result described = engine.execute(request(projectId, jobProjectId, statement.querySql(),
                    options, true), tables(projectId, jobLocation));
            rejectDuplicateQueryColumns(statement, described);
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
        } else if (statement.copySource() != null) {
            Table source = getTable(projectId, statement.copySource().datasetId(), statement.copySource().tableId());
            if (source.viewQuery() != null) {
                throw QueryEngine.invalidQuery("CREATE TABLE COPY source must be a table, clone, or snapshot");
            }
            table.setSchema(deepCopy(source.getSchema(), TableSchema.class));
            table.getExtra().putAll(deepCopyMap(source.getExtra()));
            table.getExtra().remove("expirationTime");
            table.setDescription(source.getDescription());
            table.setFriendlyName(source.getFriendlyName());
            table.setLabels(source.getLabels() != null ? new LinkedHashMap<>(source.getLabels()) : null);
            StoredTableData sourceData = dataStore.get(tableKey(statement.copySource().datasetId(),
                    statement.copySource().tableId())).orElseGet(StoredTableData::new);
            copiedData = copyTableData(sourceData);
            rows = allRows(copiedData);
        } else if (statement.querySql() != null) {
            BigQuerySqlEngine.Result result = engine.execute(request(projectId, jobProjectId, statement.querySql(),
                    options, false), tables(projectId, jobLocation));
            rejectDuplicateQueryColumns(statement, result);
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
        if (statement.kind() == SqlDialectTranslator.StatementKind.CREATE_TABLE
                && existingTable.isPresent() && !samePartitioning(existingTable.get(), table)) {
            throw QueryEngine.invalidQuery("CREATE OR REPLACE TABLE cannot change the partitioning specification");
        }
        if (statement.copySource() != null) {
            BigQueryMetadata.applyDatasetDefaults(table, destinationDataset, System.currentTimeMillis());
            if (statement.options() != null) {
                applyTableOptions(table, evaluateOptions(projectId, jobProjectId, statement.options(), options,
                        jobLocation));
            }
        }
        if (exists) {
            deleteTable(projectId, target.datasetId(), target.tableId());
        }
        putTable(projectId, target, table, rows);
        if (copiedData != null) {
            dataStore.put(tableKey(target.datasetId(), target.tableId()), copiedData);
            table.setNumRows(String.valueOf(allRows(copiedData).size()));
            tableStore.put(tableKey(target.datasetId(), target.tableId()), table);
        }
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
        Dataset dataset = getDataset(projectId, target.datasetId());
        table.setTableReference(new TableReference(projectId, target.datasetId(), target.tableId()));
        table.setId(projectId + ":" + target.datasetId() + "." + target.tableId());
        table.setLocation(dataset.getLocation());
        table.setSelfLink(selfLink(projectId, target.datasetId(), target.tableId()));
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

    // ── Copy jobs ────────────────────────────────────────────────────────────────

    /** Runs a BigQuery table copy job synchronously, including partition-decorated copies. */
    @SuppressWarnings("unchecked")
    public StoredJob copy(String projectId, String location, String jobId, Map<String, Object> copyConfig) {
        Map<String, Object> config = copyConfig != null ? deepCopyMap(copyConfig) : new LinkedHashMap<>();
        if (!(config.get("destinationTable") instanceof Map<?, ?> destinationMap)) {
            throw GcpException.invalidArgument("Required parameter is missing: destinationTable")
                    .withReason("invalid");
        }
        Map<String, Object> destination = new LinkedHashMap<>((Map<String, Object>) destinationMap);
        destination.putIfAbsent("projectId", projectId);
        config.put("destinationTable", destination);
        tableReference(destination, projectId, "destinationTable");

        boolean hasSourceTable = config.get("sourceTable") != null;
        boolean hasSourceTables = config.get("sourceTables") != null;
        if (hasSourceTable == hasSourceTables) {
            throw GcpException.invalidArgument("Exactly one of sourceTable or sourceTables is required")
                    .withReason("invalid");
        }
        List<Map<String, Object>> sourceMaps;
        if (hasSourceTable) {
            if (!(config.get("sourceTable") instanceof Map<?, ?> source)) {
                throw GcpException.invalidArgument("sourceTable must be an object").withReason("invalid");
            }
            sourceMaps = List.of(new LinkedHashMap<>((Map<String, Object>) source));
        } else {
            if (!(config.get("sourceTables") instanceof List<?> rawSources)) {
                throw GcpException.invalidArgument("sourceTables must be an array").withReason("invalid");
            }
            if (rawSources.isEmpty() || rawSources.stream().anyMatch(source -> !(source instanceof Map<?, ?>))) {
                throw GcpException.invalidArgument("sourceTables must contain at least one table reference")
                        .withReason("invalid");
            }
            sourceMaps = rawSources.stream()
                    .map(source -> (Map<String, Object>) new LinkedHashMap<>((Map<String, Object>) source)).toList();
        }
        for (int i = 0; i < sourceMaps.size(); i++) {
            Map<String, Object> source = sourceMaps.get(i);
            source.putIfAbsent("projectId", projectId);
            tableReference(source, projectId, hasSourceTable ? "sourceTable" : "sourceTables[" + i + "]");
        }
        if (hasSourceTable) {
            config.put("sourceTable", sourceMaps.getFirst());
        } else {
            config.put("sourceTables", sourceMaps);
        }

        Object operationType = config.get("operationType");
        if (operationType != null && !"COPY".equals(operationType)
                && !"OPERATION_TYPE_UNSPECIFIED".equals(operationType)) {
            throw GcpException.invalidArgument("Invalid value for operationType: " + operationType)
                    .withReason("invalid");
        }
        Long destinationExpirationMillis = copyExpirationMillis(config.get("destinationExpirationTime"));

        String write = copyDisposition(config.get("writeDisposition"), "writeDisposition",
                Set.of("WRITE_TRUNCATE", "WRITE_APPEND", "WRITE_EMPTY"), "WRITE_EMPTY");
        String create = copyDisposition(config.get("createDisposition"), "createDisposition",
                Set.of("CREATE_IF_NEEDED", "CREATE_NEVER"), "CREATE_IF_NEEDED");

        JobLocation jobLocation = new JobLocation(projectId, location);
        StoredJob job = new StoredJob();
        job.setJobId(reserveJobId(projectId, jobId));
        job.setProjectId(projectId);
        job.setLocation(jobLocation.resolved());
        job.setJobType("COPY");
        job.setCopyConfiguration(config);
        job.setState("DONE");
        job.setCreationTime(nowMillis());
        try {
            synchronized (writeLock) {
                long copiedRows = runCopy(projectId, sourceMaps, destination, write, create, jobLocation,
                        destinationExpirationMillis);
                job.setCopyStatistics(Map.of("copiedRows", String.valueOf(copiedRows)));
            }
        } catch (GcpException e) {
            job.setErrorReason(loadErrorReason(e));
            job.setErrorMessage(e.getMessage());
        } catch (RuntimeException e) {
            // The job id is already reserved, so persist the completed job even for unexpected failures.
            LOG.warnf(e, "copy job %s failed", job.getJobId());
            job.setErrorReason("invalid");
            job.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        job.setLocation(jobLocation.resolved());
        jobStore.put(job.getJobId(), job);
        return job;
    }

    private static String copyDisposition(Object raw, String field, Set<String> allowed, String defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof String value) || !allowed.contains(value)) {
            throw GcpException.invalidArgument("Invalid value for " + field + ": " + raw).withReason("invalid");
        }
        return value;
    }

    private record CopySource(Table table, StoredTableData data, String partitionId) {}

    private long runCopy(String projectId, List<Map<String, Object>> sourceMaps,
                         Map<String, Object> destinationMap, String write, String create,
                         JobLocation jobLocation, Long destinationExpirationMillis) {
        TableReference destination = tableReference(destinationMap, projectId, "destinationTable");
        validateCopyProject(projectId, destination, "destinationTable");
        Dataset destinationDataset = getDataset(projectId, destination.getDatasetId());

        List<CopySource> sources = new ArrayList<>();
        for (Map<String, Object> sourceMap : sourceMaps) {
            TableReference source = tableReference(sourceMap, projectId, "sourceTable");
            validateCopyProject(projectId, source, "sourceTable");
            jobLocation.admit(getDataset(projectId, source.getDatasetId()));
            DecoratedDestination decorated = decoratedDestination(source);
            String tableId = decorated != null ? decorated.target().tableId() : source.getTableId();
            Table table = getTable(projectId, source.getDatasetId(), tableId);
            if (table.viewQuery() != null) {
                throw GcpException.invalidArgument("Cannot copy view " + source.getDatasetId() + "." + tableId)
                        .withReason("invalid");
            }
            StoredTableData data = dataStore.get(tableKey(source.getDatasetId(), tableId))
                    .map(BigQueryService::copyTableData).orElseGet(StoredTableData::new);
            if (decorated != null) {
                validatePartitionDecorator(table, decorated.partitionId());
            }
            sources.add(new CopySource(deepCopy(table, Table.class), data,
                    decorated != null ? decorated.partitionId() : null));
        }
        requireCompatibleCopySources(sources);

        boolean crossRegion = !jobLocation.contains(destinationDataset);
        if (crossRegion) {
            if (sources.size() != 1) {
                throw GcpException.invalidArgument("A cross-region copy supports exactly one source table")
                        .withReason("invalid");
            }
            if ("WRITE_APPEND".equals(write)) {
                throw GcpException.invalidArgument("A cross-region copy does not support WRITE_APPEND")
                        .withReason("invalid");
            }
        }

        DecoratedDestination decoratedDestination = decoratedDestination(destination);
        SqlDialectTranslator.TableRef target = decoratedDestination != null ? decoratedDestination.target()
                : new SqlDialectTranslator.TableRef(destination.getDatasetId(), destination.getTableId());
        Optional<Table> existing = tableStore.get(tableKey(target.datasetId(), target.tableId()));
        existing.ifPresent(table -> expire(target.datasetId(), target.tableId(), table));
        existing = tableStore.get(tableKey(target.datasetId(), target.tableId()));
        if (existing.isEmpty() && "CREATE_NEVER".equals(create)) {
            throw GcpException.notFound("Not found: Table " + qualified(projectId, target));
        }
        if (crossRegion && "WRITE_EMPTY".equals(write) && existing.isPresent()) {
            throw GcpException.alreadyExists("Already Exists: Table " + qualified(projectId, target))
                    .withReason("duplicate");
        }

        if (decoratedDestination != null) {
            if (existing.isEmpty()) {
                Table table = deepCopy(sources.getFirst().table(), Table.class);
                applyCopyExpiration(projectId, table, target.datasetId(), destinationExpirationMillis);
                validatePartitionDecorator(table, decoratedDestination.partitionId());
                validateDecoratedCopy(projectId, target, table, decoratedDestination.partitionId(), sources);
                putTable(projectId, target, table, List.of());
                existing = Optional.of(table);
            } else {
                validateDecoratedCopy(projectId, target, existing.orElseThrow(),
                        decoratedDestination.partitionId(), sources);
            }
            List<Map<String, Object>> rows = copyRows(sources);
            BigQuerySqlEngine.Result result = new BigQuerySqlEngine.Result(
                    deepCopy(sources.getFirst().table().getSchema(), TableSchema.class), rows, "COPY", 0);
            writeRows(projectId, destination, write, create, List.of(), result);
            return rows.size();
        }

        if (existing.isEmpty()) {
            return createCopiedTable(projectId, target, sources, destinationExpirationMillis);
        }

        Table destinationTable = existing.get();
        if (isPartitioned(destinationTable) && sources.stream().noneMatch(BigQueryService::partitionedCopySource)
                && !isIngestionTimePartitioned(destinationTable)) {
            throw GcpException.invalidArgument("A non-partitioned table can only be copied into an"
                    + " ingestion-time partitioned destination").withReason("invalid");
        }
        if (isPartitioned(destinationTable) && sources.stream().allMatch(BigQueryService::partitionedCopySource)) {
            return writePartitionedCopy(projectId, target, destinationTable, sources, write);
        }

        if ("WRITE_APPEND".equals(write) || "WRITE_EMPTY".equals(write)) {
            requireCompatibleCopyAppendSchema(projectId, target, destinationTable, sources.getFirst().table());
        }
        BigQuerySqlEngine.Result result = new BigQuerySqlEngine.Result(
                deepCopy(sources.getFirst().table().getSchema(), TableSchema.class), copyRows(sources), "COPY", 0);
        writeRows(projectId, destination, write, create, List.of(), result);
        return result.rows().size();
    }

    private static TableReference tableReference(Map<String, Object> map, String defaultProject, String field) {
        Object dataset = map.get("datasetId");
        Object table = map.get("tableId");
        if (dataset != null && !(dataset instanceof String)) {
            throw GcpException.invalidArgument(field + ".datasetId must be a string").withReason("invalid");
        }
        if (table != null && !(table instanceof String)) {
            throw GcpException.invalidArgument(field + ".tableId must be a string").withReason("invalid");
        }
        if (!(dataset instanceof String datasetId) || datasetId.isBlank()) {
            throw GcpException.invalidArgument(field + ".datasetId is required").withReason("invalid");
        }
        if (!(table instanceof String tableId) || tableId.isBlank()) {
            throw GcpException.invalidArgument(field + ".tableId is required").withReason("invalid");
        }
        Object project = map.get("projectId");
        if (project != null && !(project instanceof String)) {
            throw GcpException.invalidArgument(field + ".projectId must be a string").withReason("invalid");
        }
        return new TableReference(project != null ? (String) project : defaultProject, datasetId, tableId);
    }

    private static void validateCopyProject(String projectId, TableReference reference, String field) {
        if (!projectId.equals(reference.getProjectId())) {
            throw GcpException.invalidArgument("Cross-project " + field + " references are not supported by the"
                    + " floci BigQuery emulator").withReason("invalid");
        }
    }

    private static void requireCompatibleCopySources(List<CopySource> sources) {
        Object schema = JSON.valueToTree(sources.getFirst().table().getSchema());
        Object rangePartitioning = JSON.valueToTree(sources.getFirst().table().getExtra().get("rangePartitioning"));
        Object clustering = JSON.valueToTree(sources.getFirst().table().getExtra().get("clustering"));
        for (CopySource source : sources.subList(1, sources.size())) {
            if (!schema.equals(JSON.valueToTree(source.table().getSchema()))
                    || !sameTimePartitioning(timePartitioning(sources.getFirst().table()),
                            timePartitioning(source.table()))
                    || !rangePartitioning.equals(JSON.valueToTree(
                            source.table().getExtra().get("rangePartitioning")))
                    || !clustering.equals(JSON.valueToTree(source.table().getExtra().get("clustering")))) {
                throw GcpException.invalidArgument("All source tables in a copy job must have identical schemas,"
                        + " partitioning, and clustering").withReason("invalid");
            }
        }
    }

    private static boolean sameTimePartitioning(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> normalizedFirst = first != null ? new LinkedHashMap<>(first) : null;
        Map<String, Object> normalizedSecond = second != null ? new LinkedHashMap<>(second) : null;
        if (normalizedFirst != null) {
            normalizedFirst.remove("expirationMs");
        }
        if (normalizedSecond != null) {
            normalizedSecond.remove("expirationMs");
        }
        return JSON.valueToTree(normalizedFirst).equals(JSON.valueToTree(normalizedSecond));
    }

    private static void validateDecoratedCopy(String projectId, SqlDialectTranslator.TableRef target,
                                              Table destination, String destinationPartition,
                                              List<CopySource> sources) {
        for (CopySource source : sources) {
            if (source.partitionId() == null) {
                throw GcpException.invalidArgument("A decorated copy destination requires decorated source"
                        + " partitions").withReason("invalid");
            }
            if (!JSON.valueToTree(destination.getSchema()).equals(JSON.valueToTree(source.table().getSchema()))
                    || !JSON.valueToTree(destination.getExtra().get("clustering"))
                            .equals(JSON.valueToTree(source.table().getExtra().get("clustering")))) {
                throw QueryEngine.invalidQuery("Provided Schema does not match Table "
                        + qualified(projectId, target));
            }
            Map<String, Object> sourceRange = rangePartitioning(source.table());
            Map<String, Object> destinationRange = rangePartitioning(destination);
            if (sourceRange != null || destinationRange != null) {
                if (!JSON.valueToTree(sourceRange).equals(JSON.valueToTree(destinationRange))
                        || !source.partitionId().equals(destinationPartition)) {
                    throw GcpException.invalidArgument("Integer-range partition copies require identical source"
                            + " and destination partitioning and decorators").withReason("invalid");
                }
                continue;
            }

            Map<String, Object> sourceTime = timePartitioning(source.table());
            Map<String, Object> destinationTime = timePartitioning(destination);
            if (sourceTime == null || destinationTime == null) {
                throw GcpException.invalidArgument("Partition decorators require partitioned source and"
                        + " destination tables").withReason("invalid");
            }
            boolean sourceIngestion = isIngestionTimePartitioned(source.table());
            boolean destinationIngestion = isIngestionTimePartitioned(destination);
            if (!sourceIngestion || !destinationIngestion) {
                if (!sameTimePartitioning(sourceTime, destinationTime)
                        || !source.partitionId().equals(destinationPartition)) {
                    throw GcpException.invalidArgument("Column-partitioned copies require identical source and"
                            + " destination partitioning and decorators").withReason("invalid");
                }
                continue;
            }

            String sourceType = String.valueOf(sourceTime.get("type")).toUpperCase(Locale.ROOT);
            String destinationType = String.valueOf(destinationTime.get("type")).toUpperCase(Locale.ROOT);
            if (partitionGranularity(destinationType) < partitionGranularity(sourceType)) {
                throw GcpException.invalidArgument("An ingestion-time copy destination cannot use a finer"
                        + " partition granularity than its source").withReason("invalid");
            }
            LocalDateTime sourceBoundary = timePartitionBoundary(source.table(), source.partitionId());
            String containingDestination = switch (destinationType) {
                case "HOUR" -> sourceBoundary.format(HOUR_PARTITION);
                case "DAY" -> sourceBoundary.format(DAY_PARTITION);
                case "MONTH" -> sourceBoundary.format(MONTH_PARTITION);
                case "YEAR" -> sourceBoundary.format(YEAR_PARTITION);
                default -> throw GcpException.invalidArgument("Unsupported time partitioning type: "
                        + destinationType).withReason("invalid");
            };
            if (!destinationPartition.equals(containingDestination)) {
                throw GcpException.invalidArgument("The destination partition must contain the source partition")
                        .withReason("invalid");
            }
        }
    }

    private static int partitionGranularity(String type) {
        return switch (type) {
            case "HOUR" -> 0;
            case "DAY" -> 1;
            case "MONTH" -> 2;
            case "YEAR" -> 3;
            default -> throw GcpException.invalidArgument("Unsupported time partitioning type: " + type)
                    .withReason("invalid");
        };
    }

    private static boolean partitionedCopySource(CopySource source) {
        return source.partitionId() != null || isPartitioned(source.table());
    }

    private static boolean isPartitioned(Table table) {
        return timePartitioning(table) != null || rangePartitioning(table) != null;
    }

    private static List<Map<String, Object>> copyRows(List<CopySource> sources) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CopySource source : sources) {
            List<Map<String, Object>> selected = source.partitionId() != null
                    ? selectedPartitionRows(source.table(), source.data(), source.partitionId())
                    : allRows(source.data());
            selected.forEach(row -> rows.add(new LinkedHashMap<>(row)));
        }
        return rows;
    }

    private static Long copyExpirationMillis(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value)) {
            throw GcpException.invalidArgument("destinationExpirationTime must be a string").withReason("invalid");
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeException | ArithmeticException e) {
            throw GcpException.invalidArgument("destinationExpirationTime must be a valid RFC 3339 timestamp")
                    .withReason("invalid");
        }
    }

    private void applyCopyExpiration(String projectId, Table table, String destinationDatasetId,
                                     Long expirationMillis) {
        // A copy gets destination-dataset defaults, not the source table's expiration.
        table.getExtra().remove("expirationTime");
        Dataset dataset = getDataset(projectId, destinationDatasetId);
        BigQueryMetadata.applyDatasetDefaults(table, dataset, System.currentTimeMillis());
        if (expirationMillis != null) {
            table.getExtra().put("expirationTime", String.valueOf(expirationMillis));
        }
    }

    private long createCopiedTable(String projectId, SqlDialectTranslator.TableRef target,
                                   List<CopySource> sources, Long destinationExpirationMillis) {
        Table table = deepCopy(sources.getFirst().table(), Table.class);
        applyCopyExpiration(projectId, table, target.datasetId(), destinationExpirationMillis);
        StoredTableData merged = mergeCopyData(sources);
        putTable(projectId, target, table, List.of());
        dataStore.put(tableKey(target.datasetId(), target.tableId()), merged);
        long copiedRows = allRows(merged).size();
        table.setNumRows(String.valueOf(copiedRows));
        tableStore.put(tableKey(target.datasetId(), target.tableId()), table);
        return copiedRows;
    }

    private static StoredTableData mergeCopyData(List<CopySource> sources) {
        StoredTableData merged = new StoredTableData();
        for (CopySource source : sources) {
            if (source.partitionId() != null) {
                List<Map<String, Object>> rows = selectedPartitionRows(
                        source.table(), source.data(), source.partitionId());
                if (isIngestionTimePartitioned(source.table())) {
                    merged.getPartitionRows().computeIfAbsent(source.partitionId(), ignored -> new ArrayList<>())
                            .addAll(rows.stream().map(LinkedHashMap::new).toList());
                } else {
                    merged.getRows().addAll(rows.stream().map(LinkedHashMap::new).toList());
                }
            } else {
                merged.getRows().addAll(source.data().getRows().stream().map(LinkedHashMap::new).toList());
                source.data().getPartitionRows().forEach((partition, rows) ->
                        merged.getPartitionRows().computeIfAbsent(partition, ignored -> new ArrayList<>())
                                .addAll(rows.stream().map(LinkedHashMap::new).toList()));
            }
        }
        return merged;
    }

    private long writePartitionedCopy(String projectId, SqlDialectTranslator.TableRef target, Table destination,
                                      List<CopySource> sources, String write) {
        requireCompatibleDestination(projectId, target, destination, sources.getFirst().table());
        StoredTableData incoming = mergeCopyData(sources);
        long copiedRows = allRows(incoming).size();
        StoredTableData current = dataStore.get(tableKey(target.datasetId(), target.tableId()))
                .map(BigQueryService::copyDestinationData).orElseGet(StoredTableData::new);
        if ("WRITE_EMPTY".equals(write) && !allRows(current).isEmpty()) {
            throw GcpException.alreadyExists("Already Exists: Table " + qualified(projectId, target))
                    .withReason("duplicate");
        }
        if ("WRITE_APPEND".equals(write)) {
            incoming.getRows().addAll(0, current.getRows());
            current.getPartitionRows().forEach((partition, rows) ->
                    incoming.getPartitionRows().computeIfAbsent(partition, ignored -> new ArrayList<>())
                            .addAll(0, rows.stream().map(LinkedHashMap::new).toList()));
        }
        incoming.setStreamRows(new LinkedHashMap<>(current.getStreamRows()));
        dataStore.put(tableKey(target.datasetId(), target.tableId()), incoming);
        destination.setNumRows(String.valueOf(allRows(incoming).size()));
        destination.setLastModifiedTime(nowMillis());
        destination.setEtag(etag());
        tableStore.put(tableKey(target.datasetId(), target.tableId()), destination);
        return copiedRows;
    }

    /**
     * BigQuery permits nullable destination-only fields for WRITE_APPEND, but rejects incompatible source fields.
     * Its copy limitations also disallow extra destination columns with defaults, but TableFieldSchema does not
     * currently model defaultValueExpression, so that distinction cannot be checked here:
     * https://cloud.google.com/bigquery/docs/managing-tables#copy_tables
     */
    private static void requireCompatibleCopyAppendSchema(String projectId, SqlDialectTranslator.TableRef target,
                                                           Table destination, Table source) {
        if (!compatibleCopyFields(schemaFields(source), schemaFields(destination))) {
            throw QueryEngine.invalidQuery("Provided Schema does not match Table " + qualified(projectId, target));
        }
    }

    private static boolean compatibleCopyFields(List<TableFieldSchema> sourceFields,
                                                List<TableFieldSchema> destinationFields) {
        Map<String, TableFieldSchema> destinationByName = new LinkedHashMap<>();
        destinationFields.forEach(field -> destinationByName.put(field.getName().toLowerCase(Locale.ROOT), field));
        for (TableFieldSchema sourceField : sourceFields) {
            TableFieldSchema destinationField = destinationByName.get(sourceField.getName().toLowerCase(Locale.ROOT));
            if (destinationField == null || !compatibleCopyField(sourceField, destinationField)) {
                return false;
            }
        }
        for (TableFieldSchema destinationField : destinationFields) {
            boolean presentInSource = sourceFields.stream().anyMatch(sourceField ->
                    sourceField.getName().equalsIgnoreCase(destinationField.getName()));
            if (!presentInSource && "REQUIRED".equalsIgnoreCase(destinationField.getMode())) {
                return false;
            }
        }
        return true;
    }

    private static boolean compatibleCopyField(TableFieldSchema source, TableFieldSchema destination) {
        String sourceMode = source.getMode() != null ? source.getMode() : "NULLABLE";
        String destinationMode = destination.getMode() != null ? destination.getMode() : "NULLABLE";
        return source.getType().equalsIgnoreCase(destination.getType()) && sourceMode.equalsIgnoreCase(destinationMode)
                && compatibleCopyFields(source.getFields() != null ? source.getFields() : List.of(),
                        destination.getFields() != null ? destination.getFields() : List.of());
    }

    private static Table copiedTableCandidate(SqlDialectTranslator.Statement statement, Table source) {
        Table candidate = deepCopy(source, Table.class);
        if (statement.timePartitioning() != null) {
            candidate.getExtra().put("timePartitioning", new LinkedHashMap<>(statement.timePartitioning()));
        }
        if (statement.rangePartitioning() != null) {
            candidate.getExtra().put("rangePartitioning", new LinkedHashMap<>(statement.rangePartitioning()));
        }
        return candidate;
    }

    private static void requireCompatibleDestination(String projectId, SqlDialectTranslator.TableRef target,
                                                     Table destination, Table source) {
        if (!JSON.valueToTree(destination.getSchema()).equals(JSON.valueToTree(source.getSchema()))
                || !sameTimePartitioning(timePartitioning(destination), timePartitioning(source))
                || !JSON.valueToTree(destination.getExtra().get("rangePartitioning"))
                        .equals(JSON.valueToTree(source.getExtra().get("rangePartitioning")))
                || !JSON.valueToTree(destination.getExtra().get("clustering"))
                        .equals(JSON.valueToTree(source.getExtra().get("clustering")))) {
            throw QueryEngine.invalidQuery("Provided Schema does not match Table " + qualified(projectId, target));
        }
    }

    private static StoredTableData copyTableData(StoredTableData source) {
        StoredTableData copy = new StoredTableData();
        synchronized (source) {
            copy.setRows(new ArrayList<>(source.getRows().stream().map(LinkedHashMap::new).toList()));
            Map<String, List<Map<String, Object>>> partitions = new LinkedHashMap<>();
            source.getPartitionRows().forEach((id, rows) ->
                    partitions.put(id, new ArrayList<>(rows.stream().map(LinkedHashMap::new).toList())));
            copy.setPartitionRows(partitions);
        }
        return copy;
    }

    private static StoredTableData copyDestinationData(StoredTableData source) {
        StoredTableData copy = copyTableData(source);
        synchronized (source) {
            copy.setStreamRows(new LinkedHashMap<>(source.getStreamRows()));
        }
        return copy;
    }

    private static <T> T deepCopy(T value, Class<T> type) {
        return value == null ? null : JSON.convertValue(value, type);
    }

    private static Map<String, Object> deepCopyMap(Map<String, Object> value) {
        return JSON.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
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

        JobLocation jobLocation = new JobLocation(projectId, location);
        StoredJob job = new StoredJob();
        job.setJobId(reserveJobId(projectId, jobId));
        job.setProjectId(projectId);
        job.setLocation(jobLocation.resolved());
        job.setJobType("LOAD");
        job.setLoadConfiguration(config);
        job.setState("DONE");
        job.setCreationTime(nowMillis());

        List<String> fileIds = new ArrayList<>();
        try {
            synchronized (writeLock) {
                runLoad(projectId, config, destination, sourceUris, uploadedData, fileIds, job, jobLocation);
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
        job.setLocation(jobLocation.resolved());
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
                         List<String> sourceUris, byte[] uploadedData, List<String> fileIds, StoredJob job,
                         JobLocation jobLocation) {
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
        jobLocation.admit(getDataset(projectId, destinationRef.getDatasetId()));

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
     * The location a job runs in. An explicit {@code jobReference.location} /
     * {@code QueryRequest.location} is kept; otherwise the job takes the location of the first
     * dataset it references ("the query is used for determining the processing location"),
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

        boolean contains(Dataset dataset) {
            String datasetLocation = dataset.getLocation() != null ? dataset.getLocation() : "US";
            return location == null || location.equalsIgnoreCase(datasetLocation);
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

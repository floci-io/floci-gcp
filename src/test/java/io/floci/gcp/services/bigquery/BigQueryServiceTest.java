package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.bigquery.model.Dataset;
import io.floci.gcp.services.bigquery.model.DatasetAccessEntry;
import io.floci.gcp.services.bigquery.model.DatasetReference;
import io.floci.gcp.services.bigquery.model.ErrorProto;
import io.floci.gcp.services.bigquery.model.StoredJob;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.floci.gcp.services.bigquery.model.TableReference;
import io.floci.gcp.services.bigquery.model.TableRow;
import io.floci.gcp.services.bigquery.model.TableSchema;
import io.floci.gcp.services.bigquery.model.UpdateMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class BigQueryServiceTest {

    private static final String PROJECT = "p1";
    private static final String DATASET = "ds1";
    private static final String TABLE = "t1";

    private BigQueryService service;

    @BeforeEach
    void setUp() {
        service = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());
    }

    // ── Datasets ──

    @Test
    void createDatasetSetsReferenceAndTimestamps() {
        Dataset created = service.createDataset(PROJECT, newDataset(DATASET));
        assertEquals(PROJECT, created.getDatasetReference().getProjectId());
        assertEquals(DATASET, created.getDatasetReference().getDatasetId());
        assertEquals(PROJECT + ":" + DATASET, created.getId());
        assertNotNull(created.getCreationTime());
        assertNotNull(created.getEtag());
    }

    @Test
    void createDatasetDuplicateThrowsDuplicateReason() {
        service.createDataset(PROJECT, newDataset(DATASET));
        GcpException ex = assertThrows(GcpException.class,
                () -> service.createDataset(PROJECT, newDataset(DATASET)));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
        assertEquals("duplicate", ex.getReason());
    }

    @Test
    void updateDatasetReplacesEntireResource() {
        Dataset created = newDataset(DATASET);
        created.setDescription("to be cleared");
        created.setFriendlyName("old name");
        service.createDataset(PROJECT, created);

        Dataset replacement = new Dataset();
        replacement.setFriendlyName("new name");
        Dataset updated = service.updateDataset(PROJECT, DATASET, replacement);

        assertEquals("new name", updated.getFriendlyName());
        assertNull(updated.getDescription());
    }

    @Test
    void patchDatasetPreservesOmittedFields() {
        Dataset created = newDataset(DATASET);
        created.setDescription("kept");
        service.createDataset(PROJECT, created);

        Dataset patch = new Dataset();
        patch.setFriendlyName("patched");
        Dataset patched = service.patchDataset(PROJECT, DATASET, patch);

        assertEquals("patched", patched.getFriendlyName());
        assertEquals("kept", patched.getDescription());
    }

    @Test
    void getDatasetMissingThrowsNotFound() {
        GcpException ex = assertThrows(GcpException.class, () -> service.getDataset(PROJECT, "nope"));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void listDatasetsReturnsCreated() {
        service.createDataset(PROJECT, newDataset("a"));
        service.createDataset(PROJECT, newDataset("b"));
        assertEquals(2, service.listDatasets(PROJECT).size());
    }

    @Test
    void deleteNonEmptyDatasetRequiresDeleteContents() {
        service.createDataset(PROJECT, newDataset(DATASET));
        service.createTable(PROJECT, DATASET, newTable(DATASET, TABLE));
        GcpException ex = assertThrows(GcpException.class,
                () -> service.deleteDataset(PROJECT, DATASET, false));
        assertEquals(400, ex.getHttpStatus());
        assertEquals("resourceInUse", ex.getReason());
        service.deleteDataset(PROJECT, DATASET, true);
        assertThrows(GcpException.class, () -> service.getDataset(PROJECT, DATASET));
    }

    // ── Tables ──

    @Test
    void createTableRequiresExistingDataset() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.createTable(PROJECT, "missing", newTable("missing", TABLE)));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void createTableSetsDefaultsAndNormalizesSchema() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table body = new Table();
        body.setTableReference(new TableReference(null, DATASET, TABLE));
        body.setSchema(new TableSchema(List.of(
                field("name", "STRING", null),
                field("age", "INT64", null),
                field("score", "FLOAT64", "required"),
                field("active", "BOOL", null),
                field("tags", "STRING", "REPEATED"))));

        Table created = service.createTable(PROJECT, DATASET, body);
        assertEquals("TABLE", created.getType());
        assertEquals("0", created.getNumRows());
        assertEquals(PROJECT + ":" + DATASET + "." + TABLE, created.getId());

        List<TableFieldSchema> fields = created.getSchema().getFields();
        assertEquals("STRING", fields.get(0).getType());
        assertEquals("NULLABLE", fields.get(0).getMode());
        assertEquals("INTEGER", fields.get(1).getType());
        assertEquals("FLOAT", fields.get(2).getType());
        assertEquals("REQUIRED", fields.get(2).getMode());
        assertEquals("BOOLEAN", fields.get(3).getType());
        assertEquals("REPEATED", fields.get(4).getMode());
    }

    @Test
    void createTableDuplicateThrowsDuplicateReason() {
        service.createDataset(PROJECT, newDataset(DATASET));
        service.createTable(PROJECT, DATASET, newTable(DATASET, TABLE));
        GcpException ex = assertThrows(GcpException.class,
                () -> service.createTable(PROJECT, DATASET, newTable(DATASET, TABLE)));
        assertEquals("duplicate", ex.getReason());
    }

    @Test
    void insertAllRejectsUnknownNestedFieldWhenNotIgnoringUnknownValues() {
        service.createDataset(PROJECT, newDataset(DATASET));
        TableFieldSchema sub = new TableFieldSchema();
        sub.setName("city");
        sub.setType("STRING");
        TableFieldSchema address = new TableFieldSchema();
        address.setName("address");
        address.setType("RECORD");
        address.setFields(java.util.List.of(sub));
        Table table = new Table();
        table.setTableReference(new TableReference(PROJECT, DATASET, TABLE));
        table.setSchema(new TableSchema(java.util.List.of(address)));
        service.createTable(PROJECT, DATASET, table);

        var rows = java.util.List.of(new BigQueryService.InsertRow(0,
                java.util.Map.of("address", java.util.Map.of("city", "SD", "zip", "00000"))));
        var strictErrors = service.insertAll(PROJECT, DATASET, TABLE, rows, false, false);
        assertEquals(1, strictErrors.size());

        var lenientErrors = service.insertAll(PROJECT, DATASET, TABLE, rows, false, true);
        assertEquals(0, lenientErrors.size());
    }

    // ── insertAll ──

    @Test
    void insertAllCoercesAndListsRowsInWireShape() {
        seedTable();
        List<Map<String, Object>> errors = insertRows(
                Map.of("name", "alice", "age", 30),
                Map.of("name", "bob", "age", "25"));
        assertTrue(errors.isEmpty());

        assertEquals("2", service.getTable(PROJECT, DATASET, TABLE).getNumRows());

        BigQueryService.TableData data = service.listTableData(PROJECT, DATASET, TABLE);
        assertEquals(2, data.rows().size());
        // Cells follow schema order (name, age), scalars rendered as strings.
        assertEquals("alice", data.rows().get(0).getF().get(0).getV());
        assertEquals("30", data.rows().get(0).getF().get(1).getV());
        assertEquals("25", data.rows().get(1).getF().get(1).getV());
    }

    @Test
    void insertAllRejectsUnknownFieldUnlessIgnored() {
        seedTable();
        List<Map<String, Object>> errors = service.insertAll(PROJECT, DATASET, TABLE,
                List.of(new BigQueryService.InsertRow(0, Map.of("name", "x", "bogus", 1))),
                false, false);
        assertEquals(1, errors.size());
        assertEquals(0, errors.get(0).get("index"));
        assertEquals(0, service.listTableData(PROJECT, DATASET, TABLE).rows().size());

        List<Map<String, Object>> ignored = service.insertAll(PROJECT, DATASET, TABLE,
                List.of(new BigQueryService.InsertRow(0, Map.of("name", "x", "bogus", 1))),
                false, true);
        assertTrue(ignored.isEmpty());
        assertEquals(1, service.listTableData(PROJECT, DATASET, TABLE).rows().size());
    }

    @Test
    void insertAllWithoutSkipStopsValidRows() {
        seedTable();
        List<Map<String, Object>> errors = service.insertAll(PROJECT, DATASET, TABLE,
                List.of(new BigQueryService.InsertRow(0, Map.of("name", "ok", "age", 1)),
                        new BigQueryService.InsertRow(1, Map.of("name", "bad", "age", "not-a-number"))),
                false, false);

        assertEquals(2, errors.size());
        assertEquals(0, service.listTableData(PROJECT, DATASET, TABLE).rows().size());
        Map<Object, Object> byIndex = new java.util.HashMap<>();
        errors.forEach(e -> byIndex.put(e.get("index"), e.get("errors")));
        @SuppressWarnings("unchecked")
        List<ErrorProto> stoppedErrors = (List<ErrorProto>) byIndex.get(0);
        assertEquals("stopped", stoppedErrors.get(0).getReason());
    }

    @Test
    void insertAllWithSkipInsertsValidRowsOnly() {
        seedTable();
        List<Map<String, Object>> errors = service.insertAll(PROJECT, DATASET, TABLE,
                List.of(new BigQueryService.InsertRow(0, Map.of("name", "ok", "age", 1)),
                        new BigQueryService.InsertRow(1, Map.of("name", "bad", "age", "nope"))),
                true, false);

        assertEquals(1, errors.size());
        assertEquals(1, errors.get(0).get("index"));
        assertEquals(1, service.listTableData(PROJECT, DATASET, TABLE).rows().size());
    }

    @Test
    void insertAllEnforcesRequiredFields() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table table = new Table();
        table.setTableReference(new TableReference(null, DATASET, TABLE));
        table.setSchema(new TableSchema(List.of(field("name", "STRING", "REQUIRED"))));
        service.createTable(PROJECT, DATASET, table);

        List<Map<String, Object>> errors = service.insertAll(PROJECT, DATASET, TABLE,
                List.of(new BigQueryService.InsertRow(0, Map.of())), false, false);
        assertEquals(1, errors.size());
    }

    @Test
    void repeatedAndNullCellsUseWireEncoding() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table table = new Table();
        table.setTableReference(new TableReference(null, DATASET, TABLE));
        table.setSchema(new TableSchema(List.of(
                field("name", "STRING", null),
                field("tags", "STRING", "REPEATED"))));
        service.createTable(PROJECT, DATASET, table);

        insertRows(Map.of("name", "a", "tags", List.of("x", "y")));
        insertRows(Map.of("tags", List.of()));

        List<TableRow> rows = service.listTableData(PROJECT, DATASET, TABLE).rows();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> repeated = (List<Map<String, Object>>) rows.get(0).getF().get(1).getV();
        assertEquals(2, repeated.size());
        assertEquals("x", repeated.get(0).get("v"));
        assertNull(rows.get(1).getF().get(0).getV());
    }

    // ── Query + jobs ──

    @Test
    void selectStarMaterializesAnonymousDestinationTable() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, "SELECT * FROM ds1.t1", null);

        assertEquals("DONE", job.getState());
        assertEquals(2, job.getTotalRows());
        assertEquals(BigQueryService.ANON_DATASET, job.getDestinationDatasetId());

        BigQueryService.TableData results = service.queryResults(PROJECT, job);
        assertEquals(2, results.rows().size());
        assertEquals(2, results.schema().getFields().size());

        // The SDK reads the same rows via tabledata.list on the destination table.
        BigQueryService.TableData viaTableData = service.listTableData(PROJECT,
                job.getDestinationDatasetId(), job.getDestinationTableId());
        assertEquals(2, viaTableData.rows().size());
    }

    @Test
    void whereProjectionAndLimitAreApplied() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null,
                "SELECT name FROM ds1.t1 WHERE age = 30", null);
        BigQueryService.TableData results = service.queryResults(PROJECT, job);

        assertEquals(1, results.schema().getFields().size());
        assertEquals("name", results.schema().getFields().get(0).getName());
        assertEquals(1, results.rows().size());
        assertEquals("alice", results.rows().get(0).getF().get(0).getV());

        StoredJob limited = service.query(PROJECT, "US", null, "SELECT * FROM ds1.t1 LIMIT 1", null);
        assertEquals(1, service.queryResults(PROJECT, limited).rows().size());
    }

    @Test
    void countStarReturnsSingleIntegerRow() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, "SELECT COUNT(*) FROM ds1.t1", null);
        BigQueryService.TableData results = service.queryResults(PROJECT, job);
        assertEquals("INTEGER", results.schema().getFields().get(0).getType());
        assertEquals("2", results.rows().get(0).getF().get(0).getV());
    }

    @Test
    void defaultDatasetResolvesUnqualifiedTable() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, "SELECT COUNT(*) FROM t1", DATASET);
        assertEquals("DONE", job.getState());

        GcpException ex = assertThrows(GcpException.class,
                () -> service.query(PROJECT, "US", null, "SELECT COUNT(*) FROM t1", null));
        assertEquals("invalidQuery", ex.getReason());
    }

    @Test
    void duplicateExplicitJobIdConflictsEvenWhenQueryFails() {
        seedTwoRows();
        // First insert with an explicit ID against a missing table → stored as a failed job.
        GcpException missing = assertThrows(GcpException.class,
                () -> service.query(PROJECT, "US", "dup-job", "SELECT * FROM ds1.nope", null));
        StoredJob failed = service.failedJob(PROJECT, "US", "dup-job", "SELECT * FROM ds1.nope", missing);
        assertTrue(failed.failed());

        // Re-submitting the same explicit ID must 409, not silently overwrite.
        GcpException viaQuery = assertThrows(GcpException.class,
                () -> service.query(PROJECT, "US", "dup-job", "SELECT * FROM ds1.t1", null));
        assertEquals(409, viaQuery.getHttpStatus());
        GcpException viaFailed = assertThrows(GcpException.class,
                () -> service.failedJob(PROJECT, "US", "dup-job", "SELECT * FROM ds1.nope", missing));
        assertEquals(409, viaFailed.getHttpStatus());
    }

    @Test
    void unsupportedSqlThrowsInvalidQueryReason() {
        seedTwoRows();
        GcpException ex = assertThrows(GcpException.class,
                () -> service.query(PROJECT, "US", null, "SELECT * FROM ds1.t1 GROUP BY name", null));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertEquals("invalidQuery", ex.getReason());
    }

    @Test
    void missingTableThrowsNotFound() {
        service.createDataset(PROJECT, newDataset(DATASET));
        GcpException ex = assertThrows(GcpException.class,
                () -> service.query(PROJECT, "US", null, "SELECT * FROM ds1.missing", null));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void failedJobIsPersistedAndQueryResultsRethrow() {
        StoredJob job = service.failedJob(PROJECT, "US", null, "SELECT bogus",
                QueryEngine.invalidQuery("Unrecognized name: bogus"));

        StoredJob fetched = service.getJob(PROJECT, job.getJobId());
        assertTrue(fetched.failed());
        assertEquals("DONE", fetched.getState());

        GcpException ex = assertThrows(GcpException.class, () -> service.queryResults(PROJECT, fetched));
        assertEquals("invalidQuery", ex.getReason());
    }

    @Test
    void deleteJobRemovesAnonymousTable() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, "SELECT * FROM ds1.t1", null);
        String anonDataset = job.getDestinationDatasetId();
        String anonTable = job.getDestinationTableId();

        service.deleteJob(PROJECT, job.getJobId());
        assertThrows(GcpException.class, () -> service.getJob(PROJECT, job.getJobId()));
        assertThrows(GcpException.class, () -> service.getTable(PROJECT, anonDataset, anonTable));
    }

    @Test
    void deleteJobKeepsACallerSuppliedDestinationTable() {
        seedTwoRows();
        BigQueryService.QueryOptions options = new BigQueryService.QueryOptions(
                "SELECT * FROM ds1.t1", null, List.of(), null, false, false,
                new TableReference(PROJECT, DATASET, "kept"), "WRITE_TRUNCATE", null);
        StoredJob job = service.query(PROJECT, "US", null, options);
        assertEquals("kept", job.getDestinationTableId());

        // jobs.delete "requests the deletion of the metadata of a job", so a table the caller
        // named has to survive it, unlike the emulator's own anonymous result table.
        service.deleteJob(PROJECT, job.getJobId());
        assertThrows(GcpException.class, () -> service.getJob(PROJECT, job.getJobId()));
        assertNotNull(service.getTable(PROJECT, DATASET, "kept"));
        assertEquals(2, service.storedRows(PROJECT, DATASET, "kept").size());
    }

    @Test
    void dryRunDmlAndDdlReportTheErrorARealRunWould() {
        seedTwoRows();
        assertEquals("NOT_FOUND", assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                new BigQueryService.QueryOptions("DELETE FROM ds1.missing WHERE TRUE", null, List.of(),
                        null, true, false))).getGcpStatus());
        assertEquals("NOT_FOUND", assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                new BigQueryService.QueryOptions("DROP TABLE ds1.missing", null, List.of(),
                        null, true, false))).getGcpStatus());
        assertEquals("duplicate", assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                new BigQueryService.QueryOptions("CREATE TABLE ds1.t1 (id INT64)", null, List.of(),
                        null, true, false))).getReason());

        // A dry run that is valid still reports success and writes nothing.
        StoredJob ok = service.query(PROJECT, "US", null, new BigQueryService.QueryOptions(
                "DROP TABLE IF EXISTS ds1.missing", null, List.of(), null, true, false));
        assertTrue(ok.isDryRun());
        assertNotNull(service.getTable(PROJECT, DATASET, TABLE));
    }

    @Test
    void anonymousDatasetNeverAppearsInListings() {
        seedTwoRows();
        service.query(PROJECT, "US", null, "SELECT * FROM ds1.t1", null);
        assertTrue(service.listDatasets(PROJECT).stream()
                .noneMatch(d -> BigQueryService.ANON_DATASET.equals(d.getDatasetReference().getDatasetId())));
    }

    // ── Helpers ──

    private Dataset newDataset(String datasetId) {
        Dataset d = new Dataset();
        d.setDatasetReference(new DatasetReference(null, datasetId));
        return d;
    }

    private Table newTable(String datasetId, String tableId) {
        Table t = new Table();
        t.setTableReference(new TableReference(null, datasetId, tableId));
        t.setSchema(new TableSchema(List.of(
                field("name", "STRING", null),
                field("age", "INTEGER", null))));
        return t;
    }

    private static TableFieldSchema field(String name, String type, String mode) {
        TableFieldSchema f = new TableFieldSchema();
        f.setName(name);
        f.setType(type);
        f.setMode(mode);
        return f;
    }

    private void seedTable() {
        service.createDataset(PROJECT, newDataset(DATASET));
        service.createTable(PROJECT, DATASET, newTable(DATASET, TABLE));
    }

    private void seedTwoRows() {
        seedTable();
        insertRows(Map.of("name", "alice", "age", 30), Map.of("name", "bob", "age", 25));
    }

    @SafeVarargs
    private List<Map<String, Object>> insertRows(Map<String, Object>... jsons) {
        List<BigQueryService.InsertRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < jsons.length; i++) {
            rows.add(new BigQueryService.InsertRow(i, jsons[i]));
        }
        return service.insertAll(PROJECT, DATASET, TABLE, rows, false, false);
    }

    // ── Metadata round-trip ──

    @Test
    void writableDatasetFieldsRoundTripAndOutputsAreFilled() {
        Dataset body = newDataset(DATASET);
        body.setExtra("defaultTableExpirationMs", 7_200_000);
        body.setExtra("defaultCollation", "und:ci");
        body.setExtra("storageBillingModel", "PHYSICAL");
        body.setExtra("access", List.of(Map.of("role", "READER")));
        body.setExtra("numRows", "999");
        Dataset created = service.createDataset(PROJECT, body);

        assertEquals("7200000", created.getExtra().get("defaultTableExpirationMs"));
        assertEquals("und:ci", created.getExtra().get("defaultCollation"));
        assertEquals("PHYSICAL", created.getExtra().get("storageBillingModel"));
        assertEquals("168", created.getExtra().get("maxTimeTravelHours"));
        assertFalse(created.getExtra().containsKey("access"), "access is not stored yet (PR #208)");
        assertFalse(created.getExtra().containsKey("numRows"));
        assertEquals("DEFAULT", created.getType());
        assertEquals("US", created.getLocation());
    }

    @Test
    void datasetPatchClearsDefaultExpirationWithZeroAndValidatesRanges() {
        Dataset body = newDataset(DATASET);
        body.setExtra("defaultTableExpirationMs", "7200000");
        service.createDataset(PROJECT, body);

        Dataset patch = new Dataset();
        patch.setExtra("defaultTableExpirationMs", "0");
        assertFalse(service.patchDataset(PROJECT, DATASET, patch).getExtra().containsKey("defaultTableExpirationMs"));

        Dataset tooShort = new Dataset();
        tooShort.setExtra("defaultTableExpirationMs", "1000");
        assertEquals("invalid", assertThrows(GcpException.class,
                () -> service.patchDataset(PROJECT, DATASET, tooShort)).getReason());
        Dataset badTravel = new Dataset();
        badTravel.setExtra("maxTimeTravelHours", "24");
        assertThrows(GcpException.class, () -> service.patchDataset(PROJECT, DATASET, badTravel));
    }

    @Test
    void newTablesInheritDatasetDefaultExpirations() {
        Dataset body = newDataset(DATASET);
        body.setExtra("defaultTableExpirationMs", "7200000");
        body.setExtra("defaultPartitionExpirationMs", "86400000");
        service.createDataset(PROJECT, body);

        Table plain = service.createTable(PROJECT, DATASET, newTable(DATASET, "plain"));
        long expiration = Long.parseLong((String) plain.getExtra().get("expirationTime"));
        assertEquals(Long.parseLong(plain.getCreationTime()) + 7_200_000L, expiration);

        Table partitioned = newTable(DATASET, "partitioned");
        Map<String, Object> partitioning = new java.util.HashMap<>();
        partitioning.put("type", "DAY");
        partitioning.put("expirationMs", null); // the Java SDK sends explicit nulls
        partitioned.setExtra("timePartitioning", partitioning);
        Table created = service.createTable(PROJECT, DATASET, partitioned);
        assertEquals("86400000", ((Map<?, ?>) created.getExtra().get("timePartitioning")).get("expirationMs"));
        assertFalse(created.getExtra().containsKey("expirationTime"),
                "a partitioned table inheriting the partition default gets no table expiration");

        Table explicit = newTable(DATASET, "explicit");
        explicit.setExtra("expirationTime", "4102444800000");
        assertEquals("4102444800000", service.createTable(PROJECT, DATASET, explicit).getExtra().get("expirationTime"));
    }

    @Test
    void tableMetadataRoundTripsThroughPatchAndUpdate() {
        seedTable();
        Table patch = new Table();
        patch.setExtra("clustering", Map.of("fields", List.of("name")));
        patch.setExtra("timePartitioning", Map.of("type", "DAY", "expirationMs", 3600000));
        patch.setExtra("requirePartitionFilter", true);
        Table patched = service.patchTable(PROJECT, DATASET, TABLE, patch);
        assertEquals(Map.of("fields", List.of("name")), patched.getExtra().get("clustering"));
        assertEquals("3600000", ((Map<?, ?>) patched.getExtra().get("timePartitioning")).get("expirationMs"));
        assertEquals("US", patched.getLocation());

        Table clear = new Table();
        clear.setExtra("clustering", null);
        assertFalse(service.patchTable(PROJECT, DATASET, TABLE, clear).getExtra().containsKey("clustering"));

        Table update = newTable(DATASET, TABLE);
        update.setExtra("defaultCollation", "und:ci");
        Table updated = service.updateTable(PROJECT, DATASET, TABLE, update);
        assertEquals(Map.of("defaultCollation", "und:ci"), updated.getExtra(),
                "PUT replaces every writable field");
    }

    @Test
    void invalidPartitioningIsRejected() {
        seedTable();
        Table noType = newTable(DATASET, "p1");
        noType.setExtra("timePartitioning", Map.of("field", "name"));
        assertEquals("invalid", assertThrows(GcpException.class,
                () -> service.createTable(PROJECT, DATASET, noType)).getReason());
        Table unknownField = newTable(DATASET, "p2");
        unknownField.setExtra("timePartitioning", Map.of("type", "DAY", "field", "missing"));
        assertThrows(GcpException.class, () -> service.createTable(PROJECT, DATASET, unknownField));
    }

    @Test
    void expiredTablesAreDeleted() {
        seedTwoRows();
        Table patch = new Table();
        patch.setExtra("expirationTime", String.valueOf(System.currentTimeMillis() - 1));
        service.patchTable(PROJECT, DATASET, TABLE, patch);

        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getTable(PROJECT, DATASET, TABLE)).getGcpStatus());
        assertTrue(service.listTables(PROJECT, DATASET).isEmpty());
    }

    @Test
    void rejectedDatasetPatchLeavesTheStoredDatasetUntouched() {
        Dataset created = newDataset(DATASET);
        created.setDescription("original");
        service.createDataset(PROJECT, created);

        Dataset patch = new Dataset();
        patch.setDescription("should not stick");
        patch.setExtra("maxTimeTravelHours", "24");
        assertEquals("invalid", assertThrows(GcpException.class,
                () -> service.patchDataset(PROJECT, DATASET, patch)).getReason());

        Dataset stored = service.getDataset(PROJECT, DATASET);
        assertEquals("original", stored.getDescription());
        assertFalse(stored.getExtra().containsKey("maxTimeTravelHours")
                && "24".equals(String.valueOf(stored.getExtra().get("maxTimeTravelHours"))));
    }

    @Test
    void rejectedDatasetUpdateLeavesTheStoredDatasetUntouched() {
        Dataset created = newDataset(DATASET);
        created.setDescription("original");
        service.createDataset(PROJECT, created);

        Dataset update = newDataset(DATASET);
        update.setDescription("should not stick");
        update.setExtra("defaultTableExpirationMs", "1000");
        assertEquals("invalid", assertThrows(GcpException.class,
                () -> service.updateDataset(PROJECT, DATASET, update)).getReason());

        assertEquals("original", service.getDataset(PROJECT, DATASET).getDescription());
    }

    @Test
    void rejectedTablePatchLeavesTheStoredTableUntouched() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table created = newTable(DATASET, TABLE);
        created.setDescription("original");
        service.createTable(PROJECT, DATASET, created);

        Table patch = new Table();
        patch.setDescription("should not stick");
        patch.setExtra("timePartitioning", Map.of("type", "CENTURY"));
        assertEquals("invalid", assertThrows(GcpException.class,
                () -> service.patchTable(PROJECT, DATASET, TABLE, patch)).getReason());

        Table stored = service.getTable(PROJECT, DATASET, TABLE);
        assertEquals("original", stored.getDescription());
        assertFalse(stored.getExtra().containsKey("timePartitioning"));
    }

    @Test
    void unparseableExpirationTimeIsRejectedOnWriteRatherThanBreakingReads() {
        service.createDataset(PROJECT, newDataset(DATASET));
        service.createTable(PROJECT, DATASET, newTable(DATASET, TABLE));

        Table patch = new Table();
        patch.setExtra("expirationTime", "not-a-number");
        assertEquals("invalid", assertThrows(GcpException.class,
                () -> service.patchTable(PROJECT, DATASET, TABLE, patch)).getReason());

        assertNotNull(service.getTable(PROJECT, DATASET, TABLE));
        assertEquals(1, service.listTables(PROJECT, DATASET).size());

        Table onCreate = newTable(DATASET, "t2");
        onCreate.setExtra("expirationTime", "soon");
        assertEquals("invalid", assertThrows(GcpException.class,
                () -> service.createTable(PROJECT, DATASET, onCreate)).getReason());
    }

    @Test
    void aclOnlyPatchIgnoresMetadataEntirely() {
        Dataset created = newDataset(DATASET);
        created.setDescription("original");
        created.setExtra("maxTimeTravelHours", "96");
        service.createDataset(PROJECT, created);

        DatasetAccessEntry entry = new DatasetAccessEntry();
        entry.setRole("READER");
        entry.setUserByEmail("reader@example.com");
        Dataset patch = new Dataset();
        patch.setAccess(List.of(entry));
        patch.setDescription("should be ignored");
        // Invalid metadata on an ACL-only write is ignored rather than rejected: UPDATE_ACL
        // "leaves metadata alone", so nothing here reaches validation.
        patch.setExtra("maxTimeTravelHours", "24");

        Dataset patched = service.patchDataset(PROJECT, DATASET, patch, UpdateMode.UPDATE_ACL);

        assertEquals(1, patched.getAccess().size());
        assertEquals("reader@example.com", patched.getAccess().get(0).getUserByEmail());
        assertEquals("original", patched.getDescription());
        assertEquals("96", String.valueOf(patched.getExtra().get("maxTimeTravelHours")));
    }

    @Test
    void storedRowsAndEngineRowsAreSnapshotsNotTheLiveList() {
        seedTwoRows();

        // Deterministic interleaving, no threads: hold the list an engine would be iterating,
        // let insertAll append to the backing list, then keep iterating. A live list throws
        // ConcurrentModificationException here; a snapshot does not.
        List<Map<String, Object>> streaming = service.storedRows(PROJECT, DATASET, TABLE);
        List<Map<String, Object>> engineRows = service.tables(PROJECT).rows(DATASET, TABLE);
        insertRows(Map.of("name", "carol", "age", 41));

        assertEquals(2, streaming.size());
        assertEquals(2, engineRows.size());
        assertDoesNotThrow(() -> {
            for (Map<String, Object> row : streaming) {
                assertNotNull(row);
            }
            for (Map<String, Object> row : engineRows) {
                assertNotNull(row);
            }
        });
        assertEquals(3, service.storedRows(PROJECT, DATASET, TABLE).size());
    }

    // ── DDL without the SQL engine ──

    @Test
    void ddlSchemaAndTableStatementsRunWithoutTheSqlEngine() {
        StoredJob schema = service.query(PROJECT, null, null, "CREATE SCHEMA " + DATASET, null);
        assertEquals("CREATE_SCHEMA", schema.getStatementType());
        assertEquals("CREATE", schema.getDdlOperationPerformed());
        assertNull(schema.getDestinationTableId());

        StoredJob create = service.query(PROJECT, null, null,
                "CREATE TABLE " + DATASET + ".events (id INT64 NOT NULL, at TIMESTAMP, tags ARRAY<STRING>)", null);
        assertEquals("CREATE", create.getDdlOperationPerformed());
        assertEquals("events", create.getDdlTargetTable().getTableId());
        Table table = service.getTable(PROJECT, DATASET, "events");
        assertEquals(List.of("INTEGER", "TIMESTAMP", "STRING"),
                table.getSchema().getFields().stream().map(TableFieldSchema::getType).toList());
        assertEquals("REQUIRED", table.getSchema().getFields().get(0).getMode());

        GcpException duplicate = assertThrows(GcpException.class, () -> service.query(PROJECT, null, null,
                "CREATE TABLE " + DATASET + ".events (x INT64)", null));
        assertEquals("duplicate", duplicate.getReason());
        assertEquals("SKIP", service.query(PROJECT, null, null,
                "CREATE TABLE IF NOT EXISTS " + DATASET + ".events (x INT64)", null).getDdlOperationPerformed());
        assertEquals("REPLACE", service.query(PROJECT, null, null,
                "CREATE OR REPLACE TABLE " + DATASET + ".events (x INT64)", null).getDdlOperationPerformed());
        assertEquals(1, service.getTable(PROJECT, DATASET, "events").getSchema().getFields().size());
    }

    @Test
    void truncateAndDropStatements() {
        seedTwoRows();

        StoredJob truncate = service.query(PROJECT, null, null, "TRUNCATE TABLE " + DATASET + "." + TABLE, null);
        assertEquals("TRUNCATE_TABLE", truncate.getStatementType());
        assertEquals(Map.of("deletedRowCount", "2"), truncate.getDmlStats());
        assertTrue(service.listTableData(PROJECT, DATASET, TABLE).rows().isEmpty());
        assertEquals(0, service.queryResults(PROJECT, truncate).rows().size());

        assertEquals("DROP", service.query(PROJECT, null, null, "DROP TABLE " + DATASET + "." + TABLE, null)
                .getDdlOperationPerformed());
        assertEquals("NOT_FOUND", assertThrows(GcpException.class, () -> service.query(PROJECT, null, null,
                "DROP TABLE " + DATASET + "." + TABLE, null)).getGcpStatus());
        assertEquals("SKIP", service.query(PROJECT, null, null, "DROP TABLE IF EXISTS " + DATASET + "." + TABLE,
                null).getDdlOperationPerformed());
        assertEquals("DROP", service.query(PROJECT, null, null, "DROP SCHEMA " + DATASET, null)
                .getDdlOperationPerformed());
    }

    @Test
    void dmlNeedsTheSqlEngine() {
        seedTable();
        GcpException e = assertThrows(GcpException.class, () -> service.query(PROJECT, null, null,
                "INSERT INTO " + DATASET + "." + TABLE + " (name) VALUES ('x')", null));
        assertEquals("invalidQuery", e.getReason());
        assertTrue(e.getMessage().contains("DuckDB"));
    }

    // ── Load jobs (newline-delimited JSON with a schema runs without the SQL engine) ──

    private static Map<String, Object> loadConfig(String table, Object... extra) {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("destinationTable", Map.of("datasetId", DATASET, "tableId", table));
        config.put("sourceFormat", "NEWLINE_DELIMITED_JSON");
        for (int i = 0; i < extra.length; i += 2) {
            config.put((String) extra[i], extra[i + 1]);
        }
        return config;
    }

    private static final Map<String, Object> PEOPLE_SCHEMA = Map.of("fields", List.of(
            Map.of("name", "name", "type", "STRING"), Map.of("name", "age", "type", "INT64")));

    private static byte[] ndjson(String... lines) {
        return String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void loadJobAppendsNewlineDelimitedJson() {
        service.createDataset(PROJECT, newDataset(DATASET));
        StoredJob first = service.load(PROJECT, null, null, loadConfig("people", "schema", PEOPLE_SCHEMA),
                ndjson("{\"name\": \"ana\", \"age\": 30}", "", "{\"name\": \"bo\", \"age\": \"41\"}"));
        assertFalse(first.failed(), first.getErrorMessage());
        assertEquals("LOAD", first.getJobType());
        assertEquals("2", first.getLoadStatistics().get("outputRows"));
        assertEquals("1", first.getLoadStatistics().get("inputFiles"));
        assertEquals("0", first.getLoadStatistics().get("badRecords"));
        assertEquals("INTEGER", service.getTable(PROJECT, DATASET, "people").getSchema().getFields().get(1).getType());

        // WRITE_APPEND is the default for loads, and the existing table's schema is reused.
        StoredJob second = service.load(PROJECT, null, null, loadConfig("people"), ndjson("{\"name\": \"cy\"}"));
        assertFalse(second.failed(), second.getErrorMessage());
        assertEquals(3, service.listTableData(PROJECT, DATASET, "people").rows().size());

        StoredJob truncate = service.load(PROJECT, null, null,
                loadConfig("people", "writeDisposition", "WRITE_TRUNCATE"), ndjson("{\"name\": \"dee\"}"));
        assertFalse(truncate.failed());
        assertEquals(1, service.listTableData(PROJECT, DATASET, "people").rows().size());
    }

    @Test
    void loadJobCountsBadRecordsAgainstMaxBadRecords() {
        service.createDataset(PROJECT, newDataset(DATASET));
        byte[] data = ndjson("{\"name\": \"ana\", \"age\": 30}", "{\"name\": \"x\", \"age\": \"old\"}", "not json");
        StoredJob strict = service.load(PROJECT, null, null, loadConfig("t", "schema", PEOPLE_SCHEMA), data);
        assertTrue(strict.failed());
        assertEquals("invalid", strict.getErrorReason());
        assertTrue(strict.getErrorMessage().contains("errors: 2"), strict.getErrorMessage());

        StoredJob lenient = service.load(PROJECT, null, null,
                loadConfig("t", "schema", PEOPLE_SCHEMA, "maxBadRecords", 2), data);
        assertFalse(lenient.failed(), lenient.getErrorMessage());
        assertEquals("2", lenient.getLoadStatistics().get("badRecords"));
        assertEquals("1", lenient.getLoadStatistics().get("outputRows"));
    }

    @Test
    void loadJobErrorsLandInTheJobStatus() {
        service.createDataset(PROJECT, newDataset(DATASET));
        StoredJob noSchema = service.load(PROJECT, null, null, loadConfig("fresh"), ndjson("{\"a\": 1}"));
        assertEquals("No schema specified on job or table.", noSchema.getErrorMessage());

        StoredJob csv = service.load(PROJECT, null, null,
                loadConfig("fresh", "sourceFormat", "CSV", "autodetect", true), "a,b\n1,2".getBytes());
        assertTrue(csv.failed());
        assertTrue(csv.getErrorMessage().contains("DuckDB"), csv.getErrorMessage());

        StoredJob gcs = service.load(PROJECT, null, null,
                loadConfig("fresh", "sourceUris", List.of("gs://bucket/data.json"), "schema", PEOPLE_SCHEMA), null);
        assertTrue(gcs.failed());

        StoredJob avro = service.load(PROJECT, null, null, loadConfig("fresh", "sourceFormat", "AVRO"), new byte[1]);
        assertTrue(avro.getErrorMessage().contains("AVRO"));

        GcpException missing = assertThrows(GcpException.class, () -> service.load(PROJECT, null, null,
                Map.of("sourceFormat", "CSV"), new byte[1]));
        assertEquals(400, missing.getHttpStatus());
    }

    @Test
    void appendRowsRecordsStreamCountsInTheSameWrite() {
        service.createDataset(PROJECT, newDataset(DATASET));
        service.createTable(PROJECT, DATASET, newTable(DATASET, TABLE));
        String stream = "projects/" + PROJECT + "/datasets/" + DATASET + "/tables/" + TABLE + "/streams/s1";

        service.appendRows(PROJECT, DATASET, TABLE, List.of(Map.of("name", "a")), Map.of(stream, 1L));
        service.appendRows(PROJECT, DATASET, TABLE, List.of(Map.of("name", "b"), Map.of("name", "c")),
                Map.of(stream, 3L));
        service.appendRows(PROJECT, DATASET, TABLE, List.of(Map.of("name", "d")));

        assertEquals(OptionalLong.of(3L), service.streamRowsApplied(PROJECT, DATASET, TABLE, stream));
        assertEquals(OptionalLong.empty(), service.streamRowsApplied(PROJECT, DATASET, TABLE, stream + "x"));

        service.appendRows(PROJECT, DATASET, TABLE, List.of(), Map.of(stream + "empty", 0L));
        assertEquals(OptionalLong.of(0L), service.streamRowsApplied(PROJECT, DATASET, TABLE, stream + "empty"));
        assertEquals(4, service.listTableData(PROJECT, DATASET, TABLE).rows().size());
    }
}

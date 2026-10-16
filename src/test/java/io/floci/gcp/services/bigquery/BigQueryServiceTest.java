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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
    void decoratedDestinationWritesOnlyItsSelectedPartition() {
        seedTwoRows();
        Table destination = newTable(DATASET, "events");
        destination.setExtra("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, destination);

        StoredJob first = service.query(PROJECT, "US", null, new BigQueryService.QueryOptions(
                "SELECT * FROM ds1.t1", null, List.of(), null, false, false,
                new TableReference(PROJECT, DATASET, "events$20261006"), "WRITE_TRUNCATE", null));
        service.query(PROJECT, "US", null, new BigQueryService.QueryOptions(
                "SELECT * FROM ds1.t1", null, List.of(), null, false, false,
                new TableReference(PROJECT, DATASET, "events$20261007"), "WRITE_TRUNCATE", null));
        service.query(PROJECT, "US", null, new BigQueryService.QueryOptions(
                "SELECT * FROM ds1.t1 WHERE name = 'alice'", null, List.of(), null, false, false,
                new TableReference(PROJECT, DATASET, "events$20261006"), "WRITE_TRUNCATE", null));

        assertEquals("events$20261006", first.getDestinationTableId());
        assertEquals(3, service.storedRows(PROJECT, DATASET, "events").size());
        StoredJob count = service.query(PROJECT, "US", null, "SELECT COUNT(*) FROM ds1.events", null);
        assertEquals("3", service.queryResults(PROJECT, count).rows().getFirst().getF().getFirst().getV());
        GcpException duplicate = assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                new BigQueryService.QueryOptions("SELECT * FROM ds1.t1", null, List.of(), null, false, false,
                        new TableReference(PROJECT, DATASET, "events$20261006"), "WRITE_EMPTY", null)));
        assertEquals("duplicate", duplicate.getReason());
    }

    @Test
    void decoratedDestinationCreatesAPartitionedTableWhenAllowed() {
        seedTwoRows();

        service.query(PROJECT, "US", null, new BigQueryService.QueryOptions(
                "SELECT * FROM ds1.t1", null, List.of(), null, false, false,
                new TableReference(PROJECT, DATASET, "created_events$20261006"), "WRITE_TRUNCATE",
                "CREATE_IF_NEEDED", List.of(), Map.of("type", "DAY"), null));

        Table created = service.getTable(PROJECT, DATASET, "created_events");
        assertEquals("DAY", ((Map<?, ?>) created.getExtra().get("timePartitioning")).get("type"));
        assertEquals(2, service.storedRows(PROJECT, DATASET, "created_events").size());
    }

    @Test
    void appendToIngestionTimeTablePreservesExistingPartitionMembership() {
        seedTwoRows();
        Table destination = newTable(DATASET, "events");
        destination.setExtra("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, destination);
        service.load(PROJECT, null, null,
                loadConfig("events$20010101", "schema", PEOPLE_SCHEMA, "writeDisposition", "WRITE_TRUNCATE"),
                ndjson("{\"name\": \"old\", \"age\": 1}"));

        service.query(PROJECT, "US", null, new BigQueryService.QueryOptions(
                "SELECT * FROM ds1.t1", null, List.of(), null, false, false,
                new TableReference(PROJECT, DATASET, "events"), "WRITE_APPEND", null));

        assertEquals(1, service.listTableData(PROJECT, DATASET, "events$20010101").rows().size());
        String today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        assertEquals(2, service.listTableData(PROJECT, DATASET, "events$" + today).rows().size());
        assertEquals(3, service.listTableData(PROJECT, DATASET, "events").rows().size());
    }

    @Test
    void decoratedDestinationCreationRequiresExplicitPartitioning() {
        seedTwoRows();

        GcpException error = assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                new BigQueryService.QueryOptions(
                        "SELECT * FROM ds1.t1", null, List.of(), null, false, false,
                        new TableReference(PROJECT, DATASET, "created_events$20261006"), "WRITE_TRUNCATE",
                        "CREATE_IF_NEEDED")));

        assertTrue(error.getMessage().contains("requires timePartitioning or rangePartitioning"));
        assertThrows(GcpException.class, () -> service.getTable(PROJECT, DATASET, "created_events"));
    }

    @Test
    void deletePartitionDecoratorRemovesOnlyThatPartition() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table table = newTable(DATASET, "events");
        table.getExtra().put("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, table);
        service.load(PROJECT, null, null,
                loadConfig("events$20261014", "schema", PEOPLE_SCHEMA, "writeDisposition", "WRITE_TRUNCATE"),
                ndjson("{\"name\": \"old\", \"age\": 1}"));
        service.load(PROJECT, null, null,
                loadConfig("events$20261015", "schema", PEOPLE_SCHEMA, "writeDisposition", "WRITE_TRUNCATE"),
                ndjson("{\"name\": \"new\", \"age\": 2}"));

        service.deleteTable(PROJECT, DATASET, "events$20261014");

        assertNotNull(service.getTable(PROJECT, DATASET, "events"));
        assertTrue(service.listTableData(PROJECT, DATASET, "events$20261014").rows().isEmpty());
        assertEquals(1, service.listTableData(PROJECT, DATASET, "events$20261015").rows().size());
    }

    @Test
    void insertAllSupportsOnlyRecentDailyIngestionPartitionDecorators() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table table = newTable(DATASET, "events");
        table.getExtra().put("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, table);
        String today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);

        assertTrue(service.insertAll(PROJECT, DATASET, "events$" + today,
                List.of(new BigQueryService.InsertRow(0, Map.of("name", "today", "age", 1))),
                false, false).isEmpty());
        assertEquals(1, service.listTableData(PROJECT, DATASET, "events$" + today).rows().size());

        String tooOld = LocalDate.now(ZoneOffset.UTC).minusDays(32).format(DateTimeFormatter.BASIC_ISO_DATE);
        assertThrows(GcpException.class, () -> service.insertAll(PROJECT, DATASET, "events$" + tooOld,
                List.of(new BigQueryService.InsertRow(0, Map.of("name", "old", "age", 2))), false, false));

        Table hourly = newTable(DATASET, "hourly");
        hourly.getExtra().put("timePartitioning", Map.of("type", "HOUR"));
        service.createTable(PROJECT, DATASET, hourly);
        assertThrows(GcpException.class, () -> service.insertAll(PROJECT, DATASET, "hourly$" + today + "12",
                List.of(new BigQueryService.InsertRow(0, Map.of("name", "bad", "age", 3))), false, false));
    }

    @Test
    void ordinaryIngestionTimeWritesReceiveCurrentPartitionPseudocolumns() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table table = newTable(DATASET, "events");
        table.getExtra().put("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, table);

        service.insertAll(PROJECT, DATASET, "events",
                List.of(new BigQueryService.InsertRow(0, Map.of("name", "now", "age", 1))), false, false);

        Map<String, Object> staged = service.stagingRows(PROJECT, DATASET, "events").getFirst();
        assertEquals(LocalDate.now(ZoneOffset.UTC).toString(), staged.get(BigQueryService.PARTITION_DATE));
        assertNotNull(staged.get(BigQueryService.PARTITION_TIME));
    }

    @Test
    void partitionsInformationSchemaReportsStoredDecoratedPartitions() {
        seedTwoRows();
        Table destination = newTable(DATASET, "events");
        destination.setExtra("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, destination);
        service.query(PROJECT, "US", null, new BigQueryService.QueryOptions(
                "SELECT * FROM ds1.t1", null, List.of(), null, false, false,
                new TableReference(PROJECT, DATASET, "events$20261006"), "WRITE_TRUNCATE", null));
        service.query(PROJECT, "US", null, new BigQueryService.QueryOptions(
                "SELECT * FROM ds1.t1 WHERE name = 'alice'", null, List.of(), null, false, false,
                new TableReference(PROJECT, DATASET, "events$20261007"), "WRITE_TRUNCATE", null));

        List<Map<String, Object>> partitions = service.informationSchemaRows(PROJECT, "PARTITIONS", DATASET, null);

        assertEquals(2, partitions.size());
        assertEquals(List.of("20261006", "20261007"),
                partitions.stream().map(row -> row.get("partition_id")).sorted().toList());
        Map<String, Object> first = partitions.stream()
                .filter(row -> row.get("partition_id").equals("20261006")).findFirst().orElseThrow();
        assertEquals(PROJECT, first.get("table_catalog"));
        assertEquals(DATASET, first.get("table_schema"));
        assertEquals("events", first.get("table_name"));
        assertEquals(2L, first.get("total_rows"));
        assertTrue((long) first.get("total_logical_bytes") > 0L);
        assertEquals(first.get("total_logical_bytes"), first.get("total_billable_bytes"));
        assertEquals("ACTIVE", first.get("storage_tier"));
        assertNotNull(first.get("last_modified_time"));
    }

    @Test
    void partitionsInformationSchemaDerivesFieldPartitionsIncludingNull() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table events = newTable(DATASET, "events");
        events.setSchema(new TableSchema(List.of(
                field("name", "STRING", null), field("occurred_at", "TIMESTAMP", null))));
        events.setExtra("timePartitioning", Map.of("type", "HOUR", "field", "occurred_at"));
        service.createTable(PROJECT, DATASET, events);
        service.insertAll(PROJECT, DATASET, "events", List.of(
                new BigQueryService.InsertRow(0, Map.of(
                        "name", "one", "occurred_at", "2026-10-15T08:12:00+02:00")),
                new BigQueryService.InsertRow(1, Map.of("name", "two"))), false, false);

        List<Map<String, Object>> partitions = service.informationSchemaRows(PROJECT, "PARTITIONS", DATASET, null);

        assertEquals(List.of("2026101506", "__NULL__"),
                partitions.stream().map(row -> (String) row.get("partition_id")).sorted().toList());
        assertEquals(List.of(1L, 1L),
                partitions.stream().map(row -> (Long) row.get("total_rows")).sorted().toList());
    }

    @Test
    void partitionFieldsResolveCaseInsensitivelyForDataAndInformationSchema() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table events = newTable(DATASET, "case_events");
        events.setSchema(new TableSchema(List.of(field("event_date", "DATE", null))));
        events.setExtra("timePartitioning", Map.of("type", "DAY", "field", "EVENT_DATE"));
        service.createTable(PROJECT, DATASET, events);
        service.appendRows(PROJECT, DATASET, "case_events", List.of(Map.of("event_date", "2026-10-15")));

        assertEquals(1, service.listTableData(PROJECT, DATASET, "case_events$20261015").rows().size());
        assertEquals("20261015", service.informationSchemaRows(PROJECT, "PARTITIONS", DATASET, null).stream()
                .filter(row -> row.get("table_name").equals("case_events"))
                .findFirst().orElseThrow().get("partition_id"));
    }

    @Test
    void epochSecondTimestampsRouteToFieldPartitions() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table events = newTable(DATASET, "epoch_events");
        events.setSchema(new TableSchema(List.of(field("occurred_at", "TIMESTAMP", null))));
        events.setExtra("timePartitioning", Map.of("type", "DAY", "field", "occurred_at"));
        service.createTable(PROJECT, DATASET, events);
        service.appendRows(PROJECT, DATASET, "epoch_events", List.of(Map.of("occurred_at", "1790812800")));

        assertEquals(1, service.listTableData(PROJECT, DATASET, "epoch_events$20261001").rows().size());
        assertEquals("20261001", service.informationSchemaRows(PROJECT, "PARTITIONS", DATASET, null).stream()
                .filter(row -> row.get("table_name").equals("epoch_events"))
                .findFirst().orElseThrow().get("partition_id"));
    }

    @Test
    void partitionsInformationSchemaReportsIntegerRangePartitions() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table people = newTable(DATASET, "people");
        people.getExtra().put("rangePartitioning", Map.of("field", "age",
                "range", Map.of("start", "0", "end", "20", "interval", "10")));
        service.createTable(PROJECT, DATASET, people);
        service.appendRows(PROJECT, DATASET, "people", List.of(
                Map.of("name", "in", "age", 15), Map.of("name", "out", "age", 25)));

        List<Map<String, Object>> partitions = service.informationSchemaRows(
                PROJECT, "PARTITIONS", DATASET, null);

        assertEquals(List.of("10", "__UNPARTITIONED__"),
                partitions.stream().map(row -> (String) row.get("partition_id")).sorted().toList());
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
    void jobLocationDefaultsToTheReferencedDatasetAndMustMatchIt() {
        seedTwoRows();
        Dataset eu = newDataset("eu_ds");
        eu.setLocation("EU");
        service.createDataset(PROJECT, eu);
        service.createTable(PROJECT, "eu_ds", newTable("eu_ds", TABLE));

        assertEquals("US", service.query(PROJECT, null, null, "SELECT * FROM ds1.t1", null).getLocation());
        StoredJob inferred = service.query(PROJECT, null, null, "SELECT * FROM eu_ds.t1", null);
        assertEquals("EU", inferred.getLocation());
        assertEquals("eu", service.query(PROJECT, "eu", null, "SELECT * FROM eu_ds.t1", null).getLocation());

        GcpException ex = assertThrows(GcpException.class,
                () -> service.query(PROJECT, "US", null, "SELECT * FROM eu_ds.t1", null));
        assertEquals(404, ex.getHttpStatus());
        assertEquals("notFound", ex.getReason());
        assertEquals("Not found: Dataset p1:eu_ds was not found in location US", ex.getMessage());

        GcpException regional = assertThrows(GcpException.class,
                () -> service.query(PROJECT, "us-central1", null, "SELECT * FROM ds1.t1", null));
        assertEquals("Not found: Dataset p1:ds1 was not found in location us-central1", regional.getMessage());

        assertEquals(inferred.getJobId(), service.getJob(PROJECT, inferred.getJobId(), "eu").getJobId());
        GcpException wrong = assertThrows(GcpException.class,
                () -> service.getJob(PROJECT, inferred.getJobId(), "US"));
        assertEquals("Not found: Job p1:US." + inferred.getJobId(), wrong.getMessage());
        assertThrows(GcpException.class, () -> service.deleteJob(PROJECT, inferred.getJobId(), "US"));
        service.deleteJob(PROJECT, inferred.getJobId(), "EU");
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

    // ── Copy jobs ──

    private static Map<String, Object> copyConfig(String source, String destination, Object... extra) {
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("sourceTable", Map.of("datasetId", DATASET, "tableId", source));
        config.put("destinationTable", Map.of("datasetId", DATASET, "tableId", destination));
        for (int i = 0; i < extra.length; i += 2) {
            config.put((String) extra[i], extra[i + 1]);
        }
        return config;
    }

    @Test
    void copyJobHonorsWriteAndCreateDispositions() {
        seedTwoRows();

        StoredJob created = service.copy(PROJECT, null, "copy-create", copyConfig(TABLE, "copied"));
        assertFalse(created.failed(), created.getErrorMessage());
        assertEquals("COPY", created.getJobType());
        assertEquals(2, service.listTableData(PROJECT, DATASET, "copied").rows().size());

        StoredJob occupied = service.copy(PROJECT, null, null, copyConfig(TABLE, "copied"));
        assertEquals("duplicate", occupied.getErrorReason());

        StoredJob appended = service.copy(PROJECT, null, null,
                copyConfig(TABLE, "copied", "writeDisposition", "WRITE_APPEND"));
        assertFalse(appended.failed(), appended.getErrorMessage());
        assertEquals(4, service.listTableData(PROJECT, DATASET, "copied").rows().size());

        StoredJob truncated = service.copy(PROJECT, null, null,
                copyConfig(TABLE, "copied", "writeDisposition", "WRITE_TRUNCATE"));
        assertFalse(truncated.failed(), truncated.getErrorMessage());
        assertEquals(2, service.listTableData(PROJECT, DATASET, "copied").rows().size());

        StoredJob missing = service.copy(PROJECT, null, null,
                copyConfig(TABLE, "missing", "createDisposition", "CREATE_NEVER"));
        assertEquals("notFound", missing.getErrorReason());
    }

    @Test
    void copyJobsResolveAndValidateTheirProcessingLocation() {
        Dataset euSource = newDataset("eu_source");
        euSource.setLocation("EU");
        service.createDataset(PROJECT, euSource);
        service.createTable(PROJECT, "eu_source", newTable("eu_source", "source"));
        service.appendRows(PROJECT, "eu_source", "source", List.of(Map.of("name", "row", "age", 1)));

        Dataset euDestination = newDataset("eu_destination");
        euDestination.setLocation("EU");
        service.createDataset(PROJECT, euDestination);
        service.createDataset(PROJECT, newDataset("us_destination"));

        Map<String, Object> sameRegion = Map.of(
                "sourceTable", Map.of("datasetId", "eu_source", "tableId", "source"),
                "destinationTable", Map.of("datasetId", "eu_destination", "tableId", "copied"));
        StoredJob inferred = service.copy(PROJECT, null, null, sameRegion);
        assertFalse(inferred.failed(), inferred.getErrorMessage());
        assertEquals("EU", inferred.getLocation());

        Map<String, Object> wrongExplicitDestination = Map.of(
                "sourceTable", Map.of("datasetId", "eu_source", "tableId", "source"),
                "destinationTable", Map.of("datasetId", "eu_destination", "tableId", "wrong_location"));
        StoredJob wrongExplicit = service.copy(PROJECT, "US", null, wrongExplicitDestination);
        assertEquals("notFound", wrongExplicit.getErrorReason());
        assertEquals("US", wrongExplicit.getLocation());

        Map<String, Object> crossRegion = Map.of(
                "sourceTable", Map.of("datasetId", "eu_source", "tableId", "source"),
                "destinationTable", Map.of("datasetId", "us_destination", "tableId", "copied"));
        StoredJob previewCopy = service.copy(PROJECT, null, null, crossRegion);
        assertFalse(previewCopy.failed(), previewCopy.getErrorMessage());
        assertEquals("EU", previewCopy.getLocation());
        Table copied = service.getTable(PROJECT, "us_destination", "copied");
        assertEquals("US", copied.getLocation());
        assertTrue(copied.getSelfLink().endsWith("/datasets/us_destination/tables/copied"));

        service.createTable(PROJECT, "us_destination", newTable("us_destination", "already_exists"));
        Map<String, Object> crossRegionWriteEmpty = Map.of(
                "sourceTable", Map.of("datasetId", "eu_source", "tableId", "source"),
                "destinationTable", Map.of("datasetId", "us_destination", "tableId", "already_exists"));
        StoredJob occupied = service.copy(PROJECT, null, null, crossRegionWriteEmpty);
        assertEquals("duplicate", occupied.getErrorReason());

        Map<String, Object> crossRegionAppend = new java.util.LinkedHashMap<>(crossRegion);
        crossRegionAppend.put("writeDisposition", "WRITE_APPEND");
        StoredJob unsupportedAppend = service.copy(PROJECT, null, null, crossRegionAppend);
        assertEquals("invalid", unsupportedAppend.getErrorReason());

        GcpException ddl = assertThrows(GcpException.class, () -> service.query(PROJECT, null, null,
                "CREATE TABLE us_destination.ddl_copy COPY eu_source.source", null));
        assertEquals("notFound", ddl.getReason());
    }

    @Test
    void copyJobCopiesOnlyDecoratedPartition() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table source = newTable(DATASET, "source");
        source.getExtra().put("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, source);
        Table destination = newTable(DATASET, "destination");
        destination.getExtra().put("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, destination);

        service.load(PROJECT, null, null,
                loadConfig("source$20261014", "schema", PEOPLE_SCHEMA, "writeDisposition", "WRITE_TRUNCATE"),
                ndjson("{\"name\": \"old\", \"age\": 1}"));
        service.load(PROJECT, null, null,
                loadConfig("source$20261015", "schema", PEOPLE_SCHEMA, "writeDisposition", "WRITE_TRUNCATE"),
                ndjson("{\"name\": \"new\", \"age\": 2}"));

        StoredJob first = service.copy(PROJECT, "US", null,
                copyConfig("source$20261014", "destination$20261014", "writeDisposition", "WRITE_TRUNCATE"));
        assertFalse(first.failed(), first.getErrorMessage());
        assertEquals("US", first.getLocation());
        assertEquals(1, service.listTableData(PROJECT, DATASET, "destination$20261014").rows().size());
        assertTrue(service.listTableData(PROJECT, DATASET, "destination$20261015").rows().isEmpty());

        StoredJob second = service.copy(PROJECT, null, null,
                copyConfig("source$20261015", "destination$20261015", "writeDisposition", "WRITE_APPEND"));
        assertFalse(second.failed(), second.getErrorMessage());
        assertEquals(2, service.listTableData(PROJECT, DATASET, "destination").rows().size());
    }

    @Test
    void failedDecoratedCopyDoesNotPublishANewDestination() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table source = newTable(DATASET, "partitioned_source");
        source.getExtra().put("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, source);

        StoredJob failed = service.copy(PROJECT, null, null,
                copyConfig("partitioned_source", "new_destination$20261015"));

        assertEquals("invalid", failed.getErrorReason());
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getTable(PROJECT, DATASET, "new_destination")).getGcpStatus());
    }

    @Test
    void partitionedAppendCopyPreservesDestinationStreamProgress() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table source = newTable(DATASET, "copy_source");
        source.getExtra().put("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, source);
        service.appendRows(PROJECT, DATASET, "copy_source", List.of(Map.of("name", "source", "age", 1)));
        Table destination = newTable(DATASET, "copy_destination");
        destination.getExtra().put("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, destination);
        String stream = "projects/" + PROJECT + "/datasets/" + DATASET
                + "/tables/copy_destination/streams/s1";
        service.appendRows(PROJECT, DATASET, "copy_destination", List.of(Map.of("name", "existing", "age", 2)),
                Map.of(stream, 1L));

        StoredJob copied = service.copy(PROJECT, null, null,
                copyConfig("copy_source", "copy_destination", "writeDisposition", "WRITE_APPEND"));

        assertFalse(copied.failed(), copied.getErrorMessage());
        assertEquals(OptionalLong.of(1L),
                service.streamRowsApplied(PROJECT, DATASET, "copy_destination", stream));
    }

    @Test
    void copyJobSupportsEveryTimePartitionDecoratorGranularity() {
        service.createDataset(PROJECT, newDataset(DATASET));
        List<List<String>> partitions = List.of(
                List.of("HOUR", "2026101513"),
                List.of("DAY", "20261015"),
                List.of("MONTH", "202610"),
                List.of("YEAR", "2026"));

        for (List<String> partition : partitions) {
            String type = partition.get(0);
            String id = partition.get(1);
            String suffix = type.toLowerCase();
            Table source = newTable(DATASET, "source_" + suffix);
            source.getExtra().put("timePartitioning", Map.of("type", type));
            service.createTable(PROJECT, DATASET, source);
            service.load(PROJECT, null, null,
                    loadConfig("source_" + suffix + "$" + id, "schema", PEOPLE_SCHEMA,
                            "writeDisposition", "WRITE_TRUNCATE"),
                    ndjson("{\"name\": \"" + suffix + "\", \"age\": 1}"));

            StoredJob copied = service.copy(PROJECT, null, null,
                    copyConfig("source_" + suffix + "$" + id, "destination_" + suffix + "$" + id,
                            "writeDisposition", "WRITE_TRUNCATE"));

            assertFalse(copied.failed(), copied.getErrorMessage());
            Table destination = service.getTable(PROJECT, DATASET, "destination_" + suffix);
            assertEquals(type, ((Map<?, ?>) destination.getExtra().get("timePartitioning")).get("type"));
            assertEquals(1, service.listTableData(
                    PROJECT, DATASET, "destination_" + suffix + "$" + id).rows().size());
        }

        Map<String, Object> hourly = service.stagingRows(PROJECT, DATASET, "source_hour").getFirst();
        assertFalse(hourly.containsKey(BigQueryService.PARTITION_DATE));
        assertEquals("2026-10-15T13:00:00Z", hourly.get(BigQueryService.PARTITION_TIME));
        StoredJob wrongGranularity = service.copy(PROJECT, null, null,
                copyConfig("source_hour$20261015", "bad"));
        assertEquals("invalid", wrongGranularity.getErrorReason());
        StoredJob invalidCalendarDate = service.copy(PROJECT, null, null,
                copyConfig("source_day$20260230", "bad_date"));
        assertEquals("invalid", invalidCalendarDate.getErrorReason());
    }

    @Test
    void copyJobAllowsOnlyContainingCoarserIngestionTimePartitions() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table source = newTable(DATASET, "daily_source");
        source.getExtra().put("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, source);
        service.load(PROJECT, null, null,
                loadConfig("daily_source$20261015", "schema", PEOPLE_SCHEMA,
                        "writeDisposition", "WRITE_TRUNCATE"),
                ndjson("{\"name\": \"row\", \"age\": 1}"));
        Table monthly = newTable(DATASET, "monthly_destination");
        monthly.getExtra().put("timePartitioning", Map.of("type", "MONTH"));
        service.createTable(PROJECT, DATASET, monthly);

        StoredJob valid = service.copy(PROJECT, null, null,
                copyConfig("daily_source$20261015", "monthly_destination$202610",
                        "writeDisposition", "WRITE_TRUNCATE"));
        StoredJob wrongMonth = service.copy(PROJECT, null, null,
                copyConfig("daily_source$20261015", "monthly_destination$202611",
                        "writeDisposition", "WRITE_TRUNCATE"));

        assertFalse(valid.failed(), valid.getErrorMessage());
        assertEquals(1, service.listTableData(PROJECT, DATASET, "monthly_destination$202610").rows().size());
        assertEquals("invalid", wrongMonth.getErrorReason());
        assertTrue(wrongMonth.getErrorMessage().contains("contain"), wrongMonth.getErrorMessage());
    }

    @Test
    void copyJobSupportsIntegerRangePartitionDecorators() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Map<String, Object> rangePartitioning = Map.of(
                "field", "age",
                "range", Map.of("start", "-20", "end", "20", "interval", "10"));
        Table source = newTable(DATASET, "range_source");
        source.getExtra().put("rangePartitioning", rangePartitioning);
        service.createTable(PROJECT, DATASET, source);
        Table destination = newTable(DATASET, "range_destination");
        destination.getExtra().put("rangePartitioning", rangePartitioning);
        service.createTable(PROJECT, DATASET, destination);
        service.appendRows(PROJECT, DATASET, "range_source", List.of(
                Map.of("name", "negative", "age", -15),
                Map.of("name", "positive", "age", 5)));

        StoredJob copied = service.copy(PROJECT, null, null,
                copyConfig("range_source$-20", "range_destination$-20", "writeDisposition", "WRITE_TRUNCATE"));

        assertFalse(copied.failed(), copied.getErrorMessage());
        assertEquals(1, service.listTableData(PROJECT, DATASET, "range_destination$-20").rows().size());
        assertEquals("negative", service.listTableData(PROJECT, DATASET, "range_destination$-20")
                .rows().getFirst().getF().getFirst().getV());
        StoredJob invalidBoundary = service.copy(PROJECT, null, null,
                copyConfig("range_source$-15", "invalid_range"));
        assertEquals("invalid", invalidBoundary.getErrorReason());
    }

    @Test
    void copyJobSupportsMultipleSourcesAndRejectsMismatchedSchemas() {
        seedTwoRows();
        service.createTable(PROJECT, DATASET, newTable(DATASET, "second"));
        service.appendRows(PROJECT, DATASET, "second", List.of(Map.of("name", "cy", "age", 41)));
        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("sourceTables", List.of(
                Map.of("datasetId", DATASET, "tableId", TABLE),
                Map.of("datasetId", DATASET, "tableId", "second")));
        config.put("destinationTable", Map.of("datasetId", DATASET, "tableId", "combined"));

        StoredJob combined = service.copy(PROJECT, null, null, config);
        assertFalse(combined.failed(), combined.getErrorMessage());
        assertEquals(3, service.listTableData(PROJECT, DATASET, "combined").rows().size());

        Table incompatible = newTable(DATASET, "incompatible");
        incompatible.setSchema(new TableSchema(List.of(field("other", "STRING", null))));
        service.createTable(PROJECT, DATASET, incompatible);
        config.put("sourceTables", List.of(
                Map.of("datasetId", DATASET, "tableId", TABLE),
                Map.of("datasetId", DATASET, "tableId", "incompatible")));
        config.put("destinationTable", Map.of("datasetId", DATASET, "tableId", "bad"));
        StoredJob bad = service.copy(PROJECT, null, null, config);
        assertEquals("invalid", bad.getErrorReason());
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
    void loadCreatesDecoratedDestinationOnlyWithExplicitPartitioning() {
        service.createDataset(PROJECT, newDataset(DATASET));

        StoredJob missing = service.load(PROJECT, null, null,
                loadConfig("events$20261015", "schema", PEOPLE_SCHEMA),
                ndjson("{\"name\": \"bad\", \"age\": 1}"));
        StoredJob created = service.load(PROJECT, null, null,
                loadConfig("events$20261015", "schema", PEOPLE_SCHEMA,
                        "timePartitioning", Map.of("type", "DAY")),
                ndjson("{\"name\": \"good\", \"age\": 2}"));

        assertEquals("invalid", missing.getErrorReason());
        assertFalse(created.failed(), created.getErrorMessage());
        assertEquals("DAY", ((Map<?, ?>) service.getTable(PROJECT, DATASET, "events")
                .getExtra().get("timePartitioning")).get("type"));
        assertEquals(1, service.listTableData(PROJECT, DATASET, "events$20261015").rows().size());
    }

    @Test
    void failedDecoratedLoadDoesNotPublishANewTable() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Map<String, Object> schema = Map.of("fields", List.of(
                Map.of("name", "event_date", "type", "DATE")));

        StoredJob failed = service.load(PROJECT, null, null,
                loadConfig("events$20261001", "schema", schema,
                        "timePartitioning", Map.of("type", "DAY", "field", "event_date")),
                ndjson("{\"event_date\": \"2026-10-02\"}"));

        assertEquals("invalid", failed.getErrorReason());
        assertThrows(GcpException.class, () -> service.getTable(PROJECT, DATASET, "events"));
    }

    @Test
    void loadAppliesPartitioningToAnUndecoratedNewDestination() {
        service.createDataset(PROJECT, newDataset(DATASET));

        StoredJob created = service.load(PROJECT, null, null,
                loadConfig("events", "schema", PEOPLE_SCHEMA,
                        "rangePartitioning", Map.of("field", "age",
                                "range", Map.of("start", "0", "end", "100", "interval", "10"))),
                ndjson("{\"name\": \"good\", \"age\": 22}"));

        assertFalse(created.failed(), created.getErrorMessage());
        assertEquals("age", ((Map<?, ?>) service.getTable(PROJECT, DATASET, "events")
                .getExtra().get("rangePartitioning")).get("field"));
        assertEquals(1, service.listTableData(PROJECT, DATASET, "events$20").rows().size());
    }

    @Test
    void loadAssignsInitialIngestionTimeRowsToTheCurrentPartition() {
        service.createDataset(PROJECT, newDataset(DATASET));

        StoredJob created = service.load(PROJECT, null, null,
                loadConfig("events", "schema", PEOPLE_SCHEMA,
                        "timePartitioning", Map.of("type", "DAY")),
                ndjson("{\"name\": \"current\", \"age\": 4}"));

        assertFalse(created.failed(), created.getErrorMessage());
        String today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        assertEquals(1, service.listTableData(PROJECT, DATASET, "events$" + today).rows().size());
        Map<String, Object> staged = service.stagingRows(PROJECT, DATASET, "events").getFirst();
        assertEquals(LocalDate.now(ZoneOffset.UTC).toString(), staged.get(BigQueryService.PARTITION_DATE));
        assertNotNull(staged.get(BigQueryService.PARTITION_TIME));
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

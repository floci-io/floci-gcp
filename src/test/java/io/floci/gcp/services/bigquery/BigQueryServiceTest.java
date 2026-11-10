package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.bigquery.model.Dataset;
import io.floci.gcp.services.bigquery.model.DatasetAccessEntry;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BigQueryServiceTest {

    private static final String PROJECT = "p1";
    private static final String DATASET = "ds1";
    private static final String TABLE = "t1";

    private BigQueryService service;
    private InMemoryStorage<String, StoredTableData> dataStore;

    @BeforeEach
    void setUp() {
        dataStore = new InMemoryStorage<>();
        service = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(), dataStore,
                new InMemoryStorage<>());
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
    void beginBlocksExecuteNestedStatementsInOrderAndReturnTheFinalQuery() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                BEGIN
                  SELECT * FROM ds1.t1;
                  BEGIN
                    SELECT * FROM ds1.t1;
                  END;
                END;
                SELECT * FROM ds1.t1;
                """, null);

        assertEquals("SCRIPT", job.getStatementType());
        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(2, result.rows().size());
        assertEquals("alice", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void scalarVariablesCanDriveAWhileLoopAndBindIntoTheFinalQuery() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE target_age INT64 DEFAULT 24;
                WHILE target_age < 25 DO
                  SET target_age = target_age + 1;
                END WHILE;
                SELECT * FROM ds1.t1 WHERE age = target_age;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void whileBreakExitsTheCurrentLoop() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE target_age INT64 DEFAULT 24;
                WHILE target_age < 30 DO
                  SET target_age = target_age + 1;
                  IF target_age = 25 THEN
                    BREAK;
                  END IF;
                END WHILE;
                SELECT * FROM ds1.t1 WHERE age = target_age;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void continueSkipsTheRemainderOfTheCurrentIteration() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE target_age INT64 DEFAULT 24;
                WHILE target_age < 25 DO
                  SET target_age = target_age + 1;
                  IF target_age = 25 THEN CONTINUE; END IF;
                  SET target_age = 99;
                END WHILE;
                SELECT * FROM ds1.t1 WHERE age = target_age;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void undeclaredDefaultInitializesVariableToNull() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE target_age INT64;
                IF target_age IS NULL THEN
                  SET target_age = 25;
                END IF;
                SELECT * FROM ds1.t1 WHERE age = target_age;
                """, null);

        assertEquals(1, service.queryResults(PROJECT, job).rows().size());
    }

    @Test
    void variableDefaultCanReferenceAnEarlierDeclaration() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE base_age INT64 DEFAULT 24;
                DECLARE target_age INT64 DEFAULT base_age + 1;
                SELECT * FROM ds1.t1 WHERE age = target_age;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void booleanVariablesCanControlLoopsWithoutSqlEngineScalarQueries() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE should_stop BOOL DEFAULT FALSE;
                WHILE NOT should_stop DO
                  SET should_stop = TRUE;
                END WHILE;
                SELECT * FROM ds1.t1 WHERE age = 25;
                """, null);

        assertEquals(1, service.queryResults(PROJECT, job).rows().size());
    }

    @Test
    void repeatExecutesAtLeastOnceAndStopsWhenUntilConditionIsTrue() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE target_age INT64 DEFAULT 24;
                REPEAT
                  SET target_age = target_age + 1;
                UNTIL target_age >= 25
                END REPEAT;
                SELECT * FROM ds1.t1 WHERE age = target_age;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void loopBreakExitsAnUnconditionalLoop() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE target_age INT64 DEFAULT 24;
                LOOP
                  SET target_age = target_age + 1;
                  IF target_age = 25 THEN BREAK; END IF;
                END LOOP;
                SELECT * FROM ds1.t1 WHERE age = target_age;
                """, null);

        assertEquals(1, service.queryResults(PROJECT, job).rows().size());
    }

    @Test
    void unconditionalLoopsAreStoppedAtTheDocumentedEmulatorLimit() {
        GcpException error = assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null, """
                DECLARE counter INT64 DEFAULT 0;
                LOOP
                  SET counter = counter + 1;
                END LOOP;
                """, null));

        assertTrue(error.getMessage().contains("iteration limit of 1000"));
    }

    @Test
    void labeledBreakCanExitAnOuterLoop() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE target_age INT64 DEFAULT 24;
                outer_loop: WHILE target_age < 30 DO
                  inner_loop: WHILE target_age < 30 DO
                    SET target_age = target_age + 1;
                    IF target_age = 25 THEN BREAK outer_loop; END IF;
                  END WHILE inner_loop;
                END WHILE outer_loop;
                SELECT * FROM ds1.t1 WHERE age = target_age;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void labeledBreakCanExitABeginBlock() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE target_age INT64 DEFAULT 25;
                labeled_block: BEGIN
                  BREAK labeled_block;
                  SET target_age = 24;
                END labeled_block;
                SELECT * FROM ds1.t1 WHERE age = target_age;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void labeledBreakNamesAreCaseInsensitiveAndCanBeReusedInSeparateBlocks() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE target_age INT64 DEFAULT 25;
                Shared_Label: BEGIN
                  BREAK shared_label;
                  SET target_age = 24;
                END SHARED_LABEL;
                shared_label: BEGIN
                  BREAK SHARED_LABEL;
                  SET target_age = 24;
                END shared_label;
                SELECT * FROM ds1.t1 WHERE age = target_age;
                """, null);

        assertEquals(1, service.queryResults(PROJECT, job).rows().size());
    }

    @Test
    void forLoopVariableIsScopedToTheLoopBody() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                FOR row_record IN (SELECT age FROM ds1.t1) DO
                  SELECT * FROM ds1.t1 WHERE age = row_record.age;
                END FOR;
                BEGIN
                  DECLARE row_record INT64 DEFAULT 25;
                  SELECT * FROM ds1.t1 WHERE age = row_record;
                END;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void qualifiedRecordFieldsAndColumnsAreNotShadowedByScriptVariables() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE age INT64 DEFAULT 999;
                FOR row_record IN (SELECT age FROM ds1.t1) DO
                  SELECT * FROM ds1.t1 WHERE age = row_record.age;
                END FOR;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void functionNamesAreNotReplacedBySameNamedScriptVariables() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE count INT64 DEFAULT 999;
                SELECT COUNT(*) FROM ds1.t1;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals("2", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void forInEvaluatesItsTableExpressionOnceAndExposesEachRowAsAStruct() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                FOR row_record IN (SELECT age FROM ds1.t1) DO
                  SELECT * FROM ds1.t1 WHERE age = row_record.age;
                END FOR;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void searchedCaseRunsOnlyTheFirstMatchingBranch() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE target_age INT64 DEFAULT 25;
                CASE
                  WHEN target_age = 24 THEN SELECT * FROM ds1.t1 WHERE age = 24;
                  WHEN target_age = 25 THEN SELECT * FROM ds1.t1 WHERE age = target_age;
                  ELSE SELECT * FROM ds1.t1 WHERE age = 30;
                END CASE;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void scriptDryRunStopsAtVariableStatementsInsteadOfTreatingThemAsSql() {
        StoredJob job = service.query(PROJECT, "US", null, new BigQueryService.QueryOptions(
                "DECLARE target_age INT64 DEFAULT 25; SELECT * FROM ds1.t1 WHERE age = target_age;",
                null, List.of(), null, true, false));

        assertTrue(job.isDryRun());
    }

    @Test
    void variableDefaultCanUseAnEngineEvaluatedExpression() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        TableFieldSchema field = field("_floci_script_value", "INTEGER", null);
        when(engine.execute(any(), any())).thenReturn(new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field)), List.of(Map.of("_floci_script_value", 2L)), "SELECT", 0));
        BigQueryService expressionService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        StoredJob job = expressionService.query(PROJECT, "US", null, """
                DECLARE target_age INT64 DEFAULT GREATEST(1, 2);
                SELECT * FROM ds1.t1 WHERE age = target_age;
                """, null);

        assertEquals("DONE", job.getState());
        verify(engine, times(2)).execute(any(), any());
    }

    @Test
    void loopControlTargetsAreValidatedBeforeScriptExecution() {
        GcpException outsideLoop = assertThrows(GcpException.class,
                () -> service.query(PROJECT, "US", null, "BREAK; SELECT * FROM ds1.missing;", null));
        assertEquals("invalidQuery", outsideLoop.getReason());

        GcpException unknownLabel = assertThrows(GcpException.class, () -> service.query(
                PROJECT, "US", null, "WHILE TRUE DO BREAK unknown_label; END WHILE;", null));
        assertEquals("invalidQuery", unknownLabel.getReason());

        GcpException continueBlock = assertThrows(GcpException.class, () -> service.query(
                PROJECT, "US", null, "block_label: BEGIN CONTINUE block_label; END block_label;", null));
        assertEquals("invalidQuery", continueBlock.getReason());
    }

    @Test
    void declarationsInsideConditionalBranchesAreRejected() {
        GcpException error = assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null, """
                IF TRUE THEN
                  DECLARE invalid_value INT64 DEFAULT 1;
                END IF;
                SELECT * FROM ds1.missing;
                """, null));

        assertEquals("invalidQuery", error.getReason());
    }

    @Test
    void simpleCaseRunsTheFirstMatchingValueBranch() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE target_age INT64 DEFAULT 25;
                CASE target_age
                  WHEN 24 THEN SELECT * FROM ds1.t1 WHERE age = 24;
                  WHEN 25 THEN SELECT * FROM ds1.t1 WHERE age = target_age;
                  ELSE SELECT * FROM ds1.t1 WHERE age = 30;
                END CASE;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void declareSupportsMultipleNamesAndDefaultTypeInference() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE first_age, second_age INT64 DEFAULT 24;
                DECLARE target_age DEFAULT 25;
                SET (first_age, second_age) = (target_age, first_age);
                SELECT * FROM ds1.t1 WHERE age = first_age;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void executeImmediateBindsNamedAndPositionalValuesIntoVariables() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        BigQuerySqlEngine.Result six = new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("result", "INTEGER", null))),
                List.of(Map.of("result", 6L)), "SELECT", 0);
        BigQuerySqlEngine.Result twelve = new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("result", "INTEGER", null))),
                List.of(Map.of("result", 12L)), "SELECT", 0);
        when(engine.execute(any(), any())).thenReturn(six, twelve, twelve);
        BigQueryService dynamicService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        StoredJob job = dynamicService.query(PROJECT, "US", null, """
                DECLARE factor INT64 DEFAULT 3;
                DECLARE answer INT64;
                EXECUTE IMMEDIATE "SELECT @value * 2 AS result" INTO answer USING factor AS value;
                EXECUTE IMMEDIATE "SELECT ? * 4 AS result" INTO answer USING factor;
                SELECT answer AS result;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine, times(3)).execute(request.capture(), any());
        assertEquals("SCRIPT", job.getStatementType());
        assertEquals("NAMED", request.getAllValues().getFirst().parameterMode());
        assertEquals("value", request.getAllValues().getFirst().queryParameters().getFirst().get("name"));
        assertTrue(request.getAllValues().getFirst().sql().contains("@value"));
        assertEquals("POSITIONAL", request.getAllValues().get(1).parameterMode());
        assertTrue(request.getAllValues().get(1).sql().contains("?"));
        assertEquals("SELECT 12 AS result", request.getAllValues().getLast().sql());
    }

    @Test
    void executeImmediateBindsStructUsingParameters() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        TableFieldSchema structField = field("_floci_script_value", "RECORD", null);
        structField.setFields(List.of(field("id", "INTEGER", null), field("label", "STRING", null)));
        BigQuerySqlEngine.Result structValue = new BigQuerySqlEngine.Result(new TableSchema(List.of(structField)),
                List.of(Map.of("_floci_script_value", Map.of("id", 2L, "label", "second"))), "SELECT", 0);
        BigQuerySqlEngine.Result queryResult = new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("id", "INTEGER", null))), List.of(Map.of("id", 2L)), "SELECT", 0);
        when(engine.execute(any(), any())).thenReturn(structValue, queryResult);
        BigQueryService dynamicService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        dynamicService.query(PROJECT, "US", null, """
                DECLARE row_value STRUCT<id INT64, label STRING>
                  DEFAULT STRUCT(2 AS id, 'second' AS label);
                EXECUTE IMMEDIATE "SELECT @row.id AS id" USING row_value AS row;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine, times(2)).execute(request.capture(), any());
        Map<String, Object> parameter = request.getAllValues().getLast().queryParameters().getFirst();
        assertEquals("STRUCT", ((Map<?, ?>) parameter.get("parameterType")).get("type"));
        Map<?, ?> structValues = (Map<?, ?>) ((Map<?, ?>) parameter.get("parameterValue")).get("structValues");
        assertEquals("2", ((Map<?, ?>) structValues.get("id")).get("value"));
        assertEquals("second", ((Map<?, ?>) structValues.get("label")).get("value"));
    }

    @Test
    void executeImmediateEvaluatesItsSqlTextExpression() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        BigQuerySqlEngine.Result sqlText = new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("_floci_script_value", "STRING", null))),
                List.of(Map.of("_floci_script_value", "SELECT 9 AS result")), "SELECT", 0);
        BigQuerySqlEngine.Result queryResult = new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("result", "INTEGER", null))),
                List.of(Map.of("result", 9L)), "SELECT", 0);
        when(engine.execute(any(), any())).thenReturn(sqlText, queryResult);
        BigQueryService dynamicService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        dynamicService.query(PROJECT, "US", null, """
                DECLARE statement_text STRING DEFAULT "SELECT 9 AS result";
                EXECUTE IMMEDIATE CONCAT(statement_text);
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine, times(2)).execute(request.capture(), any());
        assertEquals("SELECT 9 AS result", request.getAllValues().getLast().sql());
    }

    @Test
    void executeImmediateCannotBeNested() {
        BigQueryService dynamicService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), mock(BigQuerySqlEngine.class));

        GcpException error = assertThrows(GcpException.class, () -> dynamicService.query(PROJECT, "US", null,
                "EXECUTE IMMEDIATE \"EXECUTE IMMEDIATE 'SELECT 1'\";", null));

        assertEquals("invalidQuery", error.getReason());
    }

    @Test
    void executeImmediateCanRunABeginBlock() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        when(engine.execute(any(), any())).thenReturn(new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("result", "INTEGER", null))),
                List.of(Map.of("result", 4L)), "SELECT", 0));
        BigQueryService dynamicService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        StoredJob job = dynamicService.query(PROJECT, "US", null, """
                EXECUTE IMMEDIATE "BEGIN DECLARE local_value INT64 DEFAULT 4; SELECT local_value AS result; END";
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine).execute(request.capture(), any());
        assertEquals("SCRIPT", job.getStatementType());
        assertEquals("SELECT 4 AS result", request.getValue().sql());
    }

    @Test
    void executeImmediateCanBeTheOnlyQueryStatement() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        when(engine.execute(any(), any())).thenReturn(new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("value", "INTEGER", null))),
                List.of(Map.of("value", 7L)), "SELECT", 0));
        BigQueryService dynamicService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        StoredJob job = dynamicService.query(PROJECT, "US", null,
                "EXECUTE IMMEDIATE \"SELECT 7 AS value\";", null);

        assertEquals("SCRIPT", job.getStatementType());
        assertEquals("7", dynamicService.queryResults(PROJECT, job).rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void dateAndTimestampVariablesCanBeDeclaredAndAssigned() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        when(engine.execute(any(), any())).thenReturn(new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("run_date", "DATE", null),
                        field("run_at", "TIMESTAMP", null))),
                List.of(Map.of("run_date", "2025-01-02", "run_at", "2025-01-02T12:00:00Z")), "SELECT", 0));
        BigQueryService variableService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        StoredJob job = variableService.query(PROJECT, "US", null, """
                DECLARE run_date DATE DEFAULT DATE '2025-01-01';
                DECLARE run_at TIMESTAMP DEFAULT TIMESTAMP '2025-01-01 12:00:00+00';
                SET run_date = DATE '2025-01-02';
                SET run_at = TIMESTAMP '2025-01-02 12:00:00+00';
                SELECT run_date, run_at;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine).execute(request.capture(), any());
        assertEquals("SCRIPT", job.getStatementType());
        assertTrue(request.getValue().sql().contains("DATE '2025-01-02'"), request.getValue().sql());
        assertTrue(request.getValue().sql().contains("TIMESTAMP '2025-01-02T12:00:00Z'"),
                request.getValue().sql());
    }

    @Test
    void arrayVariablesCanBeDeclaredAndAssigned() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        when(engine.execute(any(), any())).thenReturn(new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("run_dates", "DATE", "REPEATED"))),
                List.of(Map.of("run_dates", List.of("2025-01-02", "2025-01-03"))), "SELECT", 0));
        BigQueryService variableService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        StoredJob job = variableService.query(PROJECT, "US", null, """
                DECLARE run_dates ARRAY<DATE> DEFAULT [DATE '2025-01-01'];
                SET run_dates = [DATE '2025-01-02', DATE '2025-01-03'];
                SELECT run_dates;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine).execute(request.capture(), any());
        assertEquals("SCRIPT", job.getStatementType());
        assertTrue(request.getValue().sql().contains(
                "CAST([DATE '2025-01-02', DATE '2025-01-03'] AS ARRAY<DATE>)"), request.getValue().sql());
    }

    @Test
    void structAndArrayOfStructVariablesCanBeDeclaredAssignedAndRead() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        TableFieldSchema structValue = field("_floci_script_value", "RECORD", null);
        structValue.setFields(List.of(field("id", "INTEGER", null), field("label", "STRING", null)));
        TableFieldSchema arrayValue = field("_floci_script_value", "RECORD", "REPEATED");
        arrayValue.setFields(List.of(field("id", "INTEGER", null), field("label", "STRING", null)));
        Map<String, Object> first = Map.of("id", 1L, "label", "first");
        Map<String, Object> second = Map.of("id", 2L, "label", "second");
        Map<String, Object> third = Map.of("id", 3L, "label", "third");
        BigQuerySqlEngine.Result firstStruct = new BigQuerySqlEngine.Result(new TableSchema(List.of(structValue)),
                List.of(Map.of("_floci_script_value", first)), "SELECT", 0);
        BigQuerySqlEngine.Result firstArray = new BigQuerySqlEngine.Result(new TableSchema(List.of(arrayValue)),
                List.of(Map.of("_floci_script_value", List.of(first))), "SELECT", 0);
        BigQuerySqlEngine.Result secondStruct = new BigQuerySqlEngine.Result(new TableSchema(List.of(structValue)),
                List.of(Map.of("_floci_script_value", second)), "SELECT", 0);
        BigQuerySqlEngine.Result secondArray = new BigQuerySqlEngine.Result(new TableSchema(List.of(arrayValue)),
                List.of(Map.of("_floci_script_value", List.of(second, third))), "SELECT", 0);
        BigQuerySqlEngine.Result finalResult = new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("id", "INTEGER", null), field("label", "STRING", null),
                        field("items", "RECORD", "REPEATED"), field("inferred_id", "INTEGER", null),
                        field("inferred_items", "RECORD", "REPEATED"))),
                List.of(Map.of("id", 2L, "label", "second", "items", List.of(second, third),
                        "inferred_id", 1L, "inferred_items", List.of(first))), "SELECT", 0);
        when(engine.execute(any(), any())).thenReturn(
                firstStruct, firstArray, firstStruct, firstArray, secondStruct, secondArray, finalResult);
        BigQueryService variableService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        variableService.query(PROJECT, "US", null, """
                DECLARE item STRUCT<id INT64, label STRING>
                  DEFAULT STRUCT(1 AS id, 'first' AS label);
                DECLARE items ARRAY<STRUCT<id INT64, label STRING>>
                  DEFAULT [STRUCT(1 AS id, 'first' AS label)];
                DECLARE inferred_item DEFAULT STRUCT(1 AS id, 'first' AS label);
                DECLARE inferred_items DEFAULT [STRUCT(1 AS id, 'first' AS label)];
                SET item = STRUCT(2 AS id, 'second' AS label);
                SET items = [STRUCT(2 AS id, 'second' AS label), STRUCT(3 AS id, 'third' AS label)];
                SELECT item.id, item.label, items, inferred_item.id, inferred_items;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine, times(7)).execute(request.capture(), any());
        String sql = request.getAllValues().getLast().sql();
        assertTrue(sql.contains("SELECT 2, 'second', CAST([CAST(STRUCT(2 AS `id`, 'second' AS `label`)"), sql);
        assertTrue(sql.contains("AS ARRAY<STRUCT<`id` INT64, `label` STRING>>"), sql);
        assertTrue(sql.contains("CAST([CAST(STRUCT(1 AS `id`, 'first' AS `label`)"), sql);
        assertDoesNotThrow(() -> SqlDialectTranslator.translate(sql, PROJECT, null,
                SqlDialectTranslator.QueryParameters.none()));
    }

    @Test
    void bytesDateTimeTimeAndJsonVariablesCanBeDeclaredAndAssigned() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        TableFieldSchema jsonResult = field("_floci_script_value", "JSON", null);
        BigQuerySqlEngine.Result firstJson = new BigQuerySqlEngine.Result(new TableSchema(List.of(jsonResult)),
                List.of(Map.of("_floci_script_value", "{\"value\":\"first\"}")), "SELECT", 0);
        BigQuerySqlEngine.Result secondJson = new BigQuerySqlEngine.Result(new TableSchema(List.of(jsonResult)),
                List.of(Map.of("_floci_script_value", "{\"value\":\"second\"}")), "SELECT", 0);
        BigQuerySqlEngine.Result finalResult = new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("payload", "BYTES", null), field("date_time", "DATETIME", null),
                        field("time_value", "TIME", null), field("document", "JSON", null))),
                List.of(Map.of("payload", "c2Vjb25k", "date_time", "2025-02-03T14:00:00",
                        "time_value", "14:00:00", "document", "{\"value\":\"second\"}")), "SELECT", 0);
        when(engine.execute(any(), any())).thenReturn(firstJson, secondJson, finalResult);
        BigQueryService variableService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        variableService.query(PROJECT, "US", null, """
                DECLARE payload BYTES DEFAULT b'first';
                DECLARE date_time DATETIME DEFAULT DATETIME '2025-02-03 12:00:00';
                DECLARE time_value TIME DEFAULT TIME '12:00:00';
                DECLARE document JSON DEFAULT JSON '{"value":"first"}';
                SET payload = b'second';
                SET date_time = DATETIME '2025-02-03 14:00:00';
                SET time_value = TIME '14:00:00';
                SET document = JSON '{"value":"second"}';
                SELECT payload, date_time, time_value, document;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine, times(3)).execute(request.capture(), any());
        String sql = request.getAllValues().getLast().sql();
        assertTrue(sql.contains("FROM_BASE64('c2Vjb25k')"), sql);
        assertTrue(sql.contains("DATETIME '2025-02-03 14:00'"), sql);
        assertTrue(sql.contains("TIME '14:00'"), sql);
        assertTrue(sql.contains("CAST('{\"value\":\"second\"}' AS JSON)"), sql);
        assertDoesNotThrow(() -> SqlDialectTranslator.translate(sql, PROJECT, null,
                SqlDialectTranslator.QueryParameters.none()));
    }

    @Test
    void dateTimestampAndArrayTypesCanBeInferred() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        when(engine.execute(any(), any())).thenReturn(new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("run_date", "DATE", null),
                        field("run_at", "TIMESTAMP", null), field("run_dates", "DATE", "REPEATED"))),
                List.of(Map.of("run_date", "2025-01-01", "run_at", "2025-01-01T12:00:00Z",
                        "run_dates", List.of("2025-01-01"))), "SELECT", 0));
        BigQueryService variableService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        variableService.query(PROJECT, "US", null, """
                DECLARE run_date DEFAULT DATE '2025-01-01';
                DECLARE run_at DEFAULT TIMESTAMP '2025-01-01 12:00:00+00';
                DECLARE run_dates DEFAULT [DATE '2025-01-01'];
                SELECT run_date, run_at, run_dates;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine).execute(request.capture(), any());
        assertTrue(request.getValue().sql().contains("DATE '2025-01-01'"), request.getValue().sql());
        assertTrue(request.getValue().sql().contains("TIMESTAMP '2025-01-01T12:00:00Z'"),
                request.getValue().sql());
        assertTrue(request.getValue().sql().contains("CAST([DATE '2025-01-01'] AS ARRAY<DATE>)"),
                request.getValue().sql());
    }

    @Test
    void inferredDateTimestampAndArrayValuesUseTheirResultTypes() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        BigQuerySqlEngine.Result dateResult = new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("_floci_script_value", "DATE", null))),
                List.of(Map.of("_floci_script_value", "2025-01-01")), "SELECT", 0);
        BigQuerySqlEngine.Result timestampResult = new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("_floci_script_value", "TIMESTAMP", null))),
                List.of(Map.of("_floci_script_value", "2025-01-01T12:00:00Z")), "SELECT", 0);
        BigQuerySqlEngine.Result arrayResult = new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("_floci_script_value", "DATE", "REPEATED"))),
                List.of(Map.of("_floci_script_value", List.of("2025-01-01"))), "SELECT", 0);
        BigQuerySqlEngine.Result finalResult = new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("run_date", "DATE", null), field("run_at", "TIMESTAMP", null),
                        field("run_dates", "DATE", "REPEATED"))),
                List.of(Map.of("run_date", "2025-01-01", "run_at", "2025-01-01T12:00:00Z",
                        "run_dates", List.of("2025-01-01"))), "SELECT", 0);
        when(engine.execute(any(), any())).thenReturn(dateResult, timestampResult, arrayResult, finalResult);
        BigQueryService variableService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        variableService.query(PROJECT, "US", null, """
                DECLARE run_date DEFAULT CURRENT_DATE();
                DECLARE run_at DEFAULT CURRENT_TIMESTAMP();
                DECLARE run_dates DEFAULT ARRAY(SELECT DATE '2025-01-01');
                SELECT run_date, run_at, run_dates;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine, times(4)).execute(request.capture(), any());
        String sql = request.getAllValues().getLast().sql();
        assertTrue(sql.contains("DATE '2025-01-01'"), sql);
        assertTrue(sql.contains("TIMESTAMP '2025-01-01T12:00:00Z'"), sql);
        assertTrue(sql.contains("CAST([DATE '2025-01-01'] AS ARRAY<DATE>)"), sql);
    }

    @Test
    void columnsTakePrecedenceOverSameNamedScriptVariables() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE age INT64 DEFAULT 999;
                SELECT age FROM ds1.t1;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(List.of("30", "25"), result.rows().stream()
                .map(row -> row.getF().getFirst().getV().toString()).toList());
    }

    @Test
    void scriptVariableNamesUsedAsColumnAliasesArePreserved() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        BigQueryService aliasService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);
        aliasService.createDataset(PROJECT, newDataset(DATASET));
        aliasService.createTable(PROJECT, DATASET, newTable(DATASET, TABLE));
        when(engine.execute(any(), any())).thenReturn(new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("result", "INTEGER", null))),
                List.of(Map.of("result", 25L)), "SELECT", 0));

        StoredJob job = aliasService.query(PROJECT, "US", null, """
                DECLARE result INT64 DEFAULT 999;
                SELECT age AS result FROM ds1.t1;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine).execute(request.capture(), any());
        assertTrue(request.getValue().sql().contains("age AS result"));
        assertEquals(1, aliasService.queryResults(PROJECT, job).rows().size());
    }

    @Test
    void variableNamesAreCaseInsensitive() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE Target_Age INT64 DEFAULT 25;
                SELECT * FROM ds1.t1 WHERE age = target_age;
                """, null);

        assertEquals(1, service.queryResults(PROJECT, job).rows().size());
    }

    @Test
    void variablesCannotBeRedeclaredInAnEnclosingScope() {
        GcpException error = assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null, """
                DECLARE target_age INT64 DEFAULT 25;
                BEGIN
                  DECLARE TARGET_AGE INT64 DEFAULT 24;
                END;
                SELECT * FROM ds1.t1;
                """, null));

        assertEquals("invalidQuery", error.getReason());
    }

    @Test
    void tupleSetAssignsVariablesSimultaneously() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE first_age INT64 DEFAULT 24;
                DECLARE second_age INT64 DEFAULT 25;
                SET (first_age, second_age) = (second_age, first_age);
                SELECT * FROM ds1.t1 WHERE age = first_age;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void declarationsAfterOtherStatementsAreRejected() {
        GcpException error = assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null, """
                SELECT * FROM ds1.t1;
                DECLARE target_age INT64 DEFAULT 25;
                """, null));

        assertEquals("invalidQuery", error.getReason());
    }

    @Test
    void scalarVariablesCanBeScopedToBeginBlocks() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                BEGIN
                  DECLARE target_age INT64 DEFAULT 25;
                  SELECT * FROM ds1.t1 WHERE age = target_age;
                END;
                SELECT * FROM ds1.t1;
                """, null);

        assertEquals(2, service.queryResults(PROJECT, job).rows().size());
    }

    @Test
    void assignmentsToOuterVariablesSurviveABeginBlock() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE target_age INT64 DEFAULT 24;
                BEGIN
                  SET target_age = 25;
                END;
                SELECT * FROM ds1.t1 WHERE age = target_age;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void namedParametersBindInEveryStatementOfAScript() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        when(engine.execute(any(), any())).thenReturn(new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("value", "INTEGER", null))),
                List.of(Map.of("value", 7L)), "SELECT", 0));
        BigQueryService scriptService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);
        BigQueryService.QueryOptions options = new BigQueryService.QueryOptions("SELECT @value; SELECT @value;", null,
                List.of(Map.of("name", "value", "parameterType", Map.of("type", "INT64"),
                        "parameterValue", Map.of("value", "7"))), "NAMED", false, false);

        StoredJob job = scriptService.query(PROJECT, "US", null, options);

        assertEquals("SCRIPT", job.getStatementType());
        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine, times(2)).execute(request.capture(), any());
    }

    @Test
    void multiStatementScriptsStillRejectPositionalParameters() {
        Map<String, Object> parameter = Map.of("parameterType", Map.of("type", "INT64"),
                "parameterValue", Map.of("value", "1"));
        BigQueryService.QueryOptions options = new BigQueryService.QueryOptions("SELECT ?; SELECT ?", null, List.of(parameter, parameter),
                "POSITIONAL", false, false);

        GcpException error = assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null, options));

        assertTrue(error.getMessage().contains(
                "Positional query parameters are not supported in multi-statement scripts"), error.getMessage());
    }

    @Test
    void temporaryTableColumnsAreNotReplacedBySameNamedVariables() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        when(engine.execute(any(), any())).thenReturn(new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("id", "INTEGER", null))),
                List.of(Map.of("id", 1L)), "SELECT", 0));
        BigQueryService scriptService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        scriptService.query(PROJECT, "US", null, """
                DECLARE id INT64 DEFAULT 99;
                CREATE TEMP TABLE t AS SELECT 1 AS id;
                SELECT id FROM t;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine, times(2)).execute(request.capture(), any());
        String sql = request.getAllValues().getLast().sql();
        assertTrue(sql.endsWith("SELECT id FROM t"), sql);
    }

    @Test
    void aVariableInTheSelectListIsNotHiddenByItsOwnAlias() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        when(engine.execute(any(), any())).thenReturn(new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("id", "INTEGER", null))),
                List.of(Map.of("id", 99L)), "SELECT", 0));
        BigQueryService scriptService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        scriptService.query(PROJECT, "US", null, """
                DECLARE id INT64 DEFAULT 99;
                SELECT id AS id;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine).execute(request.capture(), any());
        assertEquals("SELECT 99 AS id", request.getValue().sql());
    }

    @Test
    void aliasReferencesOutsideTheSelectListStillWinOverVariables() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        when(engine.execute(any(), any())).thenReturn(new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("total", "INTEGER", null))),
                List.of(Map.of("total", 30L)), "SELECT", 0));
        BigQueryService scriptService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        scriptService.query(PROJECT, "US", null, """
                DECLARE total INT64 DEFAULT 999;
                SELECT age AS total FROM ds1.t1 ORDER BY total;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine).execute(request.capture(), any());
        String sql = request.getValue().sql();
        assertTrue(sql.contains("age AS total"), sql);
        assertTrue(sql.endsWith("ORDER BY total"), sql);
    }

    @Test
    void stringVariableLiteralsEscapeBackslashes() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        when(engine.execute(any(), any())).thenReturn(new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("path", "STRING", null))),
                List.of(Map.of("path", "C:\\new")), "SELECT", 0));
        BigQueryService scriptService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        scriptService.query(PROJECT, "US", null, """
                DECLARE path STRING DEFAULT r'C:\\new';
                SELECT path;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine).execute(request.capture(), any());
        assertEquals("SELECT 'C:\\\\new'", request.getValue().sql());
    }

    @Test
    void nullVariablesKeepTheirDeclaredTypes() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        when(engine.execute(any(), any())).thenReturn(new BigQuerySqlEngine.Result(
                new TableSchema(List.of(field("a", "INTEGER", null))),
                List.of(Map.of("a", 1L)), "SELECT", 0));
        BigQueryService scriptService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        scriptService.query(PROJECT, "US", null, """
                DECLARE s STRING;
                DECLARE record STRUCT<a INT64>;
                SELECT s, record.a;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine).execute(request.capture(), any());
        String sql = request.getValue().sql();
        assertTrue(sql.contains("CAST(NULL AS STRING)"), sql);
        assertTrue(sql.contains("CAST(NULL AS STRUCT<`a` INT64>).a"), sql);
    }

    @Test
    void aNullConditionTakesTheElseBranchEvenWhenNegated() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE b BOOL;
                IF NOT b THEN
                  SELECT * FROM ds1.t1 WHERE age = 30;
                ELSE
                  SELECT * FROM ds1.t1 WHERE age = 25;
                END IF;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void arithmeticOnANullVariableKeepsItNull() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE target_age INT64;
                SET target_age = target_age + 1;
                IF target_age IS NULL THEN
                  SELECT * FROM ds1.t1 WHERE age = 25;
                END IF;
                """, null);

        assertEquals(1, service.queryResults(PROJECT, job).rows().size());
    }

    @Test
    void int64ConditionsCompareWithoutLosingPrecision() {
        seedTwoRows();
        StoredJob job = service.query(PROJECT, "US", null, """
                DECLARE counter INT64 DEFAULT 9007199254740993;
                IF counter = 9007199254740992 THEN
                  SELECT * FROM ds1.t1 WHERE age = 30;
                ELSE
                  SELECT * FROM ds1.t1 WHERE age = 25;
                END IF;
                """, null);

        BigQueryService.TableData result = service.queryResults(PROJECT, job);
        assertEquals(1, result.rows().size());
        assertEquals("bob", result.rows().getFirst().getF().getFirst().getV());
    }

    @Test
    void stringExpressionDefaultsAreEvaluatedInsteadOfReadAsOneLiteral() {
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        when(engine.execute(any(), any())).thenAnswer(invocation -> {
            BigQuerySqlEngine.Request request = invocation.getArgument(0);
            if (request.sql().contains("_floci_script_value")) {
                return new BigQuerySqlEngine.Result(
                        new TableSchema(List.of(field("_floci_script_value", "STRING", null))),
                        List.of(Map.of("_floci_script_value", "ab")), "SELECT", 0);
            }
            return new BigQuerySqlEngine.Result(new TableSchema(List.of(field("s", "STRING", null))),
                    List.of(Map.of("s", "ab")), "SELECT", 0);
        });
        BigQueryService scriptService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), engine);

        scriptService.query(PROJECT, "US", null, """
                DECLARE s STRING DEFAULT 'a' || 'b';
                SELECT s;
                """, null);

        ArgumentCaptor<BigQuerySqlEngine.Request> request = ArgumentCaptor.forClass(BigQuerySqlEngine.Request.class);
        verify(engine, times(2)).execute(request.capture(), any());
        assertEquals("SELECT 'ab'", request.getAllValues().getLast().sql());
    }

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
        assertEquals(1, service.listTableData(PROJECT, DATASET, "events$20261006").rows().size());
        assertEquals(2, service.listTableData(PROJECT, DATASET, "events$20261007").rows().size());
        GcpException duplicate = assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                new BigQueryService.QueryOptions("SELECT * FROM ds1.t1", null, List.of(), null, false, false,
                        new TableReference(PROJECT, DATASET, "events$20261006"), "WRITE_EMPTY", null)));
        assertEquals("duplicate", duplicate.getReason());
    }

    @Test
    void decoratedDestinationHonorsFieldRelaxation() {
        seedTwoRows();
        Table destination = newTable(DATASET, "events");
        destination.setSchema(new TableSchema(List.of(
                field("name", "STRING", "REQUIRED"), field("age", "INTEGER", "REQUIRED"))));
        destination.setExtra("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, destination);

        StoredJob job = service.query(PROJECT, "US", null, new BigQueryService.QueryOptions(
                "SELECT name FROM ds1.t1", null, List.of(), null, false, false,
                new TableReference(PROJECT, DATASET, "events$20261006"), "WRITE_TRUNCATE",
                null, List.of("ALLOW_FIELD_RELAXATION")));

        assertFalse(job.failed(), job.getErrorMessage());
        assertEquals(List.of("NULLABLE", "NULLABLE"), service.getTable(PROJECT, DATASET, "events")
                .getSchema().getFields().stream().map(TableFieldSchema::getMode).toList());
        assertEquals(2, service.listTableData(PROJECT, DATASET, "events$20261006").rows().size());
    }

    @Test
    void writeEmptyPreservesAnExistingPartitionedTableSchema() {
        seedTwoRows();
        Table destination = new Table();
        destination.setTableReference(new TableReference(null, DATASET, "partitioned_events"));
        destination.setSchema(new TableSchema(List.of(
                field("event_date", "DATE", null), field("name", "STRING", null))));
        destination.setExtra("timePartitioning", Map.of("type", "DAY", "field", "event_date"));
        service.createTable(PROJECT, DATASET, destination);

        service.query(PROJECT, "US", null, new BigQueryService.QueryOptions(
                "SELECT name FROM ds1.t1", null, List.of(), null, false, false,
                new TableReference(PROJECT, DATASET, "partitioned_events"), "WRITE_EMPTY", null));

        Table written = service.getTable(PROJECT, DATASET, "partitioned_events");
        assertEquals(List.of("event_date", "name"),
                written.getSchema().getFields().stream().map(TableFieldSchema::getName).toList());
        assertEquals(Map.of("type", "DAY", "field", "event_date"), written.getExtra().get("timePartitioning"));
        assertEquals(2, service.listTableData(PROJECT, DATASET, "partitioned_events").rows().size());
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
    void queryDestinationNormalizesIntegralRangeNumbers() {
        seedTwoRows();

        service.query(PROJECT, "US", null, new BigQueryService.QueryOptions(
                "SELECT age FROM ds1.t1", null, List.of(), null, false, false,
                new TableReference(PROJECT, DATASET, "created_ranges"), "WRITE_TRUNCATE",
                "CREATE_IF_NEEDED", List.of(), null,
                Map.of("field", "age", "range", Map.of("start", 0.0, "end", 100.0, "interval", 10.0))));

        assertEquals(Map.of("field", "age", "range",
                        Map.of("start", "0", "end", "100", "interval", "10")),
                service.getTable(PROJECT, DATASET, "created_ranges").getExtra().get("rangePartitioning"));
        assertEquals(1, service.listTableData(PROJECT, DATASET, "created_ranges$20").rows().size());
        assertEquals(1, service.listTableData(PROJECT, DATASET, "created_ranges$30").rows().size());
    }

    @Test
    void invalidQueryDestinationPartitioningDoesNotCreateTable() {
        seedTwoRows();

        GcpException error = assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                new BigQueryService.QueryOptions("SELECT * FROM ds1.t1", null, List.of(), null, false, false,
                        new TableReference(PROJECT, DATASET, "bad_events"), "WRITE_TRUNCATE",
                        "CREATE_IF_NEEDED", List.of(),
                        Map.of("type", "DAY", "field", "age"), null)));

        assertEquals("invalid", error.getReason());
        assertThrows(GcpException.class, () -> service.getTable(PROJECT, DATASET, "bad_events"));
    }

    @Test
    void truncateCannotReplacePartitionSchemaOrMutateTheExistingTableOnFailure() {
        seedTwoRows();
        Table destination = new Table();
        destination.setTableReference(new TableReference(null, DATASET, "partitioned_events"));
        destination.setSchema(new TableSchema(List.of(
                field("event_date", "DATE", null), field("name", "STRING", null))));
        destination.setExtra("timePartitioning", Map.of("type", "DAY", "field", "event_date"));
        service.createTable(PROJECT, DATASET, destination);
        service.appendRows(PROJECT, DATASET, "partitioned_events",
                List.of(Map.of("event_date", "2026-10-20", "name", "kept")));

        GcpException incompatible = assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                new BigQueryService.QueryOptions("SELECT name FROM ds1.t1", null, List.of(), null, false, false,
                        new TableReference(PROJECT, DATASET, "partitioned_events"), "WRITE_TRUNCATE", null)));

        assertEquals("invalid", incompatible.getReason());
        assertEquals(List.of("event_date", "name"), service.getTable(PROJECT, DATASET, "partitioned_events")
                .getSchema().getFields().stream().map(TableFieldSchema::getName).toList());
        assertEquals(1, service.listTableData(PROJECT, DATASET, "partitioned_events").rows().size());

        GcpException changedSpec = assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                new BigQueryService.QueryOptions("SELECT * FROM ds1.t1", null, List.of(), null, false, false,
                        new TableReference(PROJECT, DATASET, "partitioned_events"), "WRITE_APPEND",
                        "CREATE_IF_NEEDED", List.of(), Map.of("type", "HOUR", "field", "event_date"), null)));
        assertEquals("invalid", changedSpec.getReason());
        assertEquals(1, service.listTableData(PROJECT, DATASET, "partitioned_events").rows().size());
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
    void wholeTableDeleteWaitsForAnInFlightAppendAndWins() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch allowWrite = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        CountDownLatch deleteFinished = new CountDownLatch(1);
        ArmableBlockingPutStorage<String, StoredTableData> blockingData =
                new ArmableBlockingPutStorage<>(writeStarted, allowWrite);
        BigQueryService concurrentService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                blockingData, new InMemoryStorage<>());
        concurrentService.createDataset(PROJECT, newDataset(DATASET));
        concurrentService.createTable(PROJECT, DATASET, newTable(DATASET, TABLE));
        blockingData.blockNextPut();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> append = executor.submit(() -> concurrentService.appendRows(PROJECT, DATASET, TABLE,
                    List.of(Map.of("name", "in-flight", "age", 1))));
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS));
            Future<?> delete = executor.submit(() -> {
                deleteStarted.countDown();
                concurrentService.deleteTable(PROJECT, DATASET, TABLE);
                deleteFinished.countDown();
            });
            assertTrue(deleteStarted.await(5, TimeUnit.SECONDS));
            assertFalse(deleteFinished.await(100, TimeUnit.MILLISECONDS),
                    "delete must wait for the mutation that already holds writeLock");

            allowWrite.countDown();
            append.get(5, TimeUnit.SECONDS);
            delete.get(5, TimeUnit.SECONDS);
        }

        assertThrows(GcpException.class, () -> concurrentService.getTable(PROJECT, DATASET, TABLE));
        assertTrue(blockingData.get(DATASET + "/" + TABLE).isEmpty());
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
    void streamingRejectsTimePartitionValuesOutsideItsRelativeWindow() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table table = new Table();
        table.setTableReference(new TableReference(null, DATASET, "events"));
        table.setSchema(new TableSchema(List.of(field("event_date", "DATE", null))));
        table.setExtra("timePartitioning", Map.of("type", "DAY", "field", "event_date"));
        service.createTable(PROJECT, DATASET, table);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        List<Map<String, Object>> errors = service.insertAll(PROJECT, DATASET, "events", List.of(
                new BigQueryService.InsertRow(0, Map.of("event_date", today.minusYears(10).minusDays(1).toString())),
                new BigQueryService.InsertRow(1, Map.of("event_date", today.toString())),
                new BigQueryService.InsertRow(2, Map.of("event_date", today.plusYears(1).plusDays(1).toString()))),
                true, false);

        assertEquals(List.of(0, 2), errors.stream().map(error -> error.get("index")).toList());
        assertEquals(1, service.listTableData(PROJECT, DATASET, "events").rows().size());
    }

    @Test
    void rangePartitionDdlCreatesRangeMetadata() {
        service.createDataset(PROJECT, newDataset(DATASET));

        service.query(PROJECT, "US", null,
                "CREATE TABLE ds1.ranged (id INT64) "
                        + "PARTITION BY RANGE_BUCKET(id, GENERATE_ARRAY(-10, 100, 5))", null);

        assertEquals(Map.of("field", "id", "range",
                        Map.of("start", "-10", "end", "100", "interval", "5")),
                service.getTable(PROJECT, DATASET, "ranged").getExtra().get("rangePartitioning"));
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
    void stagingAddsOnlyThePseudocolumnsAvailableForThePartitionType() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table fieldPartitioned = newTable(DATASET, "field_events");
        fieldPartitioned.setSchema(new TableSchema(List.of(
                field("name", "STRING", null), field("occurred_at", "TIMESTAMP", null))));
        fieldPartitioned.getExtra().put("timePartitioning", Map.of("type", "DAY", "field", "occurred_at"));
        service.createTable(PROJECT, DATASET, fieldPartitioned);
        service.insertAll(PROJECT, DATASET, "field_events",
                List.of(new BigQueryService.InsertRow(0, Map.of(
                        "name", "event", "occurred_at", "2026-10-01T00:00:00Z"))), false, false);

        Map<String, Object> fieldStaged = service.stagingRows(PROJECT, DATASET, "field_events").getFirst();
        assertFalse(fieldStaged.containsKey(BigQueryService.PARTITION_DATE));
        assertFalse(fieldStaged.containsKey(BigQueryService.PARTITION_TIME));

        Table hourly = newTable(DATASET, "hourly_events");
        hourly.getExtra().put("timePartitioning", Map.of("type", "HOUR"));
        service.createTable(PROJECT, DATASET, hourly);
        service.insertAll(PROJECT, DATASET, "hourly_events",
                List.of(new BigQueryService.InsertRow(0, Map.of("name", "now", "age", 1))), false, false);

        Map<String, Object> hourlyStaged = service.stagingRows(PROJECT, DATASET, "hourly_events").getFirst();
        assertFalse(hourlyStaged.containsKey(BigQueryService.PARTITION_DATE));
        assertNotNull(hourlyStaged.get(BigQueryService.PARTITION_TIME));
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
        List<Map<String, Object>> eventPartitions = partitions.stream()
                .filter(row -> "events".equals(row.get("table_name"))).toList();

        assertEquals(2, eventPartitions.size());
        assertEquals(List.of("20261006", "20261007"),
                eventPartitions.stream().map(row -> row.get("partition_id")).sorted().toList());
        Map<String, Object> first = eventPartitions.stream()
                .filter(row -> row.get("partition_id").equals("20261006")).findFirst().orElseThrow();
        assertEquals(2L, first.get("total_rows"));
        assertTrue((long) first.get("total_logical_bytes") > 0L);
        assertEquals(first.get("total_logical_bytes"), first.get("total_billable_bytes"));
        assertEquals("ACTIVE", first.get("storage_tier"));
    }

    @Test
    void partitionsInformationSchemaReportsLegacyIngestionRowsAsUnpartitioned() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table table = newTable(DATASET, "legacy_events");
        table.setExtra("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, table);
        StoredTableData legacy = new StoredTableData();
        legacy.setRows(new ArrayList<>(List.of(Map.of("name", "before-partitions", "age", 1))));
        dataStore.put(DATASET + "/legacy_events", legacy);

        Map<String, Object> partition = service.informationSchemaRows(PROJECT, "PARTITIONS", DATASET, null).stream()
                .filter(row -> "legacy_events".equals(row.get("table_name"))).findFirst().orElseThrow();
        assertEquals("__UNPARTITIONED__", partition.get("partition_id"));
        assertEquals(1L, partition.get("total_rows"));
    }

    @Test
    void dmlKeepsUpdatedLegacyIngestionRowsUnpartitioned() {
        InMemoryStorage<String, Dataset> datasets = new InMemoryStorage<>();
        InMemoryStorage<String, Table> tables = new InMemoryStorage<>();
        InMemoryStorage<String, StoredTableData> tableData = new InMemoryStorage<>();
        BigQuerySqlEngine engine = mock(BigQuerySqlEngine.class);
        BigQueryService legacyService = new BigQueryService(datasets, tables, tableData,
                new InMemoryStorage<>(), engine);
        legacyService.createDataset(PROJECT, newDataset(DATASET));
        Table table = newTable(DATASET, "legacy_events");
        table.setExtra("timePartitioning", Map.of("type", "DAY"));
        legacyService.createTable(PROJECT, DATASET, table);
        StoredTableData legacy = new StoredTableData();
        legacy.setRows(new ArrayList<>(List.of(Map.of("name", "before-partitions", "age", 1))));
        tableData.put(DATASET + "/legacy_events", legacy);
        when(engine.executeDml(any(), any())).thenReturn(new BigQuerySqlEngine.DmlResult(
                1, 0, 1, 0, List.of(Map.of("name", "updated", "age", 1,
                        BigQueryService.DML_ORIGINAL_UNPARTITIONED, true)), 0));

        StoredJob updated = legacyService.query(PROJECT, "US", null,
                "UPDATE ds1.legacy_events SET name = 'updated' WHERE TRUE", null);

        assertFalse(updated.failed(), updated.getErrorMessage());
        StoredTableData stored = tableData.get(DATASET + "/legacy_events").orElseThrow();
        assertEquals(List.of(Map.of("name", "updated", "age", 1)), stored.getRows());
        assertTrue(stored.getPartitionRows().isEmpty());
        Map<String, Object> staged = legacyService.stagingRows(PROJECT, DATASET, "legacy_events").getFirst();
        assertFalse(staged.containsKey(BigQueryService.PARTITION_TIME));
        assertFalse(staged.containsKey(BigQueryService.PARTITION_DATE));
    }

    @Test
    void partitionsInformationSchemaIncludesUnpartitionedTables() {
        seedTwoRows();

        Map<String, Object> partition = service.informationSchemaRows(PROJECT, "PARTITIONS", DATASET, null).stream()
                .filter(row -> TABLE.equals(row.get("table_name")))
                .findFirst().orElseThrow();

        assertNull(partition.get("partition_id"));
        assertEquals(2L, partition.get("total_rows"));
    }

    @Test
    void partitionsInformationSchemaDerivesFieldAndRangePartitions() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table events = newTable(DATASET, "events");
        events.setSchema(new TableSchema(List.of(
                field("name", "STRING", null), field("occurred_at", "TIMESTAMP", null))));
        events.setExtra("timePartitioning", Map.of("type", "HOUR", "field", "OCCURRED_AT"));
        service.createTable(PROJECT, DATASET, events);
        service.insertAll(PROJECT, DATASET, "events", List.of(
                new BigQueryService.InsertRow(0, Map.of(
                        "name", "one", "occurred_at", "2026-10-15T08:12:00+02:00")),
                new BigQueryService.InsertRow(1, Map.of("name", "two"))), false, false);

        Table people = newTable(DATASET, "people");
        people.getExtra().put("rangePartitioning", Map.of("field", "age",
                "range", Map.of("start", "0", "end", "20", "interval", "10")));
        service.createTable(PROJECT, DATASET, people);
        service.appendRows(PROJECT, DATASET, "people", List.of(
                Map.of("name", "in", "age", 15), Map.of("name", "out", "age", 25)));

        List<Map<String, Object>> partitions = service.informationSchemaRows(PROJECT, "PARTITIONS", DATASET, null);
        assertEquals(List.of("2026101506", "__NULL__"), partitions.stream()
                .filter(row -> row.get("table_name").equals("events"))
                .map(row -> (String) row.get("partition_id")).sorted().toList());
        assertEquals(1, service.listTableData(PROJECT, DATASET, "events$2026101506").rows().size());
        assertEquals(List.of("10", "__UNPARTITIONED__"), partitions.stream()
                .filter(row -> row.get("table_name").equals("people"))
                .map(row -> (String) row.get("partition_id")).sorted().toList());
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
    void outOfRangeTimeColumnValuesRouteToUnpartitioned() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table events = newTable(DATASET, "wide_events");
        events.setSchema(new TableSchema(List.of(field("occurred_at", "TIMESTAMP", null))));
        events.setExtra("timePartitioning", Map.of("type", "DAY", "field", "occurred_at"));
        service.createTable(PROJECT, DATASET, events);
        service.appendRows(PROJECT, DATASET, "wide_events", List.of(
                Map.of("occurred_at", "1959-12-31T23:59:59Z"),
                Map.of("occurred_at", "2160-01-01T00:00:00Z")));

        Map<String, Object> partition = service.informationSchemaRows(PROJECT, "PARTITIONS", DATASET, null).stream()
                .filter(row -> "wide_events".equals(row.get("table_name")))
                .findFirst().orElseThrow();
        assertEquals("__UNPARTITIONED__", partition.get("partition_id"));
        assertEquals(2L, partition.get("total_rows"));
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
        assertEquals("invalidQuery", assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                new BigQueryService.QueryOptions(
                        "CREATE TABLE ds1.bad_partition (ts TIMESTAMP) PARTITION BY ts", null, List.of(),
                        null, true, false))).getReason());

        Table partitioned = newTable(DATASET, "partitioned");
        partitioned.setSchema(new TableSchema(List.of(field("d", "DATE", null))));
        partitioned.setExtra("timePartitioning", Map.of("type", "DAY", "field", "d"));
        service.createTable(PROJECT, DATASET, partitioned);
        assertEquals("invalidQuery", assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                new BigQueryService.QueryOptions(
                        "CREATE OR REPLACE TABLE ds1.partitioned (ts TIMESTAMP) "
                                + "PARTITION BY TIMESTAMP_TRUNC(ts, HOUR)", null, List.of(),
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

    // ── Job location of statements that write ──

    private void seedEuDataset() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Dataset eu = newDataset("eu_ds");
        eu.setLocation("EU");
        service.createDataset(PROJECT, eu);
        service.createTable(PROJECT, "eu_ds", newTable("eu_ds", TABLE));
        service.appendRows(PROJECT, "eu_ds", TABLE, List.of(Map.of("name", "ana", "age", 1)));
        service.query(PROJECT, "EU", null, "CREATE VIEW eu_ds.v AS SELECT * FROM eu_ds.t1", null);
    }

    private static void assertNotInLocation(GcpException ex, String dataset, String location) {
        assertEquals(404, ex.getHttpStatus());
        assertEquals("notFound", ex.getReason());
        assertEquals("Not found: Dataset p1:" + dataset + " was not found in location " + location, ex.getMessage());
    }

    private static BigQueryService.QueryOptions dryRun(String sql) {
        return new BigQueryService.QueryOptions(sql, null, List.of(), null, true, false);
    }

    private void assertEuDatasetUntouched() {
        assertEquals(1, service.listTableData(PROJECT, "eu_ds", TABLE).rows().size());
        assertEquals(2, service.listTables(PROJECT, "eu_ds").size());
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getTable(PROJECT, "eu_ds", "fresh")).getGcpStatus());
        assertNull(service.getTable(PROJECT, "eu_ds", TABLE).getDescription());
        assertNull(service.getTable(PROJECT, "eu_ds", "v").getDescription());
        assertEquals(List.of("name", "age"), service.getTable(PROJECT, "eu_ds", TABLE).getSchema().getFields()
                .stream().map(TableFieldSchema::getName).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "TRUNCATE TABLE eu_ds.t1",
            "DROP TABLE eu_ds.t1",
            "DROP TABLE IF EXISTS eu_ds.missing",
            "DROP VIEW eu_ds.v",
            "DROP VIEW IF EXISTS eu_ds.missing",
            "DROP SCHEMA eu_ds CASCADE",
            "DROP SCHEMA IF EXISTS eu_ds CASCADE",
            "ALTER TABLE eu_ds.t1 SET OPTIONS(description='x')",
            "ALTER TABLE IF EXISTS eu_ds.missing SET OPTIONS(description='x')",
            "ALTER VIEW eu_ds.v SET OPTIONS(description='x')",
            "CREATE TABLE eu_ds.fresh (id INT64)",
            "CREATE TABLE IF NOT EXISTS eu_ds.t1 (id INT64)",
            "CREATE OR REPLACE TABLE eu_ds.t1 (id INT64)",
            "CREATE TABLE eu_ds.fresh AS SELECT * FROM eu_ds.t1",
            "CREATE VIEW eu_ds.fresh AS SELECT * FROM eu_ds.t1",
            "CREATE TABLE eu_ds.fresh COPY eu_ds.t1"})
    void aWriteStatementMustRunInTheLocationOfItsDataset(String sql) {
        seedEuDataset();

        assertNotInLocation(assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null, sql, null)),
                "eu_ds", "US");
        assertNotInLocation(assertThrows(GcpException.class,
                () -> service.query(PROJECT, "US", null, dryRun(sql))), "eu_ds", "US");
        assertEuDatasetUntouched();

        StoredJob failed = service.insertQuery(PROJECT, "US", null,
                new BigQueryService.QueryOptions(sql, null, List.of(), null, false, false));
        assertEquals("US", failed.getLocation());
        assertEquals("notFound", failed.getErrorReason());
        assertEuDatasetUntouched();

        assertEquals("EU", service.query(PROJECT, null, null, dryRun(sql)).getLocation());
        assertEuDatasetUntouched();
        assertEquals("eu", service.query(PROJECT, "eu", null, dryRun(sql)).getLocation());
        StoredJob inferred = service.query(PROJECT, null, null, sql, null);
        assertEquals("EU", inferred.getLocation());
        assertEquals(inferred.getJobId(), service.getJob(PROJECT, inferred.getJobId(), "EU").getJobId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE ds1.fresh AS SELECT * FROM eu_ds.t1",
            "CREATE VIEW ds1.fresh AS SELECT * FROM eu_ds.t1",
            "CREATE TABLE ds1.fresh COPY eu_ds.t1"})
    void aCreateStatementCannotSpanLocations(String sql) {
        seedEuDataset();

        assertNotInLocation(assertThrows(GcpException.class, () -> service.query(PROJECT, null, null, sql, null)),
                "eu_ds", "US");
        assertNotInLocation(assertThrows(GcpException.class, () -> service.query(PROJECT, "EU", null, sql, null)),
                "ds1", "EU");
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getTable(PROJECT, DATASET, "fresh")).getGcpStatus());
    }

    @Test
    void aFailedInsertedWriteStatementKeepsTheLocationItInferred() {
        seedEuDataset();

        StoredJob duplicate = service.insertQuery(PROJECT, null, null, new BigQueryService.QueryOptions(
                "CREATE TABLE eu_ds.t1 (id INT64)", null, List.of(), null, false, false));
        assertEquals("EU", duplicate.getLocation());
        assertEquals("duplicate", duplicate.getErrorReason());

        StoredJob truncatedView = service.insertQuery(PROJECT, null, null, new BigQueryService.QueryOptions(
                "TRUNCATE TABLE eu_ds.v", null, List.of(), null, false, false));
        assertEquals("EU", truncatedView.getLocation());

        StoredJob dropped = service.insertQuery(PROJECT, null, null, new BigQueryService.QueryOptions(
                "DROP TABLE eu_ds.missing", null, List.of(), null, false, false));
        assertEquals("EU", dropped.getLocation());
        assertEquals("notFound", dropped.getErrorReason());
        assertEquals("Not found: Table p1:eu_ds.missing", dropped.getErrorMessage());
    }

    @Test
    void statementsOnAMissingDatasetKeepTheirOwnErrors() {
        assertEquals("Not found: Table p1:nope.t1", assertThrows(GcpException.class,
                () -> service.query(PROJECT, "EU", null, "DROP TABLE nope.t1", null)).getMessage());
        assertEquals("SKIP", service.query(PROJECT, "EU", null, "DROP TABLE IF EXISTS nope.t1", null)
                .getDdlOperationPerformed());
        assertEquals("Not found: Dataset p1:nope", assertThrows(GcpException.class,
                () -> service.query(PROJECT, "EU", null, "DROP SCHEMA nope", null)).getMessage());
        assertEquals("SKIP", service.query(PROJECT, "EU", null, "DROP SCHEMA IF EXISTS nope", null)
                .getDdlOperationPerformed());
    }

    @Test
    void createSchemaCreatesTheDatasetWhereTheJobRuns() {
        assertEquals("EU", service.query(PROJECT, "EU", null, "CREATE SCHEMA in_eu", null).getLocation());
        assertEquals("EU", service.getDataset(PROJECT, "in_eu").getLocation());

        assertEquals("US", service.query(PROJECT, null, null, "CREATE SCHEMA in_default", null).getLocation());
        assertEquals("US", service.getDataset(PROJECT, "in_default").getLocation());

        service.query(PROJECT, null, null, "CREATE SCHEMA with_options OPTIONS(location='EU')", null);
        assertEquals("US", service.getDataset(PROJECT, "with_options").getLocation());
        assertEquals("SKIP", service.query(PROJECT, "US", null, "CREATE SCHEMA IF NOT EXISTS in_eu", null)
                .getDdlOperationPerformed());
    }

    private static BigQueryService.QueryOptions intoDestination(String sql, String dataset, boolean dryRun) {
        return new BigQueryService.QueryOptions(sql, null, List.of(), null, dryRun, false,
                new TableReference(PROJECT, dataset, "out"), "WRITE_TRUNCATE", null);
    }

    @Test
    void aQueryDestinationTableMustBeInTheJobLocation() {
        seedEuDataset();
        service.createTable(PROJECT, DATASET, newTable(DATASET, TABLE));

        assertNotInLocation(assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                intoDestination("SELECT * FROM ds1.t1", "eu_ds", false))), "eu_ds", "US");
        assertNotInLocation(assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                intoDestination("SELECT * FROM ds1.t1", "eu_ds", true))), "eu_ds", "US");
        assertNotInLocation(assertThrows(GcpException.class, () -> service.query(PROJECT, null, null,
                intoDestination("SELECT * FROM ds1.t1", "eu_ds", false))), "eu_ds", "US");
        assertNotInLocation(assertThrows(GcpException.class, () -> service.query(PROJECT, null, null,
                intoDestination("SELECT * FROM eu_ds.t1", DATASET, false))), DATASET, "EU");
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getTable(PROJECT, "eu_ds", "out")).getGcpStatus());
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getTable(PROJECT, DATASET, "out")).getGcpStatus());

        StoredJob failed = service.insertQuery(PROJECT, "US", null,
                intoDestination("SELECT * FROM ds1.t1", "eu_ds", false));
        assertEquals("US", failed.getLocation());
        assertEquals("notFound", failed.getErrorReason());

        assertEquals("EU", service.query(PROJECT, null, null,
                intoDestination("SELECT * FROM eu_ds.t1", "eu_ds", true)).getLocation());
        assertEquals("EU", service.query(PROJECT, null, null,
                intoDestination("SELECT * FROM eu_ds.t1", "eu_ds", false)).getLocation());
        assertEquals(1, service.listTableData(PROJECT, "eu_ds", "out").rows().size());
    }

    private static Map<String, Object> euLoadConfig(String table) {
        Map<String, Object> config = loadConfig(table, "schema", PEOPLE_SCHEMA);
        config.put("destinationTable", Map.of("datasetId", "eu_ds", "tableId", table));
        return config;
    }

    @Test
    void aLoadJobMustRunInTheLocationOfItsDestination() {
        seedEuDataset();

        StoredJob wrong = service.load(PROJECT, "US", null, euLoadConfig("loaded"),
                ndjson("{\"name\": \"bea\", \"age\": 2}"));
        assertEquals("US", wrong.getLocation());
        assertEquals("notFound", wrong.getErrorReason());
        assertEquals("Not found: Dataset p1:eu_ds was not found in location US", wrong.getErrorMessage());
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getTable(PROJECT, "eu_ds", "loaded")).getGcpStatus());

        StoredJob explicit = service.load(PROJECT, "eu", null, euLoadConfig("loaded"),
                ndjson("{\"name\": \"bea\", \"age\": 2}"));
        assertFalse(explicit.failed());
        assertEquals("eu", explicit.getLocation());

        StoredJob inferred = service.load(PROJECT, null, null, euLoadConfig("inferred"),
                ndjson("{\"name\": \"bea\", \"age\": 2}"));
        assertFalse(inferred.failed());
        assertEquals("EU", inferred.getLocation());
        assertEquals(inferred.getJobId(), service.getJob(PROJECT, inferred.getJobId(), "EU").getJobId());
        assertEquals(1, service.listTableData(PROJECT, "eu_ds", "inferred").rows().size());
    }

    @Test
    void aFailedLoadJobKeepsTheLocationItInferred() {
        seedEuDataset();
        Map<String, Object> noSchema = euLoadConfig("no_schema");
        noSchema.remove("schema");

        StoredJob failed = service.load(PROJECT, null, null, noSchema, ndjson("{\"name\": \"bea\", \"age\": 2}"));

        assertEquals("invalid", failed.getErrorReason());
        assertEquals("EU", failed.getLocation());
        assertEquals(failed.getJobId(), service.getJob(PROJECT, failed.getJobId(), "EU").getJobId());

        StoredJob missingDataset = service.load(PROJECT, null, null, Map.of("destinationTable",
                Map.of("datasetId", "nope", "tableId", "t"), "sourceFormat", "NEWLINE_DELIMITED_JSON"), ndjson("{}"));
        assertEquals("US", missingDataset.getLocation());
        assertEquals("notFound", missingDataset.getErrorReason());
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
        List<BigQueryService.InsertRow> rows = new ArrayList<>();
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
        patch.setExtra("requirePartitionFilter", true);
        Table patched = service.patchTable(PROJECT, DATASET, TABLE, patch);
        assertEquals(Map.of("fields", List.of("name")), patched.getExtra().get("clustering"));
        assertEquals("US", patched.getLocation());

        Table partitioned = newTable(DATASET, "partitioned");
        partitioned.setExtra("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, partitioned);
        Table partitionPatch = new Table();
        partitionPatch.setExtra("timePartitioning", Map.of("type", "DAY", "expirationMs", 3600000));
        assertEquals("3600000", ((Map<?, ?>) service.patchTable(PROJECT, DATASET, "partitioned", partitionPatch)
                .getExtra().get("timePartitioning")).get("expirationMs"));

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

        Table wrongTimeType = newTable(DATASET, "p3");
        wrongTimeType.setExtra("timePartitioning", Map.of("type", "DAY", "field", "name"));
        assertThrows(GcpException.class, () -> service.createTable(PROJECT, DATASET, wrongTimeType));

        Table hourlyDate = newTable(DATASET, "p4");
        hourlyDate.setSchema(new TableSchema(List.of(field("event_date", "DATE", null))));
        hourlyDate.setExtra("timePartitioning", Map.of("type", "HOUR", "field", "event_date"));
        assertThrows(GcpException.class, () -> service.createTable(PROJECT, DATASET, hourlyDate));

        Table repeatedTime = newTable(DATASET, "p5");
        repeatedTime.setSchema(new TableSchema(List.of(field("event_time", "TIMESTAMP", "REPEATED"))));
        repeatedTime.setExtra("timePartitioning", Map.of("type", "DAY", "field", "event_time"));
        assertThrows(GcpException.class, () -> service.createTable(PROJECT, DATASET, repeatedTime));

        Table missingRange = newTable(DATASET, "p6");
        missingRange.setExtra("rangePartitioning", Map.of("field", "age"));
        assertThrows(GcpException.class, () -> service.createTable(PROJECT, DATASET, missingRange));

        Table badRange = newTable(DATASET, "p7");
        badRange.setExtra("rangePartitioning", Map.of("field", "age",
                "range", Map.of("start", "0", "end", "100", "interval", "0")));
        assertThrows(GcpException.class, () -> service.createTable(PROJECT, DATASET, badRange));

        Table fractionalRange = newTable(DATASET, "p8");
        fractionalRange.setExtra("rangePartitioning", Map.of("field", "age",
                "range", Map.of("start", 0, "end", 100, "interval", 2.5)));
        assertThrows(GcpException.class, () -> service.createTable(PROJECT, DATASET, fractionalRange));
    }

    @Test
    void tableUpdatesCannotChangePartitioningIdentity() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table partitioned = newTable(DATASET, TABLE);
        partitioned.setSchema(new TableSchema(List.of(field("event_date", "DATE", null))));
        partitioned.setExtra("timePartitioning", Map.of("type", "DAY", "field", "event_date"));
        service.createTable(PROJECT, DATASET, partitioned);
        service.appendRows(PROJECT, DATASET, TABLE, List.of(Map.of("event_date", "2026-10-21")));

        Table remove = new Table();
        assertEquals("invalid", assertThrows(GcpException.class,
                () -> service.patchTable(PROJECT, DATASET, TABLE, removeWithNullPartitioning(remove))).getReason());

        Table change = new Table();
        change.setExtra("timePartitioning", Map.of("type", "HOUR", "field", "event_date"));
        assertEquals("invalid", assertThrows(GcpException.class,
                () -> service.patchTable(PROJECT, DATASET, TABLE, change)).getReason());

        Table replacement = new Table();
        replacement.setSchema(new TableSchema(List.of(field("event_date", "DATE", null))));
        assertEquals("invalid", assertThrows(GcpException.class,
                () -> service.updateTable(PROJECT, DATASET, TABLE, replacement)).getReason());
        assertEquals("DAY", ((Map<?, ?>) service.getTable(PROJECT, DATASET, TABLE)
                .getExtra().get("timePartitioning")).get("type"));
        assertEquals(1, service.listTableData(PROJECT, DATASET, TABLE).rows().size());
    }

    private static Table removeWithNullPartitioning(Table table) {
        table.setExtra("timePartitioning", null);
        return table;
    }

    @Test
    void reservedPartitionPseudocolumnNamesAreRejected() {
        service.createDataset(PROJECT, newDataset(DATASET));
        for (String name : List.of("_PARTITIONTIME", "_partitiondate")) {
            Table table = newTable(DATASET, "reserved_" + name.toLowerCase());
            table.setSchema(new TableSchema(List.of(field(name, "TIMESTAMP", null))));
            GcpException error = assertThrows(GcpException.class,
                    () -> service.createTable(PROJECT, DATASET, table));
            assertEquals("invalid", error.getReason());
        }

        Table prefixed = newTable(DATASET, "reserved_prefix");
        prefixed.setSchema(new TableSchema(List.of(field("_partitionfoo", "STRING", null))));
        assertEquals("invalid", assertThrows(GcpException.class,
                () -> service.createTable(PROJECT, DATASET, prefixed)).getReason());

        Table nested = newTable(DATASET, "reserved_nested");
        TableFieldSchema record = field("payload", "RECORD", null);
        record.setFields(List.of(field("_PARTITION_SECRET", "STRING", null)));
        nested.setSchema(new TableSchema(List.of(record)));
        assertEquals("invalid", assertThrows(GcpException.class,
                () -> service.createTable(PROJECT, DATASET, nested)).getReason());
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
    void alterSetOptionsDoesNotReviveAnExpiredTable() {
        seedTwoRows();
        Table patch = new Table();
        patch.setExtra("expirationTime", String.valueOf(System.currentTimeMillis() - 1));
        service.patchTable(PROJECT, DATASET, TABLE, patch);

        assertEquals("NOT_FOUND", assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                "ALTER TABLE ds1.t1 SET OPTIONS(expiration_timestamp=NULL)", null)).getGcpStatus());
        assertEquals("SKIP", service.query(PROJECT, "US", null,
                "ALTER TABLE IF EXISTS ds1.t1 SET OPTIONS(expiration_timestamp=NULL)", null).getDdlOperationPerformed());
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getTable(PROJECT, DATASET, TABLE)).getGcpStatus());
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
    void createOrReplaceTableCannotChangePartitioning() {
        service.query(PROJECT, null, null, "CREATE SCHEMA " + DATASET, null);
        service.query(PROJECT, null, null,
                "CREATE TABLE " + DATASET + ".events (d DATE, value INT64) PARTITION BY d", null);
        service.appendRows(PROJECT, DATASET, "events", List.of(Map.of("d", "2026-10-21", "value", 7)));

        GcpException changed = assertThrows(GcpException.class, () -> service.query(PROJECT, null, null,
                "CREATE OR REPLACE TABLE " + DATASET
                        + ".events (ts TIMESTAMP, value INT64) PARTITION BY TIMESTAMP_TRUNC(ts, HOUR)", null));

        assertEquals("invalidQuery", changed.getReason());
        assertEquals("d", ((Map<?, ?>) service.getTable(PROJECT, DATASET, "events")
                .getExtra().get("timePartitioning")).get("field"));
        assertEquals(1, service.listTableData(PROJECT, DATASET, "events").rows().size());
    }

    @Test
    void sqlCopyOptionsOverrideInheritedMetadataAndDatasetDefaults() {
        Dataset dataset = newDataset(DATASET);
        dataset.setExtra("defaultTableExpirationMs", "7200000");
        service.createDataset(PROJECT, dataset);
        Table source = newTable(DATASET, "source");
        source.setDescription("original description");
        source.setFriendlyName("original name");
        source.setLabels(Map.of("old", "label"));
        service.createTable(PROJECT, DATASET, source);
        service.appendRows(PROJECT, DATASET, "source", List.of(Map.of("name", "copied", "age", 1)));
        String sql = "CREATE TABLE " + DATASET + ".destination COPY " + DATASET + ".source"
                + " OPTIONS(description='new description', friendly_name='new name', labels=[('new', 'label')],"
                + " expiration_timestamp=TIMESTAMP '2099-01-02 03:04:05 UTC')";

        StoredJob dryRun = service.query(PROJECT, "US", null,
                new BigQueryService.QueryOptions(sql, null, List.of(), null, true, false));
        assertFalse(dryRun.failed());
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getTable(PROJECT, DATASET, "destination")).getGcpStatus());

        StoredJob copied = service.query(PROJECT, null, null, sql, null);
        assertEquals("CREATE", copied.getDdlOperationPerformed());
        Table destination = service.getTable(PROJECT, DATASET, "destination");
        assertEquals("new description", destination.getDescription());
        assertEquals("new name", destination.getFriendlyName());
        assertEquals(Map.of("new", "label"), destination.getLabels());
        assertEquals(String.valueOf(Instant.parse("2099-01-02T03:04:05Z").toEpochMilli()),
                destination.getExtra().get("expirationTime"));
        assertEquals(service.stagingRows(PROJECT, DATASET, "source"),
                service.stagingRows(PROJECT, DATASET, "destination"));
        assertEquals("original description", service.getTable(PROJECT, DATASET, "source").getDescription());
        assertEquals(Map.of("old", "label"), service.getTable(PROJECT, DATASET, "source").getLabels());

        service.query(PROJECT, null, null, "CREATE OR REPLACE TABLE " + DATASET + ".destination COPY "
                + DATASET + ".source OPTIONS(description=NULL, labels=NULL, expiration_timestamp=NULL)", null);
        Table replaced = service.getTable(PROJECT, DATASET, "destination");
        assertNull(replaced.getDescription());
        assertNull(replaced.getLabels());
        assertFalse(replaced.getExtra().containsKey("expirationTime"));
        assertEquals("original name", replaced.getFriendlyName());

        StoredJob skipped = service.query(PROJECT, null, null, "CREATE TABLE IF NOT EXISTS " + DATASET
                + ".destination COPY " + DATASET + ".source OPTIONS(description='ignored')", null);
        assertEquals("SKIP", skipped.getDdlOperationPerformed());
        assertNull(service.getTable(PROJECT, DATASET, "destination").getDescription());
    }

    @Test
    void sqlCopyRejectsInvalidOptionsWithoutReplacingDestination() {
        seedTwoRows();
        for (String option : List.of("description=1", "expiration_timestamp='nope'", "unknown_option='x'",
                "require_partition_filter=true")) {
            String sql = "CREATE OR REPLACE TABLE " + DATASET + "." + TABLE + " COPY "
                    + DATASET + "." + TABLE + " OPTIONS(" + option + ")";
            assertEquals("invalidQuery", assertThrows(GcpException.class,
                    () -> service.query(PROJECT, null, null, sql, null)).getReason());
            assertEquals("invalidQuery", assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                    new BigQueryService.QueryOptions(sql, null, List.of(), null, true, false))).getReason());
            assertEquals(2, service.listTableData(PROJECT, DATASET, TABLE).rows().size());
        }
    }

    @Test
    void createOrReplaceCopyCannotChangePartitioning() {
        service.query(PROJECT, null, null, "CREATE SCHEMA " + DATASET, null);
        service.query(PROJECT, null, null,
                "CREATE TABLE " + DATASET + ".source (ts TIMESTAMP, value INT64)"
                        + " PARTITION BY TIMESTAMP_TRUNC(ts, HOUR)", null);
        service.query(PROJECT, null, null,
                "CREATE TABLE " + DATASET + ".destination (d DATE, value INT64) PARTITION BY d", null);

        String sql = "CREATE OR REPLACE TABLE " + DATASET + ".destination COPY " + DATASET + ".source";
        GcpException dryRunChanged = assertThrows(GcpException.class, () -> service.query(PROJECT, "US", null,
                new BigQueryService.QueryOptions(sql, null, List.of(), null, true, false)));
        GcpException changed = assertThrows(GcpException.class,
                () -> service.query(PROJECT, null, null, sql, null));

        assertEquals("invalidQuery", dryRunChanged.getReason());
        assertEquals("invalidQuery", changed.getReason());
        assertEquals("d", ((Map<?, ?>) service.getTable(PROJECT, DATASET, "destination")
                .getExtra().get("timePartitioning")).get("field"));
    }

    @Test
    void createTablePartitionExpressionsMustMatchTheFieldType() {
        service.query(PROJECT, null, null, "CREATE SCHEMA " + DATASET, null);

        assertEquals("invalidQuery", assertThrows(GcpException.class, () -> service.query(PROJECT, null, null,
                "CREATE TABLE " + DATASET + ".bare_timestamp (ts TIMESTAMP) PARTITION BY ts", null)).getReason());
        assertEquals("invalidQuery", assertThrows(GcpException.class, () -> service.query(PROJECT, null, null,
                "CREATE TABLE " + DATASET
                        + ".wrong_timestamp (dt DATETIME) PARTITION BY TIMESTAMP_TRUNC(dt, DAY)", null)).getReason());
        assertEquals("invalidQuery", assertThrows(GcpException.class, () -> service.query(PROJECT, null, null,
                "CREATE TABLE " + DATASET
                        + ".wrong_datetime (ts TIMESTAMP) PARTITION BY DATETIME_TRUNC(ts, DAY)", null)).getReason());
        assertEquals("invalidQuery", assertThrows(GcpException.class, () -> service.query(PROJECT, null, null,
                "CREATE TABLE " + DATASET + ".daily_date (d DATE) PARTITION BY DATE_TRUNC(d, DAY)", null))
                .getReason());

        assertEquals("CREATE", service.query(PROJECT, null, null,
                "CREATE TABLE " + DATASET + ".by_date (d DATE) PARTITION BY d", null).getDdlOperationPerformed());
        assertEquals("CREATE", service.query(PROJECT, null, null,
                "CREATE TABLE " + DATASET
                        + ".by_datetime (dt DATETIME) PARTITION BY DATETIME_TRUNC(dt, MONTH)", null)
                .getDdlOperationPerformed());
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
        Map<String, Object> config = new LinkedHashMap<>();
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
        assertEquals(Map.of("copiedRows", "2"), created.getCopyStatistics());
        assertEquals(created.getCopyStatistics(), service.getJob(PROJECT, "copy-create").getCopyStatistics());
        assertEquals(2, service.listTableData(PROJECT, DATASET, "copied").rows().size());

        StoredJob occupied = service.copy(PROJECT, null, null, copyConfig(TABLE, "copied"));
        assertEquals("duplicate", occupied.getErrorReason());
        assertNull(occupied.getCopyStatistics());

        StoredJob appended = service.copy(PROJECT, null, null,
                copyConfig(TABLE, "copied", "writeDisposition", "WRITE_APPEND"));
        assertFalse(appended.failed(), appended.getErrorMessage());
        assertEquals(4, service.listTableData(PROJECT, DATASET, "copied").rows().size());
        assertEquals(Map.of("copiedRows", "2"), appended.getCopyStatistics());

        StoredJob truncated = service.copy(PROJECT, null, null,
                copyConfig(TABLE, "copied", "writeDisposition", "WRITE_TRUNCATE"));
        assertFalse(truncated.failed(), truncated.getErrorMessage());
        assertEquals(Map.of("copiedRows", "2"), truncated.getCopyStatistics());
        assertEquals(2, service.listTableData(PROJECT, DATASET, "copied").rows().size());

        StoredJob missing = service.copy(PROJECT, null, null,
                copyConfig(TABLE, "missing", "createDisposition", "CREATE_NEVER"));
        assertEquals("notFound", missing.getErrorReason());
    }

    @Test
    void copyJobReportsZeroCopiedRowsForEmptySource() {
        seedTable();
        StoredJob copied = service.copy(PROJECT, null, null, copyConfig(TABLE, "empty_copy"));
        assertFalse(copied.failed(), copied.getErrorMessage());
        assertEquals(Map.of("copiedRows", "0"), copied.getCopyStatistics());
    }

    @Test
    void copyJobAcceptsUnspecifiedOperationType() {
        seedTwoRows();
        StoredJob copied = service.copy(PROJECT, null, null,
                copyConfig(TABLE, "copied", "operationType", "OPERATION_TYPE_UNSPECIFIED"));
        assertFalse(copied.failed(), copied.getErrorMessage());
        assertEquals(Map.of("copiedRows", "2"), copied.getCopyStatistics());
    }

    @Test
    void malformedCopyReferencesAreRejectedBeforeReservingAJobId() {
        seedTwoRows();
        List<Map<String, Object>> invalidConfigs = List.of(
                copyConfig(TABLE, "copied", "sourceTable", 5),
                copyConfig(TABLE, "copied", "sourceTable", Map.of("datasetId", DATASET, "tableId", 5)),
                copyConfig(TABLE, "copied", "sourceTable", Map.of("datasetId", 5, "tableId", TABLE)),
                copyConfig(TABLE, "copied", "sourceTable", Map.of("datasetId", DATASET)),
                copyConfig(TABLE, "copied", "sourceTable", Map.of("datasetId", DATASET, "tableId", "")),
                copyConfig(TABLE, "copied", "sourceTable", Map.of("projectId", 5,
                        "datasetId", DATASET, "tableId", TABLE)),
                copyConfig(TABLE, "copied", "destinationTable", Map.of("datasetId", DATASET, "tableId", 5)),
                Map.of("sourceTables", List.of(Map.of("datasetId", DATASET, "tableId", 5)),
                        "destinationTable", Map.of("datasetId", DATASET, "tableId", "copied")),
                Map.of("sourceTables", "not an array",
                        "destinationTable", Map.of("datasetId", DATASET, "tableId", "copied")));
        for (Map<String, Object> config : invalidConfigs) {
            GcpException error = assertThrows(GcpException.class,
                    () -> service.copy(PROJECT, null, "reusable-copy-id", config));
            assertEquals("invalid", error.getReason());
            assertTrue(service.listJobs(PROJECT).isEmpty());
        }
        StoredJob copied = service.copy(PROJECT, null, "reusable-copy-id", copyConfig(TABLE, "copied"));
        assertFalse(copied.failed(), copied.getErrorMessage());
    }

    @Test
    void malformedCopyExpirationIsRejectedForNewAndExistingDestinationsBeforeReservingAJobId() {
        seedTwoRows();
        for (String destination : List.of("new_destination", TABLE)) {
            for (Object expiration : List.of("nope", 5, "2099-01-01")) {
                GcpException error = assertThrows(GcpException.class, () -> service.copy(PROJECT, null,
                        "reusable-copy-id", copyConfig(TABLE, destination,
                                "destinationExpirationTime", expiration, "writeDisposition", "WRITE_TRUNCATE")));
                assertEquals("invalid", error.getReason());
                assertTrue(service.listJobs(PROJECT).isEmpty());
                assertEquals(2, service.listTableData(PROJECT, DATASET, TABLE).rows().size());
            }
        }
        StoredJob copied = service.copy(PROJECT, null, "reusable-copy-id", copyConfig(TABLE, "new_destination"));
        assertFalse(copied.failed(), copied.getErrorMessage());
    }

    @Test
    void copyJobRejectsSnapshotOperationType() {
        seedTwoRows();

        GcpException error = assertThrows(GcpException.class, () -> service.copy(PROJECT, null, null,
                copyConfig(TABLE, "snapshot", "operationType", "SNAPSHOT")));

        assertEquals("INVALID_ARGUMENT", error.getGcpStatus());
        assertEquals("invalid", error.getReason());
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getTable(PROJECT, DATASET, "snapshot")).getGcpStatus());
    }

    @Test
    void copyJobAcceptsExplicitCopyOperationType() {
        seedTwoRows();

        StoredJob copied = service.copy(PROJECT, null, null,
                copyConfig(TABLE, "copied", "operationType", "COPY"));

        assertFalse(copied.failed(), copied.getErrorMessage());
        assertEquals("COPY", copied.getJobType());
        assertEquals(service.stagingRows(PROJECT, DATASET, TABLE),
                service.stagingRows(PROJECT, DATASET, "copied"));
    }

    @Test
    void copyJobAppliesExplicitDestinationExpirationInsteadOfDatasetDefault() {
        Dataset dataset = newDataset(DATASET);
        dataset.setExtra("defaultTableExpirationMs", "7200000");
        service.createDataset(PROJECT, dataset);
        service.createTable(PROJECT, DATASET, newTable(DATASET, "source"));
        service.appendRows(PROJECT, DATASET, "source", List.of(Map.of("name", "copied", "age", 1)));
        Instant expiration = Instant.now().plusSeconds(86_400).truncatedTo(ChronoUnit.MILLIS);

        StoredJob copied = service.copy(PROJECT, null, null,
                copyConfig("source", "destination", "destinationExpirationTime", expiration.toString()));

        assertFalse(copied.failed(), copied.getErrorMessage());
        assertEquals(String.valueOf(expiration.toEpochMilli()),
                service.getTable(PROJECT, DATASET, "destination").getExtra().get("expirationTime"));
        assertEquals(service.stagingRows(PROJECT, DATASET, "source"),
                service.stagingRows(PROJECT, DATASET, "destination"));
    }

    @Test
    void copyExpirationValidationAcceptsRfc3339OffsetsAndFractions() {
        seedTwoRows();
        List<String> timestamps = List.of("2099-01-01T00:00:00Z", "2099-01-01T01:00:00+01:00",
                "2099-01-01T00:00:00.123456Z");
        for (int i = 0; i < timestamps.size(); i++) {
            String timestamp = timestamps.get(i);
            String destination = "expiry_copy_" + i;
            StoredJob copied = service.copy(PROJECT, null, null,
                    copyConfig(TABLE, destination, "destinationExpirationTime", timestamp));
            assertFalse(copied.failed(), copied.getErrorMessage());
            assertEquals(String.valueOf(Instant.parse(timestamp).toEpochMilli()),
                    service.getTable(PROJECT, DATASET, destination).getExtra().get("expirationTime"));
            assertEquals(timestamp, copied.getCopyConfiguration().get("destinationExpirationTime"));
        }
    }

    @Test
    void copyDestinationsUseDatasetExpirationInsteadOfSourceExpiration() {
        Dataset dataset = newDataset(DATASET);
        dataset.setExtra("defaultTableExpirationMs", "7200000");
        service.createDataset(PROJECT, dataset);
        Table source = newTable(DATASET, "source");
        source.setExtra("expirationTime", String.valueOf(System.currentTimeMillis() + 86_400_000));
        service.createTable(PROJECT, DATASET, source);

        long beforeCopy = System.currentTimeMillis();
        StoredJob copyJob = service.copy(PROJECT, null, null, copyConfig("source", "job_copy"));
        long jobExpiration = Long.parseLong((String) service.getTable(PROJECT, DATASET, "job_copy")
                .getExtra().get("expirationTime"));
        StoredJob ddlJob = service.query(PROJECT, null, null,
                "CREATE TABLE " + DATASET + ".sql_copy COPY " + DATASET + ".source", null);
        long ddlExpiration = Long.parseLong((String) service.getTable(PROJECT, DATASET, "sql_copy")
                .getExtra().get("expirationTime"));

        assertFalse(copyJob.failed(), copyJob.getErrorMessage());
        assertFalse(ddlJob.failed(), ddlJob.getErrorMessage());
        assertTrue(jobExpiration >= beforeCopy + 7_200_000L);
        assertTrue(jobExpiration <= System.currentTimeMillis() + 7_200_000L);
        assertTrue(ddlExpiration >= beforeCopy + 7_200_000L);
        assertTrue(ddlExpiration <= System.currentTimeMillis() + 7_200_000L);
    }

    @Test
    void copyRecreatesExpiredDestinationBeforeDispositionChecks() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table source = newTable(DATASET, "source");
        source.getExtra().put("timePartitioning", Map.of("type", "DAY"));
        service.createTable(PROJECT, DATASET, source);
        service.appendRows(PROJECT, DATASET, "source", List.of(Map.of("name", "copied", "age", 1)));
        Table destination = newTable(DATASET, "destination");
        destination.getExtra().put("timePartitioning", Map.of("type", "DAY"));
        destination.getExtra().put("expirationTime", String.valueOf(System.currentTimeMillis() - 1));
        service.createTable(PROJECT, DATASET, destination);

        StoredJob copied = service.copy(PROJECT, null, null,
                copyConfig("source", "destination", "writeDisposition", "WRITE_TRUNCATE"));

        assertFalse(copied.failed(), copied.getErrorMessage());
        assertEquals(1, service.listTableData(PROJECT, DATASET, "destination").rows().size());
        assertFalse(service.getTable(PROJECT, DATASET, "destination").getExtra().containsKey("expirationTime"));
    }

    @Test
    void copyAppendRejectsDifferentDestinationSchema() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table source = newTable(DATASET, "source");
        service.createTable(PROJECT, DATASET, source);
        Table destination = newTable(DATASET, "destination");
        TableFieldSchema name = new TableFieldSchema();
        name.setName("name");
        name.setType("INTEGER");
        TableFieldSchema age = new TableFieldSchema();
        age.setName("age");
        age.setType("INTEGER");
        destination.setSchema(new TableSchema(List.of(name, age)));
        service.createTable(PROJECT, DATASET, destination);

        StoredJob copied = service.copy(PROJECT, null, null,
                copyConfig("source", "destination", "writeDisposition", "WRITE_APPEND"));

        assertTrue(copied.failed());
        assertEquals(0, service.listTableData(PROJECT, DATASET, "destination").rows().size());
    }

    @Test
    void copyWriteEmptyRejectsDifferentDestinationSchemaEvenWhenEmpty() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table source = newTable(DATASET, "source");
        source.setSchema(new TableSchema(List.of(field("id", "STRING", null))));
        service.createTable(PROJECT, DATASET, source);
        service.appendRows(PROJECT, DATASET, "source", List.of(Map.of("id", "123")));
        Table destination = newTable(DATASET, "destination");
        destination.setSchema(new TableSchema(List.of(field("id", "INTEGER", null))));
        service.createTable(PROJECT, DATASET, destination);

        StoredJob copied = service.copy(PROJECT, null, null,
                copyConfig("source", "destination", "writeDisposition", "WRITE_EMPTY"));

        assertTrue(copied.failed());
        assertEquals("invalid", copied.getErrorReason());
        assertTrue(copied.getErrorMessage().contains("Provided Schema does not match Table"),
                copied.getErrorMessage());
        assertEquals("INTEGER", service.getTable(PROJECT, DATASET, "destination")
                .getSchema().getFields().getFirst().getType());
        assertEquals(0, service.listTableData(PROJECT, DATASET, "destination").rows().size());
    }

    @Test
    void copyAppendAllowsNullableDestinationOnlyFields() {
        seedTwoRows();
        Table source = service.getTable(PROJECT, DATASET, TABLE);
        Table destination = newTable(DATASET, "destination_with_optional_field");
        List<TableFieldSchema> destinationFields = new ArrayList<>(source.getSchema().getFields());
        TableFieldSchema optional = new TableFieldSchema();
        optional.setName("optional");
        optional.setType("STRING");
        destinationFields.add(optional);
        destination.setSchema(new TableSchema(destinationFields));
        service.createTable(PROJECT, DATASET, destination);

        StoredJob copied = service.copy(PROJECT, null, null,
                copyConfig(TABLE, "destination_with_optional_field", "writeDisposition", "WRITE_APPEND"));

        assertFalse(copied.failed(), copied.getErrorMessage());
        assertEquals(2, service.listTableData(PROJECT, DATASET, "destination_with_optional_field").rows().size());
        assertNull(service.listTableData(PROJECT, DATASET, "destination_with_optional_field").rows().getFirst()
                .getF().getLast().getV());
    }

    @Test
    void copyWaitsForAnInFlightSourceMutationAndIncludesIt() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch allowWrite = new CountDownLatch(1);
        CountDownLatch copyStarted = new CountDownLatch(1);
        CountDownLatch copyFinished = new CountDownLatch(1);
        ArmableBlockingPutStorage<String, StoredTableData> blockingData =
                new ArmableBlockingPutStorage<>(writeStarted, allowWrite);
        BigQueryService concurrentService = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                blockingData, new InMemoryStorage<>());
        concurrentService.createDataset(PROJECT, newDataset(DATASET));
        concurrentService.createTable(PROJECT, DATASET, newTable(DATASET, TABLE));
        blockingData.blockNextPut();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> append = executor.submit(() -> concurrentService.appendRows(PROJECT, DATASET, TABLE,
                    List.of(Map.of("name", "in-flight", "age", 1))));
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS));
            Future<StoredJob> copy = executor.submit(() -> {
                copyStarted.countDown();
                StoredJob job = concurrentService.copy(PROJECT, null, null, copyConfig(TABLE, "copied"));
                copyFinished.countDown();
                return job;
            });
            assertTrue(copyStarted.await(5, TimeUnit.SECONDS));
            assertFalse(copyFinished.await(100, TimeUnit.MILLISECONDS),
                    "copy must wait for the source mutation that already holds writeLock");

            allowWrite.countDown();
            append.get(5, TimeUnit.SECONDS);
            StoredJob copied = copy.get(5, TimeUnit.SECONDS);
            assertFalse(copied.failed(), copied.getErrorMessage());
            assertEquals(Map.of("copiedRows", "1"), copied.getCopyStatistics());
        }

        assertEquals(1, concurrentService.listTableData(PROJECT, DATASET, "copied").rows().size());
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

        Map<String, Object> crossRegionAppend = new LinkedHashMap<>(crossRegion);
        crossRegionAppend.put("writeDisposition", "WRITE_APPEND");
        StoredJob unsupportedAppend = service.copy(PROJECT, null, null, crossRegionAppend);
        assertEquals("invalid", unsupportedAppend.getErrorReason());
    }

    @Test
    void sqlCopyJobStoresItsInferredLocation() {
        Dataset eu = newDataset("eu");
        eu.setLocation("EU");
        service.createDataset(PROJECT, eu);
        Table source = newTable("eu", "source");
        source.setDescription("source description");
        source.setFriendlyName("source friendly name");
        source.setLabels(Map.of("team", "analytics"));
        service.createTable(PROJECT, "eu", source);

        StoredJob copied = service.query(PROJECT, null, null, "CREATE TABLE eu.destination COPY eu.source", null);

        assertEquals("EU", copied.getLocation());
        assertEquals("EU", service.getJob(PROJECT, copied.getJobId(), "EU").getLocation());
        Table destination = service.getTable(PROJECT, "eu", "destination");
        assertEquals("source description", destination.getDescription());
        assertEquals("source friendly name", destination.getFriendlyName());
        assertEquals(Map.of("team", "analytics"), destination.getLabels());
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
        assertEquals(Map.of("copiedRows", "1"), first.getCopyStatistics());
        assertEquals("US", first.getLocation());
        assertEquals(1, service.listTableData(PROJECT, DATASET, "destination$20261014").rows().size());
        assertTrue(service.listTableData(PROJECT, DATASET, "destination$20261015").rows().isEmpty());

        StoredJob second = service.copy(PROJECT, null, null,
                copyConfig("source$20261015", "destination$20261015", "writeDisposition", "WRITE_APPEND"));
        assertFalse(second.failed(), second.getErrorMessage());
        assertEquals(Map.of("copiedRows", "1"), second.getCopyStatistics());
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
        assertEquals(Map.of("copiedRows", "1"), copied.getCopyStatistics());
        assertEquals(OptionalLong.of(1L),
                service.streamRowsApplied(PROJECT, DATASET, "copy_destination", stream));
    }

    @Test
    void partitionCopyAllowsDestinationDefaultPartitionExpiration() {
        String sourceDatasetId = DATASET + "_copy_source";
        String destinationDatasetId = DATASET + "_copy_destination";
        service.createDataset(PROJECT, newDataset(sourceDatasetId));
        Dataset destinationDataset = newDataset(destinationDatasetId);
        destinationDataset.setExtra("defaultPartitionExpirationMs", "7200000");
        service.createDataset(PROJECT, destinationDataset);

        Map<String, Object> load = loadConfig("source", "schema", Map.of("fields", List.of(
                Map.of("name", "d", "type", "DATE"), Map.of("name", "value", "type", "INT64"))),
                "timePartitioning", Map.of("type", "DAY", "field", "d"));
        load.put("destinationTable", Map.of("datasetId", sourceDatasetId, "tableId", "source"));
        service.load(PROJECT, null, null, load,
                ndjson("{\"d\": \"2026-10-15\", \"value\": 1}"));

        Map<String, Object> decoratedConfig = new LinkedHashMap<>();
        decoratedConfig.put("sourceTable", Map.of("datasetId", sourceDatasetId, "tableId", "source$20261015"));
        decoratedConfig.put("destinationTable", Map.of("datasetId", destinationDatasetId,
                "tableId", "destination$20261015"));
        StoredJob decoratedCopy = service.copy(PROJECT, null, null, decoratedConfig);

        StoredJob wholeTableAppend = service.copy(PROJECT, null, null,
                Map.of("sourceTable", Map.of("datasetId", sourceDatasetId, "tableId", "source"),
                        "destinationTable", Map.of("datasetId", destinationDatasetId, "tableId", "destination"),
                        "writeDisposition", "WRITE_APPEND"));

        assertFalse(decoratedCopy.failed(), decoratedCopy.getErrorMessage());
        assertEquals(Map.of("copiedRows", "1"), decoratedCopy.getCopyStatistics());
        assertFalse(service.listTableData(PROJECT, destinationDatasetId, "destination$20261015")
                .rows().isEmpty());
        assertFalse(wholeTableAppend.failed(), wholeTableAppend.getErrorMessage());
        assertEquals(Map.of("copiedRows", "1"), wholeTableAppend.getCopyStatistics());
        assertEquals(2, service.listTableData(PROJECT, destinationDatasetId, "destination").rows().size());
        assertEquals("7200000", ((Map<?, ?>) service.getTable(PROJECT, destinationDatasetId, "destination")
                .getExtra().get("timePartitioning")).get("expirationMs"));
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
    void copyJobAllowsMultipleSourcesWithDifferentPartitionExpirations() {
        service.createDataset(PROJECT, newDataset(DATASET));
        Table first = newTable(DATASET, "first");
        first.setExtra("timePartitioning", Map.of("type", "DAY", "expirationMs", "7200000"));
        service.createTable(PROJECT, DATASET, first);
        service.appendRows(PROJECT, DATASET, "first", List.of(Map.of("name", "first", "age", 1)));
        Table second = newTable(DATASET, "second");
        second.setExtra("timePartitioning", Map.of("type", "DAY", "expirationMs", "14400000"));
        service.createTable(PROJECT, DATASET, second);
        service.appendRows(PROJECT, DATASET, "second", List.of(Map.of("name", "second", "age", 2)));

        StoredJob copied = service.copy(PROJECT, null, null, Map.of(
                "sourceTables", List.of(
                        Map.of("datasetId", DATASET, "tableId", "first"),
                        Map.of("datasetId", DATASET, "tableId", "second")),
                "destinationTable", Map.of("datasetId", DATASET, "tableId", "combined")));

        assertFalse(copied.failed(), copied.getErrorMessage());
        List<Map<String, Object>> expected = new ArrayList<>(service.stagingRows(PROJECT, DATASET, "first"));
        expected.addAll(service.stagingRows(PROJECT, DATASET, "second"));
        assertEquals(expected, service.stagingRows(PROJECT, DATASET, "combined"));
    }

    @Test
    void copyJobSupportsMultipleSourcesAndRejectsMismatchedSchemas() {
        seedTwoRows();
        service.createTable(PROJECT, DATASET, newTable(DATASET, "second"));
        service.appendRows(PROJECT, DATASET, "second", List.of(Map.of("name", "cy", "age", 41)));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sourceTables", List.of(
                Map.of("datasetId", DATASET, "tableId", TABLE),
                Map.of("datasetId", DATASET, "tableId", "second")));
        config.put("destinationTable", Map.of("datasetId", DATASET, "tableId", "combined"));

        StoredJob combined = service.copy(PROJECT, null, null, config);
        assertFalse(combined.failed(), combined.getErrorMessage());
        assertEquals(Map.of("copiedRows", "3"), combined.getCopyStatistics());
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
                                "range", Map.of("start", 0.0, "end", 100.0, "interval", 10.0))),
                ndjson("{\"name\": \"good\", \"age\": 22}"));

        assertFalse(created.failed(), created.getErrorMessage());
        assertEquals(Map.of("field", "age", "range",
                        Map.of("start", "0", "end", "100", "interval", "10")),
                service.getTable(PROJECT, DATASET, "events").getExtra().get("rangePartitioning"));
        assertEquals(1, service.listTableData(PROJECT, DATASET, "events$20").rows().size());
    }

    @Test
    void invalidLoadPartitioningDoesNotCreateTable() {
        service.createDataset(PROJECT, newDataset(DATASET));

        StoredJob malformed = service.load(PROJECT, null, null,
                loadConfig("bad_shape", "schema", PEOPLE_SCHEMA, "timePartitioning", "DAY"),
                ndjson("{\"name\": \"bad\", \"age\": 1}"));
        StoredJob incompleteRange = service.load(PROJECT, null, null,
                loadConfig("bad_range", "schema", PEOPLE_SCHEMA,
                        "rangePartitioning", Map.of("field", "age")),
                ndjson("{\"name\": \"bad\", \"age\": 1}"));

        assertEquals("invalid", malformed.getErrorReason());
        assertEquals("invalid", incompleteRange.getErrorReason());
        assertThrows(GcpException.class, () -> service.getTable(PROJECT, DATASET, "bad_shape"));
        assertThrows(GcpException.class, () -> service.getTable(PROJECT, DATASET, "bad_range"));
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

    private static final class ArmableBlockingPutStorage<K, V> extends InMemoryStorage<K, V> {
        private final CountDownLatch putStarted;
        private final CountDownLatch allowPut;
        private final AtomicBoolean blockNextPut = new AtomicBoolean();

        private ArmableBlockingPutStorage(CountDownLatch putStarted, CountDownLatch allowPut) {
            this.putStarted = putStarted;
            this.allowPut = allowPut;
        }

        private void blockNextPut() {
            blockNextPut.set(true);
        }

        @Override
        public void put(K key, V value) {
            if (blockNextPut.compareAndSet(true, false)) {
                putStarted.countDown();
                try {
                    if (!allowPut.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to publish BigQuery table data");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while publishing BigQuery table data", e);
                }
            }
            super.put(key, value);
        }
    }
}

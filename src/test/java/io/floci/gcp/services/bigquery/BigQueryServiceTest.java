package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.bigquery.model.Dataset;
import io.floci.gcp.services.bigquery.model.DatasetAccessEntry;
import io.floci.gcp.services.bigquery.model.DatasetAccessEntryTarget;
import io.floci.gcp.services.bigquery.model.RoutineReference;
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

    // ── Dataset access[] ──
    //
    // Storage fidelity only: entries round-trip so clients and the
    // hashicorp/google Terraform provider see a stable resource. Nothing here
    // evaluates them, and a read is still allowed regardless of what is granted.

    @Test
    void createDatasetPreservesAccessEntries() {
        Dataset d = newDataset(DATASET);
        d.setAccess(List.of(
                accessEntry("READER", "analyst@example.com"),
                accessEntry("OWNER", "owner@example.com")));

        Dataset created = service.createDataset(PROJECT, d);

        assertEquals(2, created.getAccess().size());
        assertEquals("READER", created.getAccess().get(0).getRole());
        assertEquals("analyst@example.com", created.getAccess().get(0).getUserByEmail());
        assertEquals("owner@example.com", created.getAccess().get(1).getUserByEmail());
        // and it survives a fresh read, not just the create response
        assertEquals(2, service.getDataset(PROJECT, DATASET).getAccess().size());
    }

    @Test
    void patchDatasetPreservesAccessWhenTheBodyOmitsIt() {
        Dataset d = newDataset(DATASET);
        d.setAccess(List.of(accessEntry("READER", "analyst@example.com")));
        service.createDataset(PROJECT, d);

        Dataset patch = new Dataset();
        patch.setFriendlyName("patched");
        Dataset patched = service.patchDataset(PROJECT, DATASET, patch);

        assertEquals("patched", patched.getFriendlyName());
        assertEquals(1, patched.getAccess().size(),
                "PATCH must not drop access[], which is what makes a Terraform "
                        + "update show a permanent diff");
    }

    @Test
    void patchDatasetReplacesAccessWhenTheBodyCarriesIt() {
        Dataset d = newDataset(DATASET);
        d.setAccess(List.of(accessEntry("READER", "old@example.com")));
        service.createDataset(PROJECT, d);

        Dataset patch = new Dataset();
        patch.setAccess(List.of(accessEntry("WRITER", "new@example.com")));
        Dataset patched = service.patchDataset(PROJECT, DATASET, patch);

        assertEquals(1, patched.getAccess().size());
        assertEquals("WRITER", patched.getAccess().get(0).getRole());
        assertEquals("new@example.com", patched.getAccess().get(0).getUserByEmail());
    }

    @Test
    void updateDatasetClearsAccessWhenTheBodyOmitsIt() {
        Dataset d = newDataset(DATASET);
        d.setAccess(List.of(accessEntry("READER", "analyst@example.com")));
        service.createDataset(PROJECT, d);

        // PUT is a full replacement, so an omitted field is a cleared field,
        // the same contract updateDatasetReplacesEntireResource pins for
        // description.
        Dataset replacement = new Dataset();
        replacement.setFriendlyName("new name");
        Dataset updated = service.updateDataset(PROJECT, DATASET, replacement);

        assertNull(updated.getAccess());
    }

    @Test
    void accessEntryCarriesEveryPrincipalKind() {
        DatasetAccessEntry group = new DatasetAccessEntry();
        group.setRole("READER");
        group.setGroupByEmail("team@example.com");

        DatasetAccessEntry domain = new DatasetAccessEntry();
        domain.setRole("READER");
        domain.setDomain("example.com");

        DatasetAccessEntry special = new DatasetAccessEntry();
        special.setRole("WRITER");
        special.setSpecialGroup("projectWriters");

        DatasetAccessEntry iam = new DatasetAccessEntry();
        iam.setRole("READER");
        iam.setIamMember("serviceAccount:svc@example.iam.gserviceaccount.com");

        Dataset d = newDataset(DATASET);
        d.setAccess(List.of(group, domain, special, iam));
        List<DatasetAccessEntry> stored = service.createDataset(PROJECT, d).getAccess();

        assertEquals("team@example.com", stored.get(0).getGroupByEmail());
        assertEquals("example.com", stored.get(1).getDomain());
        assertEquals("projectWriters", stored.get(2).getSpecialGroup());
        assertEquals("serviceAccount:svc@example.iam.gserviceaccount.com",
                stored.get(3).getIamMember());
    }

    // ---- updateMode ----
    //
    // Without it, every write covers metadata AND the ACL, so a client updating
    // only friendlyName has to resend access[] or lose it. That is the whole
    // reason the parameter exists, and it only bites once access[] is modelled.

    @Test
    void updateWithMetadataModeLeavesTheAclAlone() {
        Dataset d = newDataset(DATASET);
        d.setAccess(List.of(accessEntry("READER", "analyst@example.com")));
        d.setDescription("original");
        service.createDataset(PROJECT, d);

        Dataset replacement = new Dataset();
        replacement.setFriendlyName("metadata only");
        Dataset updated = service.updateDataset(
                PROJECT, DATASET, replacement, UpdateMode.UPDATE_METADATA);

        assertEquals("metadata only", updated.getFriendlyName());
        assertNull(updated.getDescription(), "metadata is still fully replaced");
        assertEquals(1, updated.getAccess().size(), "the ACL must survive untouched");
    }

    @Test
    void updateWithAclModeLeavesMetadataAlone() {
        Dataset d = newDataset(DATASET);
        d.setAccess(List.of(accessEntry("READER", "old@example.com")));
        d.setDescription("keep me");
        service.createDataset(PROJECT, d);

        Dataset replacement = new Dataset();
        replacement.setAccess(List.of(accessEntry("OWNER", "new@example.com")));
        Dataset updated = service.updateDataset(PROJECT, DATASET, replacement, UpdateMode.UPDATE_ACL);

        assertEquals("keep me", updated.getDescription(), "metadata must survive untouched");
        assertEquals("OWNER", updated.getAccess().get(0).getRole());
    }

    @Test
    void patchWithMetadataModeIgnoresAccessInTheBody() {
        Dataset d = newDataset(DATASET);
        d.setAccess(List.of(accessEntry("READER", "keep@example.com")));
        service.createDataset(PROJECT, d);

        Dataset patch = new Dataset();
        patch.setFriendlyName("patched");
        patch.setAccess(List.of(accessEntry("OWNER", "ignored@example.com")));
        Dataset patched = service.patchDataset(
                PROJECT, DATASET, patch, UpdateMode.UPDATE_METADATA);

        assertEquals("patched", patched.getFriendlyName());
        assertEquals("keep@example.com", patched.getAccess().get(0).getUserByEmail());
    }

    @Test
    void patchWithAclModeIgnoresMetadataInTheBody() {
        Dataset d = newDataset(DATASET);
        d.setFriendlyName("keep me");
        d.setAccess(List.of(accessEntry("READER", "old@example.com")));
        service.createDataset(PROJECT, d);

        Dataset patch = new Dataset();
        patch.setFriendlyName("ignored");
        patch.setAccess(List.of(accessEntry("WRITER", "new@example.com")));
        Dataset patched = service.patchDataset(PROJECT, DATASET, patch, UpdateMode.UPDATE_ACL);

        assertEquals("keep me", patched.getFriendlyName());
        assertEquals("WRITER", patched.getAccess().get(0).getRole());
    }

    @Test
    void updateModeDefaultsToFullAndRejectsAnUnknownValue() {
        assertEquals(UpdateMode.UPDATE_FULL, UpdateMode.from(null));
        assertEquals(UpdateMode.UPDATE_FULL, UpdateMode.from(""));
        assertEquals(UpdateMode.UPDATE_FULL, UpdateMode.from("UPDATE_MODE_UNSPECIFIED"));
        assertEquals(UpdateMode.UPDATE_ACL, UpdateMode.from("update_acl"));
        GcpException ex = assertThrows(GcpException.class, () -> UpdateMode.from("UPDATE_SOMETHING"));
        assertEquals("invalid", ex.getReason());
    }

    @Test
    void nestedAccessVariantsRoundTrip() {
        DatasetAccessEntry view = new DatasetAccessEntry();
        view.setView(new TableReference(PROJECT, "other_ds", "auth_view"));

        DatasetAccessEntry routine = new DatasetAccessEntry();
        routine.setRoutine(new RoutineReference(PROJECT, "other_ds", "auth_routine"));

        DatasetAccessEntryTarget target = new DatasetAccessEntryTarget();
        target.setDataset(new DatasetReference(PROJECT, "linked_ds"));
        target.setTargetTypes(List.of("VIEWS"));
        DatasetAccessEntry linked = new DatasetAccessEntry();
        linked.setDataset(target);

        Dataset d = newDataset(DATASET);
        d.setAccess(List.of(view, routine, linked));
        service.createDataset(PROJECT, d);

        List<DatasetAccessEntry> stored = service.getDataset(PROJECT, DATASET).getAccess();
        assertEquals("auth_view", stored.get(0).getView().getTableId());
        assertEquals("auth_routine", stored.get(1).getRoutine().getRoutineId());
        assertEquals("linked_ds", stored.get(2).getDataset().getDataset().getDatasetId());
        assertEquals(List.of("VIEWS"), stored.get(2).getDataset().getTargetTypes());
    }

    private static DatasetAccessEntry accessEntry(String role, String userByEmail) {
        DatasetAccessEntry e = new DatasetAccessEntry();
        e.setRole(role);
        e.setUserByEmail(userByEmail);
        return e;
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
}

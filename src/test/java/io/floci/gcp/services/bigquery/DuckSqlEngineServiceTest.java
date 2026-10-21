package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.floci.gcp.services.bigquery.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DuckSqlEngineServiceTest {

    @Test
    void callerSqlRunsAfterExternalAccessIsLocked() {
        String setup = setupForAViewQuery(false);
        int staging = setup.indexOf("read_json(");
        int lock = setup.indexOf(DuckSqlEngine.LOCK_EXTERNAL_ACCESS);
        int createView = setup.indexOf("CREATE VIEW");
        assertTrue(staging >= 0 && lock > staging, "tables are staged before external access is locked: " + setup);
        assertTrue(createView > lock, "views come from caller SQL, so they are created after the lock: " + setup);
    }

    @Test
    void dryRunCallerSqlRunsAfterExternalAccessIsLocked() {
        String setup = setupForAViewQuery(true);
        int staging = setup.indexOf("CREATE TABLE");
        int lock = setup.indexOf(DuckSqlEngine.LOCK_EXTERNAL_ACCESS);
        int createView = setup.indexOf("CREATE VIEW");
        assertTrue(staging >= 0 && lock > staging, "empty tables are created before external access is locked: " + setup);
        assertTrue(createView > lock, "views come from caller SQL, so they are created after the lock: " + setup);
    }

    /** The setup SQL sent to the sidecar for {@code SELECT a FROM ds.v}, where view {@code ds.v} reads table {@code ds.t}. */
    private static String setupForAViewQuery(boolean dryRun) {
        DuckClient client = mock(DuckClient.class);
        when(client.query(anyString(), anyString(), anyString(), isNull()))
                .thenReturn(new DuckClient.DuckResult(List.of(new DuckClient.DuckColumn("a", "BIGINT")),
                        List.of(Map.of("column_name", "a", "column_type", "BIGINT")), null));
        DuckSqlEngine engine = engine(client);

        BigQuerySqlEngine.Tables tables = mock(BigQuerySqlEngine.Tables.class);
        Table table = tableWithColumnA();
        when(tables.table("ds", "t")).thenReturn(table);
        when(tables.rows("ds", "t")).thenReturn(List.of(Map.of("a", 1)));
        Table view = tableWithColumnA();
        view.setViewDefinition(Map.of("query", "SELECT a FROM ds.t"));
        when(tables.table("ds", "v")).thenReturn(view);

        engine.execute(new BigQuerySqlEngine.Request("proj", "SELECT a FROM ds.v", "ds", List.of(), "NAMED", dryRun),
                tables);

        ArgumentCaptor<String> setupCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).query(anyString(), setupCaptor.capture(), anyString(), isNull());
        return setupCaptor.getValue();
    }

    private static Table tableWithColumnA() {
        Table table = new Table();
        TableFieldSchema field = new TableFieldSchema();
        field.setName("a");
        field.setType("INTEGER");
        table.setSchema(new TableSchema(List.of(field)));
        return table;
    }

    private static DuckSqlEngine engine(DuckClient client) {
        EmulatorConfig.BigQueryDuckConfig duck = mock(EmulatorConfig.BigQueryDuckConfig.class);
        when(duck.callbackUrl()).thenReturn(Optional.of("http://localhost"));
        EmulatorConfig.BigQueryServiceConfig bigquery = mock(EmulatorConfig.BigQueryServiceConfig.class);
        when(bigquery.duck()).thenReturn(duck);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(services.bigquery()).thenReturn(bigquery);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.services()).thenReturn(services);
        return new DuckSqlEngine(client, new DockerHostResolver(mock(ContainerDetector.class)), config,
                new ObjectMapper());
    }

    @Test
    void dryRunUsesEmptySetupWithoutReadJson() {
        DuckClient client = mock(DuckClient.class);
        when(client.query(anyString(), anyString(), anyString(), isNull())).thenReturn(new DuckClient.DuckResult(List.of(), List.of(), null));
        DuckSqlEngine engine = engine(client);

        BigQuerySqlEngine.Request request = new BigQuerySqlEngine.Request("proj", "SELECT * FROM ds.t", "ds", List.of(), "NAMED", true);
        BigQuerySqlEngine.Tables tables = mock(BigQuerySqlEngine.Tables.class);

        Table table = new Table();
        TableFieldSchema field = new TableFieldSchema();
        field.setName("a");
        field.setType("INTEGER");
        table.setSchema(new TableSchema(List.of(field)));
        when(tables.table("ds", "t")).thenReturn(table);
        when(tables.rows("ds", "t")).thenReturn(List.of(Map.of("a", 1)));

        engine.execute(request, tables);

        ArgumentCaptor<String> setupCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).query(anyString(), setupCaptor.capture(), anyString(), isNull());

        String setup = setupCaptor.getValue();
        assertTrue(setup.contains("CREATE TABLE"), "Should create staging table");
        assertFalse(setup.contains("read_json"), "Dry run should not fetch table data using read_json");
    }

    @Test
    void ingestionDmlMarksRowsThatWereOriginallyUnpartitioned() {
        DuckClient client = mock(DuckClient.class);
        DuckClient.DuckResult followup = new DuckClient.DuckResult(List.of(
                new DuckClient.DuckColumn("a", "BIGINT"),
                new DuckClient.DuckColumn(BigQueryService.DML_ORIGINAL_UNPARTITIONED, "BOOLEAN")),
                List.of(Map.of("a", 2, BigQueryService.DML_ORIGINAL_UNPARTITIONED, true)), null);
        when(client.query(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new DuckClient.DuckResult(List.of(new DuckClient.DuckColumn("Count", "BIGINT")),
                        List.of(Map.of("Count", 1)), followup));
        DuckSqlEngine engine = engine(client);
        BigQuerySqlEngine.Tables tables = mock(BigQuerySqlEngine.Tables.class);
        Table table = tableWithColumnA();
        table.setExtra("timePartitioning", Map.of("type", "DAY"));
        when(tables.table("ds", "t")).thenReturn(table);
        when(tables.rows("ds", "t")).thenReturn(List.of(Map.of("a", 1)));

        engine.executeDml(new BigQuerySqlEngine.Request("proj", "UPDATE ds.t SET a = 2 WHERE TRUE", "ds",
                List.of(), "NAMED", false), tables);

        ArgumentCaptor<String> setupCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).query(anyString(), setupCaptor.capture(), anyString(), anyString());
        String setup = setupCaptor.getValue();
        assertTrue(setup.contains("ADD COLUMN \"$floci_original_unpartitioned\" BOOLEAN"), setup);
        assertTrue(setup.contains("\"$floci_original_unpartitioned\" = \"_PARTITIONTIME\" IS NULL"), setup);
    }

    @Test
    void aResultArrayWithANullElementFailsTheQuery() {
        assertEquals("Array cannot have a null element; error in writing field a",
                nullElementError("INTEGER[]", Arrays.asList(1, null, 3)));
        assertEquals("Array cannot have a null element; error in writing field a",
                nullElementError("STRUCT(v INTEGER)[]", Arrays.asList(Map.of("v", 1), null)));
        assertEquals("Array cannot have a null element; error in writing field a.arr; error in writing field a",
                nullElementError("STRUCT(id INTEGER, arr INTEGER[])", Map.of("id", 1, "arr", Arrays.asList(1, null))));
        assertEquals("Array cannot have a null element; error in writing field a.nested.arr; error in writing field a.nested;"
                + " error in writing field a", nullElementError("STRUCT(nested STRUCT(arr INTEGER[]))[]",
                List.of(Map.of("nested", Map.of("arr", Arrays.asList(null, 2))))));
    }

    @Test
    void aNullFieldInsideAStructElementIsNotANullElement() {
        Map<String, Object> element = new HashMap<>();
        element.put("v", null);
        BigQuerySqlEngine.Result result = run("STRUCT(v INTEGER)[]", List.of(Map.of("v", 1), element));
        assertEquals(1, result.rows().size());
    }

    private static String nullElementError(String duckType, Object value) {
        GcpException e = assertThrows(GcpException.class, () -> run(duckType, value));
        assertEquals("invalidQuery", e.getReason());
        return e.getMessage();
    }

    private static BigQuerySqlEngine.Result run(String duckType, Object value) {
        DuckClient client = mock(DuckClient.class);
        Map<String, Object> row = new HashMap<>();
        row.put("a", value);
        when(client.query(anyString(), anyString(), anyString(), isNull())).thenReturn(new DuckClient.DuckResult(
                List.of(new DuckClient.DuckColumn("a", duckType)), List.of(row), null));
        BigQuerySqlEngine.Request request = new BigQuerySqlEngine.Request("proj", "SELECT 1 AS a", null, List.of(), "NAMED", false);
        return engine(client).execute(request, mock(BigQuerySqlEngine.Tables.class));
    }
}

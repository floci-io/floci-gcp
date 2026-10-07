package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.floci.gcp.services.bigquery.model.TableSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DuckSqlEngineServiceTest {

    @Test
    void callerSqlRunsAfterExternalAccessIsLocked() {
        DuckClient client = mock(DuckClient.class);
        when(client.query(anyString(), anyString(), anyString(), isNull()))
                .thenReturn(new DuckClient.DuckResult(List.of(new DuckClient.DuckColumn("a", "BIGINT")), List.of(), null));
        DuckSqlEngine engine = engine(client);

        BigQuerySqlEngine.Tables tables = mock(BigQuerySqlEngine.Tables.class);
        Table table = tableWithColumnA();
        when(tables.table("ds", "t")).thenReturn(table);
        when(tables.rows("ds", "t")).thenReturn(List.of(Map.of("a", 1)));
        Table view = tableWithColumnA();
        view.setViewDefinition(Map.of("query", "SELECT a FROM ds.t"));
        when(tables.table("ds", "v")).thenReturn(view);

        engine.execute(new BigQuerySqlEngine.Request("proj", "SELECT a FROM ds.v", "ds", List.of(), "NAMED", false),
                tables);

        ArgumentCaptor<String> setupCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).query(anyString(), setupCaptor.capture(), anyString(), isNull());
        String setup = setupCaptor.getValue();
        int staging = setup.indexOf("read_json(");
        int lock = setup.indexOf(DuckSqlEngine.LOCK_EXTERNAL_ACCESS);
        int createView = setup.indexOf("CREATE VIEW");
        assertTrue(staging >= 0 && lock > staging, "tables are staged before external access is locked: " + setup);
        assertTrue(createView > lock, "views come from caller SQL, so they are created after the lock: " + setup);
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

        EmulatorConfig.BigQueryDuckConfig duck = mock(EmulatorConfig.BigQueryDuckConfig.class);
        when(duck.callbackUrl()).thenReturn(Optional.of("http://localhost"));
        EmulatorConfig.BigQueryServiceConfig bigquery = mock(EmulatorConfig.BigQueryServiceConfig.class);
        when(bigquery.duck()).thenReturn(duck);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(services.bigquery()).thenReturn(bigquery);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.services()).thenReturn(services);

        DockerHostResolver resolver = new DockerHostResolver(mock(ContainerDetector.class));
        DuckSqlEngine engine = new DuckSqlEngine(client, resolver, config, new ObjectMapper());

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
}

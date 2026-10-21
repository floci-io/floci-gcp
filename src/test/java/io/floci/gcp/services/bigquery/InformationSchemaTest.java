package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.bigquery.model.Dataset;
import io.floci.gcp.services.bigquery.model.DatasetReference;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.floci.gcp.services.bigquery.model.TableReference;
import io.floci.gcp.services.bigquery.model.TableSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InformationSchemaTest {

    private BigQueryService service;

    private static TableFieldSchema field(String name, String type, String mode, TableFieldSchema... children) {
        TableFieldSchema f = new TableFieldSchema();
        f.setName(name);
        f.setType(type);
        f.setMode(mode);
        if (children.length > 0) {
            f.setFields(List.of(children));
        }
        return f;
    }

    @BeforeEach
    void setUp() {
        service = new BigQueryService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>());
        for (String[] ds : new String[][] {{"shop", null}, {"eu_data", "EU"}}) {
            Dataset dataset = new Dataset();
            dataset.setDatasetReference(new DatasetReference(null, ds[0]));
            dataset.setLocation(ds[1]);
            service.createDataset("p", dataset);
        }
        Table orders = new Table();
        orders.setTableReference(new TableReference(null, "shop", "orders"));
        orders.setDescription("all \"orders\"");
        orders.setLabels(Map.of("team", "data"));
        TableFieldSchema id = field("id", "INT64", "REQUIRED");
        id.setDescription("order id");
        orders.setSchema(new TableSchema(List.of(id, field("tags", "STRING", "REPEATED"),
                field("customer", "RECORD", "REPEATED", field("name", "STRING", null),
                        field("phones", "STRING", "REPEATED")))));
        service.createTable("p", "shop", orders);
        Table euTable = new Table();
        euTable.setTableReference(new TableReference(null, "eu_data", "eu_only"));
        euTable.setSchema(new TableSchema(List.of(field("x", "INTEGER", null))));
        service.createTable("p", "eu_data", euTable);
    }

    @Test
    void googleSqlTypeNames() {
        assertEquals("ARRAY<STRUCT<name STRING, phones ARRAY<STRING>>>", InformationSchema.dataType(
                service.getTable("p", "shop", "orders").getSchema().getFields().get(2)));
        assertEquals("FLOAT64", InformationSchema.dataType(field("f", "FLOAT", null)));
        assertEquals("BOOL", InformationSchema.dataType(field("b", "BOOLEAN", null)));
    }

    @Test
    void tableDdlFollowsTheDocumentedShape() {
        assertEquals("CREATE TABLE `p.shop.orders`\n(\n"
                        + "  id INT64 NOT NULL OPTIONS(description=\"order id\"),\n"
                        + "  tags ARRAY<STRING>,\n"
                        + "  customer ARRAY<STRUCT<name STRING, phones ARRAY<STRING>>>\n);",
                InformationSchema.tableDdl("p", "shop", service.getTable("p", "shop", "orders")));
    }

    @Test
    void tableDdlIncludesThePartitioningSpecification() {
        Table daily = table("daily", List.of(field("d", "DATE", null)),
                "timePartitioning", Map.of("type", "DAY", "field", "d"));
        Table monthly = table("monthly", List.of(field("d", "DATE", null)),
                "timePartitioning", Map.of("type", "MONTH", "field", "d"));
        Table ingestion = table("ingestion", List.of(field("id", "INTEGER", null)),
                "timePartitioning", Map.of("type", "HOUR"));
        Table ranged = table("ranged", List.of(field("id", "INTEGER", null)),
                "rangePartitioning", Map.of("field", "id",
                        "range", Map.of("start", "0", "end", "100", "interval", "10")));

        assertEquals("CREATE TABLE `p.shop.daily`\n(\n  d DATE\n)\nPARTITION BY d;",
                InformationSchema.tableDdl("p", "shop", daily));
        assertEquals("CREATE TABLE `p.shop.monthly`\n(\n  d DATE\n)\nPARTITION BY DATE_TRUNC(d, MONTH);",
                InformationSchema.tableDdl("p", "shop", monthly));
        assertEquals("CREATE TABLE `p.shop.ingestion`\n(\n  id INT64\n)\n"
                        + "PARTITION BY TIMESTAMP_TRUNC(_PARTITIONTIME, HOUR);",
                InformationSchema.tableDdl("p", "shop", ingestion));
        assertEquals("CREATE TABLE `p.shop.ranged`\n(\n  id INT64\n)\n"
                        + "PARTITION BY RANGE_BUCKET(id, GENERATE_ARRAY(0, 100, 10));",
                InformationSchema.tableDdl("p", "shop", ranged));
        SqlDialectTranslator.Statement replayed = SqlDialectTranslator.parseStatement(
                InformationSchema.tableDdl("p", "shop", ranged), "p", "shop");
        assertEquals(ranged.getExtra().get("rangePartitioning"), replayed.rangePartitioning());
    }

    @Test
    void columnsIdentifyPhysicalAndIngestionPartitionColumns() {
        Table physical = table("physical", List.of(field("d", "DATE", null)),
                "timePartitioning", Map.of("type", "DAY", "field", "d"));
        service.createTable("p", "shop", physical);
        Table ingestion = table("ingestion_columns", List.of(field("id", "INTEGER", null)),
                "timePartitioning", Map.of("type", "DAY"));
        service.createTable("p", "shop", ingestion);

        List<Map<String, Object>> rows = service.informationSchemaRows("p", "COLUMNS", "shop", null);
        Map<String, Object> d = rows.stream().filter(row -> "physical".equals(row.get("table_name")))
                .findFirst().orElseThrow();
        assertEquals("YES", d.get("is_partitioning_column"));
        List<Map<String, Object>> pseudo = rows.stream()
                .filter(row -> "ingestion_columns".equals(row.get("table_name")))
                .filter(row -> String.valueOf(row.get("column_name")).startsWith("_PARTITION")).toList();
        assertEquals(List.of("_PARTITIONTIME", "_PARTITIONDATE"),
                pseudo.stream().map(row -> row.get("column_name")).toList());
        for (Map<String, Object> row : pseudo) {
            assertNull(row.get("ordinal_position"));
            assertEquals("YES", row.get("is_hidden"));
            assertEquals("YES", row.get("is_system_defined"));
            assertEquals("YES", row.get("is_partitioning_column"));
        }
    }

    private static Table table(String name, List<TableFieldSchema> fields, String key, Object value) {
        Table table = new Table();
        table.setTableReference(new TableReference(null, "shop", name));
        table.setSchema(new TableSchema(fields));
        table.setExtra(key, value);
        return table;
    }

    @Test
    void columnFieldPathsFlattenNestedFields() {
        List<Map<String, Object>> rows = service.informationSchemaRows("p", "COLUMN_FIELD_PATHS", "shop", null);
        assertEquals(List.of("id", "tags", "customer", "customer.name", "customer.phones"),
                rows.stream().map(r -> r.get("field_path")).toList());
        assertEquals("ARRAY<STRING>", rows.get(4).get("data_type"));
        assertEquals("customer", rows.get(4).get("column_name"));
    }

    @Test
    void tableOptionsUseGoogleSqlLiterals() {
        List<Map<String, Object>> rows = service.informationSchemaRows("p", "TABLE_OPTIONS", "shop", null);
        assertEquals("\"all \\\"orders\\\"\"", rows.get(0).get("option_value"));
        assertEquals("[STRUCT(\"team\", \"data\")]", rows.get(1).get("option_value"));
    }

    @Test
    void regionScopeFiltersByDatasetLocation() {
        assertEquals(List.of("shop"), service.informationSchemaRows("p", "SCHEMATA", null, "us").stream()
                .map(r -> r.get("schema_name")).toList());
        assertEquals(List.of("eu_only"), service.informationSchemaRows("p", "TABLES", null, "eu").stream()
                .map(r -> r.get("table_name")).toList());
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.informationSchemaRows("p", "TABLES", "missing", null)).getGcpStatus());
    }
}

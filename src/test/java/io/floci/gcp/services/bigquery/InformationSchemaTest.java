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

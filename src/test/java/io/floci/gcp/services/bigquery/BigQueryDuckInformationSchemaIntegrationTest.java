package io.floci.gcp.services.bigquery;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INFORMATION_SCHEMA views end to end on the real floci-duck sidecar. Needs a floci-duck image
 * that reports column types, which the default image does, so this runs whenever Docker is
 * available. {@code -Dfloci.duck.image=...} selects another image.
 */
@QuarkusTest
@TestProfile(BigQueryDuckInformationSchemaIntegrationTest.SchemaProfile.class)
@EnabledIf("io.floci.gcp.services.bigquery.BigQueryDuckIntegrationTest#dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BigQueryDuckInformationSchemaIntegrationTest {

    private static final String PROJECT = "bq-is-it";
    private static final String BASE = "/bigquery/v2/projects/" + PROJECT;

    public static class SchemaProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.services.bigquery.mock", "false",
                    "floci-gcp.services.bigquery.duck.default-image",
                    System.getProperty("floci.duck.image", "floci/floci-duck:latest"),
                    "quarkus.http.test-port", "18591",
                    "floci-gcp.port", "18591",
                    "floci-gcp.docker.resource-namespace", "bq-is-it");
        }
    }

    private static Response query(String sql) {
        return given().contentType("application/json")
                .body(Map.of("query", sql, "useLegacySql", false))
                .when().post(BASE + "/queries");
    }

    private static List<List<Object>> rows(String sql) {
        return query(sql).then().statusCode(200).extract().jsonPath().getList("rows.f.v");
    }

    @Test
    @Order(1)
    void seed() {
        query("CREATE SCHEMA shop").then().statusCode(200);
        query("CREATE SCHEMA archive").then().statusCode(200);
        given().contentType("application/json")
                .body("{\"datasetReference\": {\"datasetId\": \"eu_data\"}, \"location\": \"EU\"}")
                .when().post(BASE + "/datasets").then().statusCode(200);
        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "orders"}, "description": "all orders", "labels": {"team": "data"},
                 "schema": {"fields": [
                   {"name": "id", "type": "INT64", "mode": "REQUIRED", "description": "order id"},
                   {"name": "tags", "type": "STRING", "mode": "REPEATED"},
                   {"name": "customer", "type": "RECORD", "fields": [
                     {"name": "name", "type": "STRING"},
                     {"name": "phones", "type": "STRING", "mode": "REPEATED"}]}
                 ]}}
                """).when().post(BASE + "/datasets/shop/tables").then().statusCode(200);
        query("CREATE VIEW shop.order_ids AS SELECT id FROM shop.orders").then().statusCode(200);
        query("CREATE TABLE archive.old (x INT64)").then().statusCode(200);
        query("CREATE TABLE eu_data.eu_only (x INT64)").then().statusCode(200);
    }

    @Test
    @Order(2)
    void tablesForOneDataset() {
        assertEquals(List.of(List.of("order_ids", "VIEW", "NO"), List.of("orders", "BASE TABLE", "YES")),
                rows("SELECT table_name, table_type, is_insertable_into FROM shop.INFORMATION_SCHEMA.TABLES"
                        + " ORDER BY table_name"));
        query("SELECT ddl FROM shop.INFORMATION_SCHEMA.TABLES WHERE table_name = 'orders'").then()
                .body("rows[0].f[0].v", equalTo("CREATE TABLE `bq-is-it.shop.orders`\n(\n"
                        + "  id INT64 NOT NULL OPTIONS(description=\"order id\"),\n"
                        + "  tags ARRAY<STRING>,\n"
                        + "  customer STRUCT<name STRING, phones ARRAY<STRING>>\n);"));
    }

    @Test
    @Order(3)
    void regionScopeSpansDatasetsInThatLocation() {
        assertEquals(List.of(List.of("archive", "old"), List.of("shop", "order_ids"), List.of("shop", "orders")),
                rows("SELECT table_schema, table_name FROM `region-us`.INFORMATION_SCHEMA.TABLES"
                        + " ORDER BY table_schema, table_name"));
        assertEquals(List.of(List.of("eu_only")),
                rows("SELECT table_name FROM `bq-is-it`.`region-eu`.INFORMATION_SCHEMA.TABLES"));
        assertEquals(List.of(List.of("archive", "US"), List.of("shop", "US")),
                rows("SELECT schema_name, location FROM region-us.INFORMATION_SCHEMA.SCHEMATA ORDER BY 1"));
        query("SELECT ddl FROM INFORMATION_SCHEMA.SCHEMATA WHERE schema_name = 'shop'").then()
                .body("rows[0].f[0].v", equalTo("CREATE SCHEMA `bq-is-it.shop`\nOPTIONS(\n  location=\"us\"\n);"));
    }

    @Test
    @Order(4)
    void columnsAndFieldPaths() {
        assertEquals(List.of(
                        List.of("id", "1", "NO", "INT64"),
                        List.of("tags", "2", "YES", "ARRAY<STRING>"),
                        List.of("customer", "3", "YES", "STRUCT<name STRING, phones ARRAY<STRING>>")),
                rows("SELECT column_name, ordinal_position, is_nullable, data_type"
                        + " FROM shop.INFORMATION_SCHEMA.COLUMNS WHERE table_name = 'orders' ORDER BY ordinal_position"));
        assertEquals(List.of(
                        Arrays.asList("customer", "STRUCT<name STRING, phones ARRAY<STRING>>", null),
                        Arrays.asList("customer.name", "STRING", null),
                        Arrays.asList("customer.phones", "ARRAY<STRING>", null),
                        Arrays.asList("id", "INT64", "order id"),
                        Arrays.asList("tags", "ARRAY<STRING>", null)),
                rows("SELECT field_path, data_type, description FROM shop.INFORMATION_SCHEMA.COLUMN_FIELD_PATHS"
                        + " WHERE table_name = 'orders' ORDER BY field_path"));
    }

    @Test
    @Order(5)
    void tableOptionsAndViews() {
        assertEquals(List.of(
                        List.of("description", "STRING", "\"all orders\""),
                        List.of("labels", "ARRAY<STRUCT<STRING, STRING>>", "[STRUCT(\"team\", \"data\")]")),
                rows("SELECT option_name, option_type, option_value FROM shop.INFORMATION_SCHEMA.TABLE_OPTIONS"
                        + " WHERE table_name = 'orders' ORDER BY option_name"));
        assertEquals(List.of(List.of("order_ids", "SELECT id FROM shop.orders", "YES")),
                rows("SELECT table_name, view_definition, use_standard_sql FROM shop.INFORMATION_SCHEMA.VIEWS"));
    }

    @Test
    @Order(6)
    void selectStarHasTheDocumentedColumns() {
        List<String> names = query("SELECT * FROM shop.INFORMATION_SCHEMA.VIEWS").then().statusCode(200)
                .extract().jsonPath().getList("schema.fields.name");
        assertEquals(List.of("table_catalog", "table_schema", "table_name", "view_definition", "check_option",
                "use_standard_sql"), names);
        List<String> schemata = query("SELECT * FROM INFORMATION_SCHEMA.SCHEMATA").then().statusCode(200)
                .extract().jsonPath().getList("schema.fields.type");
        assertEquals(List.of("STRING", "STRING", "STRING", "TIMESTAMP", "TIMESTAMP", "STRING", "STRING", "STRING",
                "JSON"), schemata);
    }

    @Test
    @Order(7)
    void joinsWithRegularTablesAndErrors() {
        query("INSERT INTO shop.orders (id, tags) VALUES (1, ['a'])").then().statusCode(200);
        assertEquals(List.of(List.of("orders", "1")),
                rows("SELECT t.table_name, (SELECT COUNT(*) FROM shop.orders) AS n"
                        + " FROM shop.INFORMATION_SCHEMA.TABLES t WHERE t.table_type = 'BASE TABLE'"));
        query("SELECT * FROM missing.INFORMATION_SCHEMA.TABLES").then().statusCode(404)
                .body("error.message", containsString("missing"));
        Response unsupported = query("SELECT * FROM shop.INFORMATION_SCHEMA.JOBS");
        unsupported.then().statusCode(400);
        assertTrue(unsupported.jsonPath().getString("error.message").contains("not supported"));
    }

    @Test
    @Order(8)
    void dmlCanReadInformationSchema() {
        query("CREATE TABLE shop.table_names (name STRING)").then().statusCode(200);
        query("INSERT INTO shop.table_names (name) SELECT table_name FROM shop.INFORMATION_SCHEMA.TABLES"
                + " WHERE table_name = 'orders'").then().statusCode(200);
        assertEquals(List.of(List.of("orders")), rows("SELECT name FROM shop.table_names"));
    }
}

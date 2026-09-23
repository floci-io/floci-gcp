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

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * DML, DDL, views and destination tables end to end on the real floci-duck sidecar. DML needs
 * a floci-duck image with {@code followup_sql} support, which the default image has, so this
 * runs whenever Docker is available. {@code -Dfloci.duck.image=...} selects another image.
 */
@QuarkusTest
@TestProfile(BigQueryDuckDmlIntegrationTest.DmlProfile.class)
@EnabledIf("io.floci.gcp.services.bigquery.BigQueryDuckIntegrationTest#dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BigQueryDuckDmlIntegrationTest {

    private static final String PROJECT = "bq-dml-it";
    private static final String BASE = "/bigquery/v2/projects/" + PROJECT;

    public static class DmlProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.services.bigquery.mock", "false",
                    "floci-gcp.services.bigquery.duck.default-image",
                    System.getProperty("floci.duck.image", "floci/floci-duck:latest"),
                    "quarkus.http.test-port", "18589",
                    "floci-gcp.port", "18589",
                    "floci-gcp.docker.resource-namespace", "bq-dml-it");
        }
    }

    private static Response query(String sql) {
        return given().contentType("application/json")
                .body(Map.of("query", sql, "useLegacySql", false))
                .when().post(BASE + "/queries");
    }

    private static Response insertJob(Map<String, Object> queryConfig) {
        return given().contentType("application/json")
                .body(Map.of("configuration", Map.of("query", queryConfig)))
                .when().post(BASE + "/jobs");
    }

    private static List<List<Object>> rows(String sql) {
        return query(sql).then().statusCode(200).extract().jsonPath().getList("rows.f.v");
    }

    @Test
    @Order(1)
    void ddlCreatesSchemaAndTables() {
        query("CREATE SCHEMA shop").then().statusCode(200);
        query("CREATE SCHEMA IF NOT EXISTS shop").then().statusCode(200);
        query("CREATE SCHEMA shop").then().statusCode(409).body("error.errors[0].reason", equalTo("duplicate"));

        String jobId = query("CREATE TABLE shop.items (id INT64 NOT NULL, name STRING, price NUMERIC,"
                + " tags ARRAY<STRING>)").then().statusCode(200)
                .body("jobComplete", equalTo(true))
                .body("schema", nullValue())
                .extract().jsonPath().getString("jobReference.jobId");
        given().when().get(BASE + "/jobs/" + jobId).then().statusCode(200)
                .body("statistics.query.statementType", equalTo("CREATE_TABLE"))
                .body("statistics.query.ddlOperationPerformed", equalTo("CREATE"))
                .body("statistics.query.ddlTargetTable.tableId", equalTo("items"));

        given().when().get(BASE + "/datasets/shop/tables/items").then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "name", "price", "tags")))
                .body("schema.fields.type", equalTo(List.of("INTEGER", "STRING", "NUMERIC", "STRING")))
                .body("schema.fields[0].mode", equalTo("REQUIRED"))
                .body("schema.fields[3].mode", equalTo("REPEATED"));

        query("CREATE TABLE shop.items (x INT64)").then().statusCode(409);
        query("CREATE TABLE IF NOT EXISTS shop.items (x INT64)").then().statusCode(200);
    }

    @Test
    @Order(2)
    void insertUpdateDeleteReportCounts() {
        String insertJob = query("INSERT INTO shop.items (id, name, price, tags) VALUES"
                + " (1, 'apple', 1.50, ['fruit']), (2, 'bread', 3.25, []), (3, 'cheese', 7, ['dairy', 'aged'])")
                .then().statusCode(200)
                .body("numDmlAffectedRows", equalTo("3"))
                .body("dmlStats.insertedRowCount", equalTo("3"))
                .extract().jsonPath().getString("jobReference.jobId");
        given().when().get(BASE + "/jobs/" + insertJob).then().statusCode(200)
                .body("statistics.query.statementType", equalTo("INSERT"))
                .body("statistics.query.numDmlAffectedRows", equalTo("3"));
        given().when().get(BASE + "/queries/" + insertJob).then().statusCode(200)
                .body("totalRows", equalTo("0"))
                .body("numDmlAffectedRows", equalTo("3"));

        query("UPDATE shop.items SET price = price * 2 WHERE name != 'bread'").then().statusCode(200)
                .body("numDmlAffectedRows", equalTo("2"))
                .body("dmlStats.updatedRowCount", equalTo("2"));
        query("DELETE shop.items WHERE id = 2").then().statusCode(200)
                .body("numDmlAffectedRows", equalTo("1"));
        query("DELETE FROM shop.items").then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"));

        List<List<Object>> remaining = rows("SELECT id, name, price, ARRAY_LENGTH(tags) AS n FROM shop.items"
                + " ORDER BY id");
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of(List.of("1", "apple", "3", "1"), List.of("3", "cheese", "14", "2")), remaining);

        // tabledata.list sees the DML result too
        given().when().get(BASE + "/datasets/shop/tables/items/data").then().statusCode(200)
                .body("totalRows", equalTo("2"));
    }

    @Test
    @Order(3)
    void mergeSplitsItsCounts() {
        query("CREATE TABLE shop.updates AS SELECT * FROM UNNEST([STRUCT(1 AS id, 'green apple' AS name),"
                + " STRUCT(4 AS id, 'dates' AS name)])").then().statusCode(200);
        query("MERGE shop.items t USING shop.updates u ON t.id = u.id"
                + " WHEN MATCHED THEN UPDATE SET name = u.name"
                + " WHEN NOT MATCHED THEN INSERT (id, name, price) VALUES (u.id, u.name, 0)")
                .then().statusCode(200)
                .body("numDmlAffectedRows", equalTo("2"))
                .body("dmlStats.insertedRowCount", equalTo("1"))
                .body("dmlStats.updatedRowCount", equalTo("1"))
                .body("dmlStats.deletedRowCount", equalTo("0"));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(List.of("green apple"), List.of("cheese"),
                List.of("dates")), rows("SELECT name FROM shop.items ORDER BY id"));
    }

    @Test
    @Order(4)
    void ctasAndViews() {
        query("CREATE TABLE shop.pricey AS SELECT id, name, price FROM shop.items WHERE price > 1")
                .then().statusCode(200);
        given().when().get(BASE + "/datasets/shop/tables/pricey").then().statusCode(200)
                .body("numRows", equalTo("2"))
                .body("schema.fields.type", equalTo(List.of("INTEGER", "STRING", "NUMERIC")));

        query("CREATE VIEW shop.named AS SELECT id, UPPER(name) AS shout FROM shop.items").then().statusCode(200);
        query("CREATE VIEW shop.named_top AS SELECT shout FROM shop.named WHERE id < 4").then().statusCode(200);
        given().when().get(BASE + "/datasets/shop/tables/named").then().statusCode(200)
                .body("type", equalTo("VIEW"))
                .body("view.query", containsString("UPPER(name)"))
                .body("schema.fields.name", equalTo(List.of("id", "shout")));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(List.of("GREEN APPLE"), List.of("CHEESE")),
                rows("SELECT shout FROM shop.named_top ORDER BY shout DESC"));

        query("INSERT INTO shop.named (id) VALUES (9)").then().statusCode(400);
        query("DROP TABLE shop.named").then().statusCode(400);
        query("DROP VIEW shop.named_top").then().statusCode(200);
        query("CREATE OR REPLACE VIEW shop.named AS SELECT 1 AS one").then().statusCode(200);
        org.junit.jupiter.api.Assertions.assertEquals(List.of(List.of("1")), rows("SELECT one FROM shop.named"));
    }

    @Test
    @Order(5)
    void restCreatedViewIsQueryable() {
        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "rest_view"},
                 "view": {"query": "SELECT COUNT(*) AS n FROM shop.items", "useLegacySql": false}}
                """).when().post(BASE + "/datasets/shop/tables").then().statusCode(200)
                .body("type", equalTo("VIEW"))
                .body("schema.fields[0].name", equalTo("n"));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(List.of("3")), rows("SELECT n FROM shop.rest_view"));
    }

    @Test
    @Order(6)
    void destinationTableDispositions() {
        Map<String, Object> destination = Map.of("projectId", PROJECT, "datasetId", "shop", "tableId", "snap");
        insertJob(Map.of("query", "SELECT id FROM shop.items", "useLegacySql", false,
                "destinationTable", destination)).then().statusCode(200)
                .body("status.errorResult", nullValue())
                .body("configuration.query.destinationTable.tableId", equalTo("snap"));
        insertJob(Map.of("query", "SELECT id FROM shop.items", "useLegacySql", false,
                "destinationTable", destination)).then().statusCode(200)
                .body("status.errorResult.reason", equalTo("duplicate"));
        insertJob(Map.of("query", "SELECT 100 AS id", "useLegacySql", false,
                "destinationTable", destination, "writeDisposition", "WRITE_APPEND")).then().statusCode(200)
                .body("status.errorResult", nullValue());
        given().when().get(BASE + "/datasets/shop/tables/snap").then().body("numRows", equalTo("4"));
        insertJob(Map.of("query", "SELECT 7 AS id", "useLegacySql", false,
                "destinationTable", destination, "writeDisposition", "WRITE_TRUNCATE")).then().statusCode(200);
        given().when().get(BASE + "/datasets/shop/tables/snap").then().body("numRows", equalTo("1"));
        insertJob(Map.of("query", "SELECT 1 AS id", "useLegacySql", false, "createDisposition", "CREATE_NEVER",
                "destinationTable", Map.of("datasetId", "shop", "tableId", "missing"))).then().statusCode(200)
                .body("status.errorResult.reason", equalTo("notFound"));
    }

    @Test
    @Order(7)
    void truncateAndDrop() {
        query("TRUNCATE TABLE shop.items").then().statusCode(200)
                .body("dmlStats.deletedRowCount", equalTo("3"));
        query("SELECT COUNT(*) AS n FROM shop.items").then().statusCode(200).body("rows[0].f[0].v", equalTo("0"));
        query("DROP TABLE shop.items").then().statusCode(200);
        query("DROP TABLE shop.items").then().statusCode(404);
        query("DROP TABLE IF EXISTS shop.items").then().statusCode(200);
        query("DROP SCHEMA shop").then().statusCode(400).body("error.errors[0].reason", equalTo("resourceInUse"));
        query("DROP SCHEMA shop CASCADE").then().statusCode(200);
        given().when().get(BASE + "/datasets/shop").then().statusCode(404);
    }

    @Test
    @Order(8)
    void dryRunOfDmlDoesNotChangeData() {
        query("CREATE SCHEMA dry").then().statusCode(200);
        query("CREATE TABLE dry.t (a INT64)").then().statusCode(200);
        given().contentType("application/json")
                .body(Map.of("query", "INSERT INTO dry.t (a) VALUES (1)", "useLegacySql", false, "dryRun", true))
                .when().post(BASE + "/queries").then().statusCode(200);
        query("SELECT COUNT(*) AS n FROM dry.t").then().body("rows", hasSize(1)).body("rows[0].f[0].v", equalTo("0"));
    }

    @Test
    @Order(9)
    void destinationWritesRespectTheDeclaredSchema() {
        query("CREATE SCHEMA typed_ds").then().statusCode(200);
        given().contentType("application/json")
                .body("""
                        {"tableReference": {"tableId": "typed"}, "schema": {"fields": [
                          {"name": "id", "type": "INTEGER", "mode": "REQUIRED"},
                          {"name": "total", "type": "INTEGER"}]}}
                        """)
                .when().post(BASE + "/datasets/typed_ds/tables").then().statusCode(200);
        Map<String, Object> destination = Map.of("datasetId", "typed_ds", "tableId", "typed");

        // A value the declared column cannot hold is rejected, rather than stored and breaking
        // every later read of the table.
        insertJob(Map.of("query", "SELECT 1 AS id, 'abc' AS total", "useLegacySql", false,
                "destinationTable", destination, "writeDisposition", "WRITE_APPEND")).then().statusCode(200)
                .body("status.errorResult.reason", equalTo("invalidQuery"));

        // A REQUIRED column the query does not produce is rejected too.
        insertJob(Map.of("query", "SELECT 5 AS total", "useLegacySql", false,
                "destinationTable", destination, "writeDisposition", "WRITE_APPEND")).then().statusCode(200)
                .body("status.errorResult.reason", equalTo("invalidQuery"));

        // ALLOW_FIELD_RELAXATION is the documented escape hatch, and it relaxes the mode.
        insertJob(Map.of("query", "SELECT 5 AS total", "useLegacySql", false,
                "destinationTable", destination, "writeDisposition", "WRITE_APPEND",
                "schemaUpdateOptions", List.of("ALLOW_FIELD_RELAXATION"))).then().statusCode(200)
                .body("status.errorResult", nullValue());
        given().when().get(BASE + "/datasets/typed_ds/tables/typed").then()
                .body("schema.fields[0].mode", equalTo("NULLABLE"));

        // A well-typed write still works.
        insertJob(Map.of("query", "SELECT 2 AS id, 20 AS total", "useLegacySql", false,
                "destinationTable", destination, "writeDisposition", "WRITE_APPEND")).then().statusCode(200)
                .body("status.errorResult", nullValue());
    }

    @Test
    @Order(10)
    void destinationTableWithoutATableIdIsRejected() {
        // This used to return a DONE job and persist a table under the key "shop/null".
        insertJob(Map.of("query", "SELECT 1 AS id", "useLegacySql", false,
                "destinationTable", Map.of("datasetId", "typed_ds"))).then().statusCode(200)
                .body("status.errorResult.reason", equalTo("invalid"));
        insertJob(Map.of("query", "SELECT 1 AS id", "useLegacySql", false,
                "destinationTable", Map.of("tableId", "orphan"))).then().statusCode(200)
                .body("status.errorResult.reason", equalTo("invalid"));
    }
}

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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                .body("statistics.query.ddlTargetTable.tableId", equalTo("items"))
                .body("configuration.query.destinationTable.projectId", equalTo(PROJECT))
                .body("configuration.query.destinationTable.datasetId", equalTo("shop"))
                .body("configuration.query.destinationTable.tableId", equalTo("items"));

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
    @Order(3)
    void mergeInsertValuesReadTheSourceRow() {
        query("CREATE TABLE shop.incremental AS SELECT 1 AS id, 'a' AS name").then().statusCode(200);
        // The statement dbt-bigquery generates for an incremental model with a unique_key.
        query("MERGE shop.incremental AS DBT_INTERNAL_DEST USING (SELECT * FROM UNNEST([STRUCT(1 AS id, 'b' AS name),"
                + " STRUCT(2 AS id, 'c' AS name)])) AS DBT_INTERNAL_SOURCE ON (DBT_INTERNAL_SOURCE.id = DBT_INTERNAL_DEST.id)"
                + " WHEN MATCHED THEN UPDATE SET `id` = DBT_INTERNAL_SOURCE.`id`, `name` = DBT_INTERNAL_SOURCE.`name`"
                + " WHEN NOT MATCHED THEN INSERT (`id`, `name`) VALUES (`id`, `name`)")
                .then().statusCode(200)
                .body("dmlStats.insertedRowCount", equalTo("1"))
                .body("dmlStats.updatedRowCount", equalTo("1"));
        assertEquals(List.of(List.of("1", "b"), List.of("2", "c")),
                rows("SELECT id, name FROM shop.incremental ORDER BY id"));

        query("CREATE TABLE shop.incoming AS SELECT * FROM UNNEST([STRUCT(2 AS id, 'x' AS name), STRUCT(3 AS id, 'd' AS name)])")
                .then().statusCode(200);
        query("MERGE shop.incremental t USING shop.incoming ON t.id = incoming.id"
                + " WHEN NOT MATCHED AND name != 'x' THEN INSERT (id, name) VALUES (id * 10, UPPER(name))")
                .then().statusCode(200)
                .body("dmlStats.insertedRowCount", equalTo("1"));
        assertEquals(List.of(List.of("1", "b"), List.of("2", "c"), List.of("30", "D")),
                rows("SELECT id, name FROM shop.incremental ORDER BY id"));
    }

    @Test
    @Order(3)
    void notMatchedClausesKeepDatePartsSubqueriesComparisonsAndCaseWorking() {
        String target = "CREATE OR REPLACE TABLE shop.merge_t (id INT64, d DATE, a INT64, b INT64, flag BOOL, v STRING)";
        query("CREATE TABLE shop.merge_s AS SELECT * FROM UNNEST(["
                + "STRUCT(1 AS id, DATE '2026-03-05' AS d, 1 AS a, 2 AS b, TRUE AS flag, 'from_source' AS v),"
                + " STRUCT(2 AS id, DATE '2026-03-10' AS d, 5 AS a, 3 AS b, FALSE AS flag, 'from_source2' AS v)])")
                .then().statusCode(200);
        query("CREATE TABLE shop.lookup AS SELECT 'from_lookup' AS v").then().statusCode(200);
        String merge = "MERGE shop.merge_t T USING shop.merge_s S ON T.id = S.id WHEN NOT MATCHED";

        query(target).then().statusCode(200);
        query(merge + " AND EXTRACT(DAY FROM d) = 5 AND d > DATE '2026-01-01'"
                + " AND TIMESTAMP_TRUNC(TIMESTAMP(d), DAY, 'UTC') = TIMESTAMP(d)"
                + " AND DATE_TRUNC(CASE WHEN [d, d] IS NOT NULL THEN d ELSE d END, MONTH) = DATE_TRUNC(d, MONTH) THEN INSERT (id, d)"
                + " VALUES (id, DATE_TRUNC(d, MONTH))").then().statusCode(200);
        assertEquals(List.of(List.of("1", "2026-03-01")), rows("SELECT id, d FROM shop.merge_t ORDER BY id"));

        query(target).then().statusCode(200);
        query(merge + " THEN INSERT (id, v) VALUES (id, (SELECT v FROM shop.lookup LIMIT 1))").then().statusCode(200);
        assertEquals(List.of(List.of("1", "from_lookup"), List.of("2", "from_lookup")),
                rows("SELECT id, v FROM shop.merge_t ORDER BY id"));

        query(target).then().statusCode(200);
        query(merge + " AND a < b THEN INSERT (id, a, b) VALUES (id, a, b)").then().statusCode(200);
        assertEquals(List.of(List.of("1", "1", "2")), rows("SELECT id, a, b FROM shop.merge_t ORDER BY id"));

        query(target).then().statusCode(200);
        query(merge + " AND CASE WHEN flag THEN TRUE ELSE FALSE END THEN INSERT (id, flag) VALUES (id, flag)")
                .then().statusCode(200);
        assertEquals(List.of(List.of("1", "true")), rows("SELECT id, flag FROM shop.merge_t ORDER BY id"));

        for (String now : List.of("CURRENT_DATE", "CURRENT_TIMESTAMP")) {
            query(target).then().statusCode(200);
            query(merge + " THEN INSERT (id, v) VALUES (id, CAST(" + now + " AS STRING))").then().statusCode(200);
            assertEquals(List.of(List.of("1", "true"), List.of("2", "true")),
                    rows("SELECT id, v IS NOT NULL FROM shop.merge_t ORDER BY id"));
        }

        query(target).then().statusCode(200);
        query(merge + " THEN INSERT (id, v) VALUES (id, CAST(EXTRACT(DATE FROM TIMESTAMP(d) AT TIME ZONE 'UTC') AS STRING))")
                .then().statusCode(200);
        assertEquals(List.of(List.of("1", "2026-03-05"), List.of("2", "2026-03-10")),
                rows("SELECT id, v FROM shop.merge_t ORDER BY id"));

        query(target).then().statusCode(200);
        query("MERGE shop.merge_t USING UNNEST([STRUCT(9 AS src_id)]) ON FALSE WHEN NOT MATCHED"
                + " THEN INSERT (id) VALUES (src_id)").then().statusCode(200);
        assertEquals(List.of(List.of("9")), rows("SELECT id FROM shop.merge_t ORDER BY id"));
        query(target).then().statusCode(200);
        query("MERGE shop.merge_t T USING UNNEST([STRUCT(9 AS id, 'u' AS v)]) ON FALSE WHEN NOT MATCHED"
                + " THEN INSERT (id, v) VALUES (id, v)").then().statusCode(200);
        assertEquals(List.of(List.of("9", "u")), rows("SELECT id, v FROM shop.merge_t ORDER BY id"));
        query(target).then().statusCode(200);
        query("MERGE shop.merge_t AS _merge_source USING UNNEST([STRUCT(9 AS id)]) ON FALSE WHEN NOT MATCHED"
                + " THEN INSERT (id) VALUES (id)").then().statusCode(200);
        assertEquals(List.of(List.of("9")), rows("SELECT id FROM shop.merge_t ORDER BY id"));

        query("CREATE TABLE shop.days_s AS SELECT 1 AS id, DATE '2026-03-05' AS d, DATE '2026-03-01' AS day"
                + " UNION ALL SELECT 2, DATE '2026-03-01', DATE '2026-03-05'").then().statusCode(200);
        query("CREATE TABLE shop.days_t (id INT64, d DATE, day DATE)").then().statusCode(200);
        query("MERGE shop.days_t T USING shop.days_s S ON T.id = S.id"
                + " WHEN NOT MATCHED AND DATE_DIFF(d, day, DAY) > 0 THEN INSERT (id, day) VALUES (id, day)")
                .then().statusCode(200);
        assertEquals(List.of(List.of("1", "2026-03-01")), rows("SELECT id, day FROM shop.days_t ORDER BY id"));
    }

    @Test
    @Order(3)
    void correlatedSubqueriesInNotMatchedClausesFailLikeBigQuery() {
        query("CREATE TABLE shop.corr_s AS SELECT 1 AS id, 'a' AS k, 'src1' AS v UNION ALL SELECT 2, 'b', 'src2'")
                .then().statusCode(200);
        query("CREATE TABLE shop.corr_t (id INT64, k STRING, v STRING)").then().statusCode(200);
        query("CREATE TABLE shop.corr_lookup AS SELECT 'a' AS lk, 'name_a' AS name UNION ALL SELECT 'b', 'name_b'")
                .then().statusCode(200);
        query("CREATE TABLE shop.corr_lookup_k AS SELECT 'a' AS k, 'inner_a' AS name UNION ALL SELECT 'zzz', 'inner_zzz'")
                .then().statusCode(200);
        String merge = "MERGE shop.corr_t T USING shop.corr_s S ON T.id = S.id WHEN NOT MATCHED";

        query(merge + " THEN INSERT (id, k, v) VALUES (id, k, (SELECT name FROM shop.corr_lookup WHERE lk = k))")
                .then().statusCode(400)
                .body("error.message", containsString("Correlated Subquery is unsupported in INSERT clause."));
        query(merge + " AND EXISTS (SELECT 1 FROM shop.corr_lookup WHERE lk = k AND name = 'name_b')"
                + " THEN INSERT (id, k) VALUES (id, k)")
                .then().statusCode(400)
                .body("error.message", containsString("Correlated Subquery is unsupported in WHEN clause."));
        query(merge + " THEN INSERT (id, v) VALUES (id, (SELECT name FROM shop.corr_lookup_k l WHERE l.k = S.k))")
                .then().statusCode(400)
                .body("error.message", containsString("Correlated Subquery is unsupported in INSERT clause."));

        query(merge + " AND EXISTS (SELECT 1 FROM shop.corr_lookup l WHERE l.lk = S.k"
                + " AND EXISTS (SELECT 1 FROM shop.corr_lookup S)) THEN INSERT (id, k) VALUES (id, k)")
                .then().statusCode(400)
                .body("error.message", containsString("Correlated Subquery is unsupported in WHEN clause."));
        query(merge + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(l.name) FROM shop.corr_lookup l"
                + " WHERE l.lk IN (SELECT k FROM shop.corr_lookup_k)))")
                .then().statusCode(400)
                .body("error.message", containsString("Correlated Subquery is unsupported in INSERT clause."));
        query(merge + " AND k IN (SELECT lk FROM shop.corr_lookup) THEN INSERT (id, k) VALUES (id, k)")
                .then().statusCode(400)
                .body("error.message", containsString("Correlated Subquery is unsupported in WHEN clause."));
        query("MERGE shop.corr_t T USING UNNEST([STRUCT(9 AS id, 'a' AS v)]) AS u ON T.id = u.id WHEN NOT MATCHED"
                + " THEN INSERT (id, v) VALUES (id, (SELECT u.v))")
                .then().statusCode(400)
                .body("error.message", containsString("Correlated Subquery is unsupported in INSERT clause."));

        query(merge + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(name) FROM shop.corr_lookup_k WHERE k = 'a'))")
                .then().statusCode(200);
        assertEquals(List.of(List.of("1", "inner_a"), List.of("2", "inner_a")),
                rows("SELECT id, v FROM shop.corr_t ORDER BY id"));

        query("CREATE TABLE shop.corr_a AS SELECT 'x' AS a_col").then().statusCode(200);
        query("CREATE TABLE shop.corr_b AS SELECT 'from_b' AS v").then().statusCode(200);
        query("DELETE FROM shop.corr_t WHERE TRUE").then().statusCode(200);
        query(merge + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(v) FROM shop.corr_a, shop.corr_b))")
                .then().statusCode(200);
        assertEquals(List.of(List.of("1", "from_b"), List.of("2", "from_b")),
                rows("SELECT id, v FROM shop.corr_t ORDER BY id"));
        query("DELETE FROM shop.corr_t WHERE TRUE").then().statusCode(200);
        query(merge + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(name) AS v FROM shop.corr_lookup ORDER BY v LIMIT 1))")
                .then().statusCode(200);
        assertEquals(List.of(List.of("1", "name_b"), List.of("2", "name_b")),
                rows("SELECT id, v FROM shop.corr_t ORDER BY id"));

        query(merge + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(name) AS v FROM shop.corr_lookup WHERE lk = v))")
                .then().statusCode(400)
                .body("error.message", containsString("Correlated Subquery is unsupported in INSERT clause."));
        query("DELETE FROM shop.corr_t WHERE TRUE").then().statusCode(200);
        query(merge + " AND ('a') IN (SELECT lk FROM shop.corr_lookup) THEN INSERT (id, k) VALUES (id, k)")
                .then().statusCode(200);
        assertEquals(List.of(List.of("1", "a"), List.of("2", "b")), rows("SELECT id, k FROM shop.corr_t ORDER BY id"));

        query("CREATE TABLE shop.corr_lookup_d AS SELECT 'a' AS lk, 'name_a' AS name, DATE '2026-01-01' AS d"
                + " UNION ALL SELECT 'b', 'name_b', DATE '2026-01-05'").then().statusCode(200);
        for (String constantOperand : List.of("CAST('a' AS STRING) IN (SELECT lk FROM shop.corr_lookup_d)",
                "SAFE_CAST('1' AS INT64) IN (SELECT 1)",
                "DATE_SUB(DATE '2026-01-02', INTERVAL 1 DAY) IN (SELECT d FROM shop.corr_lookup_d)")) {
            query("DELETE FROM shop.corr_t WHERE TRUE").then().statusCode(200);
            query(merge + " AND " + constantOperand + " THEN INSERT (id, k) VALUES (id, k)").then().statusCode(200);
            assertEquals(List.of(List.of("1", "a"), List.of("2", "b")), rows("SELECT id, k FROM shop.corr_t ORDER BY id"),
                    constantOperand);
        }
        query("DELETE FROM shop.corr_t WHERE TRUE").then().statusCode(200);
        query(merge + " THEN INSERT (id, v) VALUES (id, (SELECT MAX(name) v FROM shop.corr_lookup_d ORDER BY v LIMIT 1))")
                .then().statusCode(200);
        assertEquals(List.of(List.of("1", "name_b"), List.of("2", "name_b")),
                rows("SELECT id, v FROM shop.corr_t ORDER BY id"));
    }

    @Test
    @Order(4)
    void ctasAndViews() {
        String ctasJob = query("CREATE TABLE shop.pricey AS SELECT id, name, price FROM shop.items WHERE price > 1")
                .then().statusCode(200)
                .extract().jsonPath().getString("jobReference.jobId");
        given().when().get(BASE + "/jobs/" + ctasJob).then().statusCode(200)
                .body("statistics.query.statementType", equalTo("CREATE_TABLE_AS_SELECT"))
                .body("configuration.query.destinationTable.tableId", equalTo("pricey"));
        given().when().get(BASE + "/queries/" + ctasJob).then().statusCode(200)
                .body("totalRows", equalTo("0"))
                .body("rows", nullValue());
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
    @Order(4)
    void ctasWithArraySubscripts() {
        query("CREATE TABLE shop.subscripted AS SELECT [STRUCT('a' AS p)][SAFE_OFFSET(0)].p AS p, [10, 20][OFFSET(1)] AS o")
                .then().statusCode(200);
        assertEquals(List.of(List.of("a", "20")), rows("SELECT p, o FROM shop.subscripted"));
    }

    @Test
    @Order(4)
    void alterSetOptionsChangesOnlyTheListedOptions() {
        query("CREATE TABLE shop.optioned (x INT64)").then().statusCode(200);
        // dbt-bigquery runs this after every seed load.
        String jobId = query("ALTER TABLE shop.optioned SET OPTIONS()").then().statusCode(200)
                .extract().jsonPath().getString("jobReference.jobId");
        given().when().get(BASE + "/jobs/" + jobId).then().statusCode(200)
                .body("statistics.query.statementType", equalTo("ALTER_TABLE"))
                .body("statistics.query.ddlOperationPerformed", equalTo("ALTER"))
                .body("statistics.query.ddlTargetTable.tableId", equalTo("optioned"));

        query("ALTER TABLE shop.optioned SET OPTIONS(description='desc one', friendly_name='Friendly',"
                + " labels=[('team', 'data'), ('env', 'dev')], expiration_timestamp=TIMESTAMP '2099-01-01 00:00:00 UTC')")
                .then().statusCode(200);
        query("ALTER TABLE shop.optioned SET OPTIONS(labels=[STRUCT('only' AS key, 'one' AS value)])").then().statusCode(200);
        query("ALTER TABLE IF EXISTS shop.optioned SET OPTIONS(description='desc two')").then().statusCode(200);
        given().when().get(BASE + "/datasets/shop/tables/optioned").then().statusCode(200)
                .body("description", equalTo("desc two"))
                .body("friendlyName", equalTo("Friendly"))
                .body("labels", equalTo(Map.of("only", "one")))
                .body("expirationTime", equalTo("4070908800000"));

        query("ALTER TABLE shop.optioned SET OPTIONS(description=NULL, friendly_name=NULL, labels=NULL,"
                + " expiration_timestamp=NULL)").then().statusCode(200);
        given().when().get(BASE + "/datasets/shop/tables/optioned").then().statusCode(200)
                .body("description", nullValue())
                .body("friendlyName", nullValue())
                .body("labels", nullValue())
                .body("expirationTime", nullValue());

        String skipped = query("ALTER TABLE IF EXISTS shop.no_such_table SET OPTIONS(description='x')")
                .then().statusCode(200).extract().jsonPath().getString("jobReference.jobId");
        given().when().get(BASE + "/jobs/" + skipped).then()
                .body("statistics.query.ddlOperationPerformed", equalTo("SKIP"));
        query("ALTER TABLE shop.no_such_table SET OPTIONS()").then().statusCode(404)
                .body("error.message", equalTo("Not found: Table " + PROJECT + ":shop.no_such_table"));

        query("CREATE VIEW shop.optioned_v AS SELECT 1 AS one").then().statusCode(200);
        query("ALTER TABLE shop.optioned_v SET OPTIONS(description='v')").then().statusCode(400)
                .body("error.message", equalTo("ALTER TABLE SET OPTIONS only supports setting options for tables,"
                        + " while optioned_v is a(n) view"));
        query("ALTER VIEW shop.optioned SET OPTIONS(description='v')").then().statusCode(400)
                .body("error.message", equalTo("ALTER VIEW SET OPTIONS only supports setting options for views,"
                        + " while optioned is a(n) table"));
        query("ALTER VIEW shop.optioned_v SET OPTIONS(description='v')").then().statusCode(200);
        given().when().get(BASE + "/datasets/shop/tables/optioned_v").then().body("description", equalTo("v"));
    }

    @Test
    @Order(4)
    void alterSetOptionsEvaluatesConstantExpressionsLikeBigQuery() {
        query("CREATE TABLE shop.optioned_expr (x INT64)").then().statusCode(200);
        query("CREATE TABLE shop.optioned_distinct (x INT64)").then().statusCode(200);
        query("ALTER TABLE shop.optioned_expr SET OPTIONS(description=CONCAT('a', 'b'), friendly_name=UPPER('x'),"
                + " expiration_timestamp=TIMESTAMP_ADD(TIMESTAMP '2030-01-01 00:00:00+00', INTERVAL 7 DAY))")
                .then().statusCode(200);
        given().when().get(BASE + "/datasets/shop/tables/optioned_expr").then().statusCode(200)
                .body("description", equalTo("ab"))
                .body("friendlyName", equalTo("X"))
                .body("expirationTime", equalTo("1894060800000"));
        query("ALTER TABLE shop.optioned_expr SET OPTIONS(description=CAST(NULL AS STRING))").then().statusCode(200);
        given().when().get(BASE + "/datasets/shop/tables/optioned_expr").then().statusCode(200)
                .body("description", nullValue())
                .body("friendlyName", equalTo("X"));

        query("ALTER TABLE shop.optioned_expr SET OPTIONS(description=1 + 1)").then().statusCode(400)
                .body("error.message", equalTo("Option description value has type INT64 which cannot be coerced to"
                        + " expected type STRING"));
        query("ALTER TABLE shop.optioned_expr SET OPTIONS(expiration_timestamp=CONCAT('2030-01-01', ' 00:00:00'))")
                .then().statusCode(400)
                .body("error.message", equalTo("Expected type TIMESTAMP; found STRING; failed to set"
                        + " 'expiration_timestamp' in OPTIONS()"));
        query("ALTER TABLE shop.optioned_expr SET OPTIONS(expiration_timestamp=DATE_ADD(CURRENT_DATE(), INTERVAL 30 DAY))")
                .then().statusCode(400)
                .body("error.message", equalTo("Expected type TIMESTAMP; found DATE; failed to set"
                        + " 'expiration_timestamp' in OPTIONS()"));
        query("ALTER TABLE shop.optioned_expr SET OPTIONS(description=(SELECT 'x'))").then().statusCode(400)
                .body("error.message", equalTo("Expected constant expression but found unsupported statement or clause;"
                        + " failed to evaluate 'description' in table OPTIONS"));
        query("ALTER TABLE shop.optioned_expr SET OPTIONS(labels=[('env', LOWER('DEV'))])").then().statusCode(400)
                .body("error.message", equalTo("Found unsupported function call 'ARRAY[...]'; failed to evaluate"
                        + " 'labels' in table OPTIONS"));
        query("ALTER TABLE shop.optioned_expr SET OPTIONS(description=['a', 'b'])").then().statusCode(400)
                .body("error.message", equalTo("Option description value has type ARRAY<STRING> which cannot be"
                        + " coerced to expected type STRING"));
        query("ALTER TABLE shop.optioned_expr SET OPTIONS(expiration_timestamp=[TIMESTAMP '2030-01-01 00:00:00+00'])")
                .then().statusCode(400)
                .body("error.message", equalTo("Expected type TIMESTAMP; found ARRAY<TIMESTAMP>; failed to set"
                        + " 'expiration_timestamp' in OPTIONS()"));
        query("ALTER TABLE shop.optioned_expr SET OPTIONS(description=CAST(x AS STRING) FROM shop.optioned_expr)")
                .then().statusCode(400)
                .body("error.message", equalTo("Syntax error: Expected \")\" but got keyword FROM"));
        query("ALTER TABLE shop.optioned_expr SET OPTIONS(description='x' WHERE TRUE)").then().statusCode(400)
                .body("error.message", equalTo("Syntax error: Expected \")\" but got keyword WHERE"));
        query("ALTER TABLE shop.optioned_distinct SET OPTIONS(description=CASE WHEN 1 IS DISTINCT FROM 2"
                + " THEN 'different' ELSE 'same' END, friendly_name=IF(1 IS NOT DISTINCT FROM 1, 'eq', 'ne'))")
                .then().statusCode(200);
        given().when().get(BASE + "/datasets/shop/tables/optioned_distinct").then().statusCode(200)
                .body("description", equalTo("different"))
                .body("friendlyName", equalTo("eq"));

        for (String sql : List.of("ALTER TABLE shop.optioned_expr SET OPTIONS(description=1 + 1)",
                "CREATE TABLE shop.optioned_expr_copy COPY shop.optioned_expr OPTIONS(description=1 + 1)")) {
            given().contentType("application/json")
                    .body(Map.of("query", sql, "useLegacySql", false, "dryRun", true))
                    .when().post(BASE + "/queries").then().statusCode(400)
                    .body("error.message", equalTo("Option description value has type INT64 which cannot be coerced"
                            + " to expected type STRING"));
        }
        given().contentType("application/json")
                .body(Map.of("query", "ALTER TABLE shop.optioned_expr SET OPTIONS(description=CONCAT('a', 'b'))",
                        "useLegacySql", false, "dryRun", true))
                .when().post(BASE + "/queries").then().statusCode(200);

        given().when().get(BASE + "/datasets/shop/tables/optioned_expr").then().statusCode(200)
                .body("description", nullValue())
                .body("friendlyName", equalTo("X"))
                .body("expirationTime", equalTo("1894060800000"));
        given().when().get(BASE + "/datasets/shop/tables/optioned_expr_copy").then().statusCode(404);
    }

    @Test
    @Order(4)
    void ctasWithAParenthesizedQuery() {
        // The shape dbt-bigquery generates for table and incremental models.
        query("CREATE OR REPLACE TABLE shop.wrapped PARTITION BY d CLUSTER BY id OPTIONS() AS ("
                + "WITH s AS (SELECT id FROM shop.items) SELECT id, CURRENT_DATE() AS d, id * 10 ten FROM s)")
                .then().statusCode(200);
        given().when().get(BASE + "/datasets/shop/tables/wrapped").then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "d", "ten")));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(List.of("1", "10"), List.of("3", "30"),
                List.of("4", "40")), rows("SELECT id, ten FROM shop.wrapped ORDER BY id"));

        query("CREATE OR REPLACE TABLE shop.wrapped AS SELECT 99 AS id")
                .then().statusCode(400).body("error.errors[0].reason", equalTo("invalidQuery"));
        given().when().get(BASE + "/datasets/shop/tables/wrapped").then().statusCode(200)
                .body("timePartitioning.field", equalTo("d"))
                .body("numRows", equalTo("3"));

        query("CREATE TABLE shop.invalid_ingestion PARTITION BY DATE(_PARTITIONTIME) AS SELECT 1 AS id")
                .then().statusCode(400).body("error.errors[0].reason", equalTo("invalidQuery"));
        given().when().get(BASE + "/datasets/shop/tables/invalid_ingestion").then().statusCode(404);
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
    void dryRunOfDmlReportsTheErrorARealRunWould() {
        query("CREATE SCHEMA dry_err").then().statusCode(200);
        query("CREATE TABLE dry_err.t (a INT64)").then().statusCode(200);
        for (String sql : List.of(
                "INSERT INTO dry_err.t (a) SELECT a FROM dry_err.missing",
                "MERGE dry_err.t T USING dry_err.missing S ON T.a = S.a WHEN MATCHED THEN DELETE",
                "UPDATE dry_err.t SET nope = 1 WHERE TRUE")) {
            Response dry = given().contentType("application/json")
                    .body(Map.of("query", sql, "useLegacySql", false, "dryRun", true))
                    .when().post(BASE + "/queries");
            Response real = query(sql);
            dry.then().statusCode(real.statusCode());
            assertTrue(real.statusCode() >= 400, sql);
        }
    }

    @Test
    @Order(10)
    void unknownDispositionsAreRejected() {
        query("CREATE SCHEMA disp").then().statusCode(200);
        Map<String, Object> destination = Map.of("datasetId", "disp", "tableId", "t");
        insertJob(Map.of("query", "SELECT 1 AS id", "useLegacySql", false, "destinationTable", destination,
                "writeDisposition", "WRITE_SOMETIMES")).then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalid"));
        insertJob(Map.of("query", "SELECT 1 AS id", "useLegacySql", false, "destinationTable", destination,
                "createDisposition", "CREATE_MAYBE")).then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalid"));
        given().when().get(BASE + "/datasets/disp/tables/t").then().statusCode(404);
    }

    @Test
    @Order(11)
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
    @Order(12)
    void destinationTableWithoutATableIdIsRejected() {
        // This used to return a DONE job and persist a table under the key "shop/null".
        insertJob(Map.of("query", "SELECT 1 AS id", "useLegacySql", false,
                "destinationTable", Map.of("datasetId", "typed_ds"))).then().statusCode(200)
                .body("status.errorResult.reason", equalTo("invalid"));
        insertJob(Map.of("query", "SELECT 1 AS id", "useLegacySql", false,
                "destinationTable", Map.of("tableId", "orphan"))).then().statusCode(200)
                .body("status.errorResult.reason", equalTo("invalid"));
    }

    @Test
    @Order(100)
    void destinationPartitionDecoratorsWriteOnePartition() {
        query("CREATE SCHEMA partitions").then().statusCode(200);
        given().contentType("application/json").body("""
                {"tableReference":{"tableId":"events"},
                 "schema":{"fields":[{"name":"id","type":"INT64"}]},
                 "timePartitioning":{"type":"DAY"}}
                """).when().post(BASE + "/datasets/partitions/tables").then().statusCode(200);

        Map<String, Object> first = Map.of("datasetId", "partitions", "tableId", "events$20261006");
        insertJob(Map.of("query", "SELECT 1 AS id", "useLegacySql", false, "destinationTable", first,
                "writeDisposition", "WRITE_TRUNCATE")).then().statusCode(200)
                .body("status.errorResult", nullValue())
                .body("configuration.query.destinationTable.tableId", equalTo("events$20261006"));
        insertJob(Map.of("query", "SELECT 2 AS id", "useLegacySql", false, "destinationTable", first,
                "writeDisposition", "WRITE_APPEND")).then().statusCode(200);
        insertJob(Map.of("query", "SELECT 3 AS id", "useLegacySql", false, "destinationTable",
                Map.of("datasetId", "partitions", "tableId", "events$20261007"),
                "writeDisposition", "WRITE_TRUNCATE")).then().statusCode(200);
        insertJob(Map.of("query", "SELECT 4 AS id", "useLegacySql", false,
                "destinationTable", first, "writeDisposition", "WRITE_TRUNCATE")).then().statusCode(200);

        assertEquals(List.of(List.of("3"), List.of("4")),
                rows("SELECT id FROM partitions.events ORDER BY id"));
        query("SELECT * FROM partitions.events ORDER BY id").then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id")))
                .body("rows.f.v", equalTo(List.of(List.of("3"), List.of("4"))));
        // GoogleSQL reads a partition by filtering its partitioning column or the ingestion-time
        // pseudocolumns. Partition decorators are for load, copy, export, and table-data operations.
        query("SELECT * REPLACE (id + 100 AS id), id AS original_id FROM partitions.events"
                + " WHERE _PARTITIONDATE = DATE '2026-10-06'").then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "original_id")))
                .body("rows.f.v", equalTo(List.of(List.of("104", "4"))));
        assertEquals(List.of(List.of("1")), rows("SELECT COUNT(*) FROM partitions.events"
                + " WHERE _PARTITIONTIME = TIMESTAMP '2026-10-07 00:00:00+00'"));

        query("UPDATE partitions.events SET id = id + 10"
                + " WHERE _PARTITIONDATE = DATE '2026-10-06'").then().statusCode(200)
                .body("numDmlAffectedRows", equalTo("1"));
        assertEquals(List.of(List.of("3"), List.of("14")),
                rows("SELECT id FROM partitions.events ORDER BY id"));
        assertEquals(List.of(List.of("20261006"), List.of("20261007")),
                rows("SELECT partition_id FROM partitions.INFORMATION_SCHEMA.PARTITIONS"
                        + " WHERE table_name = 'events' ORDER BY partition_id"));

        Map<String, Object> old = Map.of("datasetId", "partitions", "tableId", "events$19690101");
        insertJob(Map.of("query", "SELECT 20 AS id", "useLegacySql", false, "destinationTable", old,
                "writeDisposition", "WRITE_TRUNCATE")).then().statusCode(200)
                .body("status.errorResult", nullValue());
        query("UPDATE partitions.events SET id = id + 1"
                + " WHERE _PARTITIONDATE = DATE '1969-01-01'").then().statusCode(200)
                .body("numDmlAffectedRows", equalTo("1"));
        assertEquals(List.of(List.of("21")), rows("SELECT id FROM partitions.events"
                + " WHERE _PARTITIONDATE = DATE '1969-01-01'"));

        query("UPDATE partitions.events SET _PARTITIONTIME = CASE"
                + " WHEN id = 21 THEN TIMESTAMP '2026-10-06 00:00:00+00'"
                + " WHEN id = 14 THEN TIMESTAMP '1969-01-01 00:00:00+00'"
                + " ELSE _PARTITIONTIME END WHERE id IN (14, 21)")
                .then().statusCode(400).body("error.errors[0].reason", equalTo("invalidQuery"));
        assertEquals(List.of(List.of("21")), rows("SELECT id FROM partitions.events"
                + " WHERE _PARTITIONDATE = DATE '1969-01-01'"));
        assertEquals(List.of(List.of("14")), rows("SELECT id FROM partitions.events"
                + " WHERE _PARTITIONDATE = DATE '2026-10-06'"));

        query("INSERT INTO partitions.events(id) SELECT * FROM partitions.events WHERE id = 3")
                .then().statusCode(200).body("numDmlAffectedRows", equalTo("1"));
        assertEquals(List.of(List.of("2")), rows("SELECT COUNT(*) FROM partitions.events WHERE id = 3"));
    }

    @Test
    @Order(101)
    void insertPartitionTimeDerivesPartitionDate() {
        query("CREATE TABLE partitions.metrics (id INT64) PARTITION BY DATE(_PARTITIONTIME)")
                .then().statusCode(200);
        query("INSERT INTO partitions.metrics (id, _PARTITIONTIME) "
                + "SELECT 1, TIMESTAMP '2026-10-08 00:00:00+00'")
                .then().statusCode(200).body("errors", nullValue());

        assertEquals(List.of(List.of("1")),
                rows("SELECT id FROM partitions.metrics WHERE _PARTITIONDATE = DATE '2026-10-08'"));
        query("INSERT INTO partitions.metrics (id, _PARTITIONTIME) "
                + "SELECT 2, TIMESTAMP '2026-10-08 12:30:00+00'")
                .then().statusCode(400).body("error.errors[0].reason", equalTo("invalidQuery"));
        query("INSERT INTO partitions.metrics (id, _PARTITIONTIME) "
                + "SELECT 3, TIMESTAMP '1969-12-31 00:00:00+00'")
                .then().statusCode(400).body("error.errors[0].reason", equalTo("invalidQuery"));
        assertEquals(List.of(List.of("1")), rows("SELECT COUNT(*) FROM partitions.metrics"));
        query("INSERT INTO partitions.metrics VALUES (2)")
                .then().statusCode(400).body("error.errors[0].reason", equalTo("invalidQuery"));
        assertEquals(List.of(List.of("0")), rows("SELECT COUNT(*) FROM partitions.metrics WHERE id = 2"));
    }

    @Test
    @Order(102)
    void hourlyPartitionTimeSurvivesInternalStaging() {
        given().contentType("application/json").body("""
                {"tableReference":{"tableId":"hourly_events"},
                 "schema":{"fields":[{"name":"id","type":"INT64"}]},
                 "timePartitioning":{"type":"HOUR"}}
                """).when().post(BASE + "/datasets/partitions/tables").then().statusCode(200);
        insertJob(Map.of("query", "SELECT 1 AS id", "useLegacySql", false, "destinationTable",
                Map.of("datasetId", "partitions", "tableId", "hourly_events$2026100812"),
                "writeDisposition", "WRITE_TRUNCATE")).then().statusCode(200)
                .body("status.errorResult", nullValue());

        assertEquals(List.of(List.of("1")), rows("SELECT COUNT(*) FROM partitions.hourly_events"
                + " WHERE _PARTITIONTIME = TIMESTAMP '2026-10-08 12:00:00+00'"));
        query("SELECT _PARTITIONDATE FROM partitions.hourly_events").then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"));
    }

    @Test
    @Order(103)
    void queryTimestampValuesRouteToPartitions() {
        query("CREATE TABLE partitions.by_timestamp PARTITION BY DATE(ts) AS "
                + "SELECT TIMESTAMP '2026-10-01 00:00:00+00' AS ts").then().statusCode(200);

        given().when().get(BASE + "/datasets/partitions/tables/by_timestamp$20261001/data")
                .then().statusCode(200).body("totalRows", equalTo("1"));
        assertEquals(List.of(List.of("20261001")),
                rows("SELECT partition_id FROM partitions.INFORMATION_SCHEMA.PARTITIONS"
                        + " WHERE table_name = 'by_timestamp'"));
        query("SELECT _PARTITIONTIME FROM partitions.by_timestamp").then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"));

        query("MERGE partitions.by_timestamp t "
                + "USING (SELECT TIMESTAMP '2026-10-02 00:00:00+00' AS ts) s ON FALSE "
                + "WHEN NOT MATCHED THEN INSERT VALUES (s.ts)").then().statusCode(200)
                .body("numDmlAffectedRows", equalTo("1"));
        assertEquals(List.of(List.of("2")), rows("SELECT COUNT(*) FROM partitions.by_timestamp"));
        assertEquals(List.of(List.of("20261001"), List.of("20261002")),
                rows("SELECT partition_id FROM partitions.INFORMATION_SCHEMA.PARTITIONS"
                        + " WHERE table_name = 'by_timestamp' ORDER BY partition_id"));
    }

    @Test
    @Order(104)
    void updatingPartitionTimeMovesAnIngestionTimeRow() {
        query("CREATE TABLE partitions.movable_events (id INT64) PARTITION BY DATE(_PARTITIONTIME)")
                .then().statusCode(200);
        query("INSERT INTO partitions.movable_events (id, _PARTITIONTIME) "
                + "SELECT 1, TIMESTAMP '2026-10-08 00:00:00+00'").then().statusCode(200);

        query("UPDATE partitions.movable_events "
                + "SET _PARTITIONTIME = TIMESTAMP '2026-10-09 00:00:01+00' WHERE id = 1")
                .then().statusCode(400).body("error.errors[0].reason", equalTo("invalidQuery"));
        assertEquals(List.of(List.of("1")), rows("SELECT COUNT(*) FROM partitions.movable_events "
                + "WHERE _PARTITIONDATE = DATE '2026-10-08'"));

        query("UPDATE partitions.movable_events "
                + "SET _PARTITIONTIME = TIMESTAMP '2026-10-09 00:00:00+00' WHERE id = 1")
                .then().statusCode(200).body("numDmlAffectedRows", equalTo("1"));

        assertEquals(List.of(List.of("0")), rows("SELECT COUNT(*) FROM partitions.movable_events "
                + "WHERE _PARTITIONDATE = DATE '2026-10-08'"));
        assertEquals(List.of(List.of("1")), rows("SELECT COUNT(*) FROM partitions.movable_events "
                + "WHERE _PARTITIONDATE = DATE '2026-10-09'"));
    }

    @Test
    @Order(105)
    void truncatedPartitionTimeCreatesAnIngestionTimePartitionedTable() {
        query("CREATE TABLE partitions.hourly_ingestion (id INT64) "
                + "PARTITION BY TIMESTAMP_TRUNC(_PARTITIONTIME, HOUR)").then().statusCode(200);
        given().when().get(BASE + "/datasets/partitions/tables/hourly_ingestion")
                .then().statusCode(200)
                .body("timePartitioning.type", equalTo("HOUR"))
                .body("timePartitioning.field", nullValue());

        query("INSERT INTO partitions.hourly_ingestion (id) VALUES (1)")
                .then().statusCode(200).body("numDmlAffectedRows", equalTo("1"));
        assertEquals(List.of(List.of("1")),
                rows("SELECT COUNT(*) FROM partitions.hourly_ingestion WHERE _PARTITIONTIME IS NOT NULL"));
    }

    @Test
    @Order(106)
    void createTableCopyCopiesAStandaloneTableAndRejectsViews() {
        query("CREATE SCHEMA ddl_copy").then().statusCode(200);
        query("CREATE TABLE ddl_copy.copy_source AS SELECT 7 AS id").then().statusCode(200);
        query("CREATE VIEW ddl_copy.copy_source_view AS SELECT 8 AS id").then().statusCode(200);

        String jobId = query("CREATE TABLE ddl_copy.copy_destination COPY ddl_copy.copy_source")
                .then().statusCode(200)
                .extract().jsonPath().getString("jobReference.jobId");
        given().when().get(BASE + "/jobs/" + jobId).then().statusCode(200)
                .body("statistics.query.statementType", equalTo("CREATE_TABLE_COPY"))
                .body("statistics.query.ddlOperationPerformed", equalTo("CREATE"));
        assertEquals(List.of(List.of("7")), rows("SELECT id FROM ddl_copy.copy_destination"));

        query("CREATE OR REPLACE TABLE ddl_copy.copy_destination COPY ddl_copy.copy_source_view")
                .then().statusCode(400).body("error.errors[0].reason", equalTo("invalidQuery"));
        assertEquals(List.of(List.of("7")), rows("SELECT id FROM ddl_copy.copy_destination"));
    }

    @Test
    @Order(13)
    void defaultDatasetInAnotherProjectHoldsUnqualifiedTables() {
        String data = "/bigquery/v2/projects/bq-dml-data";
        Map<String, Object> defaultDataset = Map.of("projectId", "bq-dml-data", "datasetId", "inv");
        given().contentType("application/json").body(Map.of("datasetReference", Map.of("datasetId", "inv")))
                .when().post(data + "/datasets").then().statusCode(200);

        insertJob(Map.of("query", "CREATE TABLE stock (sku STRING, qty INT64)", "useLegacySql", false,
                "defaultDataset", defaultDataset)).then().statusCode(200)
                .body("status.errorResult", nullValue())
                .body("statistics.query.ddlTargetTable.projectId", equalTo("bq-dml-data"));
        insertJob(Map.of("query", "INSERT INTO stock VALUES ('a', 2), ('b', 3)", "useLegacySql", false,
                "defaultDataset", defaultDataset)).then().statusCode(200)
                .body("status.errorResult", nullValue());

        given().contentType("application/json")
                .body(Map.of("query", "SELECT SUM(qty) AS total FROM stock", "useLegacySql", false,
                        "defaultDataset", defaultDataset))
                .when().post(BASE + "/queries")
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("5"))
                .body("jobReference.projectId", equalTo(PROJECT));
        given().when().get(data + "/datasets/inv/tables/stock/data").then().statusCode(200)
                .body("totalRows", equalTo("2"));
        given().when().get(BASE + "/datasets/inv/tables/stock").then().statusCode(404);
    }
}

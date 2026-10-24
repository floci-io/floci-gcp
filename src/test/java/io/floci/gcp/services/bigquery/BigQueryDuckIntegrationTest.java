package io.floci.gcp.services.bigquery;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs GoogleSQL end to end on the real floci-duck sidecar. Needs Docker; skipped when no
 * Docker daemon is reachable or {@code -Dfloci.skip-docker-tests=true} is set.
 * {@code -Dfloci.duck.image=...} selects the sidecar image, so the same cases cover both a
 * floci-duck that reports column types and an older one (the DESCRIBE fallback).
 */
@QuarkusTest
@TestProfile(BigQueryDuckIntegrationTest.DuckEngineProfile.class)
@EnabledIf("io.floci.gcp.services.bigquery.BigQueryDuckIntegrationTest#dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BigQueryDuckIntegrationTest {

    private static final String PROJECT = "bq-duck-it";
    private static final String BASE = "/bigquery/v2/projects/" + PROJECT;

    public static class DuckEngineProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // floci-duck calls back into the emulator, so the advertised port must be the one
            // the test instance listens on.
            return Map.of(
                    "floci-gcp.services.bigquery.mock", "false",
                    "floci-gcp.services.bigquery.duck.default-image",
                    System.getProperty("floci.duck.image", "floci/floci-duck:latest"),
                    "quarkus.http.test-port", "18588",
                    "floci-gcp.port", "18588",
                    "floci-gcp.docker.resource-namespace", "bq-duck-it");
        }
    }

    static boolean dockerAvailable() {
        if (Boolean.getBoolean("floci.skip-docker-tests")) {
            return false;
        }
        return System.getenv("DOCKER_HOST") != null || Files.exists(Path.of("/var/run/docker.sock"));
    }

    private static Response query(String body) {
        return given().contentType("application/json").body(body).when().post(BASE + "/queries");
    }

    @Inject
    DuckSqlEngine engine;

    private static Response query(Map<String, Object> body) {
        return given().contentType("application/json").body(body).when().post(BASE + "/queries");
    }

    @Test
    @Order(1)
    void seedTables() {
        given().contentType("application/json")
                .body("{\"datasetReference\": {\"datasetId\": \"shop\"}}")
                .when().post(BASE + "/datasets").then().statusCode(200);
        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "users"}, "schema": {"fields": [
                  {"name": "id", "type": "INT64"},
                  {"name": "name", "type": "STRING"},
                  {"name": "joined", "type": "TIMESTAMP"},
                  {"name": "tags", "type": "STRING", "mode": "REPEATED"},
                  {"name": "address", "type": "RECORD", "fields": [{"name": "city", "type": "STRING"}]}
                ]}}
                """).when().post(BASE + "/datasets/shop/tables").then().statusCode(200);
        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "orders"}, "schema": {"fields": [
                  {"name": "user_id", "type": "INT64"},
                  {"name": "total", "type": "NUMERIC"}
                ]}}
                """).when().post(BASE + "/datasets/shop/tables").then().statusCode(200);
        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "empty"}, "schema": {"fields": [{"name": "x", "type": "INT64"}]}}
                """).when().post(BASE + "/datasets/shop/tables").then().statusCode(200);

        given().contentType("application/json").body("""
                {"rows": [
                  {"json": {"id": 1, "name": "ana", "joined": "2024-01-02T03:04:05.5Z", "tags": ["a", "b"],
                            "address": {"city": "Lima"}}},
                  {"json": {"id": 2, "name": "bo", "joined": 1704164645, "tags": [], "address": null}},
                  {"json": {"id": 3, "name": "cyd", "joined": "2023-10-01 12:00 UTC", "tags": [], "address": null}}
                ]}
                """).when().post(BASE + "/datasets/shop/tables/users/insertAll")
                .then().statusCode(200).body("insertErrors", nullValue());
        given().contentType("application/json").body("""
                {"rows": [
                  {"json": {"user_id": 1, "total": "10.50"}},
                  {"json": {"user_id": 1, "total": "4.25"}},
                  {"json": {"user_id": 2, "total": "1"}}
                ]}
                """).when().post(BASE + "/datasets/shop/tables/orders/insertAll")
                .then().statusCode(200).body("insertErrors", nullValue());
    }

    @Test
    @Order(2)
    void joinGroupByOrderBy() {
        query("""
                {"query": "SELECT u.name, SUM(o.total) AS spent, COUNT(*) FROM `bq-duck-it.shop.users` u JOIN shop.orders o ON u.id = o.user_id GROUP BY u.name ORDER BY spent DESC", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("jobComplete", equalTo(true))
                .body("schema.fields.name", equalTo(List.of("name", "spent", "f0_")))
                .body("schema.fields.type", equalTo(List.of("STRING", "NUMERIC", "INTEGER")))
                .body("rows", hasSize(2))
                .body("rows[0].f.v", equalTo(List.of("ana", "14.75", "2")))
                .body("rows[1].f.v", equalTo(List.of("bo", "1", "1")));
    }

    @Test
    @Order(3)
    void namedParameters() {
        query("""
                {"query": "SELECT name FROM shop.users WHERE id = @id AND name IN UNNEST(@names)",
                 "parameterMode": "NAMED", "useLegacySql": false,
                 "queryParameters": [
                   {"name": "id", "parameterType": {"type": "INT64"}, "parameterValue": {"value": "2"}},
                   {"name": "names", "parameterType": {"type": "ARRAY", "arrayType": {"type": "STRING"}},
                    "parameterValue": {"arrayValues": [{"value": "bo"}, {"value": "x"}]}}
                 ]}
                """)
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("bo"));
    }

    @Test
    @Order(4)
    void timestampArrayAndRecordRoundTrip() {
        Response resp = query("""
                {"query": "SELECT joined, tags, address FROM shop.users ORDER BY id", "useLegacySql": false,
                 "formatOptions": {"useInt64Timestamp": true}}
                """);
        resp.then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("TIMESTAMP", "STRING", "RECORD")))
                .body("schema.fields[1].mode", equalTo("REPEATED"))
                .body("rows[0].f[0].v", equalTo("1704164645500000"))
                .body("rows[0].f[1].v.v", equalTo(List.of("a", "b")))
                .body("rows[0].f[2].v.f[0].v", equalTo("Lima"))
                .body("rows[1].f[0].v", equalTo("1704164645000000"))
                .body("rows[1].f[2].v", nullValue())
                .body("rows[2].f[0].v", equalTo("1696161600000000"))
                .body("rows[2].f[2].v", nullValue());

        String jobId = resp.jsonPath().getString("jobReference.jobId");
        given().when().get(BASE + "/queries/" + jobId).then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1704164645.5"));
    }

    @Test
    @Order(5)
    void emptyTableAndCte() {
        query("""
                {"query": "WITH e AS (SELECT x FROM shop.empty) SELECT COUNT(*) AS n FROM e", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("0"));
    }

    @Test
    @Order(5)
    void arraysWithNullElementsFailTheQueryOnlyInTheResult() {
        assertNullElementError("SELECT ARRAY_AGG(x) AS a FROM UNNEST([1, NULL, 3]) x", "a");
        assertNullElementError("SELECT [1, NULL, 3] AS a", "a");
        assertNullElementError("SELECT 1 AS x, [CAST(NULL AS INT64)] AS b", "b");
        assertNullElementError("SELECT [STRUCT(1 AS v), NULL] AS a", "a");
        assertNullElementError("SELECT STRUCT(1 AS id, [1, NULL] AS arr) AS s", "s.arr; error in writing field s");
        assertNullElementError("SELECT [STRUCT([1, NULL] AS inner_arr)] AS a",
                "a.inner_arr; error in writing field a");
        query("""
                {"query": "SELECT ARRAY_LENGTH(a) AS n FROM (SELECT ARRAY_AGG(x) AS a FROM UNNEST([1, NULL, 3]) x)", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("3")));
        query("""
                {"query": "SELECT ARRAY_AGG(STRUCT(x AS v) ORDER BY x) AS a FROM UNNEST([1, NULL]) x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v.f.v", equalTo(List.of(List.of("1"), Arrays.asList((String) null))));
    }

    private static void assertNullElementError(String sql, String field) {
        query("{\"query\": \"" + sql + "\", \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", equalTo("Array cannot have a null element; error in writing field " + field));
    }

    @Test
    @Order(5)
    void parenthesizedQueriesAndNestedCtesRun() {
        query("""
                {"query": "(SELECT 1 + 1)", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("f0_")))
                .body("rows[0].f.v", equalTo(List.of("2")));
        query("""
                {"query": "(SELECT 1 x) UNION ALL (SELECT 2 x) ORDER BY x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("1"), List.of("2"))));
        query("""
                {"query": "(WITH s AS (SELECT 1 x) SELECT x + 1 FROM s)", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("f0_")))
                .body("rows[0].f.v", equalTo(List.of("2")));
        query("""
                {"query": "SELECT *, (WITH s AS (SELECT 1 x) SELECT x FROM s) y FROM (WITH s AS (SELECT 1 x) SELECT x FROM s)", "useLegacySql": false}
                """)
        .then().statusCode(200)
        .body("schema.fields.name", equalTo(List.of("x", "y")))
        .body("rows[0].f.v", equalTo(List.of("1", "1")));
    }

    @Test
    @Order(5)
    void resultWrapperResolvesNestedCtes() {
        given().contentType("application/json").body(Map.of(
                "query", """
                        WITH INPUT AS (
                          WITH new_aggregated AS (SELECT name FROM shop.users),
                               nested_results AS (SELECT name FROM new_aggregated)
                          SELECT name FROM nested_results
                        )
                        SELECT TO_JSON_STRING(row) FROM INPUT AS row ORDER BY name
                        """,
                "useLegacySql", false))
                .when().post(BASE + "/queries")
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("f0_")))
                .body("schema.fields.type", equalTo(List.of("STRING")))
                .body("rows", hasSize(3));
    }

    @Test
    @Order(5)
    void anonymousSubqueryColumnsAreNamedInTheResultLikeBigQuery() {
        assertResultColumns("SELECT * FROM (SELECT 1 + 1)", List.of("f0_"), List.of("2"));
        assertResultColumns("SELECT * FROM (SELECT 1 + 1, 'a' || 'b')", List.of("f0_", "f1_"), List.of("2", "ab"));
        assertResultColumns("SELECT * FROM (SELECT 1 + 1, 2 AS x, 3 + 3)", List.of("f0_", "x", "f1_"),
                List.of("2", "2", "6"));
        assertResultColumns("SELECT * FROM (SELECT * FROM (SELECT 1 + 1))", List.of("f0_"), List.of("2"));
        assertResultColumns("WITH s AS (SELECT 1 + 1) SELECT * FROM s", List.of("f0_"), List.of("2"));
        assertResultColumns("SELECT t.* FROM (SELECT 1 + 1) t", List.of("f0_"), List.of("2"));
        assertResultColumns("SELECT * FROM (SELECT 1)", List.of("f0_"), List.of("1"));
        assertResultColumns("SELECT *, 9 + 9 FROM (SELECT 2 + 2, 3 AS x)", List.of("f0_", "x", "f1_"),
                List.of("4", "3", "18"));
        assertResultColumns("SELECT * FROM (SELECT 1 + 1 UNION ALL SELECT 2 + 2) ORDER BY 1", List.of("f0_"),
                List.of("2"));
        assertResultColumns("SELECT (SELECT 1 + 1) AS v, 1 + 1", List.of("v", "f0_"), List.of("2", "2"));
        assertResultColumns("SELECT *, 9 AS f0_ FROM (SELECT 1 + 1)", List.of("f1_", "f0_"), List.of("2", "9"));
        assertResultColumns("SELECT 1 + 1, 9 AS f0_", List.of("f1_", "f0_"), List.of("2", "9"));
        assertResultColumns("SELECT *, 9 AS f1_ FROM (SELECT 1 + 1, 2 + 2)", List.of("f0_", "f2_", "f1_"),
                List.of("2", "4", "9"));
        assertResultColumns("SELECT * FROM (SELECT 1 + 1, 9 AS f0_)", List.of("f1_", "f0_"), List.of("2", "9"));
        assertResultColumns("SELECT * FROM (SELECT * EXCEPT (x), 9 + 9 FROM (SELECT 2 + 2, 3 AS x))",
                List.of("f0_", "f1_"), List.of("4", "18"));
        for (String sql : List.of("SELECT 1 + 1, * FROM (SELECT 2 + 2)",
                "SELECT * FROM (SELECT 1 + 1) a, (SELECT 2 + 2) b")) {
            query("{\"query\": \"" + sql + "\", \"useLegacySql\": false}")
                    .then().statusCode(400)
                    .body("error.message", containsString("Duplicate column names in the result are not supported."
                            + " Found duplicate(s): $col1"));
        }
    }

    private static void assertResultColumns(String sql, List<String> names, List<String> firstRow) {
        query("{\"query\": \"" + sql + "\", \"useLegacySql\": false}")
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(names))
                .body("rows[0].f.v", equalTo(firstRow));
    }

    @Test
    @Order(5)
    void jsonTypeReturnsBigQueryTypeNames() {
        query("""
                {"query": "SELECT JSON_TYPE(JSON '{\\"a\\": 1}'), JSON_TYPE(JSON '[1, 2]'), JSON_TYPE(JSON '\\"s\\"'), JSON_TYPE(JSON '20'), JSON_TYPE(JSON '-3'), JSON_TYPE(JSON '1.5'), JSON_TYPE(JSON '18446744073709551615'), JSON_TYPE(JSON 'true'), JSON_TYPE(JSON 'null'), JSON_TYPE(CAST(NULL AS JSON))", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(java.util.Arrays.asList("object", "array", "string", "number",
                        "number", "number", "number", "boolean", "null", null)));
    }

    @Test
    @Order(5)
    void containsSubstrExecutesWithBigQueryScalarSemantics() {
        query("""
                {"query": "SELECT CONTAINS_SUBSTR('A, B', ',') AS contains_comma, CONTAINS_SUBSTR('Ⅸ day', 'IX') AS compatibility, CONTAINS_SUBSTR('Straße', 'STRASSE') AS case_expansion, CONTAINS_SUBSTR('ＡＢＣ', 'abc') AS width, CONTAINS_SUBSTR('é', 'e') AS keeps_accents, CONTAINS_SUBSTR(CAST(NULL AS STRING), 'x') IS NULL AS keeps_null", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("BOOLEAN"))
                .body("rows[0].f.v", equalTo(List.of("true", "true", "true", "true", "false", "true")));
    }

    @Test
    @Order(5)
    void selectAsStructAliasesRemainVisibleToHavingAndOrderBy() {
        query("""
                {"query": "SELECT AS STRUCT COUNT(*) AS n FROM UNNEST([1, 2]) x HAVING n > 1 ORDER BY n", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("RECORD"))
                .body("schema.fields[0].fields[0].name", equalTo("n"))
                .body("rows[0].f[0].v.f[0].v", equalTo("2"));

        query("""
                {"query": "SELECT AS STRUCT 1 + 1 ORDER BY 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("RECORD"))
                .body("schema.fields[0].fields[0].name", equalTo("_field_1"))
                .body("rows[0].f[0].v.f[0].v", equalTo("2"));
    }

    @Test
    @Order(5)
    void distinctCountIfCountsOnlyDistinctTrueResults() {
        for (String values : List.of("[1, 2, -1, NULL]", "[-1, 0]", "ARRAY<INT64>[NULL]", "ARRAY<INT64>[]")) {
            String expected = values.equals("[1, 2, -1, NULL]") ? "1" : "0";
            query(Map.of("query", "SELECT COUNTIF(DISTINCT x > 0) AS c FROM UNNEST(" + values + ") x",
                            "useLegacySql", false))
                    .then().log().ifValidationFails().statusCode(200)
                    .body("schema.fields[0].type", equalTo("INTEGER"))
                    .body("rows[0].f[0].v", equalTo(expected));
        }
        query(Map.of("query", "SELECT COUNTIF(DISTINCT x > 0) OVER () AS c FROM UNNEST([1, 2, -1]) x",
                        "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("1"), List.of("1"), List.of("1"))));
    }

    @Test
    @Order(5)
    void pivotSourcesApplyAliasAndHavingRewrites() {
        query(Map.of("query", "SELECT * FROM (SELECT 1 sample, 'one' AS metric)"
                        + " PIVOT(ANY_VALUE(sample) FOR metric IN ('one'))", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("one")))
                .body("rows[0].f[0].v", equalTo("1"));
        query(Map.of("query", "SELECT one.n AS n FROM (SELECT STRUCT(MAX(x) AS n) AS s, 'one' AS metric"
                        + " FROM UNNEST([1,2]) x HAVING s.n > 0)"
                        + " PIVOT(ANY_VALUE(s) FOR metric IN ('one'))", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));
    }

    @Test
    @Order(5)
    void pivotSourcesApplyNestedSelectAsStructRewrites() {
        query(Map.of("query", "SELECT one.x FROM"
                        + " (SELECT (SELECT AS STRUCT 1 AS x) AS s, 'one' AS k)"
                        + " PIVOT(ANY_VALUE(s) FOR k IN ('one'))", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1"));
    }

    @Test
    @Order(5)
    void pivotExecutesOnTheDuckSidecar() {
        query(Map.of("query", """
                SELECT one, two
                FROM (
                  SELECT 'one' AS metric, 1 AS value
                  UNION ALL
                  SELECT 'two' AS metric, 2 AS value
                )
                PIVOT (ANY_VALUE(value) FOR metric IN ('one', 'two'))
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("one", "two")))
                .body("rows[0].f.v", equalTo(List.of("1", "2")));
    }

    @Test
    @Order(5)
    void mixedStructStarsExpandIntoSiblingFields() {
        for (String sql : List.of(
                "SELECT AS STRUCT t.*, 2 AS y FROM (SELECT 1 AS x) t",
                "SELECT AS STRUCT t.*, u.* FROM (SELECT 1 AS x) t CROSS JOIN (SELECT 2 AS y) u",
                "SELECT AS STRUCT t.* FROM (SELECT 1 AS x, 2 AS y) t",
                "SELECT AS STRUCT t.* EXCEPT(secret), 2 AS y FROM (SELECT 1 AS x, 9 AS secret) t",
                "SELECT (SELECT AS STRUCT t.*, 2 AS y FROM (SELECT 1 AS x) t) AS record",
                "SELECT DISTINCT AS STRUCT t.*, 2 y FROM (SELECT 1 AS x UNION ALL SELECT 1 AS x) t ORDER BY x LIMIT 1")) {
            query(Map.of("query", sql, "useLegacySql", false))
                    .then().log().ifValidationFails().statusCode(200)
                    .body("schema.fields", hasSize(1))
                    .body("rows", hasSize(1))
                    .body("schema.fields[0].type", equalTo("RECORD"))
                    .body("schema.fields[0].fields.name", equalTo(List.of("x", "y")))
                    .body("rows[0].f[0].v.f.v", equalTo(List.of("1", "2")));
        }
    }

    @Test
    @Order(5)
    void mixedStructStarsPreserveLaterNestedQueryRewrites() {
        for (String sql : List.of(
                "SELECT AS STRUCT t.*, 2 AS y FROM (SELECT 1 sample) t",
                "SELECT AS STRUCT t.*, 2 AS y FROM (SELECT * FROM"
                        + " (SELECT 'sample' AS metric, 1 AS amount)"
                        + " PIVOT(ANY_VALUE(amount) FOR metric IN ('sample'))) t")) {
            query(Map.of("query", sql, "useLegacySql", false))
                    .then().log().ifValidationFails().statusCode(200)
                    .body("schema.fields[0].fields.name", equalTo(List.of("sample", "y")))
                    .body("rows[0].f[0].v.f.v", equalTo(List.of("1", "2")));
        }
        query(Map.of("query", "SELECT AS STRUCT t.*, 2 AS y FROM"
                        + " (SELECT STRUCT(MAX(x) AS n) AS sample FROM UNNEST([1]) x HAVING sample.n = 1) t",
                        "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("sample", "y")))
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v.f[0].v.f[0].v", equalTo("1"))
                .body("rows[0].f[0].v.f[1].v", equalTo("2"));
    }

    @Test
    @Order(5)
    void quotedDerivedTableAliasesWorkWithoutAs() {
        query(Map.of("query", "SELECT `select`.x FROM (SELECT 1 AS x) `select`", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x")))
                .body("rows[0].f[0].v", equalTo("1"));
    }

    @Test
    @Order(5)
    void safeFromHexDecodesBytesAndReturnsNullForInvalidHex() {
        query(Map.of("query", """
                SELECT SAFE.FROM_HEX('4142') AS decoded,
                  TO_HEX(SAFE.FROM_HEX('aBc')) AS odd,
                  SAFE.FROM_HEX('') AS empty,
                  SAFE.FROM_HEX('xyz') AS invalid,
                  SAFE.FROM_HEX('0x41') AS prefixed,
                  SAFE.FROM_HEX('aa bb') AS spaced,
                  SAFE.FROM_HEX('é') AS non_ascii,
                  SAFE.FROM_HEX(NULL) AS untyped_null,
                  SAFE.FROM_HEX(CAST(NULL AS STRING)) AS typed_null
                """, "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields[0].type", equalTo("BYTES"))
                .body("rows[0].f[0].v", equalTo("QUI="))
                .body("rows[0].f[1].v", equalTo("0abc"))
                .body("rows[0].f[2].v", equalTo(""))
                .body("rows[0].f[3].v", nullValue())
                .body("rows[0].f[4].v", nullValue())
                .body("rows[0].f[5].v", nullValue())
                .body("rows[0].f[6].v", nullValue())
                .body("rows[0].f[7].v", nullValue())
                .body("rows[0].f[8].v", nullValue());
    }

    @Test
    @Order(5)
    void safeFromHexDoesNotSuppressArgumentErrorsOrAcceptNumericInputs() {
        query(Map.of("query", "SELECT SAFE.FROM_HEX(CAST(ERROR('argument failed') AS STRING)) AS decoded",
                        "useLegacySql", false))
                .then().statusCode(400)
                .body("error.message", containsString("argument failed"));
        query(Map.of("query", "SELECT SAFE.FROM_HEX(123) AS decoded", "useLegacySql", false))
                .then().statusCode(400);
    }

    @Test
    @Order(5)
    void aliasedStructOffsetsExposeFieldsAndPreserveTheWholeRecord() {
        for (String projection : List.of("x, pos", "*")) {
            query(Map.of("query", "SELECT " + projection
                            + " FROM UNNEST([STRUCT(1 AS x)]) AS item WITH OFFSET AS pos", "useLegacySql", false))
                    .then().log().ifValidationFails().statusCode(200)
                    .body("schema.fields.name", equalTo(List.of("x", "pos")))
                    .body("rows[0].f.v", equalTo(List.of("1", "0")));
        }
        query(Map.of("query", "SELECT item, item.x AS qualified, pos FROM"
                        + " UNNEST([STRUCT(1 AS x)]) AS item WITH OFFSET AS pos", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields[0].type", equalTo("RECORD"))
                .body("schema.fields[0].fields.name", equalTo(List.of("x")))
                .body("rows[0].f[0].v.f[0].v", equalTo("1"))
                .body("rows[0].f[1].v", equalTo("1"))
                .body("rows[0].f[2].v", equalTo("0"));
        query(Map.of("query", "SELECT item.* FROM UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos",
                        "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x")))
                .body("rows[0].f[0].v", equalTo("1"));
    }

    @Test
    @Order(5)
    void offsetArrayColumnsAndJoinedStarsRetainTheirVisibleFields() {
        query(Map.of("query", "WITH inputs AS (SELECT [STRUCT(1 AS x)] AS items)"
                        + " SELECT x, pos, item FROM inputs CROSS JOIN UNNEST(items) item WITH OFFSET AS pos",
                        "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1"))
                .body("rows[0].f[1].v", equalTo("0"))
                .body("schema.fields[2].fields.name", equalTo(List.of("x")));
        query(Map.of("query", "SELECT * FROM (SELECT 9 AS marker) d CROSS JOIN"
                        + " UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos CROSS JOIN"
                        + " UNNEST([STRUCT(2 AS y)]) other WITH OFFSET AS idx", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("marker", "x", "pos", "y", "idx")))
                .body("rows[0].f.v", equalTo(List.of("9", "1", "0", "2", "0")));
        query(Map.of("query", "SELECT * REPLACE(x + 1 AS x) FROM"
                        + " UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x", "pos")))
                .body("rows[0].f.v", equalTo(List.of("2", "0")));
        query(Map.of("query", "SELECT * EXCEPT(pos) FROM"
                        + " UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x")));
    }

    @Test
    @Order(5)
    void leftJoinedOffsetStructsKeepNullWholeRecords() {
        query(Map.of("query", "SELECT item, x, pos FROM"
                        + " (SELECT ARRAY<STRUCT<x INT64>>[] AS items) source"
                        + " LEFT JOIN UNNEST(items) item WITH OFFSET AS pos", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("rows", hasSize(1))
                .body("schema.fields[0].type", equalTo("RECORD"))
                .body("rows[0].f[0].v", nullValue())
                .body("rows[0].f[1].v", nullValue())
                .body("rows[0].f[2].v", nullValue());
    }

    @Test
    @Order(5)
    void offsetAliasesRemainVisibleInCorrelatedSubqueries() {
        for (String expression : List.of("(SELECT item.x)", "(SELECT (SELECT item.x))",
                "(SELECT item.x FROM (SELECT 9 AS unrelated) local)",
                "(SELECT inner_item.x FROM UNNEST([item]) inner_item)")) {
            query(Map.of("query", "SELECT " + expression + " AS x FROM"
                            + " UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos", "useLegacySql", false))
                    .then().log().ifValidationFails().statusCode(200)
                    .body("rows[0].f[0].v", equalTo("1"));
        }
        for (String from : List.of("(SELECT 2 AS x) item",
                "(SELECT STRUCT(2 AS x) AS item) local",
                "UNNEST([STRUCT(2 AS x)]) item",
                "UNNEST([STRUCT(2 AS x)]) item WITH OFFSET AS inner_pos",
                "UNNEST([STRUCT(STRUCT(2 AS x) AS item)]) local")) {
            query(Map.of("query", "SELECT (SELECT item.x FROM " + from + ") AS x FROM"
                            + " UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos", "useLegacySql", false))
                    .then().log().ifValidationFails().statusCode(200)
                    .body("rows[0].f[0].v", equalTo("2"));
        }
        query(Map.of("query", "SELECT (SELECT item) AS whole FROM"
                        + " UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("x")))
                .body("rows[0].f[0].v.f[0].v", equalTo("1"));
    }

    @Test
    @Order(5)
    void nestedAndMixedReplacementStarsHideOffsetHelpers() {
        for (String projection : List.of("* REPLACE(x + 1 AS x)",
                "* REPLACE(x + 1 AS x), item AS original")) {
            String inner = "SELECT " + projection
                    + " FROM UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos";
            for (String sql : List.of(inner, "SELECT * FROM (" + inner + ") q",
                    "WITH q AS (" + inner + ") SELECT * FROM q")) {
                List<String> names = projection.contains("original")
                        ? List.of("x", "pos", "original") : List.of("x", "pos");
                query(Map.of("query", sql, "useLegacySql", false))
                        .then().log().ifValidationFails().statusCode(200)
                        .body("schema.fields.name", equalTo(names))
                        .body("rows[0].f[0].v", equalTo("2"))
                        .body("rows[0].f[1].v", equalTo("0"));
            }
        }
    }

    @Test
    @Order(5)
    void parsedTimestampZonesOverrideTheFallbackZone() {
        query(Map.of("query", """
                SELECT
                  UNIX_SECONDS(PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S%z',
                    '2024-01-01 00:00:00+0000', 'America/Los_Angeles')) AS explicit_zone,
                  UNIX_SECONDS(SAFE.PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S%z',
                    '2024-01-01 00:00:00+0200', 'America/Los_Angeles')) AS safe_zone,
                  UNIX_SECONDS(PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S',
                    '2024-01-01 00:00:00', 'America/Los_Angeles')) AS fallback_zone,
                  UNIX_SECONDS(PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S %Z',
                    '2024-01-01 00:00:00 UTC', 'America/Los_Angeles')) AS named_zone,
                  UNIX_SECONDS(PARSE_TIMESTAMP('%Y-%m-%d %%z',
                    '2024-01-01 %z', 'America/Los_Angeles')) AS escaped_zone,
                  SAFE.PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S%z',
                    'invalid', 'America/Los_Angeles') AS invalid
                """, "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1704067200"))
                .body("rows[0].f[1].v", equalTo("1704060000"))
                .body("rows[0].f[2].v", equalTo("1704096000"))
                .body("rows[0].f[3].v", equalTo("1704067200"))
                .body("rows[0].f[4].v", equalTo("1704096000"))
                .body("rows[0].f[5].v", nullValue());
    }

    @Test
    @Order(5)
    void reviewedStructPivotAndHavingCasesExecuteOnTheDuckSidecar() {
        query(Map.of("query", "SELECT DISTINCT AS STRUCT x FROM UNNEST([1, 1]) x",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v.f[0].v", equalTo("1"));

        query(Map.of("query", "SELECT [STRUCT(STRUCT(1 AS x) AS child, 2 AS y)][OFFSET(0)].*",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("child", "y")))
                .body("schema.fields.type", equalTo(List.of("RECORD", "INTEGER")))
                .body("rows[0].f[0].v.f[0].v", equalTo("1"))
                .body("rows[0].f[1].v", equalTo("2"));

        query(Map.of("query", """
                SELECT a.id, p.one
                FROM (SELECT 1 AS id) a
                JOIN (SELECT 1 AS id, 'one' AS metric, 7 AS value) PIVOT (
                  ANY_VALUE(value) FOR metric IN ('one')) p
                ON a.id = p.id
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("1", "7")));

        query(Map.of("query", "SELECT STRUCT(MAX(x) AS n) AS s FROM UNNEST([1,2]) x"
                        + " HAVING (SELECT s.n FROM (SELECT STRUCT(0 AS n) AS s)) = 0",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1));
    }

    @Test
    @Order(5)
    void reviewedStarCasesExecuteOnTheDuckSidecar() {
        query(Map.of("query", "SELECT * EXCEPT(secret) REPLACE(UPPER(name) AS name)"
                        + " FROM UNNEST([STRUCT('ana' AS name, 'hidden' AS secret, 1 AS id)])",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("name", "id")))
                .body("rows[0].f.v", equalTo(List.of("ANA", "1")));

        query(Map.of("query", "CREATE TABLE shop.reviewed_star_2025 AS SELECT 1 AS id",
                        "useLegacySql", false))
                .then().statusCode(200);
        query(Map.of("query", "CREATE TABLE shop.reviewed_star_2026 AS SELECT 2 AS id",
                        "useLegacySql", false))
                .then().statusCode(200);

        query(Map.of("query", "SELECT d.*, w.* FROM"
                        + " (SELECT 'derived' AS marker, 'kept' AS _TABLE_SUFFIX) d"
                        + " CROSS JOIN `shop.reviewed_star_*` w ORDER BY id",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("marker", "_TABLE_SUFFIX", "id")))
                .body("rows[0].f.v", equalTo(List.of("derived", "kept", "1")));

        query(Map.of("query", "SELECT w.*, d.* FROM `shop.reviewed_star_*` w"
                        + " CROSS JOIN (SELECT 'derived' AS marker, 'kept' AS _TABLE_SUFFIX) d ORDER BY id",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "marker", "_TABLE_SUFFIX")))
                .body("rows[0].f.v", equalTo(List.of("1", "derived", "kept")));
    }

    @Test
    @Order(5)
    void reviewedAggregateModifiersExecuteOnTheDuckSidecar() {
        query(Map.of("query", """
                SELECT ARRAY_CONCAT_AGG(items ORDER BY id LIMIT 2) AS items
                FROM UNNEST([
                  STRUCT(3 AS id, [3] AS items),
                  STRUCT(1 AS id, [1, 10] AS items),
                  STRUCT(2 AS id, [2, 20] AS items)])
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("1", "10", "2", "20")));

        query(Map.of("query", "WITH q AS (SELECT APPROX_QUANTILES(x, 2 RESPECT NULLS) AS quantiles"
                        + " FROM UNNEST([NULL, NULL, 1, 1, 1, 4, 5, 6, 7, 8, 9, 10]) x)"
                        + " SELECT quantiles[OFFSET(0)] IS NULL, quantiles[OFFSET(1)], quantiles[OFFSET(2)] FROM q",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("true", "4", "10")));
    }

    @Test
    @Order(5)
    void structUnnestAliasPreservesTheWholeRecordWhenAFieldHasTheSameName() {
        query(Map.of("query", "SELECT al, al.al AS field, al.x"
                        + " FROM UNNEST([STRUCT(1 AS al, 2 AS x)]) al", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields[0].name", equalTo("al"))
                .body("schema.fields[0].type", equalTo("RECORD"))
                .body("schema.fields[0].fields.name", equalTo(List.of("al", "x")))
                .body("rows[0].f[0].v.f.v", equalTo(List.of("1", "2")))
                .body("rows[0].f[1].v", equalTo("1"))
                .body("rows[0].f[2].v", equalTo("2"));
        query(Map.of("query", "SELECT al.* FROM UNNEST([STRUCT(1 AS al, 2 AS x)]) al",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("al", "x")))
                .body("rows[0].f.v", equalTo(List.of("1", "2")));
    }

    @Test
    @Order(5)
    void arrayConcatAggLimitIgnoresNullArraysButCountsEmptyArrays() {
        query(Map.of("query", """
                SELECT ARRAY_CONCAT_AGG(items ORDER BY id LIMIT 2) AS items
                FROM UNNEST([
                  STRUCT(0 AS id, CAST(NULL AS ARRAY<INT64>) AS items),
                  STRUCT(1 AS id, [1] AS items), STRUCT(2 AS id, [2] AS items)])
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("1", "2")));
        query(Map.of("query", """
                SELECT ARRAY_CONCAT_AGG(items ORDER BY id LIMIT 2) AS items
                FROM UNNEST([
                  STRUCT(0 AS id, CAST(NULL AS ARRAY<INT64>) AS items),
                  STRUCT(1 AS id, ARRAY<INT64>[] AS items),
                  STRUCT(2 AS id, [2] AS items), STRUCT(3 AS id, [3] AS items)])
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("2")));
    }

    @Test
    @Order(5)
    void arrayConcatAggIsNullWithoutNonNullArraysButKeepsEmptyArrays() {
        query(Map.of("query", """
                SELECT ARRAY_CONCAT_AGG(items) IS NULL AS all_null
                FROM UNNEST([STRUCT(CAST(NULL AS ARRAY<INT64>) AS items),
                  STRUCT(CAST(NULL AS ARRAY<INT64>) AS items)])
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("true"));
        query(Map.of("query", """
                SELECT ARRAY_LENGTH(ARRAY_CONCAT_AGG(items)) AS len
                FROM UNNEST([STRUCT(ARRAY<INT64>[] AS items), STRUCT(CAST(NULL AS ARRAY<INT64>) AS items)])
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("0"));
    }

    @Test
    @Order(5)
    void selectAsStructResolvesOffsetAliasesInLaterClauses() {
        query(Map.of("query", """
                SELECT AS STRUCT x FROM UNNEST([STRUCT(1 AS x), STRUCT(2 AS x)]) item WITH OFFSET AS pos
                WHERE item.x = 2
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.size()", equalTo(1))
                .body("rows[0].f[0].v.f[0].v", equalTo("2"));
    }

    @Test
    @Order(5)
    void selectAsStructFieldsRewriteNestedQueries() {
        query(Map.of("query", "SELECT AS STRUCT (SELECT 1 sample) AS child", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.f[0].v", equalTo("1"));
        query(Map.of("query", "SELECT AS STRUCT (SELECT total_x FROM (SELECT 'x' AS k, 5 AS v)"
                        + " PIVOT(SUM(v) AS total FOR k IN ('x'))) AS child", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.f[0].v", equalTo("5"));
    }

    @Test
    @Order(5)
    void selectAsStructExpandsIndexedRecordStars() {
        query(Map.of("query", "SELECT AS STRUCT [STRUCT(1 AS x, 2 AS y)][OFFSET(0)].*",
                        "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields[0].type", equalTo("RECORD"))
                .body("schema.fields[0].fields.name", equalTo(List.of("x", "y")))
                .body("rows[0].f[0].v.f.v", equalTo(List.of("1", "2")));
    }

    @Test
    @Order(5)
    void cteColumnsShadowEnclosingOffsetAliases() {
        query(Map.of("query", "WITH local AS (SELECT STRUCT(2 AS x) AS item)"
                        + " SELECT (SELECT item.x FROM local) AS x"
                        + " FROM UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));
    }

    @Test
    @Order(5)
    void physicalTableColumnsShadowEnclosingOffsetAliases() {
        query(Map.of("query", "CREATE TABLE shop.local_record AS SELECT STRUCT(2 AS x) AS item",
                        "useLegacySql", false))
                .then().statusCode(200);
        query(Map.of("query", "SELECT (SELECT item.x FROM shop.local_record) AS x"
                        + " FROM UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos",
                        "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));
        query(Map.of("query", "CREATE VIEW shop.local_record_view AS"
                        + " SELECT (SELECT item.x FROM shop.local_record) AS x"
                        + " FROM UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos",
                        "useLegacySql", false))
                .then().statusCode(200);
        query(Map.of("query", "SELECT x FROM shop.local_record_view", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));
    }

    @Test
    @Order(5)
    void unmatchedLiteralRecordUnnestKeepsTheWholeElementNull() {
        query(Map.of("query", "SELECT item IS NULL AS missing FROM (SELECT 2 AS id) t"
                        + " LEFT JOIN UNNEST([STRUCT(1 AS id)]) item ON t.id = item.id",
                        "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("rows[0].f[0].v", equalTo("true"));
    }

    @Test
    @Order(5)
    void concatOfBytesReturnsBytes() {
        query(Map.of("query", "SELECT CONCAT(b'AB', b'CD') AS payload", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("BYTES"))
                .body("rows[0].f[0].v", equalTo("QUJDRA=="));
    }

    @Test
    @Order(5)
    void pivotAggregatePrefixesMatchGoogleSqlColumnNames() {
        query(Map.of("query", "SELECT total_x, m_y FROM (SELECT 'x' AS k, 1 AS v UNION ALL SELECT 'y', 2)"
                        + " PIVOT(SUM(v) AS total, MAX(v) AS m FOR k IN ('x', 'y'))", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("1", "2")));
    }

    @Test
    @Order(5)
    void reviewedTimestampFunctionsExecuteOnTheDuckSidecar() {
        query(Map.of("query", """
                SELECT
                  TIMESTAMP_TRUNC('2024-05-17 12:34:56+00', HOUR) AS truncated_timestamp,
                  DATETIME_TRUNC('2024-05-17 12:34:56', HOUR) AS truncated_datetime,
                  TIMESTAMP_TRUNC('2024-01-01 07:34:56+00', DAY, 'America/Los_Angeles') AS zoned_trunc,
                  DATE_TRUNC('2024-01-01 07:34:56+00', DAY, 'America/Los_Angeles') AS zoned_date_trunc,
                  SAFE.PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S', '2024-01-01 00:00:00',
                    'America/Los_Angeles') AS safe_parsed,
                  PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S', '2024-01-01 00:00:00',
                    'America/Los_Angeles') AS parsed
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of(
                        "TIMESTAMP", "DATETIME", "TIMESTAMP", "TIMESTAMP", "TIMESTAMP", "TIMESTAMP")))
                .body("rows[0].f[0].v", equalTo("1715947200"))
                .body("rows[0].f[1].v", equalTo("2024-05-17T12:00:00"))
                .body("rows[0].f[2].v", equalTo("1704009600"))
                .body("rows[0].f[3].v", equalTo("1704009600"))
                .body("rows[0].f[4].v", equalTo("1704096000"))
                .body("rows[0].f[5].v", equalTo("1704096000"));
    }

    @Test
    @Order(5)
    void hashesAndToHexExecuteOnTheDuckSidecar() {
        query(Map.of("query", """
                SELECT TO_HEX(b'\\x00\\xff') AS raw,
                       TO_HEX(MD5('abc')) AS md5,
                       TO_HEX(SHA1('abc')) AS sha1,
                       TO_HEX(SHA256('abc')) AS sha256
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of(
                        "00ff",
                        "900150983cd24fb0d6963f7d28e17f72",
                        "a9993e364706816aba3e25717850c26c9cd0d89d",
                        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")));
    }

    @Test
    @Order(5)
    void unnestWithOffsetSupportsOptionalAliases() {
        query(Map.of("query", """
                SELECT value, position
                FROM UNNEST([10, 20]) AS value WITH OFFSET AS position
                ORDER BY position
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("10", "0"), List.of("20", "1"))));

        query(Map.of("query", """
                SELECT value, offset
                FROM UNNEST([10, 20]) AS value WITH OFFSET
                ORDER BY offset
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("value", "offset")))
                .body("rows.f.v", equalTo(List.of(List.of("10", "0"), List.of("20", "1"))));

        query(Map.of("query", "SELECT * FROM UNNEST([10, 20]) WITH OFFSET ORDER BY offset",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[1].name", equalTo("offset"))
                .body("rows.f.v", equalTo(List.of(List.of("10", "0"), List.of("20", "1"))));
    }

    @Test
    @Order(5)
    void unnestAliasesDoNotCaptureDatePartsOrIntervalUnits() {
        query(Map.of("query", "SELECT DATE_TRUNC(DATE '2024-05-17', MONTH)"
                        + " FROM UNNEST([1]) month WITH OFFSET AS pos", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2024-05-01"));

        query(Map.of("query", "SELECT DATE '2024-05-17' + INTERVAL 1 DAY"
                + " FROM UNNEST([1]) day WITH OFFSET AS pos", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2024-05-18T00:00:00"));
    }

    @Test
    @Order(5)
    void nestedBytesRoundTripThroughDuckStaging() {
        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "nested_bytes"}, "schema": {"fields": [
                  {"name": "payload", "type": "RECORD", "fields": [
                    {"name": "content", "type": "BYTES"},
                    {"name": "attachments", "type": "BYTES", "mode": "REPEATED"}
                  ]},
                  {"name": "events", "type": "RECORD", "mode": "REPEATED", "fields": [
                    {"name": "content", "type": "BYTES"}
                  ]}
                ]}}
                """).when().post(BASE + "/datasets/shop/tables").then().statusCode(200);
        given().contentType("application/json").body("""
                {"rows": [{"json": {
                  "payload": {"content": "AP8=", "attachments": ["YQ==", "Yg=="]},
                  "events": [{"content": "AQI="}]
                }}]}
                """).when().post(BASE + "/datasets/shop/tables/nested_bytes/insertAll")
                .then().statusCode(200).body("insertErrors", nullValue());

        query(Map.of("query", "SELECT payload, events FROM shop.nested_bytes", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.f[0].v", equalTo("AP8="))
                .body("rows[0].f[0].v.f[1].v.v", equalTo(List.of("YQ==", "Yg==")))
                .body("rows[0].f[1].v.v.f.v", equalTo(List.of(List.of("AQI="))));
    }

    @Test
    @Order(5)
    void correlatedLeftJoinUnnestPreservesRowsForEmptyArrays() {
        query(Map.of("query", """
                WITH users AS (
                  SELECT 1 AS id, ['a', 'b'] AS tags
                  UNION ALL SELECT 2 AS id, CAST([] AS ARRAY<STRING>) AS tags
                )
                SELECT u.id, tag
                FROM users u LEFT JOIN UNNEST(u.tags) tag
                ORDER BY u.id, tag
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "tag")))
                .body("rows", hasSize(3))
                .body("rows[0].f.v", equalTo(List.of("1", "a")))
                .body("rows[1].f.v", equalTo(List.of("1", "b")))
                .body("rows[2].f[0].v", equalTo("2"))
                .body("rows[2].f[1].v", nullValue());
    }

    @Test
    @Order(5)
    void scalarOffsetAliasesDoNotReplaceUsingJoinKeys() {
        for (String key : List.of("id", "`id`")) {
            query(Map.of("query", "SELECT t.id, pos FROM"
                            + " (SELECT 1 AS id UNION ALL SELECT 2 AS id) t LEFT JOIN"
                            + " UNNEST([1]) id WITH OFFSET AS pos USING(" + key + ") ORDER BY t.id",
                            "useLegacySql", false))
                    .then().log().ifValidationFails().statusCode(200)
                    .body("rows", hasSize(2))
                    .body("rows[0].f.v", equalTo(List.of("1", "0")))
                    .body("rows[1].f[0].v", equalTo("2"))
                    .body("rows[1].f[1].v", nullValue());
        }
    }

    @Test
    @Order(5)
    void successivePivotsExecuteAfterTheirSourcesShrink() {
        query(Map.of("query", "SELECT a.id, a.x, b.y, c.z FROM"
                        + " (SELECT 1 AS id, 2 AS v, 'x' AS k) PIVOT(SUM(v) FOR k IN ('x')) a"
                        + " CROSS JOIN (SELECT 3 AS v, 'y' AS k) PIVOT(SUM(v) FOR k IN ('y')) b"
                        + " CROSS JOIN (SELECT 4 AS v, 'z' AS k) PIVOT(SUM(v) FOR k IN ('z')) c",
                        "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "x", "y", "z")))
                .body("rows[0].f.v", equalTo(List.of("1", "2", "3", "4")));
    }

    @Test
    @Order(5)
    void leftJoinUnnestUsingPreservesMatchesAndUnmatchedRows() {
        for (String source : List.of("UNNEST([STRUCT(1 AS id)]) item",
                "UNNEST([STRUCT(1 AS id)])", "UNNEST([STRUCT(1 AS id)]) item WITH OFFSET AS pos",
                "UNNEST([STRUCT(1 AS id)]) WITH OFFSET", "UNNEST([1]) id")) {
            query(Map.of("query", "SELECT id FROM (SELECT 1 AS id) t LEFT JOIN " + source + " USING(id)",
                            "useLegacySql", false))
                    .then().log().ifValidationFails().statusCode(200)
                    .body("schema.fields.name", equalTo(List.of("id")))
                    .body("rows[0].f[0].v", equalTo("1"));
        }
        query(Map.of("query", "SELECT id, item, pos FROM (SELECT 2 AS id) t LEFT JOIN"
                        + " UNNEST([STRUCT(1 AS id)]) item WITH OFFSET AS pos USING(id)", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("2"))
                .body("rows[0].f[1].v", nullValue())
                .body("rows[0].f[2].v", nullValue());
    }

    @Test
    @Order(5)
    void conditionedJoinUnnestFiltersArrayElements() {
        query(Map.of("query", """
                SELECT x
                FROM UNNEST([1, 2]) x
                JOIN UNNEST([2, 3]) y ON x = y
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("2"));
    }

    @Test
    @Order(5)
    void arrayAggIgnoreNullsOrdersValuesBeforeAggregation() {
        query(Map.of("query", """
                WITH source AS (
                  SELECT 2 AS ordinal, 'second' AS value
                  UNION ALL SELECT 1, 'first'
                  UNION ALL SELECT 3, CAST(NULL AS STRING)
                )
                SELECT ARRAY_AGG(value IGNORE NULLS ORDER BY ordinal) AS values FROM source
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("STRING"))
                .body("schema.fields[0].mode", equalTo("REPEATED"))
                .body("rows[0].f[0].v.v", equalTo(List.of("first", "second")));
    }

    @Test
    @Order(5)
    void arrayAggSupportsInlineLimit() {
        query(Map.of("query", """
                SELECT ARRAY_AGG(value IGNORE NULLS ORDER BY ordinal LIMIT 2) AS values
                FROM (SELECT 3 AS ordinal, 'third' AS value
                      UNION ALL SELECT 1, 'first'
                      UNION ALL SELECT 2, 'second'
                      UNION ALL SELECT 4, CAST(NULL AS STRING))
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("first", "second")));
    }

    @Test
    @Order(5)
    void arrayOffsetUsesGoogleSqlZeroBasedIndexes() {
        query(Map.of("query", """
                SELECT ['zero', 'one'][OFFSET(0)] AS first_value,
                  ['zero', 'one'][OFFSET(1)] AS second_value
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("zero", "one")));
        query(Map.of("query", "SELECT ['zero'][SAFE_OFFSET(-1)] AS value", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", nullValue());
        query(Map.of("query", "SELECT ['zero'][OFFSET(1)] AS value", "useLegacySql", false))
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"));
        query(Map.of("query", "SELECT CAST(NULL AS ARRAY<INT64>)[OFFSET(0)] AS value", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", nullValue());
        query(Map.of("query", "SELECT ['zero'][OFFSET(CAST(NULL AS INT64))] AS value",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", nullValue());
        query(Map.of("query", "SELECT [10, 20][OFFSET([0, 1][OFFSET(0)])] AS value",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("10"));
        query(Map.of("query", "SELECT ARRAY<INT64>[1, 2][OFFSET(0)] AS value",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1"));
        query(Map.of("query", "SELECT ([1, 2])[OFFSET(0)] AS value", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1"));
    }

    @Test
    @Order(5)
    void indexedNestedStructQueriesExecuteAfterTranslation() {
        query(Map.of("query", "SELECT ARRAY(SELECT AS STRUCT 1 AS id)[OFFSET(0)].id AS value",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1"));
    }

    @Test
    @Order(5)
    void arraySubquerySelectAsStructExceptExecutesAfterTranslation() {
        query(Map.of("query", "SELECT ARRAY(SELECT AS STRUCT * EXCEPT(id)"
                        + " FROM UNNEST([STRUCT('1' AS id, 'example' AS type)]) al)",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("RECORD"))
                .body("schema.fields[0].mode", equalTo("REPEATED"))
                .body("schema.fields[0].fields.name", equalTo(List.of("type")))
                .body("rows[0].f[0].v.v[0].f[0].v", equalTo("example"));
    }

    @Test
    @Order(5)
    void chainedSafeOffsetsRetainTheirStructReceiver() {
        query(Map.of("query", """
                SELECT records[SAFE_OFFSET(0)].elements[SAFE_OFFSET(0)].value AS value
                FROM (SELECT [STRUCT([STRUCT(7 AS value)] AS elements)] AS records)
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("7"));
    }

    @Test
    @Order(5)
    void nestedArrayStructsRetainInnerFieldNames() {
        query(Map.of("query", """
                SELECT records[OFFSET(1)].child.x AS value
                FROM (SELECT [STRUCT(STRUCT(1 AS x) AS child),
                  STRUCT(STRUCT(2 AS x) AS child)] AS records)
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));
    }

    @Test
    @Order(5)
    void havingCanDereferenceAnAggregateStructAlias() {
        query(Map.of("query", """
                WITH grouped AS (
                  SELECT STRUCT(group_id, MAX(event_time) AS latest_at) AS latest_per_group
                  FROM (
                    SELECT 'first' AS group_id, TIMESTAMP '2021-01-29 00:00:00+00' AS event_time
                    UNION ALL SELECT 'first', TIMESTAMP '2021-01-30 00:00:00+00'
                  )
                  GROUP BY group_id
                  HAVING latest_per_group.latest_at <= TIMESTAMP '2021-01-30 00:00:00+00'
                )
                SELECT latest_per_group.group_id FROM grouped
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("first"));
    }

    @Test
    @Order(5)
    void timestampSubCoercesStringLiteralsBeforeIntervalArithmetic() {
        query(Map.of("query", """
                SELECT TIMESTAMP_SUB('2021-01-30', INTERVAL 3 DAY) AS previous
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("TIMESTAMP")))
                .body("rows[0].f[0].v", equalTo("1611705600"));
    }

    @Test
    @Order(5)
    void safeParseDateReturnsNullForMalformedInput() {
        query(Map.of("query", """
                SELECT SAFE.PARSE_DATE('%Y%m%d', '20230801') AS parsed,
                  SAFE.PARSE_DATE('%Y%m%d', 'invalid') AS malformed
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("DATE", "DATE")))
                .body("rows[0].f[0].v", equalTo("2023-08-01"))
                .body("rows[0].f[1].v", nullValue());
    }

    @Test
    @Order(5)
    void dateTruncCoercesStringLiteralsLikeBigQuery() {
        query(Map.of("query", """
                SELECT DATE_TRUNC('2024-05-17', MONTH) AS month_start
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("month_start")))
                .body("schema.fields.type", equalTo(List.of("DATE")))
                .body("rows[0].f[0].v", equalTo("2024-05-01"));
    }

    @Test
    @Order(5)
    void extractWeekUsesBigQueryWeekdayBoundaries() {
        query(Map.of("query", """
                SELECT
                  EXTRACT(WEEK(TUESDAY) FROM DATE '2024-01-01') AS before_tuesday,
                  EXTRACT(WEEK(TUESDAY) FROM DATE '2024-01-02') AS first_tuesday,
                  EXTRACT(WEEK FROM DATE '2024-01-06') AS before_sunday,
                  EXTRACT(WEEK FROM DATE '2024-01-07') AS first_sunday
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("0", "1", "0", "1")));
    }

    @Test
    @Order(5)
    void starExceptMatchesColumnNamesCaseInsensitively() {
        query(Map.of("query", "SELECT * EXCEPT(ID) FROM UNNEST([STRUCT(1 AS id, 2 AS value)])",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("value")))
                .body("rows[0].f[0].v", equalTo("2"));
    }

    @Test
    @Order(5)
    void concatKeepsAggregatesInTheirQueryScope() {
        query(Map.of("query", "SELECT CONCAT(COUNT(*), ' rows') AS summary FROM shop.users",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("3 rows"));

        query(Map.of("query", "SELECT CONCAT(7) AS text, CONCAT(CAST(NULL AS INT64)) AS absent",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("STRING", "STRING")))
                .body("rows[0].f[0].v", equalTo("7"))
                .body("rows[0].f[1].v", nullValue());
    }

    @Test
    @Order(5)
    void wildcardStarOmitsTheSuffixPseudocolumn() {
        query(Map.of("query", "CREATE TABLE shop.events_2025 AS SELECT 1 AS id", "useLegacySql", false))
                .then().statusCode(200);
        query(Map.of("query", "CREATE TABLE shop.events_2026 AS SELECT 2 AS id", "useLegacySql", false))
                .then().statusCode(200);

        query(Map.of("query", "SELECT * FROM `shop.events_*` ORDER BY id", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id")))
                .body("rows.f.v", equalTo(List.of(List.of("1"), List.of("2"))));
        query(Map.of("query", "SELECT id, _TABLE_SUFFIX FROM `shop.events_*` ORDER BY id", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("1", "2025"), List.of("2", "2026"))));
        query(Map.of("query", "SELECT * FROM (SELECT _TABLE_SUFFIX FROM `shop.events_*`)"
                        + " ORDER BY _TABLE_SUFFIX", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("_TABLE_SUFFIX")))
                .body("rows.f.v", equalTo(List.of(List.of("2025"), List.of("2026"))));

        query(Map.of("query", "CREATE TABLE shop.people_a AS"
                        + " SELECT 'Ada' AS first_name, 'Lovelace' AS last_name", "useLegacySql", false))
                .then().statusCode(200);
        query(Map.of("query", "CREATE TABLE shop.people_b AS"
                        + " SELECT 'Hopper' AS last_name, 'Grace' AS first_name", "useLegacySql", false))
                .then().statusCode(200);
        query(Map.of("query", "SELECT first_name, last_name FROM `shop.people_*` ORDER BY first_name",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("Ada", "Lovelace"), List.of("Grace", "Hopper"))));

        query(Map.of("query", "CREATE TABLE shop.people_c AS"
                        + " SELECT 'Katherine' AS first_name, 'Kay' AS nickname", "useLegacySql", false))
                .then().statusCode(200);
        query(Map.of("query", "SELECT first_name, nickname FROM `shop.people_*` ORDER BY first_name",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("first_name", "nickname")))
                .body("rows[0].f[0].v", equalTo("Ada"))
                .body("rows[0].f[1].v", nullValue())
                .body("rows[1].f[0].v", equalTo("Grace"))
                .body("rows[1].f[1].v", nullValue())
                .body("rows[2].f.v", equalTo(List.of("Katherine", "Kay")));

        query(Map.of("query", "CREATE TABLE shop.people_d AS SELECT 4 AS first_name",
                        "useLegacySql", false)).then().statusCode(200);
        query(Map.of("query", "SELECT first_name FROM `shop.people_*`", "useLegacySql", false))
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"));

        query(Map.of("query", "CREATE TABLE shop.records_a AS SELECT"
                        + " STRUCT('Ada' AS first_name, 'Lovelace' AS last_name) AS person",
                        "useLegacySql", false)).then().statusCode(200);
        query(Map.of("query", "CREATE TABLE shop.records_b AS SELECT"
                        + " STRUCT('Hopper' AS last_name, 'Grace' AS first_name, 'Amazing Grace' AS nickname) AS person",
                        "useLegacySql", false)).then().statusCode(200);
        query(Map.of("query", "SELECT person.first_name, person.last_name, person.nickname"
                        + " FROM `shop.records_*` ORDER BY person.first_name", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("Ada"))
                .body("rows[0].f[1].v", equalTo("Lovelace"))
                .body("rows[0].f[2].v", nullValue())
                .body("rows[1].f.v", equalTo(List.of("Grace", "Hopper", "Amazing Grace")));

        query(Map.of("query", "CREATE VIEW shop.records_view AS SELECT person FROM shop.records_a",
                        "useLegacySql", false)).then().statusCode(200);
        query(Map.of("query", "SELECT person FROM `shop.records_*`", "useLegacySql", false))
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"));
    }

    @Test
    @Order(5)
    void indexedArraySubqueryUsesItsCte() {
        query(Map.of("query", "WITH x AS (SELECT 7 AS id)"
                        + " SELECT ARRAY(SELECT id FROM x)[OFFSET(0)] AS value", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("7"));

    }

    @Test
    @Order(5)
    void singleValueUnpivotExcludesNullsByDefault() {
        String sql = """
                SELECT metric, metric_value FROM (SELECT 1 AS present, CAST(NULL AS INT64) AS absent)
                UNPIVOT (metric_value FOR metric IN (present, absent))
                """;

        query(Map.of("query", sql, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f.v", equalTo(List.of("present", "1")));
    }

    @Test
    @Order(5)
    void implicitAliasesThatAreDuckDbKeywordsWork() {
        query("""
                {"query": "SELECT sample, name FROM (SELECT 1 sample, 'ana' name) WHERE sample = 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("sample", "name")))
                .body("rows[0].f.v", equalTo(List.of("1", "ana")));
        query("""
                {"query": "SELECT name value, COUNT(*) year FROM shop.users GROUP BY name ORDER BY value", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("value", "year")))
                .body("rows[0].f.v", equalTo(List.of("ana", "1")));
        query("""
                {"query": "SELECT (DATE '2024-01-01' + INTERVAL 1 DAY) name, 'a' LIKE 'b'", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("name", "f0_")));
    }

    @Test
    @Order(5)
    void offsetIsAnOrdinaryName() {
        query("""
                {"query": "SELECT 1 offset", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("offset")))
                .body("rows[0].f.v", equalTo(List.of("1")));
        query("""
                {"query": "SELECT offset + 1 o2 FROM (SELECT 1 offset) WHERE offset = 1 ORDER BY offset", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("o2")))
                .body("rows[0].f.v", equalTo(List.of("2")));
        query("""
                {"query": "SELECT x, 5 offset FROM UNNEST([1, 2, 3]) x ORDER BY x LIMIT 1 OFFSET 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x", "offset")))
                .body("rows[0].f.v", equalTo(List.of("2", "5")));
        query("""
                {"query": "WITH a AS (SELECT 1 x), offset AS (SELECT x + 1 x FROM a) SELECT x FROM offset", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("2")));
    }

    @Test
    @Order(5)
    void windowFramesEndingAtTheCurrentRowRun() {
        query("""
                {"query": "SELECT x, SUM(x) OVER (ORDER BY x ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) s, SUM(x) OVER (ORDER BY x RANGE BETWEEN 1 PRECEDING AND CURRENT ROW) r, COUNT(*) OVER (ORDER BY x ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING) c FROM UNNEST([1,2,3]) x ORDER BY x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("1", "1", "1", "3"), List.of("2", "3", "3", "2"),
                        List.of("3", "6", "5", "1"))));
    }

    @Test
    @Order(5)
    void arraySubscriptsMatchBigQuery() {
        query("""
                {"query": "SELECT [10,20,30][OFFSET(1)] o, [10,20,30][SAFE_OFFSET(5)] so, [10,20,30][ORDINAL(1)] ord, [10,20,30][SAFE_ORDINAL(9)] sord, [10,20,30][SAFE_OFFSET(NULL)] n", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("o", "so", "ord", "sord", "n")))
                .body("rows[0].f.v", equalTo(Arrays.asList("20", null, "10", null, null)));
        // A bare subscript is also GoogleSQL's JSON subscript operator, so it is left as it is.
        query("""
                {"query": "WITH t AS (SELECT JSON '{\\"a\\":[1,2]}' AS j, JSON '[5,6]' AS k) SELECT j['a'] field, j['a'][1] field_index, TO_JSON(STRUCT(1 AS x))['x'] to_json_field, k[0] index0, j.a[1] dotted_index, JSON_QUERY(j, '$.a')[0] json_query_index FROM t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("[1,2]", "2", "1", "5", "2", "1")));
        query("""
                {"query": "SELECT json_value.class.students[0]['name'] AS first_student FROM UNNEST([JSON '{\\"class\\" : {\\"students\\" : [{\\"name\\" : \\"Jane\\"}]}}', JSON '{\\"class\\" : {\\"students\\" : []}}', JSON '{\\"class\\" : {\\"students\\" : [{\\"name\\" : \\"John\\"}, {\\"name\\": \\"Jamie\\"}]}}']) AS json_value", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(Arrays.asList(List.of("\"Jane\""), Arrays.asList((Object) null), List.of("\"John\""))));
        query("""
                {"query": "SELECT ARRAY_AGG(x ORDER BY x DESC)[SAFE_OFFSET(0)] top, ARRAY_AGG(STRUCT(x AS v) ORDER BY x)[OFFSET(0)].v low FROM UNNEST([3,1,2]) x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("3", "1")));
        query("""
                {"query": "SELECT t.a[SAFE_OFFSET(0)] f, SPLIT('a,b,c')[OFFSET(2)] s FROM (SELECT [7,8] a) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("7", "c")));
        query("""
                {"query": "SELECT [10,20,30][OFFSET(5)]", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("Array index 5 is out of bounds (overflow)"));
        query("""
                {"query": "SELECT [10,20,30][ORDINAL(0)]", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("Array index 0 is out of bounds (underflow)"));
        query("""
                {"query": "SELECT [STRUCT([10, 20] AS a)][SAFE_OFFSET(0)].a[SAFE_OFFSET(1)] AS v", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("20")));
        query("""
                {"query": "SELECT ARRAY_AGG(x) OVER ()[SAFE_OFFSET(0)] AS v FROM UNNEST([1, 2]) x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("1"), List.of("1"))));
        query("""
                {"query": "SELECT [10, 20][SAFE_OFFSET(?)] AS v, [?, 20][OFFSET(?)] AS w",
                 "parameterMode": "POSITIONAL", "useLegacySql": false,
                 "queryParameters": [{"parameterType": {"type": "INT64"}, "parameterValue": {"value": "1"}},
                                     {"parameterType": {"type": "INT64"}, "parameterValue": {"value": "5"}},
                                     {"parameterType": {"type": "INT64"}, "parameterValue": {"value": "1"}}]}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("20", "20")));
        query("""
                {"query": "SELECT [10][SAFE_OFFSET(@i)] AS v, [10][SAFE_ORDINAL(@i)] AS w",
                 "parameterMode": "NAMED", "useLegacySql": false,
                 "queryParameters": [{"name": "i", "parameterType": {"type": "INT64"}, "parameterValue": {"value": "9223372036854775807"}}]}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(Arrays.asList(null, null)));
        query("""
                {"query": "SELECT [10][OFFSET(@i)] AS v",
                 "parameterMode": "NAMED", "useLegacySql": false,
                 "queryParameters": [{"name": "i", "parameterType": {"type": "INT64"}, "parameterValue": {"value": "9223372036854775807"}}]}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("Array index 9223372036854775807 is out of bounds (overflow)"));
    }

    @Test
    @Order(5)
    void generateDateArrayMatchesBigQuery() {
        query("""
                {"query": "SELECT GENERATE_DATE_ARRAY('2024-01-01', '2024-01-04') a, GENERATE_DATE_ARRAY(DATE '2024-01-01', DATE '2024-01-10', INTERVAL 3 DAY) b, GENERATE_DATE_ARRAY('2024-01-31', '2024-04-30', INTERVAL 1 MONTH) c, GENERATE_DATE_ARRAY('2024-01-05', '2024-01-01') d, GENERATE_DATE_ARRAY('2024-01-01', NULL) n", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("2024-01-01", "2024-01-02", "2024-01-03", "2024-01-04")))
                .body("rows[0].f[1].v.v", equalTo(List.of("2024-01-01", "2024-01-04", "2024-01-07", "2024-01-10")))
                .body("rows[0].f[2].v.v", equalTo(List.of("2024-01-31", "2024-02-29", "2024-03-29", "2024-04-29")))
                .body("rows[0].f[3].v", equalTo(List.of()))
                .body("rows[0].f[4].v", equalTo(List.of()));
        query("""
                {"query": "SELECT GENERATE_DATE_ARRAY('2024-01-01', NULL) IS NULL, ARRAY_LENGTH(GENERATE_DATE_ARRAY(NULL, '2024-01-03')), ARRAY_LENGTH(GENERATE_DATE_ARRAY('2024-01-03', '2024-01-01'))", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(Arrays.asList("true", null, "0")));
        query("""
                {"query": "SELECT d FROM UNNEST(GENERATE_DATE_ARRAY('2024-01-01', '2024-01-03', INTERVAL 1 WEEK)) d", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("2024-01-01"))));
        query("""
                {"query": "SELECT GENERATE_DATE_ARRAY('2024-01-01', '2024-01-03', INTERVAL 0 DAY)", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("GENERATE_ARRAY step cannot be 0."));
    }

    @Test
    @Order(5)
    void structDotStarExpandsLikeBigQuery() {
        query("""
                {"query": "SELECT t.s.*, t.n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s, 7 n) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b", "n")))
                .body("rows[0].f.v", equalTo(List.of("1", "x", "7")));
        query("""
                {"query": "SELECT STRUCT(1 AS a, 'x' AS b).*", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b")))
                .body("rows[0].f.v", equalTo(List.of("1", "x")));
        query("""
                {"query": "SELECT g, ANY_VALUE(STRUCT(x AS v, x * 10 AS w)).*, MAX(x) m FROM UNNEST([STRUCT('a' AS g, 1 AS x), ('b', 3)]) GROUP BY g ORDER BY g", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("g", "v", "w", "m")))
                .body("rows.f.v", equalTo(List.of(List.of("a", "1", "10", "1"), List.of("b", "3", "30", "3"))));
        query("""
                {"query": "SELECT t.s.inr.* FROM (SELECT STRUCT(STRUCT(1 AS c, 2 AS d) AS inr) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("1", "2")));
        query("""
                {"query": "SELECT t.s.* FROM (SELECT CAST(NULL AS STRUCT<a INT64, b STRING>) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(Arrays.asList(null, null)));
        query("""
                {"query": "SELECT a[OFFSET(0)].s.* FROM (SELECT [STRUCT(STRUCT(1 AS v, 2 AS w) AS s)] a)", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("v", "w")))
                .body("rows[0].f.v", equalTo(List.of("1", "2")));
        query("""
                {"query": "SELECT ANY_VALUE(STRUCT(STRUCT(1 AS v, 2 AS w) AS s)).s.*, (STRUCT(STRUCT(3 AS x) AS s)).s.* FROM UNNEST([1])", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("v", "w", "x")))
                .body("rows[0].f.v", equalTo(List.of("1", "2", "3")));
        query("""
                {"query": "SELECT STRUCT(2 AS a).* EXCEPT DISTINCT SELECT 1 AS a", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("2"))));
        query("""
                {"query": "SELECT STRUCT(1 AS a).* EXCEPT DISTINCT SELECT 1 AS a", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("totalRows", equalTo("0"));
    }

    @Test
    @Order(5)
    void intervalStepSizeCanBeAnExpression() {
        query("""
                {"query": "SELECT DATE_ADD(DATE '2024-01-10', INTERVAL -5 DAY) minus, DATE_ADD(DATE '2024-01-10', INTERVAL - 5 DAY) spaced, DATE_ADD(DATE '2024-01-10', INTERVAL 2 * 3 DAY) product, DATE_ADD(DATE '2024-01-10', INTERVAL -x DAY) negated FROM UNNEST([2]) x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("minus", "spaced", "product", "negated")))
                .body("rows[0].f.v", equalTo(List.of("2024-01-05", "2024-01-05", "2024-01-16", "2024-01-08")));
        query("""
                {"query": "SELECT DATE_ADD(DATE '2024-01-10', INTERVAL -1 QUARTER) q, DATE_ADD(DATE '2024-01-10', INTERVAL -1 WEEK) w, UNIX_MICROS(TIMESTAMP_ADD(TIMESTAMP '2024-01-10 00:00:00', INTERVAL -1 MILLISECOND)) ms, UNIX_MICROS(TIMESTAMP_ADD(TIMESTAMP '2024-01-10 00:00:00', INTERVAL -1 MICROSECOND)) us", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("2023-10-10", "2024-01-03", "1704844799999000", "1704844799999999")));
        query("""
                {"query": "SELECT DATE_ADD(DATE '2024-01-10', INTERVAL n + day DAY) FROM (SELECT 1 n, 2 day)", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("2024-01-13")));
        query("""
                {"query": "SELECT DATE_ADD(DATE '2024-01-10', INTERVAL @n DAY) a, DATE_ADD(DATE '2024-01-10', INTERVAL -@n DAY) b",
                 "parameterMode": "NAMED", "useLegacySql": false,
                 "queryParameters": [{"name": "n", "parameterType": {"type": "INT64"}, "parameterValue": {"value": "5"}}]}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("2024-01-15", "2024-01-05")));
        query("""
                {"query": "SELECT DATE_ADD(DATE '2024-01-10', INTERVAL -EXTRACT(DAY FROM DATE_ADD(DATE '2024-01-01', INTERVAL 1 DAY)) DAY) c, DATE_ADD(DATE '2024-01-10', INTERVAL -EXTRACT(DAY FROM DATE_ADD(DATE '2024-01-01', INTERVAL -1 DAY)) DAY) d", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("2024-01-08", "2023-12-10")));
    }

    @Test
    @Order(5)
    void nullArrayIsReturnedAsEmptyArray() {
        query("""
                {"query": "SELECT CAST(NULL AS ARRAY<INT64>) a, 1 b", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields[0].mode", equalTo("REPEATED"))
                .body("rows[0].f[0].v", equalTo(List.of()))
                .body("rows[0].f[1].v", equalTo("1"));
    }

    @Test
    @Order(5)
    void arrayAggNullModifiersMatchBigQuery() {
        query("""
                {"query": "SELECT ARRAY_AGG(x IGNORE NULLS ORDER BY x) a, ARRAY_AGG(DISTINCT x IGNORE NULLS ORDER BY x DESC) b, ARRAY_AGG(IF(x = 1, 'keep ignore nulls', NULL) IGNORE NULLS) c FROM UNNEST([1, NULL, 3, 3]) x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("1", "3", "3")))
                .body("rows[0].f[1].v.v", equalTo(List.of("3", "1")))
                .body("rows[0].f[2].v.v", equalTo(List.of("keep ignore nulls")));
        query("""
                {"query": "SELECT ARRAY_AGG(x RESPECT NULLS ORDER BY x) a FROM UNNEST([2, 1]) x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("1", "2")));
        query("""
                {"query": "SELECT x, ARRAY_AGG(x IGNORE NULLS) OVER (ORDER BY x) a FROM UNNEST([1, 2]) x", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body("error.message", containsString("does not support IGNORE NULLS or RESPECT NULLS"));
    }

    @Test
    @Order(5)
    void arrayAggFieldsNamedLikeKeywordsAreFields() {
        query("""
                {"query": "SELECT ARRAY_AGG(t.limit ORDER BY t.limit) AS a, ARRAY_AGG(t.limit ORDER BY t.limit DESC LIMIT 1) AS b, ARRAY_AGG(t.ignore ORDER BY t.ignore DESC) AS c, ARRAY_AGG(t.respect IGNORE NULLS ORDER BY t.ignore) AS d FROM UNNEST([STRUCT(2 AS `limit`, 1 AS `ignore`, 5 AS `respect`), STRUCT(1 AS `limit`, 3 AS `ignore`, CAST(NULL AS INT64) AS `respect`)]) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("1", "2")))
                .body("rows[0].f[1].v.v", equalTo(List.of("2")))
                .body("rows[0].f[2].v.v", equalTo(List.of("3", "1")))
                .body("rows[0].f[3].v.v", equalTo(List.of("5")));
    }

    @Test
    @Order(5)
    void arrayAggLimitMatchesBigQuery() {
        query("""
                {"query": "SELECT ARRAY_AGG(x IGNORE NULLS ORDER BY x DESC LIMIT 2) top2, ARRAY_AGG(x IGNORE NULLS ORDER BY x LIMIT 1) lowest, ARRAY_AGG(DISTINCT x IGNORE NULLS ORDER BY x LIMIT 2) d, ARRAY_AGG(x LIMIT 0) z FROM UNNEST([3, 1, NULL, 2, 3]) x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("top2", "lowest", "d", "z")))
                .body("rows[0].f[0].v.v", equalTo(List.of("3", "3")))
                .body("rows[0].f[1].v.v", equalTo(List.of("1")))
                .body("rows[0].f[2].v.v", equalTo(List.of("1", "2")))
                .body("rows[0].f[3].v", equalTo(List.of()));
        query("""
                {"query": "SELECT g, ARRAY_AGG(x ORDER BY x DESC LIMIT 1) top FROM UNNEST([STRUCT('a' AS g, 1 AS x), ('a', 2), ('b', 3)]) GROUP BY g ORDER BY g", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[1].v.v", equalTo(List.of("2")))
                .body("rows[1].f[1].v.v", equalTo(List.of("3")));
        query("""
                {"query": "SELECT ARRAY_AGG(x LIMIT 1) OVER () z FROM UNNEST([1, 2]) x", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body("error.message", containsString("LIMIT in arguments is not supported on analytic functions"));
        query("""
                {"query": "SELECT ARRAY_AGG(CASE WHEN x > ? THEN x ELSE NULL END IGNORE NULLS ORDER BY x LIMIT ?) a FROM UNNEST([3, 1, NULL, 2, 3]) x",
                 "parameterMode": "POSITIONAL", "useLegacySql": false,
                 "queryParameters": [{"parameterType": {"type": "INT64"}, "parameterValue": {"value": "1"}},
                                     {"parameterType": {"type": "INT64"}, "parameterValue": {"value": "2"}}]}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("2", "3")));
        query("""
                {"query": "SELECT ARRAY_AGG(x ORDER BY x LIMIT -1) a FROM UNNEST([3, 1, 2]) x", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body("error.message", containsString("LIMIT expects a non-negative integer literal or parameter"));
        query("""
                {"query": "SELECT ARRAY_AGG(x ORDER BY x LIMIT 1 + 1) a FROM UNNEST([3, 1, 2]) x", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("LIMIT expects an integer literal or parameter"));
        query("""
                {"query": "SELECT ARRAY_AGG(x ORDER BY x LIMIT @n) a FROM UNNEST([3, 1, 2]) x",
                 "parameterMode": "NAMED", "useLegacySql": false,
                 "queryParameters": [{"name": "n", "parameterType": {"type": "INT64"}, "parameterValue": {"value": "-1"}}]}
                """)
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body("error.message", containsString("LIMIT value should not be negative"));
        query("""
                {"query": "SELECT ARRAY_AGG(x ORDER BY x LIMIT @n) a FROM UNNEST([3, 1, 2]) x",
                 "parameterMode": "NAMED", "useLegacySql": false,
                 "queryParameters": [{"name": "n", "parameterType": {"type": "INT64"}, "parameterValue": {"value": "2"}}]}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("1", "2")));
    }

    @Test
    @Order(5)
    void queriesCannotReadFilesOrUrls() {
        String other = "/bigquery/v2/projects/bq-duck-it-other";
        given().contentType("application/json").body("{\"datasetReference\": {\"datasetId\": \"vault\"}}")
                .when().post(other + "/datasets").then().statusCode(200);
        given().contentType("application/json")
                .body("{\"tableReference\": {\"tableId\": \"secrets\"}, \"schema\": {\"fields\": [{\"name\": \"v\", \"type\": \"STRING\"}]}}")
                .when().post(other + "/datasets/vault/tables").then().statusCode(200);
        given().contentType("application/json").body("{\"rows\": [{\"json\": {\"v\": \"top-secret\"}}]}")
                .when().post(other + "/datasets/vault/tables/secrets/insertAll").then().statusCode(200);
        // The address floci-duck itself stages rows from: reachable from the sidecar by construction.
        String rows = engine.flociEndpoint()
                + "/_floci-gcp/bigquery/projects/bq-duck-it-other/datasets/vault/tables/secrets/rows.ndjson";

        for (String sql : List.of("SELECT content FROM read_text('" + rows + "')",
                "SELECT (SELECT content FROM read_text('" + rows + "')) AS c FROM shop.users",
                "SELECT * FROM '" + rows + "'",
                "SELECT * FROM read_csv('/etc/hostname')")) {
            query("{\"query\": \"" + sql + "\", \"useLegacySql\": false}")
                    .then().statusCode(400)
                    .body("error.errors[0].reason", equalTo("invalidQuery"))
                    .body(not(containsString("top-secret")));
        }
        // DML is caller SQL too: INSERT ... SELECT must not copy another project's rows into the caller's table.
        query("""
                {"query": "CREATE TABLE shop.copied (v STRING)", "useLegacySql": false}
                """)
                .then().statusCode(200);
        query("{\"query\": \"INSERT INTO shop.copied (v) SELECT v FROM '" + rows + "'\", \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body(not(containsString("top-secret")));
        query("""
                {"query": "SELECT COUNT(*) FROM shop.copied", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("0"));
        // The tables a query references are still staged over the same route.
        query("""
                {"query": "SELECT COUNT(*) FROM shop.users", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("3"));
    }

    @Test
    @Order(6)
    void dryRunReturnsSchemaWithoutAJob() {
        query("""
                {"query": "SELECT id, name FROM shop.users", "dryRun": true, "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "name")))
                .body("rows", nullValue())
                .body("jobReference.jobId", nullValue());

        Response job = given().contentType("application/json").body("""
                {"jobReference": {"jobId": "dry-run-job"},
                 "configuration": {"dryRun": true, "query": {"query": "SELECT name FROM shop.users", "useLegacySql": false}}}
                """).when().post(BASE + "/jobs");
        job.then().statusCode(200)
                .body("status.state", equalTo("DONE"))
                .body("configuration.dryRun", equalTo(true))
                .body("statistics.query.schema.fields[0].name", equalTo("name"));
        assertEquals("SELECT", job.jsonPath().getString("statistics.query.statementType"));
        given().when().get(BASE + "/jobs/dry-run-job").then().statusCode(404);
    }

    @Test
    @Order(7)
    void nonEmptyTypedStructArrayLiteralExecutes() {
        query(Map.of("query", """
                SELECT TO_JSON_STRING(ARRAY<STRUCT<name STRING, value STRING>>[
                  STRUCT('source' AS name, 'origin' AS value)
                ]) AS metadata
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("[{\"name\":\"source\",\"value\":\"origin\"}]"));
    }

    @Test
    @Order(8)
    void sqlErrorsSurfaceAsInvalidQuery() {
        query("""
                {"query": "SELECT nope FROM shop.users", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body("error.message", containsString("nope"));

        given().contentType("application/json").body("""
                {"configuration": {"query": {"query": "SELECT nope FROM shop.users", "useLegacySql": false}}}
                """).when().post(BASE + "/jobs")
                .then().statusCode(200)
                .body("status.state", equalTo("DONE"))
                .body("status.errorResult.reason", equalTo("invalidQuery"));

        query("""
                {"query": "SELECT 1", "useLegacySql": true}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("Legacy SQL"));
    }
    @Test
    @Order(8)
    void everyReferencedDatasetMustShareTheJobLocation() {
        given().contentType("application/json")
                .body("{\"datasetReference\": {\"datasetId\": \"eu_shop\"}, \"location\": \"EU\"}")
                .when().post(BASE + "/datasets").then().statusCode(200);
        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "refunds"}, "schema": {"fields": [{"name": "user_id", "type": "INT64"}]}}
                """).when().post(BASE + "/datasets/eu_shop/tables").then().statusCode(200);

        query("""
                {"query": "SELECT COUNT(*) AS n FROM eu_shop.refunds", "useLegacySql": false}
                """).then().statusCode(200)
                .body("jobReference.location", equalTo("EU"))
                .body("rows[0].f[0].v", equalTo("0"));

        query("""
                {"query": "SELECT u.id FROM shop.users u JOIN eu_shop.refunds r ON u.id = r.user_id",
                 "useLegacySql": false}
                """).then().statusCode(404)
                .body("error.errors[0].reason", equalTo("notFound"))
                .body("error.message", equalTo(
                        "Not found: Dataset " + PROJECT + ":eu_shop was not found in location US"));
    }
}

package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    void arrayConcatAggAndHllSketchesCompose() {
        query("""
                {"query": "SELECT ARRAY_CONCAT_AGG(tags) AS tags FROM shop.users", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("STRING"))
                .body("schema.fields[0].mode", equalTo("REPEATED"))
                .body("rows[0].f[0].v.v", equalTo(List.of("a", "b")));

        query(Map.of("query", """
                WITH sketches AS (SELECT HLL_COUNT.INIT(name, 20) AS sketch FROM shop.users)
                SELECT HLL_COUNT.EXTRACT(sketch) AS extracted,
                  (SELECT HLL_COUNT.MERGE(value) FROM UNNEST([sketch, sketch]) value) AS merged
                FROM sketches
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("INTEGER", "INTEGER")))
                .body("rows[0].f.v", equalTo(List.of("3", "3")));

        query(Map.of("query", """
                SELECT HLL_COUNT.INIT(CAST(NULL AS STRING)) IS NULL AS init_all_null,
                  HLL_COUNT.MERGE(CAST(NULL AS BYTES)) = 0 AS merge_all_null,
                  HLL_COUNT.MERGE_PARTIAL(CAST(NULL AS BYTES)) IS NULL AS merge_partial_all_null
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("BOOLEAN", "BOOLEAN", "BOOLEAN")))
                .body("rows[0].f.v", equalTo(List.of("true", "true", "true")));
    }

    @Test
    @Order(5)
    void hllOperationsReadBigQueryCompatibleSketchBytes() {
        query(Map.of("query", """
                WITH sketches AS (
                  SELECT FROM_BASE64('CHAQARgCIAuCBwsQARgPIBQyA9bwNQ==') AS sketch
                )
                SELECT HLL_COUNT.EXTRACT(sketch) AS extracted,
                  (SELECT HLL_COUNT.MERGE(value)
                   FROM UNNEST([sketch, FROM_BASE64('CHAQAhgCIAuCBw4QAhgPIBQyBr/IHISsGQ==')]) value) AS merged
                FROM sketches
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("INTEGER", "INTEGER")))
                .body("rows[0].f.v", equalTo(List.of("1", "3")));
    }

    @Test
    @Order(5)
    void containsSubstrExecutesWithBigQueryScalarSemantics() {
        query("""
                {"query": "SELECT CONTAINS_SUBSTR('A, B', ',') AS contains_comma", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("BOOLEAN"))
                .body("rows[0].f[0].v", equalTo("true"));
    }

    @Test
    @Order(5)
    void byteLengthRandAndStrictIpFromStringTranslateToDuckDb() {
        query(Map.of("query", """
                SELECT BYTE_LENGTH('hé') AS string_bytes,
                  RAND() >= 0 AND RAND() < 1 AS rand_in_range,
                  LENGTH(NET.IP_FROM_STRING('127.0.0.1')) AS ip_bytes
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("INTEGER", "BOOLEAN", "INTEGER")))
                .body("rows[0].f[0].v", equalTo("3"))
                .body("rows[0].f[1].v", equalTo("true"))
                .body("rows[0].f[2].v", equalTo("4"));
    }

    @Test
    @Order(5)
    void farmFingerprintMatchesBigQuerySignedInt64Vectors() {
        query(Map.of("query", """
                SELECT FARM_FINGERPRINT('1footrue') AS string_hash,
                  FARM_FINGERPRINT('2applefalse') AS positive_hash,
                  FARM_FINGERPRINT(CAST('1footrue' AS BYTES)) AS bytes_hash,
                  FARM_FINGERPRINT(CAST(NULL AS STRING)) AS null_hash
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("INTEGER", "INTEGER", "INTEGER", "INTEGER")))
                .body("rows[0].f[0].v", equalTo("-1541654101129638711"))
                .body("rows[0].f[1].v", equalTo("2794438866806483259"))
                .body("rows[0].f[2].v", equalTo("-1541654101129638711"))
                .body("rows[0].f[3].v", nullValue());
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
        query(Map.of("query", """
                CREATE TEMP FUNCTION last(x ARRAY<INT64>) AS (x);
                SELECT last([1, 2])[OFFSET(0)] AS value;
                """, "useLegacySql", false))
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
    void chainedSafeOffsetsRetainTheirStructReceiver() {
        query(Map.of("query", """
                SELECT groups[SAFE_OFFSET(0)].items[SAFE_OFFSET(0)].value AS value
                FROM (SELECT [STRUCT([STRUCT(7 AS value)] AS items)] AS groups)
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
    void netIpFunctionsProduceBigQueryCompatibleAddressBytes() {
        query(Map.of("query", """
                SELECT NET.SAFE_IP_FROM_STRING('123.123.123.123') AS ipv4,
                  NET.IP_TRUNC(NET.SAFE_IP_FROM_STRING('1111:1111:1111:1111::'), 64) AS ipv6,
                  NET.SAFE_IP_FROM_STRING('not-an-ip') AS malformed
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("BYTES", "BYTES", "BYTES")))
                .body("rows[0].f[0].v", equalTo("e3t7ew=="))
                .body("rows[0].f[1].v", equalTo("EREREREREREAAAAAAAAAAA=="))
                .body("rows[0].f[2].v", nullValue());
    }

    @Test
    @Order(5)
    void netIpToStringFormatsCanonicalAddresses() {
        query(Map.of("query", """
                SELECT NET.IP_TO_STRING(NET.SAFE_IP_FROM_STRING('123.123.123.123')) AS ipv4,
                  NET.IP_TO_STRING(NET.SAFE_IP_FROM_STRING('1111:1111:1111:1111::')) AS ipv6
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("STRING", "STRING")))
                .body("rows[0].f[0].v", equalTo("123.123.123.123"))
                .body("rows[0].f[1].v", equalTo("1111:1111:1111:1111::"));
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
    void toJsonStringFormatsTemporalValuesWithoutChangingStrings() throws Exception {
        Response response = query(Map.of("query", """
                WITH INPUT AS (
                  SELECT TIMESTAMP '2021-07-09 00:00:00+00' AS occurred_at,
                    DATE '2021-07-10' AS event_date,
                    TIME '03:04:05.123400' AS event_time,
                    DATETIME '2021-07-11 06:07:08.123400' AS event_datetime,
                    '2021-07-09 00:00:00+00' AS timestamp_shaped_text
                )
                SELECT TO_JSON_STRING(row) AS json FROM INPUT AS row
                """, "useLegacySql", false));
        response.then().statusCode(200)
                .body("schema.fields[0].type", equalTo("STRING"))
                .body("rows[0].f[0].v", equalTo(
                        "{\"occurred_at\":\"2021-07-09T00:00:00Z\","
                                        + "\"event_date\":\"2021-07-10\","
                                        + "\"event_time\":\"03:04:05.123400\","
                                        + "\"event_datetime\":\"2021-07-11T06:07:08.123400\","
                                        + "\"timestamp_shaped_text\":\"2021-07-09 00:00:00+00\"}"));

        JsonNode json = new ObjectMapper().readTree(response.jsonPath().getString("rows[0].f[0].v"));
        assertEquals(Instant.parse("2021-07-09T00:00:00Z"), Instant.parse(json.path("occurred_at").asText()));
        assertEquals("2021-07-10", json.path("event_date").asText());
        assertEquals("03:04:05.123400", json.path("event_time").asText());
        assertEquals("2021-07-11T06:07:08.123400", json.path("event_datetime").asText());
        assertEquals("2021-07-09 00:00:00+00", json.path("timestamp_shaped_text").asText());

        query(Map.of("query", """
                SELECT TO_JSON_STRING(CAST(NULL AS STRING)) AS null_value,
                  TO_JSON_STRING(STRUCT(1 AS id, [10, 20] AS coordinates), TRUE) AS pretty,
                  TO_JSON_STRING(STRUCT(1 AS id), NULL) AS null_pretty
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("null"))
                .body("rows[0].f[1].v", equalTo(
                        "{\n  \"id\": 1,\n  \"coordinates\": [\n    10,\n    20\n  ]\n}"))
                .body("rows[0].f[2].v", nullValue());
    }

    @Test
    @Order(5)
    void toJsonStringSerializesGeneratedUuidsAsStrings() throws Exception {
        Response response = query(Map.of("query", """
                WITH input AS (SELECT GENERATE_UUID() AS unique_key)
                SELECT TO_JSON_STRING(row) AS json FROM input AS row
                """, "useLegacySql", false));
        response.then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("STRING")));

        JsonNode json = new ObjectMapper().readTree(response.jsonPath().getString("rows[0].f[0].v"));
        assertEquals(UUID.fromString(json.path("unique_key").asText()).toString(), json.path("unique_key").asText());
    }

    @Test
    @Order(5)
    void temporaryFunctionsCanComposeBytesBitwiseOperators() {
        query(Map.of("query", "SELECT CONCAT(7, '_', 'value') AS combined", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("7_value"));

        query(Map.of("query", """
                CREATE TEMP FUNCTION check_bit_set(bs BYTES, idx INT64) RETURNS BOOL AS (
                  BYTE_LENGTH(bs) > 0 AND
                  BIT_COUNT(bs & (CONCAT(REPEAT(b'\\x00', BYTE_LENGTH(bs) - 1), b'\\x01') << idx)) != 0
                );
                SELECT check_bit_set(b'\\x10', 4) AS set_bit,
                       check_bit_set(b'\\x10', 3) AS clear_bit,
                       check_bit_set(b'', 4) AS empty_bytes
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("true", "false", "false")));

        query(Map.of("query", """
                CREATE TEMP FUNCTION check_bit_set(bs BYTES, idx INT64) RETURNS BOOL AS (
                  BYTE_LENGTH(bs) > 0 AND
                  BIT_COUNT(bs & (CONCAT(REPEAT(b'\\x00', BYTE_LENGTH(bs) - 1), b'\\x01') << idx)) != 0
                );
                WITH inputs AS (
                  SELECT 'row1' AS row_id, b'\\x10' AS value
                  UNION ALL SELECT 'row2', b''
                  UNION ALL SELECT 'row3', CAST(NULL AS BYTES)
                )
                SELECT row_id,
                  ARRAY_AGG(STRUCT(COALESCE(check_bit_set(value, 4), false) AS bit_set)) AS flags
                FROM inputs
                GROUP BY row_id
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(3));
    }

    @Test
    @Order(5)
    void temporaryFunctionReturnTypeIsApplied() {
        query(Map.of("query", """
                CREATE TEMP FUNCTION as_float() RETURNS FLOAT64 AS (1);
                SELECT as_float() AS value;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("FLOAT"))
                .body("rows[0].f[0].v", equalTo("1.0"));
    }

    @Test
    @Order(5)
    void temporaryFunctionParametersDoNotReplaceExtractDateParts() {
        query(Map.of("query", """
                CREATE TEMP FUNCTION f(day INT64, ts TIMESTAMP)
                AS (EXTRACT(DAY FROM ts) + day);
                SELECT f(1, TIMESTAMP '2026-10-01 00:00:00+00') AS value;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));

        query(Map.of("query", """
                CREATE TEMP FUNCTION f(day INT64, ts TIMESTAMP)
                AS (EXTRACT(DAY FROM TIMESTAMP_TRUNC(ts, DAY)) + day);
                SELECT f(1, TIMESTAMP '2026-10-01 00:00:00+00') AS value;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));

        query(Map.of("query", """
                CREATE TEMP FUNCTION add_days(day INT64, d DATE)
                AS (UNIX_DATE(DATE_ADD(d, INTERVAL 1 DAY)) + day);
                SELECT add_days(1, DATE '1970-01-01') AS value;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));
    }

    @Test
    @Order(5)
    void typedTemporaryFunctionCanReadAGroupedStructField() {
        query(Map.of("query", """
                CREATE TEMP FUNCTION normalize_category(category STRING) AS (
                  CASE WHEN category IS NULL THEN CAST(0 AS STRING) ELSE category END
                );
                SELECT normalize_category(item.category) AS category, COUNT(1) AS total
                FROM UNNEST([STRUCT(CAST(1 AS STRING) AS category)]) item
                GROUP BY item.category
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1"))
                .body("rows[0].f[1].v", equalTo("1"));
    }

    @Test
    @Order(5)
    void temporaryFunctionSubqueryAliasShadowsItsParameter() {
        query(Map.of("query", """
                CREATE TEMP FUNCTION f(x INT64) AS ((SELECT x FROM UNNEST([2]) AS x));
                SELECT f(1) AS value
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));
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

        query(Map.of("query", "CREATE TEMP FUNCTION first_id() AS ((WITH x AS (SELECT 8 AS id)"
                        + " SELECT ARRAY(SELECT id FROM x)[OFFSET(0)])); SELECT first_id() AS value;",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("8"));
    }

    @Test
    @Order(5)
    void bytesRepeatStillRejectsNegativeCountsWhenEvaluationIsRequired() {
        query(Map.of("query", "SELECT REPEAT(b'x', -1)", "useLegacySql", false))
                .then().statusCode(400)
                .body("error.message", containsString("BYTES repeat count must not be negative"));
    }

    @Test
    @Order(5)
    void toJsonStringSerializesHugeintsWithoutPrecisionLoss() {
        query(Map.of("query", """
                SELECT TO_JSON_STRING(CAST('9223372036854775808' AS HUGEINT)) AS value
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("\"9223372036854775808\""));
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

        query("""
                {"query": "SELECT 1; SELECT 2 AS value", "dryRun": true, "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("value")))
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

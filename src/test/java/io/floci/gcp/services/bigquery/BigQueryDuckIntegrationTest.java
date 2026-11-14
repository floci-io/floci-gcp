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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
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
                {"tableReference": {"tableId": "structs"}, "schema": {"fields": [
                  {"name": "s", "type": "RECORD", "fields": [
                    {"name": "a", "type": "INT64"},
                    {"name": "b", "type": "STRING"}
                  ]},
                  {"name": "amount", "type": "INT64"}
                ]}}
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
                  {"json": {"s": {"a": 1, "b": "x"}, "amount": 5}},
                  {"json": {"s": {"a": 2, "b": "x"}, "amount": 7}}
                ]}
                """).when().post(BASE + "/datasets/shop/tables/structs/insertAll")
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
    void aggregateStructStarResolvesTheCompleteNestedOperandPath() {
        List<String> queries = List.of(
                "SELECT ANY_VALUE(t.s.nested).* EXCEPT(a), COUNT(*) AS n"
                        + " FROM (SELECT STRUCT(STRUCT(1 AS a, 2 AS b) AS nested, 0 AS other) AS s) t",
                "SELECT ANY_VALUE(s.nested).* EXCEPT(a), COUNT(*) AS n"
                        + " FROM (SELECT STRUCT(STRUCT(1 AS a, 2 AS b) AS nested, 0 AS other) AS s) t",
                "SELECT ANY_VALUE(t.s.nested.leaf).* EXCEPT(a), COUNT(*) AS n"
                        + " FROM (SELECT STRUCT(STRUCT(STRUCT(1 AS a, 2 AS b) AS leaf, 3 AS c)"
                        + " AS nested, 0 AS other) AS s) t",
                "SELECT ANY_VALUE(u.s.nested).* EXCEPT(a), COUNT(*) AS n"
                        + " FROM (SELECT STRUCT(STRUCT(1 AS a, 9 AS c) AS nested) s) t"
                        + " CROSS JOIN (SELECT STRUCT(STRUCT(1 AS a, 2 AS b) AS nested) s) u",
                "WITH t AS (SELECT STRUCT(STRUCT(1 AS a, 2 AS b) AS nested, 0 AS other) AS s)"
                        + " SELECT ANY_VALUE(t.s.nested).* EXCEPT(a), COUNT(*) AS n FROM t");
        for (String sql : queries) {
            for (String grouping : List.of("", " GROUP BY ALL")) {
                query(Map.of("query", sql + grouping + " QUALIFY ROW_NUMBER() OVER (PARTITION BY b) = 1",
                                "useLegacySql", false))
                        .then().statusCode(200)
                        .body("schema.fields.name", equalTo(List.of("b", "n")))
                        .body("rows[0].f.v", equalTo(List.of("2", "1")));
            }
        }
        query(Map.of("query", "SELECT t.s.nested.* REPLACE(t.id AS a)"
                        + " FROM (SELECT STRUCT(STRUCT(1 AS a, 7 AS _floci_source_0) AS nested, 0 AS other) s,"
                        + " 9 AS id) t", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "_floci_source_0")))
                .body("rows[0].f.v", equalTo(List.of("9", "7")));
    }

    @Test
    @Order(2)
    void aggregateStructStarResolvesFieldsFromItsOwnJoinedSource() {
        for (String grouping : List.of("", " GROUP BY ALL")) {
            query(Map.of("query", "SELECT ANY_VALUE(u.s).* EXCEPT(a), COUNT(*) AS n"
                            + " FROM (SELECT STRUCT(0 AS a, 1 AS c) s) t"
                            + " CROSS JOIN (SELECT STRUCT(0 AS a, 2 AS b) s) u" + grouping
                            + " QUALIFY ROW_NUMBER() OVER (PARTITION BY b) = 1", "useLegacySql", false))
                    .then().statusCode(200)
                    .body("schema.fields.name", equalTo(List.of("b", "n")))
                    .body("rows[0].f.v", equalTo(List.of("2", "1")));
        }
        query(Map.of("query", "WITH t AS (SELECT STRUCT(0 AS a, 1 AS c) s),"
                        + " u AS (SELECT STRUCT(0 AS a, 2 AS b) s)"
                        + " SELECT ANY_VALUE(u.s).* EXCEPT(a), COUNT(*) AS n FROM t CROSS JOIN u GROUP BY ALL"
                        + " QUALIFY ROW_NUMBER() OVER (PARTITION BY b) = 1", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f.v", equalTo(List.of("2", "1")));
    }

    @Test
    @Order(2)
    void aggregateStructStarFiltersSourcesBeforeAggregation() {
        for (String grouping : List.of("", " GROUP BY ALL")) {
            String source = "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n"
                    + " FROM (SELECT STRUCT(1 AS a, 2 AS b) s, 5 AS b) t WHERE b ";
            query(Map.of("query", source + "> 0" + grouping, "useLegacySql", false))
                    .then().statusCode(200)
                    .body("schema.fields.name", equalTo(List.of("b", "n")))
                    .body("rows[0].f.v", equalTo(List.of("2", "1")));
            query(Map.of("query", source + "< 0" + grouping, "useLegacySql", false))
                    .then().statusCode(200)
                    .body("totalRows", equalTo("1"))
                    .body("rows[0].f[0].v", nullValue())
                    .body("rows[0].f[1].v", equalTo("0"));
            query(Map.of("query", "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS a"
                            + " FROM (SELECT STRUCT(1 AS a, 2 AS b) s, 5 AS a) t WHERE a > 0" + grouping
                            + " HAVING a > 0 QUALIFY ROW_NUMBER() OVER (PARTITION BY b) = 1",
                            "useLegacySql", false))
                    .then().statusCode(200)
                    .body("schema.fields.name", equalTo(List.of("b", "a")))
                    .body("rows[0].f.v", equalTo(List.of("2", "1")));
        }
    }

    @Test
    @Order(2)
    void structStarReplacementKeepsFieldReferencesSeparateFromSiblingOutputs() {
        query(Map.of("query", "SELECT 99 AS b, t.s.* REPLACE(t.s.b + 1 AS a)"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "a", "b_1")))
                .body("rows[0].f.v", equalTo(List.of("99", "3", "2")));
        for (String grouping : List.of("", " GROUP BY ALL", " GROUP BY 1, 2")) {
            query(Map.of("query", "SELECT 99 AS b, t.s.* EXCEPT(b) REPLACE(t.s.b + 1 AS a)"
                            + " FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t" + grouping, "useLegacySql", false))
                    .then().statusCode(200)
                    .body("schema.fields.name", equalTo(List.of("b", "a")))
                    .body("rows[0].f.v", equalTo(List.of("99", "3")));
        }
        query(Map.of("query", "SELECT t.s.* REPLACE(t.s.b + 1 AS a, t.s.a + 1 AS b), 99 AS b"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b", "b_1")))
                .body("rows[0].f.v", equalTo(List.of("3", "2", "99")));
        query(Map.of("query", "SELECT 99 AS b, t.s.* REPLACE((SELECT t.s.b + 1) AS a)"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "a", "b_1")))
                .body("rows[0].f.v", equalTo(List.of("99", "3", "2")));
    }

    @Test
    @Order(2)
    void structStarOrderingGroupingAndJoinColumnsMatchBigQuery() {
        query(Map.of("query", "SELECT t.s.* EXCEPT(a) FROM (SELECT STRUCT(1 AS a, 'x' AS b) s, 9 AS b"
                        + " UNION ALL SELECT STRUCT(2 AS a, 'y' AS b), 1) t ORDER BY b DESC", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows.f.v", equalTo(List.of(List.of("y"), List.of("x"))));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), t.id FROM (SELECT STRUCT(1 AS a, 'x' AS b) s, 5 AS id) t"
                        + " ORDER BY UPPER(b)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "id")))
                .body("rows[0].f.v", equalTo(List.of("x", "5")));
        query(Map.of("query", "SELECT t.s.* REPLACE (c AS a) FROM (SELECT STRUCT(1 AS a) s, 5 AS c) t"
                        + " JOIN (SELECT 5 AS c) u USING (c)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f.v", equalTo(List.of("5")));
        query(Map.of("query", "SELECT t.s.* REPLACE(STRUCT(t.id AS n) AS a)"
                        + " FROM (SELECT STRUCT(1 AS a) s, 5 AS id UNION ALL SELECT STRUCT(2 AS a), 3) t"
                        + " ORDER BY a.n DESC", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f[0].v.f[0].v", equalTo("5"))
                .body("rows[1].f[0].v.f[0].v", equalTo("3"));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s"
                        + " UNION ALL SELECT STRUCT(2 AS a, 'x' AS b) UNION ALL SELECT STRUCT(3 AS a, 'x' AS b)"
                        + " UNION ALL SELECT STRUCT(4 AS a, 'y' AS b)) t GROUP BY ALL"
                        + " QUALIFY ROW_NUMBER() OVER (ORDER BY n DESC) = 1", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f.v", equalTo(List.of("x", "3")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s"
                        + " UNION ALL SELECT STRUCT(2 AS a, 'y' AS b)) t GROUP BY ALL HAVING COUNT(*) > 0"
                        + " QUALIFY ROW_NUMBER() OVER (ORDER BY b DESC) = 1 ORDER BY b DESC LIMIT 1",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f.v", equalTo(List.of("y", "1")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s"
                        + " UNION ALL SELECT STRUCT(2 AS a, 'x' AS b) UNION ALL SELECT STRUCT(3 AS a, 'y' AS b)) t"
                        + " GROUP BY ALL QUALIFY ROW_NUMBER() OVER (PARTITION BY b ORDER BY n DESC) = 1 ORDER BY b",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows.f.v", equalTo(List.of(List.of("x", "2"), List.of("y", "1"))));
    }

    @Test
    @Order(2)
    void plainStructStarGroupsLikeBigQuery() {
        assertResultColumns("SELECT t.s.* FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t GROUP BY ALL",
                List.of("a", "b"), List.of("1", "2"));
        assertResultColumns("SELECT t.s.*, 5 AS b FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t GROUP BY ALL",
                List.of("a", "b", "b_1"), List.of("1", "2", "5"));
        assertResultColumns("SELECT t.s.*, 5 AS b FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t GROUP BY 1, 2, 3",
                List.of("a", "b", "b_1"), List.of("1", "2", "5"));
        assertResultColumns("SELECT 5 AS b, t.s.* FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t GROUP BY ALL",
                List.of("b", "a", "b_1"), List.of("5", "1", "2"));
        assertResultColumns("SELECT t.s.* FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t GROUP BY ALL"
                + " ORDER BY b DESC", List.of("a", "b"), List.of("1", "2"));
    }

    @Test
    @Order(2)
    void indexedAggregateStructStarAcceptsParentheses() {
        assertResultColumns("SELECT (ARRAY_AGG(t ORDER BY id))[OFFSET(0)].* EXCEPT (id)"
                        + " FROM (SELECT 1 AS id, 'x' AS name UNION ALL SELECT 2, 'y') t",
                List.of("name"), List.of("x"));
        assertResultColumns("SELECT (ARRAY_AGG(t ORDER BY id))[OFFSET(0)].* REPLACE (99 AS id)"
                        + " FROM (SELECT 1 AS id, 'x' AS name UNION ALL SELECT 2, 'y') t",
                List.of("id", "name"), List.of("99", "x"));
    }

    @Test
    @Order(2)
    void structStarGroupedDuplicateNamesAndSourceStructOrderingMatchBigQuery() {
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), 5 AS b FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t"
                        + " GROUP BY 1, 2", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "b_1")))
                .body("rows[0].f.v", equalTo(List.of("2", "5")));
        query(Map.of("query", "SELECT 5 AS b, t.s.* EXCEPT(a) FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t"
                        + " GROUP BY ALL", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "b_1")))
                .body("rows[0].f.v", equalTo(List.of("5", "2")));
        query(Map.of("query", "SELECT 5 AS b, t.s.* REPLACE(9 AS a)"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t GROUP BY ALL", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "a", "b_1")))
                .body("rows[0].f.v", equalTo(List.of("5", "9", "2")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), t.id AS b, -t.id AS b_1"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS b) s, 1 AS id"
                        + " UNION ALL SELECT STRUCT(1 AS a, 2 AS b), 2) t"
                        + " GROUP BY ALL ORDER BY b_1", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "b_2", "b_1")))
                .body("rows[0].f.v", equalTo(List.of("2", "2", "-2")))
                .body("rows[1].f.v", equalTo(List.of("2", "1", "-1")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), t.id AS b"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS b, 3 AS b_1) s, 1 AS id"
                        + " UNION ALL SELECT STRUCT(1 AS a, 2 AS b, 3 AS b_1), 2) t"
                        + " GROUP BY ALL", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "b_1", "b_2")))
                .body("rows[0].f.v", equalTo(List.of("2", "3", "1")))
                .body("rows[1].f.v", equalTo(List.of("2", "3", "2")));
        query(Map.of("query", "SELECT t.s.* REPLACE(STRUCT(t.id AS n) AS a)"
                        + " FROM (SELECT STRUCT(1 AS a) s, 5 AS id, STRUCT(7 AS n) u"
                        + " UNION ALL SELECT STRUCT(2 AS a), 3, STRUCT(9 AS n)) t"
                        + " ORDER BY u.n DESC", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f[0].v.f[0].v", equalTo("3"))
                .body("rows[1].f[0].v.f[0].v", equalTo("5"));
        query(Map.of("query", "SELECT t.s.* REPLACE(STRUCT(t.id AS n) AS a)"
                        + " FROM (SELECT STRUCT(1 AS a) s, 5 AS id, STRUCT(7 AS n) u"
                        + " UNION ALL SELECT STRUCT(2 AS a), 3, STRUCT(9 AS n)) t"
                        + " ORDER BY t.u.n DESC", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f[0].v.f[0].v", equalTo("3"))
                .body("rows[1].f[0].v.f[0].v", equalTo("5"));
        query(Map.of("query", "SELECT t.s.* REPLACE(9 AS a)"
                        + " FROM (SELECT STRUCT(1 AS a, STRUCT(1 AS n) AS u) s, STRUCT(9 AS n) u"
                        + " UNION ALL SELECT STRUCT(2 AS a, STRUCT(2 AS n) AS u), STRUCT(0 AS n)) t"
                        + " ORDER BY u.n", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "u")))
                .body("rows[0].f[0].v", equalTo("9"))
                .body("rows[0].f[1].v.f[0].v", equalTo("1"))
                .body("rows[1].f[1].v.f[0].v", equalTo("2"));
    }

    @Test
    @Order(2)
    void structStarDuplicateNamesSkipASuffixALaterFieldUses() {
        query(Map.of("query", "SELECT 5 AS b, t.s.* EXCEPT(a)"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS b, 3 AS b_1) s) t", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "b_2", "b_1")))
                .body("rows[0].f.v", equalTo(List.of("5", "2", "3")));
        query(Map.of("query", "SELECT DISTINCT 5 AS b, t.s.* EXCEPT(a)"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS b, 3 AS b_1) s) t", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "b_2", "b_1")))
                .body("rows[0].f.v", equalTo(List.of("5", "2", "3")));
        query(Map.of("query", "SELECT 5 AS b, 6 AS b, t.s.* EXCEPT(a)"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS b, 3 AS b_1) s) t", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "b_2", "b_3", "b_1")))
                .body("rows[0].f.v", equalTo(List.of("5", "6", "2", "3")));
        query(Map.of("query", "SELECT 5 AS b, t.s.* EXCEPT(a)"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS b, 3 AS b_1) s"
                        + " UNION ALL SELECT STRUCT(1 AS a, 2 AS b, 4 AS b_1)) t ORDER BY b_1 DESC",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "b_2", "b_1")))
                .body("rows[0].f.v", equalTo(List.of("5", "2", "4")))
                .body("rows[1].f.v", equalTo(List.of("5", "2", "3")));
        query(Map.of("query", "SELECT 5 AS b, t.s.* REPLACE(9 AS a)"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS b, 3 AS b_1) s"
                        + " UNION ALL SELECT STRUCT(1 AS a, 7 AS b, 4 AS b_1)) t"
                        + " GROUP BY ALL ORDER BY b_1", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "a", "b_2", "b_1")))
                .body("rows[0].f.v", equalTo(List.of("5", "9", "2", "3")))
                .body("rows[1].f.v", equalTo(List.of("5", "9", "7", "4")));
    }

    @Test
    @Order(2)
    void indexedAggregateStructStarAppliesItsStarList() {
        query(Map.of("query", "SELECT ARRAY_AGG(t ORDER BY id DESC LIMIT 1)[OFFSET(0)].* EXCEPT(id)"
                        + " FROM (SELECT 1 AS id, 'x' AS name UNION ALL SELECT 2, 'y') t",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("name")))
                .body("rows[0].f[0].v", equalTo("y"));
        query(Map.of("query", "SELECT ARRAY_AGG(t ORDER BY id DESC LIMIT 1)[OFFSET(0)].* REPLACE (99 AS id)"
                        + " FROM (SELECT 1 AS id, 'x' AS name UNION ALL SELECT 2, 'y') t",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "name")))
                .body("rows[0].f.v", equalTo(List.of("99", "y")));
    }

    @Test
    @Order(2)
    void structStarSetOperationArmsExecuteIndependently() {
        String star = "SELECT t.s.* EXCEPT(a), t.id FROM (SELECT STRUCT(1 AS a, 2 AS c) s, 5 AS id) t";
        query(Map.of("query", star + " UNION ALL SELECT 3, 6", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "id")))
                .body("rows.f.v", containsInAnyOrder(List.of("2", "5"), List.of("3", "6")));
        query(Map.of("query", star + " UNION ALL SELECT u.s.* EXCEPT(b), u.id"
                        + " FROM (SELECT STRUCT(3 AS b, 4 AS d) s, 7 AS id) u", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "id")))
                .body("rows.f.v", containsInAnyOrder(List.of("2", "5"), List.of("4", "7")));
        query(Map.of("query", star + " UNION ALL SELECT 3, 6 ORDER BY 1 DESC", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("3", "6"), List.of("2", "5"))));
        query(Map.of("query", star + " UNION ALL SELECT 3, 6 LIMIT 1", "useLegacySql", false))
                .then().statusCode(200)
                .body("totalRows", equalTo("1"))
                .body("rows[0].f.v", anyOf(equalTo(List.of("2", "5")), equalTo(List.of("3", "6"))));
        query(Map.of("query", "(" + star + ") UNION ALL (SELECT 3, 6)", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", containsInAnyOrder(List.of("2", "5"), List.of("3", "6")));
        query(Map.of("query", "WITH c AS (" + star + " UNION ALL SELECT 3, 6)"
                        + " SELECT * FROM c ORDER BY id", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("2", "5"), List.of("3", "6"))));
        query(Map.of("query", "SELECT 3, 6 UNION ALL " + star, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("f0_", "f1_")))
                .body("rows.f.v", containsInAnyOrder(List.of("3", "6"), List.of("2", "5")));
        String oneColumn = "SELECT t.s.* EXCEPT(a) FROM (SELECT STRUCT(1 AS a, 2 AS c) s) t";
        query(Map.of("query", oneColumn + " INTERSECT DISTINCT SELECT 2", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("2"))));
        query(Map.of("query", oneColumn + " EXCEPT DISTINCT SELECT 2", "useLegacySql", false))
                .then().statusCode(200)
                .body("totalRows", equalTo("0"));
        query(Map.of("query", oneColumn + " UNION DISTINCT SELECT 2", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("2"))));
        query(Map.of("query", "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS c) s) t GROUP BY ALL"
                        + " UNION ALL SELECT 2, 1", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("2", "1"), List.of("2", "1"))));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), COUNT(*) AS n"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS c) s) t GROUP BY 1"
                        + " UNION ALL SELECT 2, 1", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("2", "1"), List.of("2", "1"))));
        query(Map.of("query", "SELECT t.s.* REPLACE(10 AS a), t.id FROM (SELECT STRUCT(1 AS a) s, 5 AS id) t"
                        + " UNION ALL SELECT 2, 7", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", containsInAnyOrder(List.of("10", "5"), List.of("2", "7")));
        query(Map.of("query", "SELECT * FROM (" + oneColumn + " UNION ALL SELECT 2) q ORDER BY c",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("2"), List.of("2"))));
    }

    @Test
    @Order(2)
    void setOperationTrailingClausesStayOutsideTheLastArm() {
        query(Map.of("query", "SELECT 1 AS x UNION ALL SELECT t.s.* EXCEPT(a)"
                        + " FROM (SELECT STRUCT(0 AS a, 2 AS c) s) t ORDER BY x", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x")))
                .body("rows.f.v", equalTo(List.of(List.of("1"), List.of("2"))));
        query(Map.of("query", "SELECT 1 AS x UNION ALL SELECT t.s.* EXCEPT(a)"
                        + " FROM (SELECT STRUCT(0 AS a, 2 AS c) s) t ORDER BY x DESC LIMIT 1",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("2")));
        query(Map.of("query", "SELECT 0 AS c UNION ALL (SELECT t.s.* EXCEPT(a)"
                        + " FROM (SELECT STRUCT(0 AS a, 2 AS c) s, 3 AS id) t ORDER BY t.id LIMIT 1)",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c")))
                .body("rows.f.v", equalTo(List.of(List.of("0"), List.of("2"))));

    }

    @Test
    @Order(2)
    void usingColumnsReadTheMergedValueInOuterJoins() {
        query(Map.of("query", "SELECT u.s.* REPLACE(c AS a) FROM (SELECT 1 AS c) t"
                        + " RIGHT JOIN (SELECT 2 AS c, STRUCT(0 AS a) s) u USING(c)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f.v", equalTo(List.of("2")));
        query(Map.of("query", "SELECT u.s.* REPLACE(c AS a)"
                        + " FROM (SELECT 1 AS c UNION ALL SELECT 9) t"
                        + " FULL JOIN (SELECT 2 AS c, STRUCT(0 AS a) s"
                        + " UNION ALL SELECT 9, STRUCT(1 AS a) s) u USING(c) ORDER BY a", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("1"), List.of("2"), List.of("9"))));
        query(Map.of("query", "SELECT t.s.* REPLACE(c AS a)"
                        + " FROM (SELECT 1 AS c, STRUCT(5 AS a) s UNION ALL SELECT 9, STRUCT(6 AS a) s) t"
                        + " LEFT JOIN (SELECT 1 AS c UNION ALL SELECT 7) u USING(c) ORDER BY a",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("1"), List.of("9"))));
        query(Map.of("query", "SELECT t.s.* REPLACE(c AS a) FROM (SELECT 1 AS c, STRUCT(5 AS a) s) t"
                        + " JOIN (SELECT 1 AS c) u USING(c) JOIN (SELECT 1 AS c, 2 AS z) v USING(c)",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("1")));
    }

    @Test
    @Order(2)
    void structStarRenamesAndGroupingHelpersSurviveTheTail() {
        query(Map.of("query", "SELECT t.s.* REPLACE(SUM(t.id) AS a), 99 AS a"
                        + " FROM (SELECT STRUCT(1 AS a) s, 5 AS id UNION ALL SELECT STRUCT(1 AS a), 6) t"
                        + " GROUP BY t.s ORDER BY MAX(t.id)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "a_1")))
                .body("rows[0].f.v", equalTo(List.of("11", "99")));
        query(Map.of("query", "SELECT t.s.* REPLACE(SUM(t.id) AS a), 99 AS a"
                        + " FROM (SELECT STRUCT(1 AS a) s, 5 AS id UNION ALL SELECT STRUCT(1 AS a), 6) t"
                        + " GROUP BY t.s ORDER BY SUM(t.id)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "a_1")))
                .body("rows[0].f.v", equalTo(List.of("11", "99")));
        query(Map.of("query", "SELECT t.s.* REPLACE(SUM(t.id) AS a), 99 AS a"
                        + " FROM (SELECT STRUCT(1 AS a) s, 5 AS id UNION ALL SELECT STRUCT(1 AS a), 6) t"
                        + " GROUP BY t.s QUALIFY ROW_NUMBER() OVER (ORDER BY SUM(t.id)) = 1",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "a_1")))
                .body("rows[0].f.v", equalTo(List.of("11", "99")));
        query(Map.of("query", "SELECT t.s.* REPLACE(SUM(t.id) AS a), 99 AS a"
                        + " FROM (SELECT STRUCT(1 AS a) s, 5 AS id UNION ALL SELECT STRUCT(1 AS a), 6) t"
                        + " GROUP BY t.s QUALIFY ROW_NUMBER() OVER (ORDER BY MAX(t.id)) = 1",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "a_1")))
                .body("rows[0].f.v", equalTo(List.of("11", "99")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(c) REPLACE(SUM(t.id) AS a)"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS c) s, 5 AS id"
                        + " UNION ALL SELECT STRUCT(1 AS a, 3 AS c), 6) t"
                        + " GROUP BY t.s HAVING a > (SELECT a FROM (SELECT 4 AS a))", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", containsInAnyOrder(List.of("5"), List.of("6")));
        query(Map.of("query", "SELECT t.s.* REPLACE(10 AS a), u.s.* REPLACE(20 AS a)"
                        + " FROM (SELECT STRUCT(1 AS a) s) t, (SELECT STRUCT(2 AS a) s) u", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "a_1")))
                .body("rows[0].f.v", equalTo(List.of("10", "20")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(z), u.s.* REPLACE(STRUCT(1 AS x) AS y)"
                        + " FROM (SELECT STRUCT(1 AS x, 0 AS z) s) t, (SELECT STRUCT(2 AS x, 3 AS y) s) u",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x", "x_1", "y")))
                .body("rows[0].f[0].v", equalTo("1"))
                .body("rows[0].f[1].v", equalTo("2"))
                .body("rows[0].f[2].v.f.v", equalTo(List.of("1")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(z), u.s.* REPLACE([10,20] AS x)"
                        + " FROM (SELECT STRUCT(1 AS x, 0 AS z) s) t, (SELECT STRUCT(2 AS x) s) u",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x", "x_1")))
                .body("rows[0].f[0].v", equalTo("1"))
                .body("rows[0].f[1].v.v", equalTo(List.of("10", "20")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), ROW_NUMBER() OVER () AS rn"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t GROUP BY ALL QUALIFY rn = 1",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "rn")))
                .body("rows[0].f.v", equalTo(List.of("2", "1")));
    }

    @Test
    @Order(2)
    void multipleStructStarsExecuteWithTheirOwnStarLists() {
        String twoStars = "SELECT t.s.* EXCEPT(a), u.s.* EXCEPT(b)"
                + " FROM (SELECT STRUCT(1 AS a, 10 AS c) s) t, (SELECT STRUCT(2 AS b, 20 AS d) s) u";
        query(Map.of("query", twoStars, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "d")))
                .body("rows[0].f.v", equalTo(List.of("10", "20")));
        query(Map.of("query", "SELECT t.s.* REPLACE(10 AS a), u.s.* REPLACE(20 AS b)"
                        + " FROM (SELECT STRUCT(1 AS a, 5 AS c) s) t, (SELECT STRUCT(2 AS b, 6 AS d) s) u",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "c", "b", "d")))
                .body("rows[0].f.v", equalTo(List.of("10", "5", "20", "6")));
        query(Map.of("query", "SELECT s.* EXCEPT(x), u.t.* EXCEPT(y)"
                        + " FROM (SELECT STRUCT(1 AS x, 5 AS c) s) t, (SELECT STRUCT(2 AS y, 6 AS c) t) u",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "c_1")))
                .body("rows[0].f.v", equalTo(List.of("5", "6")));
        String colliding = "SELECT t.s.* EXCEPT(a), u.s.* EXCEPT(b)"
                + " FROM (SELECT STRUCT(1 AS a, 5 AS c) s) t, (SELECT STRUCT(2 AS b, 6 AS c) s) u";
        query(Map.of("query", colliding, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "c_1")))
                .body("rows[0].f.v", equalTo(List.of("5", "6")));
        query(Map.of("query", "SELECT 99 AS c, t.s.* EXCEPT(a), u.s.* EXCEPT(b)"
                        + " FROM (SELECT STRUCT(1 AS a, 5 AS c) s) t, (SELECT STRUCT(2 AS b, 6 AS c) s) u",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "c_1", "c_2")))
                .body("rows[0].f.v", equalTo(List.of("99", "5", "6")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), u.s.* EXCEPT(b), 9 AS c"
                        + " FROM (SELECT STRUCT(1 AS a, 5 AS c) s) t, (SELECT STRUCT(2 AS b, 6 AS c) s) u",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "c_1", "c_2")))
                .body("rows[0].f.v", equalTo(List.of("5", "6", "9")));
        query(Map.of("query", "SELECT t.s.* REPLACE(x AS a), u.s.* EXCEPT(b)"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS x) s) t, (SELECT STRUCT(2 AS b, 8 AS d) s) u,"
                        + " (SELECT 5 AS x) v", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "x", "d")))
                .body("rows[0].f.v", equalTo(List.of("5", "2", "8")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a) REPLACE(t.s.c + 1 AS c), u.s.* EXCEPT(b)"
                        + " FROM (SELECT STRUCT(1 AS a, 5 AS c) s) t, (SELECT STRUCT(2 AS b, 6 AS d) s) u",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "d")))
                .body("rows[0].f.v", equalTo(List.of("6", "6")));
        query(Map.of("query", "SELECT t.*, u.s.* EXCEPT(b)"
                        + " FROM (SELECT STRUCT(2 AS b, 8 AS d) s, 7 AS id) u, (SELECT 1 AS x) t",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x", "d")))
                .body("rows[0].f.v", equalTo(List.of("1", "8")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), u.s.* EXCEPT(b)"
                        + " FROM (SELECT STRUCT(1 AS a, 10 AS c) s UNION ALL SELECT STRUCT(2 AS a, 3 AS c)) t,"
                        + " (SELECT STRUCT(2 AS b, 20 AS d) s) u ORDER BY c DESC", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("10", "20"), List.of("3", "20"))));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), u.s.* EXCEPT(b), COUNT(*) AS n"
                        + " FROM (SELECT STRUCT(1 AS a, 'x' AS c) s) t, (SELECT STRUCT(2 AS b, 20 AS d) s) u"
                        + " GROUP BY ALL QUALIFY ROW_NUMBER() OVER (ORDER BY n DESC) = 1",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "d", "n")))
                .body("rows[0].f.v", equalTo(List.of("x", "20", "1")));
        query(Map.of("query", "WITH c AS (" + twoStars + ") SELECT * FROM c", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("10", "20")));
        query(Map.of("query", twoStars + " UNION ALL SELECT 3, 4", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", containsInAnyOrder(List.of("10", "20"), List.of("3", "4")));
        query(Map.of("query", "SELECT ARRAY(SELECT AS STRUCT t.s.* EXCEPT(a), u.s.* EXCEPT(b)"
                        + " FROM (SELECT STRUCT(1 AS a, 2 AS c) s) t, (SELECT STRUCT(3 AS b, 4 AS d) s) u) AS arr",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("2", "4")));
        query(Map.of("query", "CREATE TABLE shop.multi_star_a AS SELECT STRUCT(1 AS a, 5 AS c) AS s",
                        "useLegacySql", false))
                .then().statusCode(200);
        query(Map.of("query", "CREATE TABLE shop.multi_star_b AS SELECT STRUCT(2 AS b, 6 AS c) AS s",
                        "useLegacySql", false))
                .then().statusCode(200);
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), u.s.* EXCEPT(b)"
                        + " FROM shop.multi_star_a t, shop.multi_star_b u", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "c_1")))
                .body("rows[0].f.v", equalTo(List.of("5", "6")));
    }

    @Test
    @Order(2)
    void scalarSubqueryStructStarsExecute() {
        query(Map.of("query", "SELECT (SELECT STRUCT(1 AS a, 2 AS c)).* EXCEPT(a),"
                        + " (SELECT STRUCT(3 AS b, 4 AS d)).* EXCEPT(b)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "d")))
                .body("rows[0].f.v", equalTo(List.of("2", "4")));
        query(Map.of("query", "SELECT (SELECT STRUCT(1 AS a, 2 AS c)).* EXCEPT(a), u.s.* EXCEPT(b)"
                        + " FROM (SELECT STRUCT(3 AS b, 4 AS d) s) u", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "d")))
                .body("rows[0].f.v", equalTo(List.of("2", "4")));
        query(Map.of("query", "SELECT (SELECT STRUCT(t.id AS n)).*, u.s.* EXCEPT(b)"
                        + " FROM (SELECT 5 AS id) t, (SELECT STRUCT(2 AS b, 8 AS d) s) u", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("n", "d")))
                .body("rows[0].f.v", equalTo(List.of("5", "8")));
        query(Map.of("query", "SELECT (SELECT STRUCT(1 AS a)).* REPLACE(10 AS a),"
                        + " (SELECT STRUCT(2 AS b)).* REPLACE(20 AS b)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b")))
                .body("rows[0].f.v", equalTo(List.of("10", "20")));
        query(Map.of("query", "SELECT (SELECT STRUCT(1 AS a, 5 AS c)).* EXCEPT(a),"
                        + " (SELECT STRUCT(2 AS b, 6 AS c)).* EXCEPT(b)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "c_1")))
                .body("rows[0].f.v", equalTo(List.of("5", "6")));
        query(Map.of("query", "SELECT (SELECT AS STRUCT 1 AS a, 2 AS c).* EXCEPT(a),"
                        + " (SELECT AS STRUCT 3 AS b, 4 AS d).* EXCEPT(b)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "d")))
                .body("rows[0].f.v", equalTo(List.of("2", "4")));
        query(Map.of("query", "SELECT (SELECT STRUCT(1 AS a, 2 AS c)).* EXCEPT(a)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c")))
                .body("rows[0].f.v", equalTo(List.of("2")));
        query(Map.of("query", "SELECT (SELECT STRUCT(1 AS a, 2 AS c)).* EXCEPT(a) UNION ALL SELECT 4",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", containsInAnyOrder(List.of("2"), List.of("4")));
    }

    @Test
    @Order(2)
    void multipleStructStarsRejectInvalidStarListsAndASiblingStar() {
        String bases = " FROM (SELECT STRUCT(1 AS a) s) t, (SELECT STRUCT(2 AS b, 8 AS d) s) u";
        query(Map.of("query", "SELECT t.s.* EXCEPT(z), u.s.* EXCEPT(b)" + bases, "useLegacySql", false))
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body("error.message", equalTo("Column z in SELECT * EXCEPT list does not exist"));
        query(Map.of("query", "SELECT t.s.* REPLACE(10 AS z), u.s.* EXCEPT(b)" + bases, "useLegacySql", false))
                .then().statusCode(400)
                .body("error.message", equalTo("Column z in SELECT * REPLACE list does not exist"));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a) REPLACE(10 AS a), u.s.* EXCEPT(b)" + bases,
                        "useLegacySql", false))
                .then().statusCode(400)
                .body("error.message", equalTo("Column a cannot occur in both SELECT * EXCEPT and REPLACE"));
        query(Map.of("query", "SELECT t.s.* REPLACE(x AS a), u.s.* EXCEPT(b)" + bases, "useLegacySql", false))
                .then().statusCode(400)
                .body("error.message", equalTo("Unrecognized name: x"));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a), *" + bases, "useLegacySql", false))
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body("error.message", containsString("Dot-star with EXCEPT on a STRUCT expression"));
    }

    @Test
    @Order(2)
    void aggregateStructStarRejectsAnAggregateThatCannotReturnAStruct() {
        String base = " FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t";
        String[][] cases = {
                {"SELECT MAX(t.s).* EXCEPT(a)" + base, "MAX is not defined for arguments of type STRUCT"},
                {"SELECT MAX(t.s).*" + base, "MAX is not defined for arguments of type STRUCT"},
                {"SELECT MIN(t.s).* EXCEPT(a)" + base, "MIN is not defined for arguments of type STRUCT"},
                {"SELECT COUNT(t.s).* EXCEPT(a)" + base, "Dot-star is not supported for type INT64"},
                {"SELECT ARRAY_AGG(t.s).* EXCEPT(a)" + base, "Dot-star is not supported for type ARRAY"},
                {"SELECT MAX(t.s.a).*" + base, "Dot-star is not supported for the aggregate expression"},
        };
        for (String[] c : cases) {
            query(Map.of("query", c[0], "useLegacySql", false))
                    .then().statusCode(400)
                    .body("error.errors[0].reason", equalTo("invalidQuery"))
                    .body("error.message", equalTo(c[1]));
        }
        query(Map.of("query", "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n" + base + " GROUP BY ALL",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")));
    }

    @Test
    @Order(2)
    void groupedStructStarReplacementsExecute() {
        String one = "(SELECT STRUCT(1 AS a) s, 5 AS id UNION ALL SELECT STRUCT(1 AS a), 6)";
        String data = "(SELECT STRUCT(1 AS a, 2 AS c) s, 5 AS id"
                + " UNION ALL SELECT STRUCT(1 AS a, 3 AS c), 6 UNION ALL SELECT STRUCT(2 AS a, 4 AS c), 7)";
        query(Map.of("query", "SELECT t.s.* REPLACE(SUM(t.id) AS a) FROM " + one + " t GROUP BY t.s",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f.v", equalTo(List.of("11")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(c) REPLACE(SUM(t.id) AS a), COUNT(*) AS n"
                        + " FROM " + data + " t GROUP BY t.s ORDER BY a", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "n")))
                .body("rows.f.v", equalTo(List.of(List.of("5", "1"), List.of("6", "1"), List.of("7", "1"))));
        query(Map.of("query", "SELECT t.id, t.s.* EXCEPT(c) REPLACE(SUM(t.id) AS a)"
                        + " FROM " + data + " t GROUP BY t.id ORDER BY id", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "a")))
                .body("rows.f.v", equalTo(List.of(List.of("5", "5"), List.of("6", "6"), List.of("7", "7"))));
        query(Map.of("query", "SELECT 99 AS a, t.s.* REPLACE(SUM(t.id) AS a)"
                        + " FROM " + one + " t GROUP BY t.s", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "a_1")))
                .body("rows[0].f.v", equalTo(List.of("99", "11")));
        query(Map.of("query", "SELECT t.s.* REPLACE(SUM(t.id) AS a), 99 AS a"
                        + " FROM " + one + " t GROUP BY t.s", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "a_1")))
                .body("rows[0].f.v", equalTo(List.of("11", "99")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(c) REPLACE(SUM(t.id) AS a)"
                        + " FROM " + data + " t GROUP BY t.s HAVING a > 6", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("7")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(c) REPLACE(SUM(t.id) AS a)"
                        + " FROM " + data + " t GROUP BY t.s"
                        + " QUALIFY ROW_NUMBER() OVER (ORDER BY SUM(t.id) DESC) = 1", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("7")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(c) REPLACE(SUM(t.id) AS a)"
                        + " FROM " + data + " t GROUP BY t.s ORDER BY MAX(t.id)", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("5"), List.of("6"), List.of("7"))));
        query(Map.of("query", "SELECT t.s.* EXCEPT(c) REPLACE(SUM(t.id) AS a), MOD(t.id, 2) AS k"
                        + " FROM " + data + " t GROUP BY t.s, k ORDER BY a", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "k")))
                .body("rows.f.v", equalTo(List.of(List.of("5", "1"), List.of("6", "0"), List.of("7", "1"))));
        query(Map.of("query", "SELECT t.id, t.s.* EXCEPT(c) REPLACE(SUM(t.id) AS a), COUNT(*) AS n"
                        + " FROM " + data + " t GROUP BY 1 ORDER BY 1", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "a", "n")))
                .body("rows.f.v", equalTo(List.of(List.of("5", "5", "1"), List.of("6", "6", "1"),
                        List.of("7", "7", "1"))));
        query(Map.of("query", "SELECT t.s.* EXCEPT(c) REPLACE(SUM(t.id) AS a)"
                        + " FROM " + data + " t GROUP BY ALL", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("18")));
        query(Map.of("query", "SELECT t.s.* REPLACE(SUM(t.id) AS a, MAX(t.id) AS c), t.id"
                        + " FROM " + data + " t GROUP BY 3 ORDER BY id", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "c", "id")))
                .body("rows[0].f.v", equalTo(List.of("5", "5", "5")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(c) REPLACE(SUM(t.s.c) AS a)"
                        + " FROM " + data + " t GROUP BY t.s ORDER BY a", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("2"), List.of("3"), List.of("4"))));
        query(Map.of("query", "SELECT t.s.* REPLACE(SUM(t.id) AS a) FROM " + one + " t GROUP BY t.s"
                        + " UNION ALL SELECT 3", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", containsInAnyOrder(List.of("11"), List.of("3")));
        query(Map.of("query", "CREATE TABLE shop.grouped_star AS SELECT STRUCT(1 AS a, 2 AS c) s, 5 AS id"
                        + " UNION ALL SELECT STRUCT(1 AS a, 3 AS c), 6", "useLegacySql", false))
                .then().statusCode(200);
        query(Map.of("query", "SELECT t.s.* EXCEPT(c) REPLACE(SUM(t.id) AS a)"
                        + " FROM shop.grouped_star t GROUP BY t.s ORDER BY a", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("5"), List.of("6"))));
    }

    @Test
    @Order(2)
    void groupedStructStarReplacementsRejectInvalidStarLists() {
        String base = " FROM (SELECT STRUCT(1 AS a, 2 AS c) s, 5 AS id) t GROUP BY t.s";
        query(Map.of("query", "SELECT t.s.* EXCEPT(z) REPLACE(SUM(t.id) AS a)" + base, "useLegacySql", false))
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body("error.message", equalTo("Column z in SELECT * EXCEPT list does not exist"));
        query(Map.of("query", "SELECT t.s.* EXCEPT(c) REPLACE(SUM(t.id) AS z)" + base, "useLegacySql", false))
                .then().statusCode(400)
                .body("error.message", equalTo("Column z in SELECT * REPLACE list does not exist"));
        query(Map.of("query", "SELECT t.s.* EXCEPT(a) REPLACE(SUM(t.id) AS a)" + base, "useLegacySql", false))
                .then().statusCode(400)
                .body("error.message", equalTo("Column a cannot occur in both SELECT * EXCEPT and REPLACE"));
    }

    @Test
    @Order(2)
    void starReplacementsRejectDirectWindowsButPreserveSubqueryWindows() {
        List<String> invalidQueries = List.of(
                "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE(COUNT(*) OVER () AS a)"
                        + " FROM (SELECT STRUCT(1 AS a) s UNION ALL SELECT STRUCT(2 AS a)) t) AS arr",
                "SELECT t.s.* REPLACE(COUNT(*) OVER () AS a) FROM (SELECT STRUCT(1 AS a) s) t",
                "SELECT * REPLACE(COUNT(*) OVER () AS a) FROM (SELECT 1 AS a)",
                "SELECT * EXCEPT(b) REPLACE(COUNT(*) OVER () AS a) FROM (SELECT 1 AS a, 2 AS b)",
                "SELECT ARRAY(SELECT AS STRUCT * REPLACE(COUNT(*) OVER () AS a)"
                        + " FROM (SELECT 1 AS a)) AS arr",
                "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE(ROW_NUMBER() OVER w AS a)"
                        + " FROM (SELECT STRUCT(1 AS a) s) t WINDOW w AS (ORDER BY t.s.a)) AS arr");
        for (String sql : invalidQueries) {
            query(Map.of("query", sql, "useLegacySql", false))
                    .then().statusCode(400)
                    .body("error.errors[0].reason", equalTo("invalidQuery"))
                    .body("error.message", equalTo("Cannot use analytic functions inside SELECT * REPLACE"));
        }
        List<String> validQueries = List.of(
                "SELECT ARRAY(SELECT AS STRUCT t.s.*"
                        + " REPLACE((SELECT COUNT(*) OVER () FROM UNNEST([7, 8]) x LIMIT 1) AS a)"
                        + " FROM (SELECT STRUCT(1 AS a) s UNION ALL SELECT STRUCT(2 AS a)) t) AS arr",
                "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE(t.n AS a)"
                        + " FROM (SELECT s, COUNT(*) OVER () AS n FROM"
                        + " (SELECT STRUCT(1 AS a) s UNION ALL SELECT STRUCT(2 AS a))) t) AS arr");
        for (String sql : validQueries) {
            query(Map.of("query", sql, "useLegacySql", false))
                    .then().statusCode(200)
                    .body("schema.fields[0].fields.name", equalTo(List.of("a")))
                    .body("rows[0].f[0].v.v.size()", equalTo(2))
                    .body("rows[0].f[0].v.v[0].f[0].v", equalTo("2"))
                    .body("rows[0].f[0].v.v[1].f[0].v", equalTo("2"));
        }
    }

    @Test
    @Order(2)
    void structStarReplacementReadsRangeVariablesAndAliaslessSourcesFromTheRow() {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("SELECT t.s.* REPLACE(x AS a) FROM (SELECT STRUCT(1 AS a, 2 AS x) s) t, UNNEST([7]) x",
                List.of("7", "2"));
        expected.put("SELECT t.s.* REPLACE(x AS a) FROM (SELECT STRUCT(1 AS a, 2 AS x) s) t"
                + " CROSS JOIN (SELECT 7 AS x)", List.of("7", "2"));
        expected.put("SELECT t.s.* REPLACE(x AS a) FROM (SELECT STRUCT(1 AS a, 2 AS x) s) t"
                + " CROSS JOIN (SELECT 7 AS x) u", List.of("7", "2"));
        expected.put("SELECT t.s.* REPLACE(o AS a) FROM (SELECT STRUCT(1 AS a, 2 AS o) s) t,"
                + " UNNEST([7]) AS x WITH OFFSET o", List.of("0", "2"));
        expected.put("SELECT t.s.* REPLACE(x.f AS a) FROM (SELECT STRUCT(1 AS a, 2 AS x) s) t,"
                + " UNNEST([STRUCT(7 AS f)]) x", List.of("7", "2"));
        expected.put("SELECT t.id, t.s.* EXCEPT(x) REPLACE(SUM(x) AS a)"
                + " FROM (SELECT 1 AS id, STRUCT(1 AS a, 2 AS x) s) t, UNNEST([7, 8]) x GROUP BY 1",
                List.of("1", "15"));
        expected.put("SELECT t.id, t.s.* EXCEPT(x) REPLACE(SUM(x) AS a)"
                + " FROM (SELECT 1 AS id, STRUCT(1 AS a, 2 AS x) s) t"
                + " CROSS JOIN (SELECT 7 AS x UNION ALL SELECT 8) GROUP BY 1", List.of("1", "15"));
        expected.put("SELECT t.s.* EXCEPT(x), x, COUNT(*) AS n"
                + " FROM (SELECT STRUCT(1 AS a, 2 AS x) s) t, UNNEST([7]) x GROUP BY ALL",
                List.of("1", "7", "1"));
        expected.put("SELECT t.s.* EXCEPT(a), x FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t,"
                + " UNNEST([7]) AS x WITH OFFSET o", List.of("2", "7"));
        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            query(Map.of("query", entry.getKey(), "useLegacySql", false))
                    .then().statusCode(200)
                    .body("rows[0].f.v", equalTo(entry.getValue()));
        }
    }

    @Test
    @Order(2)
    void structStarReplacementSuffixesDuplicateNamesInSelectOrder() {
        Map<String, List<List<String>>> expected = new LinkedHashMap<>();
        expected.put("SELECT 99 AS a, t.s.* REPLACE(10 AS a) FROM (SELECT STRUCT(1 AS a) s) t",
                List.of(List.of("a", "a_1"), List.of("99", "10")));
        expected.put("SELECT t.s.* REPLACE(10 AS a), 99 AS a FROM (SELECT STRUCT(1 AS a) s) t",
                List.of(List.of("a", "a_1"), List.of("10", "99")));
        expected.put("SELECT 99 AS a, 98 AS a_1, t.s.* REPLACE(10 AS a) FROM (SELECT STRUCT(1 AS a) s) t",
                List.of(List.of("a", "a_1", "a_2"), List.of("99", "98", "10")));
        expected.put("SELECT 99 AS a, t.s.* REPLACE(10 AS a), 97 AS a FROM (SELECT STRUCT(1 AS a) s) t",
                List.of(List.of("a", "a_1", "a_2"), List.of("99", "10", "97")));
        expected.put("SELECT 99 AS A, t.s.* REPLACE(10 AS a) FROM (SELECT STRUCT(1 AS a) s) t",
                List.of(List.of("A", "a_1"), List.of("99", "10")));
        expected.put("SELECT 99 AS a, t.s.* EXCEPT(b) REPLACE(10 AS a) FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t",
                List.of(List.of("a", "a_1"), List.of("99", "10")));
        expected.put("SELECT 99 AS a, t.s.* REPLACE(-t.s.a AS a)"
                + " FROM (SELECT STRUCT(1 AS a) s UNION ALL SELECT STRUCT(2 AS a)) t ORDER BY 2 LIMIT 1",
                List.of(List.of("a", "a_1"), List.of("99", "-2")));
        expected.put("WITH c AS (SELECT 99 AS a, t.s.* REPLACE(10 AS a) FROM (SELECT STRUCT(1 AS a) s) t)"
                + " SELECT * FROM c", List.of(List.of("a", "a_1"), List.of("99", "10")));
        expected.put("SELECT 99 AS a, t.s.* REPLACE(-t.s.a AS a) FROM (SELECT STRUCT(1 AS a) s"
                + " UNION ALL SELECT STRUCT(2 AS a)) t ORDER BY 2 DESC LIMIT 1 OFFSET 1",
                List.of(List.of("a", "a_1"), List.of("99", "-2")));
        expected.put("SELECT DISTINCT 99 AS a, t.s.* REPLACE(t.s.a * 0 AS a) FROM (SELECT STRUCT(1 AS a) s"
                + " UNION ALL SELECT STRUCT(2 AS a)) t", List.of(List.of("a", "a_1"), List.of("99", "0")));
        expected.put("SELECT t.id AS a, t.s.* REPLACE(t.id + 1 AS a) FROM (SELECT STRUCT(1 AS a) s, 5 AS id) t"
                + " WHERE t.id > 0", List.of(List.of("a", "a_1"), List.of("5", "6")));
        expected.put("SELECT 99 AS a, t.s.* REPLACE(x AS a) FROM (SELECT STRUCT(1 AS a, 2 AS x) s) t, UNNEST([7]) x",
                List.of(List.of("a", "a_1", "x"), List.of("99", "7", "2")));
        for (Map.Entry<String, List<List<String>>> entry : expected.entrySet()) {
            query(Map.of("query", entry.getKey(), "useLegacySql", false))
                    .then().statusCode(200)
                    .body("schema.fields.name", equalTo(entry.getValue().get(0)))
                    .body("rows[0].f.v", equalTo(entry.getValue().get(1)));
        }
        query(Map.of("query", "SELECT 99 AS a, t.s.* REPLACE(-t.s.a AS a)"
                + " FROM (SELECT STRUCT(1 AS a) s UNION ALL SELECT STRUCT(2 AS a)) t ORDER BY t.s.a DESC",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "a_1")))
                .body("rows.f.v", equalTo(List.of(List.of("99", "-2"), List.of("99", "-1"))));
    }

    @Test
    @Order(2)
    void structStarReplacementPreservesAnOuterColumnShadowedByAField() {
        query(Map.of("query", """
                SELECT ARRAY(
                  SELECT AS STRUCT t.s.* REPLACE(x + 1 AS a)
                  FROM (SELECT STRUCT(1 AS a, 2 AS x) s) t
                ) AS arr FROM (SELECT 5 AS x) outer_q
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("6", "2")));
        query(Map.of("query", """
                SELECT ARRAY(
                  SELECT AS STRUCT t.s.* REPLACE(x + 1 AS a)
                  FROM (SELECT STRUCT(1 AS a, 2 AS x) s) t
                ) AS arr FROM (SELECT 5 AS x UNION ALL SELECT 8) outer_q ORDER BY x
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("6", "2")))
                .body("rows[1].f[0].v.v[0].f.v", equalTo(List.of("9", "2")));
    }

    @Test
    @Order(2)
    void structStarReplacementPreservesColumnsNamedLikeDateParts() {
        query(Map.of("query", """
                SELECT ARRAY(
                  SELECT AS STRUCT t.s.* REPLACE(day + 1 AS a)
                  FROM (SELECT STRUCT(1 AS a, 2 AS day) s, 5 AS day) t
                ) AS arr
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("6", "2")));
        query(Map.of("query", """
                SELECT ARRAY(
                  SELECT AS STRUCT t.s.* REPLACE(
                    DATE_ADD(t.s.d, INTERVAL amount DAY) AS d)
                  FROM (SELECT STRUCT(DATE '2024-01-01' AS d) s, 2 AS amount, 5 AS day) t
                ) AS arr
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("2024-01-03")));
        query(Map.of("query", """
                SELECT ARRAY(
                  SELECT AS STRUCT t.s.* REPLACE(SAFE.PARSE_DATE('%Y-%m-%d', '2024-01-02') AS d)
                  FROM (SELECT STRUCT(DATE '2024-01-01' AS d) s) t
                ) AS arr
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("2024-01-02")));
    }

    @Test
    @Order(2)
    void structStarReplacementPreservesNestedCorrelatedNames() {
        query(Map.of("query", """
                SELECT ARRAY(
                  SELECT AS STRUCT t.s.* REPLACE((SELECT x + 1) AS a)
                  FROM (SELECT STRUCT(1 AS a, 2 AS x) s, 5 AS x) t
                ) AS arr
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("6", "2")));
        query(Map.of("query", """
                SELECT ARRAY(
                  SELECT AS STRUCT t.s.* REPLACE((SELECT MAX(x) FROM (SELECT 9 AS x) u WHERE x > 0) AS a)
                  FROM (SELECT STRUCT(1 AS a, 2 AS x) s, 5 AS x) t
                ) AS arr
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("9", "2")));
        query(Map.of("query", """
                SELECT ARRAY(
                  SELECT AS STRUCT t.s.* REPLACE((
                    SELECT x FROM (SELECT 0 AS y) u
                    UNION ALL SELECT x FROM (SELECT 9 AS x) v ORDER BY x DESC LIMIT 1
                  ) AS a)
                  FROM (SELECT STRUCT(1 AS a, 2 AS x) s, 5 AS x) t
                ) AS arr
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("9", "2")));
    }

    @Test
    @Order(2)
    void structStarReplacementPreservesBareStructPaths() {
        query(Map.of("query", """
                SELECT ARRAY(
                  SELECT AS STRUCT t.s.* REPLACE(other.b AS a)
                  FROM (SELECT STRUCT(1 AS a, STRUCT(2 AS b) AS other) s, STRUCT(5 AS b) other) t
                ) AS arr
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f[0].v", equalTo("5"))
                .body("rows[0].f[0].v.v[0].f[1].v.f[0].v", equalTo("2"));
    }

    @Test
    @Order(2)
    void structStarGeneratedNamesDoNotHideStructFields() {
        query(Map.of("query", """
                SELECT t.s.* REPLACE(t.id AS a)
                FROM (SELECT STRUCT(1 AS a, 7 AS _floci_source_0) s, 9 AS id) t
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "_floci_source_0")))
                .body("rows[0].f.v", equalTo(List.of("9", "7")));
        query(Map.of("query", """
                SELECT ARRAY(
                  SELECT AS STRUCT t.s.* REPLACE(t.id AS a)
                  FROM (SELECT STRUCT(1 AS a, 7 AS _floci_replace_0, 8 AS _floci_operand) s, 9 AS id) t
                ) AS arr
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("a", "_floci_replace_0", "_floci_operand")))
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("9", "7", "8")));
    }

    @Test
    @Order(2)
    void structStarGeneratedNamesRespectStoredStructSchemas() {
        String tablePath = BASE + "/datasets/shop/tables/star_aliases";
        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "star_aliases"}, "schema": {"fields": [
                  {"name": "s", "type": "RECORD", "fields": [
                    {"name": "a", "type": "INT64"},
                    {"name": "_floci_source_0", "type": "INT64"},
                    {"name": "_floci_projection_0", "type": "INT64"}
                  ]},
                  {"name": "id", "type": "INT64"}
                ]}}
                """).when().post(BASE + "/datasets/shop/tables").then().statusCode(200);
        try {
            given().contentType("application/json").body("""
                    {"rows": [{"json": {"s": {
                      "a": 1, "_floci_source_0": 7, "_floci_projection_0": 8
                    }, "id": 9}}]}
                    """).when().post(tablePath + "/insertAll").then().statusCode(200)
                    .body("insertErrors", nullValue());
            query(Map.of("query", "SELECT t.s.* REPLACE(t.id AS a) FROM shop.star_aliases t",
                            "useLegacySql", false))
                    .then().statusCode(200)
                    .body("schema.fields.name", equalTo(List.of("a", "_floci_source_0", "_floci_projection_0")))
                    .body("rows[0].f.v", equalTo(List.of("9", "7", "8")));
            query(Map.of("query", "SELECT t.s.* REPLACE(t.id AS a), COUNT(*) AS n"
                            + " FROM shop.star_aliases t GROUP BY ALL", "useLegacySql", false))
                    .then().statusCode(200)
                    .body("schema.fields.name", equalTo(List.of("a", "_floci_source_0", "_floci_projection_0", "n")))
                    .body("rows[0].f.v", equalTo(List.of("9", "7", "8", "1")));
            query(Map.of("query", "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS a"
                            + " FROM shop.star_aliases t GROUP BY ALL", "useLegacySql", false))
                    .then().statusCode(200)
                    .body("schema.fields.name", equalTo(List.of("_floci_source_0", "_floci_projection_0", "a")))
                    .body("rows[0].f.v", equalTo(List.of("7", "8", "1")));
        } finally {
            given().when().delete(tablePath).then().statusCode(204);
        }
    }

    @Test
    @Order(2)
    void structStarOrderingCanReadAnUnselectedBareSourceColumn() {
        query(Map.of("query", """
                SELECT t.s.* REPLACE(-t.s.a AS a)
                FROM (SELECT STRUCT(1 AS a) s, 9 AS id UNION ALL SELECT STRUCT(2 AS a), 1) t
                ORDER BY id LIMIT 1
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f.v", equalTo(List.of("-2")));
    }

    @Test
    @Order(2)
    void castingFloatingPointZerosToStringOmitsTheSignAndDecimalPoint() {
        query(Map.of("query", "SELECT CAST(value AS STRING) AS text_value FROM UNNEST(["
                        + "CAST(0 AS FLOAT64), CAST(-0.0 AS FLOAT64), CAST(0.5 AS FLOAT64)]) AS value",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("0"), List.of("0"), List.of("0.5"))));
        query(Map.of("query", "SELECT CAST('0.0' AS STRING) AS text_value", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("0.0"));
        query(Map.of("query", "SELECT CAST(0.0 AS STRING) AS pos, CAST(-0.0 AS STRING) AS neg, "
                        + "CAST(0.5 AS STRING) AS half", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("0", "0", "0.5"))));
        query(Map.of("query", "SELECT CAST(NUMERIC '0' AS STRING) AS zero, "
                        + "CAST(NUMERIC '12.30' AS STRING) AS scaled, CAST(NUMERIC '100' AS STRING) AS whole, "
                        + "CAST(NUMERIC '-0.5' AS STRING) AS negative, "
                        + "CAST(NUMERIC '0.000000001' AS STRING) AS small", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("0", "12.3", "100", "-0.5", "0.000000001"))));
        query(Map.of("query", "SELECT CAST(COUNT(*) AS STRING) AS c FROM shop.users", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("3"));
        query(Map.of("query", "SELECT CAST(SUM(total) OVER (ORDER BY user_id, total) AS STRING) AS c "
                        + "FROM shop.orders ORDER BY user_id, total", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("4.25"), List.of("14.75"), List.of("15.75"))));
        query(Map.of("query", "SELECT COUNT(*) AS c FROM shop.users HAVING CAST(COUNT(*) AS STRING) = '3'",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("3"));
        query(Map.of("query", "SELECT COUNT(DISTINCT CAST(RAND() AS STRING)) = COUNT(*) AS per_row "
                        + "FROM shop.users", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("true"));
        query(Map.of("query", "SELECT CAST(id AS STRING) AS s, COUNT(*) AS c FROM shop.users "
                        + "GROUP BY CAST(id AS STRING) ORDER BY s", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("1", "1"), List.of("2", "1"), List.of("3", "1"))));
        query(Map.of("query", "SELECT CAST(total AS STRING) AS s, COUNT(*) AS c FROM shop.orders "
                        + "GROUP BY CAST(total AS STRING) ORDER BY s", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("1", "1"), List.of("10.5", "1"),
                        List.of("4.25", "1"))));
    }

    @Test
    @Order(2)
    void proceduralVariablesAndLoopsExecuteOnDuck() {
        query("""
                {"query": "DECLARE target_id INT64 DEFAULT 0; WHILE target_id < 2 DO SET target_id = target_id + 1; END WHILE; SELECT name FROM shop.users WHERE id = target_id", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("jobComplete", equalTo(true))
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("bo"));
    }

    @Test
    @Order(2)
    void dateTimestampAndArrayVariablesKeepTheirTypes() {
        query(Map.of("query", """
                BEGIN
                  DECLARE run_date DATE DEFAULT DATE '2025-01-01';
                  DECLARE run_at TIMESTAMP DEFAULT TIMESTAMP '2025-01-01 12:00:00+00';
                  DECLARE run_dates ARRAY<DATE> DEFAULT [DATE '2025-01-01'];
                  SET run_date = DATE '2025-01-02';
                  SET run_at = TIMESTAMP '2025-01-02 12:00:00+00';
                  SET run_dates = [DATE '2025-01-02', DATE '2025-01-03'];
                  SELECT run_date, run_at, run_dates;
                END;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("DATE", "TIMESTAMP", "DATE")))
                .body("schema.fields[2].mode", equalTo("REPEATED"))
                .body("rows[0].f[0].v", equalTo("2025-01-02"))
                .body("rows[0].f[1].v", notNullValue())
                .body("rows[0].f[2].v.v", equalTo(List.of("2025-01-02", "2025-01-03")));
    }

    @Test
    @Order(2)
    void declaredStructsArraysOfStructsAndAdditionalTypesRunOnDuck() {
        query(Map.of("query", """
                BEGIN
                  DECLARE item STRUCT<id INT64, label STRING>
                    DEFAULT STRUCT(1 AS id, 'first' AS label);
                  DECLARE items ARRAY<STRUCT<id INT64, label STRING>>
                    DEFAULT [STRUCT(1 AS id, 'first' AS label)];
                  DECLARE payload BYTES DEFAULT b'first';
                  DECLARE date_time DATETIME DEFAULT DATETIME '2025-02-03 12:00:00';
                  DECLARE time_value TIME DEFAULT TIME '12:00:00';
                  DECLARE document JSON DEFAULT JSON '{"value":"first"}';
                  SET item = STRUCT(2 AS id, 'second' AS label);
                  SET items = [STRUCT(2 AS id, 'second' AS label), STRUCT(3 AS id, 'third' AS label)];
                  SET payload = b'second';
                  SET date_time = DATETIME '2025-02-03 14:00:00';
                  SET time_value = TIME '14:00:00';
                  SET document = JSON '{"value":"second"}';
                  SELECT item.id, item.label, items, payload, date_time, time_value, document;
                END;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of(
                        "INTEGER", "STRING", "RECORD", "BYTES", "DATETIME", "TIME", "JSON")))
                .body("schema.fields[2].mode", equalTo("REPEATED"))
                .body("rows[0].f[0].v", equalTo("2"))
                .body("rows[0].f[1].v", equalTo("second"))
                .body("rows[0].f[2].v.v.f.v", equalTo(List.of(List.of("2", "second"), List.of("3", "third"))))
                .body("rows[0].f[3].v", equalTo("c2Vjb25k"))
                .body("rows[0].f[4].v", equalTo("2025-02-03T14:00:00"))
                .body("rows[0].f[5].v", equalTo("14:00:00"))
                .body("rows[0].f[6].v", notNullValue());
    }

    @Test
    @Order(2)
    void typedArrayLiteralsKeepTheirElementTypeOnDuck() {
        // Live BigQuery: `ARRAY<FLOAT64>[]` and `ARRAY<FLOAT64>[1]` both return ARRAY<FLOAT64> when the
        // declaration has no type of its own, the empty one included.
        query(Map.of("query", """
                DECLARE numbers DEFAULT ARRAY<FLOAT64>[1];
                DECLARE empty DEFAULT ARRAY<FLOAT64>[];
                SELECT numbers, empty, ARRAY_LENGTH(empty) AS empty_length;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("FLOAT", "FLOAT", "INTEGER")))
                .body("schema.fields[0].mode", equalTo("REPEATED"))
                .body("schema.fields[1].mode", equalTo("REPEATED"))
                .body("rows[0].f[0].v.v", equalTo(List.of("1.0")))
                .body("rows[0].f[1].v.v", equalTo(List.of()))
                .body("rows[0].f[2].v", equalTo("0"));
    }

    @Test
    @Order(2)
    void copiedVariablesKeepTheirDeclaredTypeOnDuck() {
        // Live BigQuery: an empty ARRAY<FLOAT64> and a NULL STRING keep their declared type when
        // another variable copies them.
        query(Map.of("query", """
                DECLARE empty DEFAULT ARRAY<FLOAT64>[];
                DECLARE copy DEFAULT empty;
                DECLARE a STRING;
                DECLARE b DEFAULT a;
                SELECT copy, b, ARRAY_LENGTH(copy) AS copy_length;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("FLOAT", "STRING", "INTEGER")))
                .body("schema.fields[0].mode", equalTo("REPEATED"))
                .body("rows[0].f[0].v.v", equalTo(List.of()))
                .body("rows[0].f[1].v", nullValue())
                .body("rows[0].f[2].v", equalTo("0"));
    }

    @Test
    @Order(2)
    void structValuesAreAssignedByFieldPositionOnDuck() {
        // Live BigQuery assigns a STRUCT by position: the default and the SET both keep source order,
        // so the differently named `STRUCT(1 AS b, 2 AS a)` still reaches a=1, b=2.
        query(Map.of("query", """
                DECLARE by_default STRUCT<a INT64, b INT64> DEFAULT STRUCT(1 AS b, 2 AS a);
                DECLARE by_set STRUCT<a INT64, b INT64>;
                SET by_set = STRUCT(2 AS b, 1 AS a);
                SELECT by_default.a AS default_a, by_default.b AS default_b,
                       by_set.a AS set_a, by_set.b AS set_b;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("default_a", "default_b", "set_a", "set_b")))
                .body("rows[0].f[0].v", equalTo("1"))
                .body("rows[0].f[1].v", equalTo("2"))
                .body("rows[0].f[2].v", equalTo("2"))
                .body("rows[0].f[3].v", equalTo("1"));
    }

    @Test
    @Order(2)
    void temporaryTableColumnsWinOverSameNamedVariables() {
        // Live BigQuery returns 1: a temporary table's column shadows the script variable.
        query(Map.of("query", "DECLARE id INT64 DEFAULT 99; CREATE TEMP TABLE t AS SELECT 1 AS id; "
                + "SELECT id FROM t;", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("id"))
                .body("rows[0].f[0].v", equalTo("1"));
        // Live BigQuery returns 99: an alias does not hide the variable it names.
        query(Map.of("query", "DECLARE id INT64 DEFAULT 99; SELECT id AS id", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("id"))
                .body("rows[0].f[0].v", equalTo("99"));
    }

    @Test
    @Order(2)
    void scriptVariablesLeaveDeclaredTemporaryColumnsAlone() {
        // The declared schema and the INSERT column list name columns; the value a variable supplies
        // in the same statement still substitutes, as in BigQuery.
        query(Map.of("query", """
                DECLARE id INT64 DEFAULT 99;
                CREATE TEMP TABLE declared (id INT64);
                INSERT INTO declared (id) VALUES (5);
                SELECT id FROM declared;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("5"));
        query(Map.of("query", """
                DECLARE id INT64 DEFAULT 99;
                CREATE TEMP TABLE declared (id INT64);
                INSERT INTO declared (id) VALUES (id);
                SELECT id FROM declared;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("99"));
        // An UPDATE reads the target's columns, so the variable leaves them alone.
        query(Map.of("query", """
                DECLARE id INT64 DEFAULT 99;
                CREATE TEMP TABLE updated AS SELECT 1 AS id;
                UPDATE updated SET id = id + 1 WHERE id = 1;
                SELECT id FROM updated;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));
    }

    @Test
    @Order(2)
    void forLoopRowsKeepTheTypesOfTheirQuery() {
        // Live BigQuery reports DATE here and returns 2025-01-01.
        query(Map.of("query", """
                FOR r IN (SELECT DATE '2025-01-01' AS d) DO
                  SELECT r.d AS d;
                END FOR;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("DATE"))
                .body("rows[0].f[0].v", equalTo("2025-01-01"));
        // A TIMESTAMP field keeps its type too, in the select list and as a parameter.
        query(Map.of("query", """
                FOR r IN (SELECT TIMESTAMP '2025-01-01 00:00:00+00' AS t, DATE '2025-01-02' AS d) DO
                  SELECT r.t AS t, r.d AS d;
                END FOR;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("TIMESTAMP", "DATE")))
                .body("rows[0].f[0].v", equalTo("1735689600"))
                .body("rows[0].f[1].v", equalTo("2025-01-02"));
        // The row also binds as a struct parameter, keeping each field's type.
        query(Map.of("query", """
                FOR r IN (SELECT DATE '2025-01-01' AS d) DO
                  EXECUTE IMMEDIATE "SELECT @row.d AS d" USING r AS row;
                END FOR;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("DATE"))
                .body("rows[0].f[0].v", equalTo("2025-01-01"));
        query(Map.of("query", """
                FOR r IN (SELECT TIMESTAMP '2025-01-01 00:00:00+00' AS t) DO
                  EXECUTE IMMEDIATE "SELECT @row.t AS t" USING r AS row;
                END FOR;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("TIMESTAMP"))
                .body("rows[0].f[0].v", equalTo("1735689600"));
    }

    @Test
    @Order(2)
    void createdTemporaryTablesRejectNullsInRequiredFields() {
        // Live BigQuery: "Required field item.id cannot be null; error in writing field item".
        query(Map.of("query", """
                CREATE TEMP TABLE required_fields (item STRUCT<id INT64 NOT NULL>)
                  AS SELECT STRUCT(NULL AS id) AS item;
                SELECT * FROM required_fields;
                """, "useLegacySql", false))
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body("error.message", containsString("required field item.id"));
    }

    @Test
    @Order(2)
    void scriptParametersBindByNameWhateverTheParameterMode() {
        // BigQuery infers NAMED from the names, so a script without a parameterMode binds them.
        query(Map.of("query", "SELECT @value AS a; SELECT @value AS b", "useLegacySql", false,
                "queryParameters", List.of(Map.of("name", "value", "parameterType", Map.of("type", "INT64"),
                        "parameterValue", Map.of("value", "7")))))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("b"))
                .body("rows[0].f[0].v", equalTo("7"));
    }

    @Test
    @Order(2)
    void scriptsRejectPositionalParametersEvenWithoutAParameterMode() {
        // BigQuery binds `?` across the statements of a script and returns 2 here; the emulator
        // rejects positional parameters in scripts because each statement would rebind the first one.
        Map<String, Object> first = Map.of("parameterType", Map.of("type", "INT64"),
                "parameterValue", Map.of("value", "1"));
        Map<String, Object> second = Map.of("parameterType", Map.of("type", "INT64"),
                "parameterValue", Map.of("value", "2"));
        query(Map.of("query", "SELECT ? AS a; SELECT ? AS b", "useLegacySql", false,
                "queryParameters", List.of(first, second)))
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body("error.message", containsString(
                        "Positional query parameters are not supported in multi-statement scripts"));
    }

    @Test
    @Order(2)
    void scriptLoopsKeepTheLastQueryResult() {
        // Live BigQuery returns 42: a loop's result is the last statement that produced one, so the
        // terminating condition, which runs after the body, does not erase it.
        query(Map.of("query", """
                DECLARE n INT64 DEFAULT 0;
                WHILE (n < 1) DO
                  SET n = n + 1;
                  SELECT 42 AS answer;
                END WHILE;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("answer"))
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("42"));
        // Live BigQuery returns 2: each iteration replaces the previous result.
        query(Map.of("query", """
                DECLARE n INT64 DEFAULT 0;
                WHILE (n < 2) DO
                  SET n = n + 1;
                  SELECT n AS v;
                END WHILE;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("2"));
        // Live BigQuery returns 42: REPEAT tests its condition after the body, like WHILE.
        query(Map.of("query", """
                DECLARE n INT64 DEFAULT 0;
                REPEAT
                  SET n = n + 1;
                  SELECT 42 AS answer;
                UNTIL n >= 1 END REPEAT;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("42"));
        // Live BigQuery returns 9: a loop that produces no result leaves the earlier one in place.
        query(Map.of("query", """
                DECLARE n INT64 DEFAULT 0;
                SELECT 9 AS a;
                WHILE n < 1 DO SET n = n + 1; END WHILE;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("9"));
    }

    @Test
    @Order(2)
    void scriptArithmeticOverflowIsAnInvalidQuery() {
        // Live BigQuery: "Integer Overflow at [2:9]", an invalid query rather than a server error.
        query(Map.of("query", """
                DECLARE x INT64 DEFAULT 9223372036854775807;
                SET x = x + 1;
                SELECT x;
                """, "useLegacySql", false))
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body("error.message", containsString("Integer Overflow"));
        // jobs.insert reports the same failure in the job status, not as a server error.
        given().contentType("application/json").body("""
                {"configuration": {"query": {"query":
                  "DECLARE x INT64 DEFAULT 9223372036854775807;\\nSET x = x + 1;\\nSELECT x;",
                  "useLegacySql": false}}}
                """).when().post(BASE + "/jobs")
                .then().statusCode(200)
                .body("status.state", equalTo("DONE"))
                .body("status.errorResult.reason", equalTo("invalidQuery"))
                .body("status.errorResult.message", containsString("Integer Overflow"));
    }

    @Test
    @Order(2)
    void simpleCaseComparesTheWholeWhenOperand() {
        // Live BigQuery returns 'right': a simple CASE compares FALSE = (FALSE OR TRUE), so no WHEN matches.
        query(Map.of("query", """
                CASE FALSE
                  WHEN FALSE OR TRUE THEN SELECT 'wrong' AS result;
                  ELSE SELECT 'right' AS result;
                END CASE;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("result"))
                .body("rows[0].f[0].v", equalTo("right"));
    }

    @Test
    @Order(2)
    void executeImmediateUsingTakesAComparisonValueWithAnAlias() {
        // Live BigQuery returns true: `1 < 2` is the USING value and `AS flag` names it.
        query(Map.of("query", """
                DECLARE flag BOOL DEFAULT FALSE;
                EXECUTE IMMEDIATE 'SELECT @flag AS flag' USING 1 < 2 AS flag;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("true"));
    }

    @Test
    @Order(2)
    void singleProceduralStatementsRunAsScripts() {
        // Live BigQuery accepts a request made only of declarations and returns no rows.
        query(Map.of("query", "DECLARE x INT64;", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", anyOf(nullValue(), hasSize(0)));
        query(Map.of("query", "DECLARE x INT64 DEFAULT 5;", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", anyOf(nullValue(), hasSize(0)));
        // Live BigQuery returns 1: control flow is a script even when it is the whole request.
        query(Map.of("query", "IF TRUE THEN SELECT 1 AS a; END IF;", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("a"))
                .body("rows[0].f[0].v", equalTo("1"));
        // Live BigQuery rejects an assignment to an undeclared variable.
        query(Map.of("query", "SET x = 1;", "useLegacySql", false))
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body("error.message", containsString("has not been declared"));
    }

    @Test
    @Order(2)
    void aSelectedVariableKeepsItsNameAsTheColumnName() {
        // Live BigQuery names each column after the variable, so a client reads row.output_value.
        query(Map.of("query", """
                DECLARE input_value INT64 DEFAULT 7;
                DECLARE output_value INT64;
                EXECUTE IMMEDIATE "SELECT @value * 2 AS result" INTO output_value USING input_value AS value;
                SELECT output_value;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("output_value"))
                .body("rows[0].f[0].v", equalTo("14"));
        query(Map.of("query", """
                DECLARE x INT64 DEFAULT 1;
                BEGIN SET x = 2; END;
                SELECT x;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("x"))
                .body("rows[0].f[0].v", equalTo("2"));
        query(Map.of("query", """
                DECLARE total INT64 DEFAULT 0;
                FOR r IN (SELECT 1 AS n UNION ALL SELECT 2) DO
                  SET total = total + r.n;
                END FOR;
                SELECT total;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("total"))
                .body("rows[0].f[0].v", equalTo("3"));
        // A tuple assignment names both of its columns.
        query(Map.of("query", """
                DECLARE a INT64;
                DECLARE b INT64;
                SET (a, b) = (1, 2);
                SELECT a, b;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b")))
                .body("rows[0].f[0].v", equalTo("1"))
                .body("rows[0].f[1].v", equalTo("2"));
        // A path over a struct variable is named after its last field.
        query(Map.of("query", """
                DECLARE item STRUCT<id INT64> DEFAULT STRUCT(1 AS id);
                SELECT item.id;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("id"))
                .body("rows[0].f[0].v", equalTo("1"));
    }

    @Test
    @Order(2)
    void scriptNumericVariablesKeepTheirDeclaredType() {
        // A FLOAT64 literal stays floating point, so the sum is 0.30000000000000004 and not NUMERIC 0.3.
        query(Map.of("query", "DECLARE f FLOAT64 DEFAULT 0.1; SELECT f + 0.2 AS v", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("FLOAT"))
                .body("rows[0].f[0].v", equalTo("0.30000000000000004"));
        query(Map.of("query", "DECLARE f FLOAT64 DEFAULT 0.1; SELECT f", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("FLOAT"))
                .body("rows[0].f[0].v", equalTo("0.1"));
        query(Map.of("query", "DECLARE n NUMERIC DEFAULT 1.5; SELECT n * 2 AS v", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("NUMERIC"))
                .body("rows[0].f[0].v", equalTo("3"));
        query(Map.of("query", "DECLARE n NUMERIC DEFAULT 1.5; SELECT n", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("NUMERIC"))
                .body("rows[0].f[0].v", equalTo("1.5"));
        query(Map.of("query", "DECLARE n NUMERIC DEFAULT 1.5; SET n = n * 2; SELECT n", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("NUMERIC"))
                .body("rows[0].f[0].v", equalTo("3"));
        // An INT64 operand above 2^53 keeps every digit, as BigQuery's NUMERIC does.
        query(Map.of("query", """
                DECLARE n NUMERIC DEFAULT 0;
                SET n = n + 9007199254740993;
                SELECT n;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("9007199254740993"));
        query(Map.of("query", """
                DECLARE n NUMERIC DEFAULT 9007199254740993;
                IF n = 9007199254740993 THEN SELECT 'equal' AS r; ELSE SELECT 'differs' AS r; END IF;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("equal"));
    }

    @Test
    @Order(2)
    void aScriptVariablesColumnsStayInTheQueryThatReadsThem() {
        // Each branch of a set operation, and each sibling subquery, is a query of its own: the columns one
        // reads leave the names another writes to the variable. Every value below is live BigQuery's.
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                SELECT COUNT(*) FROM t UNION ALL SELECT v;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(2))
                .body("rows[0].f[0].v", equalTo("1"))
                .body("rows[1].f[0].v", equalTo("1"));
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                SELECT (SELECT MAX(v) FROM t) AS a, (SELECT v) AS b;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("4"))
                .body("rows[0].f[1].v", equalTo("1"));
        // A CTE or a derived table a branch reads exposes its columns there, not in the branch beside it.
        query(Map.of("query",
                "DECLARE v INT64 DEFAULT 1; WITH t AS (SELECT 4 AS v) SELECT v FROM t UNION ALL SELECT v",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(2))
                .body("rows[0].f[0].v", equalTo("4"))
                .body("rows[1].f[0].v", equalTo("1"));
        query(Map.of("query",
                "DECLARE v INT64 DEFAULT 1; SELECT v FROM (SELECT 4 AS v) UNION ALL SELECT v",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(2))
                .body("rows[0].f[0].v", equalTo("4"))
                .body("rows[1].f[0].v", equalTo("1"));
        query(Map.of("query", "DECLARE v INT64 DEFAULT 1; SELECT 4 AS v UNION ALL SELECT v",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(2))
                .body("rows[0].f[0].v", equalTo("4"))
                .body("rows[1].f[0].v", equalTo("1"));
        // A WITH clause declares its CTE for every branch of the set operation, so a later branch that reads
        // it resolves the column too, while a branch that does not still binds the variable: 0 and 4, then
        // 0, 4 and 1.
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                WITH t AS (SELECT 4 AS v) SELECT 0 UNION ALL SELECT v FROM t;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(2))
                .body("rows[0].f[0].v", equalTo("0"))
                .body("rows[1].f[0].v", equalTo("4"));
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                WITH t AS (SELECT 4 AS v) SELECT 0 UNION ALL SELECT v FROM t UNION ALL SELECT v;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(3))
                .body("rows[0].f[0].v", equalTo("0"))
                .body("rows[1].f[0].v", equalTo("4"))
                .body("rows[2].f[0].v", equalTo("1"));
        // A nested WITH of the same name shadows the CTE of the query around it, so the column it exposes is
        // the one that answers, and the variable where it exposes none: 0 and 1, then 0 and 2.
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                WITH t AS (SELECT 4 AS v)
                SELECT 0 UNION ALL
                SELECT (WITH t AS (SELECT 2 AS w) SELECT v FROM t);
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(2))
                .body("rows[0].f[0].v", equalTo("0"))
                .body("rows[1].f[0].v", equalTo("1"));
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                WITH t AS (SELECT 4 AS v)
                SELECT 0 UNION ALL
                SELECT (WITH t AS (SELECT 2 AS v) SELECT v FROM t);
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(2))
                .body("rows[0].f[0].v", equalTo("0"))
                .body("rows[1].f[0].v", equalTo("2"));
        // A nested WITH of another name leaves the CTE around it in scope: 0 and 4.
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                WITH t AS (SELECT 4 AS v)
                SELECT 0 UNION ALL
                SELECT (WITH u AS (SELECT 9 AS w) SELECT v FROM t);
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(2))
                .body("rows[0].f[0].v", equalTo("0"))
                .body("rows[1].f[0].v", equalTo("4"));
        // A branch still reads the columns the query around the set operation reads: 4 + 2 = 6.
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                SELECT (SELECT SUM(x) FROM (SELECT (SELECT v) AS x UNION ALL SELECT 2)) AS s FROM t;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("6"));
        // The select-star EXCEPT stays inside its query, so the table's columns still answer: 4, and
        // EXCEPT DISTINCT really is a set operation: one row, 4.
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v, 2 AS w;
                SELECT * EXCEPT(w) FROM t WHERE v > 0;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("4"));
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                SELECT v FROM t EXCEPT DISTINCT SELECT 9;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("4"));
    }

    @Test
    @Order(2)
    void aScriptVariablesArithmeticIsTypedByItsOperands() {
        // The operands, not the declared type of the target, type the arithmetic: BigQuery subtracts these
        // two INT64 literals exactly and then coerces the 1 to FLOAT64, and multiplies 3 by 4 in INT64.
        query(Map.of("query", """
                DECLARE f FLOAT64;
                SET f = 9007199254740993 - 9007199254740992;
                SELECT f;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("FLOAT"))
                .body("rows[0].f[0].v", equalTo("1.0"));
        query(Map.of("query", """
                DECLARE f FLOAT64;
                SET f = 3 * 4;
                SELECT f;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("FLOAT"))
                .body("rows[0].f[0].v", equalTo("12.0"));
        // A FLOAT64 target does not widen the operands, so an INT64 overflow still fails.
        query(Map.of("query", """
                DECLARE f FLOAT64;
                SET f = 9223372036854775807 + 1;
                SELECT f;
                """, "useLegacySql", false))
                .then().statusCode(400)
                .body("error.message", containsString("Integer Overflow"));
    }

    @Test
    @Order(2)
    void scriptVariablesLeaveTheStatementsOwnNamesAlone() {
        // Live BigQuery resolves the derived table's column first: 2, not the variable.
        query(Map.of("query", "DECLARE x INT64 DEFAULT 1; SELECT x FROM (SELECT 2 AS x)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("x"))
                .body("rows[0].f[0].v", equalTo("2"));
        // An UNNEST alias is a column scope too: 1.
        query(Map.of("query", "DECLARE id INT64 DEFAULT 7; SELECT id AS v FROM UNNEST([1]) AS id",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("v"))
                .body("rows[0].f[0].v", equalTo("1"));
        // A CTE name names a table, not a variable: 5, as in BigQuery.
        query(Map.of("query", "DECLARE x INT64 DEFAULT 1; WITH x AS (SELECT 5 AS v) SELECT v FROM x",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("v"))
                .body("rows[0].f[0].v", equalTo("5"));
        // A nested query's alias hides the variable inside that query only: 7 and 1.
        query(Map.of("query", """
                DECLARE x INT64 DEFAULT 7;
                SELECT x AS v, (SELECT x FROM UNNEST([1]) AS x) AS nested;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("7"))
                .body("rows[0].f[1].v", equalTo("1"));
        // A subquery that aliases the name still names the variable inside it: 7 and 7.
        query(Map.of("query", """
                DECLARE x INT64 DEFAULT 7;
                SELECT x AS v, (SELECT x AS x FROM UNNEST([1]) AS u) AS nested;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("7"))
                .body("rows[0].f[1].v", equalTo("7"));
        // An alias stays visible inside a parenthesised or function-call condition: 2.
        query(Map.of("query", """
                DECLARE total INT64 DEFAULT 0;
                SELECT COUNT(*) AS total FROM UNNEST([1,2]) AS n HAVING (total > 1);
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("total"))
                .body("rows[0].f[0].v", equalTo("2"));
        query(Map.of("query", """
                DECLARE total INT64 DEFAULT 0;
                SELECT COUNT(*) AS total FROM UNNEST([1,2]) AS n HAVING COALESCE(total, 0) > 1;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));
        // A CTE reference inside a nested query still names the table.
        query(Map.of("query", """
                DECLARE x INT64 DEFAULT 7;
                WITH x AS (SELECT 5 AS v) SELECT v FROM (SELECT v FROM x) AS nested;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("v"))
                .body("rows[0].f[0].v", equalTo("5"));
        // A variable that is a function argument or a grouped expression is not a whole select item,
        // so only the item that is one is named after the variable: live BigQuery answers f0_ and x.
        query(Map.of("query", "DECLARE x INT64 DEFAULT 7; SELECT COALESCE(NULL, x), x", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("f0_"))
                .body("rows[0].f[0].v", equalTo("7"))
                .body("schema.fields[1].name", equalTo("x"))
                .body("rows[0].f[1].v", equalTo("7"));
        query(Map.of("query", "DECLARE x INT64 DEFAULT 7; SELECT IF(x > 1, x, 0)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("f0_"))
                .body("rows[0].f[0].v", equalTo("7"));
        query(Map.of("query", "DECLARE x INT64 DEFAULT 7; SELECT (x) + 1", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("f0_"))
                .body("rows[0].f[0].v", equalTo("8"));
        // A struct field named like the variable is no select-list alias either: live BigQuery
        // resolves the variable, so the condition holds and the count is 2.
        query(Map.of("query", """
                DECLARE total INT64 DEFAULT 5;
                SELECT STRUCT(COUNT(*) AS total) AS s FROM UNNEST([1,2]) AS u HAVING total > 1;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("s"))
                .body("rows[0].f[0].v.f[0].v", equalTo("2"));
    }

    @Test
    @Order(2)
    void scriptVariablesLeaveTheColumnsOfASubqueryAndACTEAlone() {
        // A table column wins inside a subquery too, whether the procedure evaluates the expression on
        // its own or as a condition. Every value below is live BigQuery's.
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                SET v = (SELECT v FROM t);
                SELECT v;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("v"))
                .body("rows[0].f[0].v", equalTo("4"));
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                IF EXISTS(SELECT 1 FROM t WHERE v = 4) THEN SELECT 'col' AS r; ELSE SELECT 'var' AS r; END IF;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("col"));
        // The columns a CTE's select list exposes win over a variable, as a derived table's do: 4, a
        // row, 2 then 4, and one row.
        query(Map.of("query", "DECLARE v INT64 DEFAULT 1; WITH t AS (SELECT 4 AS v) SELECT v FROM t",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("v"))
                .body("rows[0].f[0].v", equalTo("4"));
        query(Map.of("query",
                "DECLARE v INT64 DEFAULT 1; WITH t AS (SELECT 4 AS v) SELECT * FROM t WHERE v > 3",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("4"));
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                WITH t AS (SELECT 4 AS v UNION ALL SELECT 2) SELECT v AS o FROM t ORDER BY v;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"))
                .body("rows[1].f[0].v", equalTo("4"));
        query(Map.of("query",
                "DECLARE v INT64 DEFAULT 1; WITH t AS (SELECT 4 AS v) SELECT COUNT(*) AS c FROM t GROUP BY v",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("c"))
                .body("rows[0].f[0].v", equalTo("1"));
        // A later CTE reads the columns an earlier one exposes: 4.
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                WITH a AS (SELECT 4 AS v), b AS (SELECT v FROM a) SELECT v FROM b;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("4"));
        // A CTE nothing reads declares no column in scope, so the variable still answers: 1, 6 and 4.
        query(Map.of("query", "DECLARE v INT64 DEFAULT 1; WITH t AS (SELECT 4 AS v) SELECT v",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1"));
        query(Map.of("query", "DECLARE v INT64 DEFAULT 1; WITH t AS (SELECT 4 AS v) SELECT 5 + v",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("f0_"))
                .body("rows[0].f[0].v", equalTo("6"));
        query(Map.of("query", "DECLARE v INT64 DEFAULT 1; WITH t AS (SELECT 4 AS v) SELECT (SELECT v FROM t)",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("4"));
        // A stored table's column wins in an expression and in a loop condition alike: 3 and one
        // iteration.
        query(Map.of("query", """
                DECLARE id INT64 DEFAULT 1;
                DECLARE m INT64;
                SET m = (SELECT MAX(id) FROM shop.users);
                SELECT m;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("3"));
        query(Map.of("query", """
                DECLARE i INT64 DEFAULT 0;
                DECLARE n INT64 DEFAULT 0;
                WHILE (SELECT COUNT(*) FROM shop.users WHERE id = 1) > i DO
                  SET n = n + 1;
                  SET i = i + 1;
                END WHILE;
                SELECT n;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1"));
    }

    @Test
    @Order(2)
    void aScriptVariableOutsideASubqueryKeepsItsValue() {
        // A table a nested query reads exposes its columns inside that query only, so a name the query
        // around it writes still binds the variable. Every value below is live BigQuery's: out, 2, 3 and 5.
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                IF v IN (SELECT v FROM t) THEN SELECT 'in' AS r; ELSE SELECT 'out' AS r; END IF;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("out"));
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                SET v = v + (SELECT COUNT(*) FROM t);
                SELECT v;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                WHILE v < 3 DO SET v = v + 1; END WHILE;
                SELECT v;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("3"));
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                SET v = (SELECT v FROM t) + v;
                SELECT v;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("5"));
        // The statement path scopes them the same way: 4 and 1, with or without another table beside the
        // subquery, and 4 for a column the enclosing query reads.
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                SELECT (SELECT MAX(v) FROM t) AS m, v AS outer_v;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("4"))
                .body("rows[0].f[1].v", equalTo("1"));
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                CREATE TEMP TABLE u AS SELECT 2 AS z;
                SELECT (SELECT MAX(v) FROM t) AS m, v AS o FROM u;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("4"))
                .body("rows[0].f[1].v", equalTo("1"));
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                CREATE TEMP TABLE u AS SELECT 2 AS z;
                SELECT (SELECT z + v FROM u) AS s FROM t;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("6"));
        query(Map.of("query", """
                DECLARE v INT64 DEFAULT 1;
                CREATE TEMP TABLE t AS SELECT 4 AS v;
                SELECT (SELECT v) AS s FROM t;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("4"));
        // A CTE and a derived table a subquery reads expose their columns there too: 4 with 1, twice.
        query(Map.of("query",
                "DECLARE v INT64 DEFAULT 1; WITH t AS (SELECT 4 AS v)"
                        + " SELECT (SELECT v FROM t) AS m, v AS o",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("4"))
                .body("rows[0].f[1].v", equalTo("1"));
        query(Map.of("query",
                "DECLARE v INT64 DEFAULT 1; SELECT (SELECT MAX(v) FROM (SELECT 4 AS v)) AS m, v AS o",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("4"))
                .body("rows[0].f[1].v", equalTo("1"));
    }

    @Test
    @Order(2)
    void aTemporaryFunctionsParametersShadowScriptVariables() {
        // Live BigQuery: 3, a parameter is local to the function, so the script variable of the same name
        // is neither its declaration nor the references in its body.
        query(Map.of("query", "DECLARE x INT64 DEFAULT 7; CREATE TEMP FUNCTION f(x INT64) AS (x + 1);"
                        + " SELECT f(2)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("f0_"))
                .body("rows[0].f[0].v", equalTo("3"));
        // The function's parameters end with its body: the statement that follows still binds the
        // variable, as live BigQuery's 7 shows.
        query(Map.of("query", "DECLARE x INT64 DEFAULT 7; CREATE TEMP FUNCTION f(x INT64) AS (x + 1);"
                        + " SELECT x", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("x"))
                .body("rows[0].f[0].v", equalTo("7"));
        query(Map.of("query", "DECLARE x INT64 DEFAULT 7; CREATE TEMP FUNCTION f(x INT64) RETURNS INT64"
                        + " AS (x * 2); SELECT f(3), x", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("6"))
                .body("rows[0].f[1].v", equalTo("7"));
        // A parameter of another name leaves the variable alone inside the body too: 9.
        query(Map.of("query", "DECLARE x INT64 DEFAULT 7; CREATE TEMP FUNCTION f(y INT64) AS (y + x);"
                        + " SELECT f(2)", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("9"));
    }

    @Test
    @Order(2)
    void aForLoopLeavesNoResultOfItsOwn() {
        // Live BigQuery answers a script that ends in a FOR loop with no rows at all, whether the loop
        // runs or not, so the earlier SELECT's result is gone: no schema, no rows, zero total rows.
        query(Map.of("query", """
                DECLARE n INT64 DEFAULT 0;
                SELECT 9 AS answer;
                FOR r IN (SELECT 1 AS id FROM UNNEST([1]) WHERE FALSE) DO SET n = n + 1; END FOR;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema", nullValue())
                .body("rows", nullValue())
                .body("totalRows", equalTo("0"));
        query(Map.of("query", """
                DECLARE n INT64 DEFAULT 0;
                SELECT 9 AS answer;
                FOR r IN (SELECT 1 AS id FROM UNNEST([1])) DO SET n = n + 1; END FOR;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema", nullValue())
                .body("rows", nullValue())
                .body("totalRows", equalTo("0"));
        // A loop that runs a query reports that query's result: 5.
        query(Map.of("query", """
                DECLARE n INT64 DEFAULT 0;
                SELECT 9 AS answer;
                FOR r IN (SELECT 1 AS id FROM UNNEST([1])) DO SELECT 5 AS inner_result; END FOR;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("inner_result"))
                .body("rows[0].f[0].v", equalTo("5"));
        // A WHILE loop that never runs keeps the earlier result: 9.
        query(Map.of("query", """
                DECLARE n INT64 DEFAULT 0;
                SELECT 9 AS answer;
                WHILE FALSE DO SET n = n + 1; END WHILE;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].name", equalTo("answer"))
                .body("rows[0].f[0].v", equalTo("9"));
    }

    @Test
    @Order(2)
    void aBareBreakOrContinueReportsBigQuerysWording() {
        // Live BigQuery: "BREAK without label is only allowed inside of a loop body at [1:1]".
        query(Map.of("query", "BREAK;", "useLegacySql", false))
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"))
                .body("error.message", containsString(
                        "BREAK without label is only allowed inside of a loop body"));
        // Live BigQuery: "CONTINUE is only allowed inside of a loop body at [1:1]".
        query(Map.of("query", "CONTINUE;", "useLegacySql", false))
                .then().statusCode(400)
                .body("error.message", containsString("CONTINUE is only allowed inside of a loop body"));
    }

    @Test
    @Order(2)
    void executeImmediateUsesParametersAndAssignsItsResult() {
        query(Map.of("query", """
                DECLARE input_value INT64 DEFAULT 7;
                DECLARE output_value INT64;
                EXECUTE IMMEDIATE "SELECT @value * 2 AS result"
                  INTO output_value USING input_value AS value;
                SELECT output_value;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("INTEGER"))
                .body("rows[0].f[0].v", equalTo("14"));
    }

    @Test
    @Order(2)
    void executeImmediateBindsAndReturnsAQueryResult() {
        query(Map.of("query", """
                DECLARE user_id INT64 DEFAULT 2;
                DECLARE user_name STRING;
                EXECUTE IMMEDIATE "SELECT name FROM shop.users WHERE id = @requested_id"
                  INTO user_name USING user_id AS requested_id;
                SELECT user_name;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("bo"));
    }

    @Test
    @Order(2)
    void executeImmediateCreatesTemporaryTables() {
        query(Map.of("query", """
                EXECUTE IMMEDIATE "CREATE TEMP TABLE dynamic_values AS SELECT 1 AS value UNION ALL SELECT 2";
                SELECT SUM(value) AS total FROM dynamic_values;
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("3"));
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
        query("""
                {"query": "SELECT @p x, @n n2, @p y, 'lit' z",
                 "parameterMode": "NAMED", "useLegacySql": false,
                 "queryParameters": [
                   {"name": "p", "parameterType": {"type": "STRING"}, "parameterValue": {"value": "abc"}},
                   {"name": "n", "parameterType": {"type": "INT64"}, "parameterValue": {"value": "5"}}
                 ]}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x", "n2", "y", "z")))
                .body("rows[0].f.v", equalTo(List.of("abc", "5", "abc", "lit")));
    }

    @Test
    @Order(5)
    void stringOperandsOfDateFunctionsAreCoerced() {
        query("""
                {"query": "SELECT DATE_ADD(@d, INTERVAL 1 DAY) AS a, DATE_SUB(@d, INTERVAL 1 MONTH) AS s, DATE_DIFF(@d2, @d, DAY) AS dd, DATE_TRUNC(@d, MONTH) AS t, UNIX_SECONDS(TIMESTAMP_ADD(@ts, INTERVAL 1 HOUR)) AS ta, UNIX_SECONDS(TIMESTAMP_TRUNC(@ts, DAY)) AS tt, FORMAT_DATETIME('%Y-%m-%d %H:%M:%S', DATETIME_ADD(@ts, INTERVAL 1 HOUR)) AS da, DATE_ADD('2026-03-01', INTERVAL 2 DAY) AS lit",
                 "parameterMode": "NAMED", "useLegacySql": false,
                 "queryParameters": [
                   {"name": "d", "parameterType": {"type": "STRING"}, "parameterValue": {"value": "2026-03-01"}},
                   {"name": "d2", "parameterType": {"type": "STRING"}, "parameterValue": {"value": "2026-03-31"}},
                   {"name": "ts", "parameterType": {"type": "STRING"}, "parameterValue": {"value": "2026-03-05 09:00:00"}}]}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("2026-03-02", "2026-02-01", "30", "2026-03-01", "1772704800",
                        "1772668800", "2026-03-05 10:00:00", "2026-03-03")));
        query("""
                {"query": "SELECT DATE_ADD(('2026-03-01'), INTERVAL 1 DAY) AS a, DATE_TRUNC((('2026-03-15')), MONTH) AS t, TIMESTAMP_DIFF(TIMESTAMP '2021-09-21 23:00:00+00', '2021-09-21 01:00:00+00', HOUR) AS th, DATE_DIFF(DATE '2021-09-22', '2021-09-21', DAY) AS dd, DATETIME_DIFF(DATETIME '2021-09-21 23:00:00', '2021-09-21 01:30:00', MINUTE) AS dm", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("2026-03-02", "2026-03-01", "22", "1", "1290")));
        query("""
                {"query": "SELECT DATE_ADD(@d, INTERVAL 1 DAY) AS a, DATE_DIFF(@d, DATE '2026-03-01', DAY) AS dd, TIMESTAMP_ADD(@d, INTERVAL 1 HOUR) AS ta",
                 "parameterMode": "NAMED", "useLegacySql": false,
                 "queryParameters": [{"name": "d", "parameterType": {"type": "STRING"}, "parameterValue": {}}]}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(Arrays.asList(null, null, null)));
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
                .body("rows[0].f[0].v.v.f.v", equalTo(List.of(Arrays.asList((String) null), List.of("1"))));
    }

    @Test
    @Order(5)
    void dmlThatWouldStoreANullArrayElementFailsAndWritesNothing() {
        query(Map.of("query", "CREATE TABLE shop.null_elements (id INT64, a ARRAY<INT64>, s STRUCT<b ARRAY<STRING>>)",
                "useLegacySql", false)).then().statusCode(200);
        query(Map.of("query", "INSERT INTO shop.null_elements (id, a, s) VALUES (1, [1, 2], STRUCT(['x'] AS b))",
                "useLegacySql", false)).then().statusCode(200);
        assertNullElementError("INSERT INTO shop.null_elements (id, a) VALUES (2, [1, NULL])", "a");
        assertNullElementError("INSERT INTO shop.null_elements (id, a) SELECT 3, [1, NULL]", "a");
        assertNullElementError("UPDATE shop.null_elements SET a = [5, NULL] WHERE id = 1", "a");
        assertNullElementError("UPDATE shop.null_elements SET s = STRUCT(['y', NULL] AS b) WHERE id = 1",
                "s.b; error in writing field s");
        assertNullElementError("MERGE shop.null_elements t USING (SELECT 9 AS id, [NULL] AS a) s ON t.id = s.id"
                + " WHEN NOT MATCHED THEN INSERT (id, a) VALUES (s.id, s.a)", "a");
        assertNullElementError("MERGE shop.null_elements t USING (SELECT 1 AS id) s ON t.id = s.id"
                + " WHEN MATCHED THEN UPDATE SET a = [NULL, 3]", "a");
        query(Map.of("query", "UPDATE shop.null_elements SET a = [NULL] WHERE id = 42", "useLegacySql", false))
                .then().statusCode(200);
        query(Map.of("query", "SELECT id, a, s FROM shop.null_elements ORDER BY id", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[1].v.v", equalTo(List.of("1", "2")))
                .body("rows[0].f[2].v.f[0].v.v", equalTo(List.of("x")));
        given().when().get(BASE + "/datasets/shop/tables/null_elements/data").then().statusCode(200)
                .body("totalRows", equalTo("1"))
                .body("rows[0].f[1].v.v", equalTo(List.of("1", "2")));
        query(Map.of("query", "SELECT ARRAY_SLICE([1, 2, 3], 3, 3) AS a, ARRAY_SLICE([1, 2, 3], 10, 12) AS b,"
                + " ARRAY_SLICE([1, 2, 3], 2, 10) AS c, ARRAY_SLICE([1, 2, 3], 5, 1) AS d, ARRAY_SLICE([1, 2, 3], -10, -8) AS e,"
                + " ARRAY_SLICE([1, 2, 3], -5, -4) AS f, ARRAY_SLICE([1, 2, 3], 0, -10) AS g, ARRAY_SLICE(ARRAY<INT64>[], 3, 3) AS h",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.collect { it.v.collect { e -> e.v } }", equalTo(List.of(List.of(), List.of(), List.of("3"),
                        List.of(), List.of(), List.of(), List.of(), List.of())));
    }

    @Test
    @Order(5)
    void bareSubscriptOnAVisibleArrayIsZeroBased() {
        query("""
                {"query": "SELECT CAST([1, 2, 3] AS ARRAY<INT64>)[1] AS v, CAST(['a', 'b'] AS ARRAY<STRING>)[0] AS w, SAFE_CAST([1, 2, 3] AS ARRAY<INT64>)[1] AS x, CAST([10, 20] AS ARRAY<INT64>)[SAFE_OFFSET(5)] AS y", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(Arrays.asList("2", "a", "2", null)));
        for (String sql : List.of("SELECT CAST([1, 2, 3] AS ARRAY<INT64>)[3] AS v", "SELECT SAFE_CAST([1, 2, 3] AS ARRAY<INT64>)[3] AS v")) {
            query(Map.of("query", sql, "useLegacySql", false))
                    .then().statusCode(400)
                    .body("error.message", containsString("Array index 3 is out of bounds (overflow)"));
        }
        query("""
                {"query": "SELECT ARRAY_CONCAT_AGG(a)[0] AS f, ARRAY_CONCAT_AGG(a)[2] AS t FROM UNNEST([STRUCT([1, 2] AS a), STRUCT([3])])", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("1", "3")));
        query("""
                {"query": "SELECT ARRAY_CONCAT_AGG(a)[3] AS f FROM UNNEST([STRUCT([1, 2] AS a), STRUCT([3])])", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("Array index 3 is out of bounds (overflow)"));
        query("""
                {"query": "SELECT ARRAY<INT64>[1, 2, 3][1] AS t, ARRAY<STRING>['a', 'b'][0] AS s", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("2", "a")));
        query("""
                {"query": "SELECT ARRAY<INT64>[1, 2, 3][3] AS t", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("Array index 3 is out of bounds (overflow)"));
        query("""
                {"query": "SELECT ARRAY<INT64>[][0] AS a", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("Array index 0 is out of bounds (overflow)"));
        query("""
                {"query": "SELECT ARRAY<INT64>[][SAFE_OFFSET(0)] AS a", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", nullValue());
        query("""
                {"query": "SELECT [10, 20, 30][2] AS a, [10, 20, 30][0] AS b, ['x', 'y'][1] AS c, [10, 20][CAST(NULL AS INT64)] AS n", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(Arrays.asList("30", "10", "y", null)));
        query("""
                {"query": "SELECT SPLIT('a,b,c')[1] AS s, GENERATE_ARRAY(1, 5)[0] AS g, ARRAY(SELECT x FROM UNNEST([7, 8]) x ORDER BY x)[1] AS arr, ARRAY_CONCAT([1], [2, 3])[2] AS cc, ARRAY_REVERSE([1, 2, 3])[0] AS rv, GENERATE_DATE_ARRAY('2024-01-01', '2024-01-03')[2] AS gd", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("b", "1", "8", "3", "3", "2024-01-03")));
        query("""
                {"query": "SELECT ARRAY_AGG(x ORDER BY x)[1] AS a FROM UNNEST([3, 1, 2]) x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));
        query("""
                {"query": "SELECT [10, 20, 30][3] AS a", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("Array index 3 is out of bounds (overflow)"));
        query("""
                {"query": "SELECT [10, 20, 30][-1] AS a", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("Array index -1 is out of bounds (underflow)"));
        query("""
                {"query": "SELECT JSON '[1, 2, 3]'[0] AS j", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1"));
        query("""
                {"query": "SELECT ([10, 20, 30])[1] AS a, (([10, 20, 30]))[0] AS b, (SPLIT('a,b,c'))[2] AS c", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("20", "10", "c")));
        query("""
                {"query": "SELECT ([10, 20, 30])[3] AS a", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("Array index 3 is out of bounds (overflow)"));
        query("""
                {"query": "SELECT x, ARRAY_AGG(x) OVER (ORDER BY x)[0] AS f, (ARRAY_AGG(x) OVER (ORDER BY x))[0] AS g FROM UNNEST([3, 1, 2]) x ORDER BY x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("1", "1", "1"), List.of("2", "1", "1"), List.of("3", "1", "1"))));
        query("""
                {"query": "SELECT x, ARRAY_AGG(x) OVER (ORDER BY x)[5] AS v FROM UNNEST([3, 1, 2]) x", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("Array index 5 is out of bounds (overflow)"));
    }

    @Test
    @Order(5)
    void yearElementsDropLeadingZerosLikeBigQuery() {
        query(Map.of("query", "SELECT d, FORMAT_DATE('%Y', d) e0, FORMAT_DATE('%G', d) e1, FORMAT_DATE('%C', d) e2, FORMAT_DATE('%y', d) e3, FORMAT_DATE('%g', d) e4, FORMAT_DATE('%F', d) e5, FORMAT_DATE('%c', d) e6, FORMAT_DATE('%x', d) e7, FORMAT_DATE('%D', d) e8, FORMAT_DATE('%E4Y', d) e9 FROM UNNEST([DATE '0009-01-05', DATE '0099-01-02', DATE '0999-12-31', DATE '1000-01-01', DATE '2024-03-05']) d ORDER BY d",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("0009-01-05", "9", "9", "0", "09", "09", "9-01-05", "%c", "01/05/09", "01/05/09", "0009"),
                        List.of("0099-01-02", "99", "99", "0", "99", "99", "99-01-02", "%c", "01/02/99", "01/02/99", "0099"),
                        List.of("0999-12-31", "999", "1000", "9", "99", "00", "999-12-31", "%c", "12/31/99", "12/31/99", "0999"),
                        List.of("1000-01-01", "1000", "1000", "10", "00", "00", "1000-01-01", "%c", "01/01/00", "01/01/00", "1000"),
                        List.of("2024-03-05", "2024", "2024", "20", "24", "24", "2024-03-05", "%c", "03/05/24", "03/05/24", "2024"))));
        query(Map.of("query", "SELECT FORMAT_TIMESTAMP('%Y', t) e0, FORMAT_TIMESTAMP('%G', t) e1, FORMAT_TIMESTAMP('%C', t) e2, FORMAT_TIMESTAMP('%y', t) e3, FORMAT_TIMESTAMP('%g', t) e4, FORMAT_TIMESTAMP('%F', t) e5, FORMAT_TIMESTAMP('%c', t) e6, FORMAT_TIMESTAMP('%x', t) e7, FORMAT_TIMESTAMP('%D', t) e8, FORMAT_TIMESTAMP('%E4Y', t) e9 FROM UNNEST([TIMESTAMP '0099-01-02 03:04:05+00', TIMESTAMP '0009-01-05 03:04:05+00']) t ORDER BY t",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("9", "9", "0", "09", "09", "9-01-05", "Mon Jan  5 03:04:05 9", "01/05/09", "01/05/09", "0009"),
                        List.of("99", "99", "0", "99", "99", "99-01-02", "Fri Jan  2 03:04:05 99", "01/02/99", "01/02/99", "0099"))));
        query(Map.of("query", "SELECT FORMAT_DATETIME('%Y', DATETIME '0099-01-02 03:04:05') e0, FORMAT_DATETIME('%G', DATETIME '0099-01-02 03:04:05') e1, FORMAT_DATETIME('%C', DATETIME '0099-01-02 03:04:05') e2, FORMAT_DATETIME('%y', DATETIME '0099-01-02 03:04:05') e3, FORMAT_DATETIME('%g', DATETIME '0099-01-02 03:04:05') e4, FORMAT_DATETIME('%F', DATETIME '0099-01-02 03:04:05') e5, FORMAT_DATETIME('%c', DATETIME '0099-01-02 03:04:05') e6, FORMAT_DATETIME('%x', DATETIME '0099-01-02 03:04:05') e7, FORMAT_DATETIME('%D', DATETIME '0099-01-02 03:04:05') e8, FORMAT_DATETIME('%E4Y', DATETIME '0099-01-02 03:04:05') e9", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("99", "99", "0", "99", "99", "99-01-02", "Fri Jan  2 03:04:05 99", "01/02/99", "01/02/99", "0099")));
        query(Map.of("query", "SELECT f, FORMAT_DATE(f, DATE '0099-01-02') v FROM UNNEST(['%Y', '%G', '%C', '%y', '%g', '%F', '%c', '%x', '%D', '%E4Y']) f WITH OFFSET o ORDER BY o",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("%Y", "99"),
                        List.of("%G", "99"),
                        List.of("%C", "0"),
                        List.of("%y", "99"),
                        List.of("%g", "99"),
                        List.of("%F", "99-01-02"),
                        List.of("%c", "%c"),
                        List.of("%x", "01/02/99"),
                        List.of("%D", "01/02/99"),
                        List.of("%E4Y", "0099"))));
        query(Map.of("query", "SELECT FORMAT_DATE('%E10S|%E9S|%E007S', DATE '2024-03-05') v", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("00.0000000000|%E9S|00.0000000"));
        query(Map.of("query", "SELECT f, FORMAT_DATE(f, DATE '2024-03-05') v FROM UNNEST(['%E0S', '%E7S', '%E9S', '%E10S', '%E007S', '%E07S', '%E20S', '%E100S', '%E00S']) f WITH OFFSET o ORDER BY o",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("%E0S", "%E0S"), List.of("%E7S", "%E7S"), List.of("%E9S", "%E9S"),
                        List.of("%E10S", "00.0000000000"), List.of("%E007S", "00.0000000"), List.of("%E07S", "00.0000000"),
                        List.of("%E20S", "00.000000000000000000"), List.of("%E100S", "00.000000000000000000"), List.of("%E00S", "00"))));
    }

    @Test
    @Order(5)
    void orderByReadsAStructSelectAliasFieldLikeBigQuery() {
        String[][] cases = {
                {"SELECT x = 1 AS d, STRUCT(x AS n) AS s FROM UNNEST([1, 2]) x ORDER BY s.n DESC", "rows.collect { it.f[1].v.f[0].v }", "2,1"},
                {"SELECT STRUCT(x AS n, x * 10 AS m) AS s FROM UNNEST([3, 1, 2]) x ORDER BY s.m", "rows.collect { it.f[0].v.f[1].v }", "10,20,30"},
                {"SELECT STRUCT(MAX(x) AS n) AS s, MOD(x, 2) AS k FROM UNNEST([1, 2, 3]) x GROUP BY k ORDER BY s.n DESC", "rows.collect { it.f[0].v.f[0].v }", "3,2"},
                {"SELECT STRUCT(x * -1 AS n) AS x FROM UNNEST([3, 1, 2]) x ORDER BY x.n", "rows.collect { it.f[0].v.f[0].v }", "-3,-2,-1"},
                {"SELECT DISTINCT STRUCT(x AS n) AS s FROM UNNEST([2, 1, 2]) x ORDER BY s.n", "rows.collect { it.f[0].v.f[0].v }", "1,2"},
                {"SELECT x, STRUCT(x AS n) AS s, ROW_NUMBER() OVER (ORDER BY x DESC) AS r FROM UNNEST([2, 1]) x ORDER BY s.n", "rows.collect { it.f[2].v }", "2,1"},
                {"SELECT ARRAY(SELECT y FROM UNNEST([3, 1]) y ORDER BY y) AS a, STRUCT(x AS n) AS s FROM UNNEST([2, 1]) x ORDER BY s.n", "rows.collect { it.f[1].v.f[0].v }", "1,2"},
                {"SELECT UPPER(w) AS w, STRUCT(w AS raw) AS s FROM UNNEST(['b', 'C', 'a']) w ORDER BY s.raw", "rows.collect { it.f[0].v }", "C,A,B"},
                {"SELECT -x AS x, STRUCT(x AS n) AS s FROM UNNEST([1, 2, 3]) x ORDER BY s.n", "rows.collect { it.f[0].v }", "-1,-2,-3"},
                {"SELECT x, STRUCT(1 AS n) AS s FROM UNNEST([3, 1, 2]) x ORDER BY s.n, x DESC", "rows.collect { it.f[0].v }", "3,2,1"},
                {"SELECT (SELECT AS STRUCT x AS n) AS s FROM UNNEST([3, 1, 2]) x ORDER BY s.n", "rows.collect { it.f[0].v.f[0].v }", "1,2,3"},
                {"SELECT x, (SELECT AS STRUCT (SELECT MAX(y) * -1 FROM UNNEST([3, 1, 2]) y WHERE y <= x) AS n) AS s FROM UNNEST([1, 2, 3]) x ORDER BY s.n", "rows.collect { it.f[0].v }", "3,2,1"},
                {"SELECT STRUCT(s.n * -1 AS n) AS s FROM (SELECT STRUCT(x AS n) AS s FROM UNNEST([1, 2, 3]) x) ORDER BY s.n", "rows.collect { it.f[0].v.f[0].v }", "-3,-2,-1"},
                {"SELECT x, STRUCT(x AS n) AS s FROM UNNEST([2, 1, 3]) x QUALIFY ROW_NUMBER() OVER (ORDER BY s.n) <= 2 ORDER BY x", "rows.collect { it.f[0].v }", "1,2"},
                {"SELECT DISTINCT STRUCT(x AS n) AS s FROM UNNEST([2, 1, 3]) x QUALIFY ROW_NUMBER() OVER (ORDER BY s.n) <= 2 ORDER BY s.n", "rows.collect { it.f[0].v.f[0].v }", "1,2"},
                {"SELECT t.s.n AS m, STRUCT(0 AS n) AS s FROM (SELECT STRUCT(x AS n) AS s FROM UNNEST([2, 1, 3]) x) AS t ORDER BY t.s.n", "rows.collect { it.f[0].v }", "1,2,3"},
                {"SELECT STRUCT(MAX(t.s.n) AS n) AS s, t.s.n AS k FROM (SELECT STRUCT(x AS n) AS s FROM UNNEST([2, 1, 3]) x) AS t GROUP BY k HAVING t.s.n > 1 ORDER BY k", "rows.collect { it.f[1].v }", "2,3"},
        };
        for (String[] c : cases) {
            query(Map.of("query", c[0], "useLegacySql", false))
                    .then().statusCode(200)
                    .body(c[1], equalTo(List.of(c[2].split(","))));
        }
    }

    @Test
    @Order(5)
    void formatFunctionsCoerceAStringOrNullValueLikeBigQuery() {
        query(Map.of("query", "SELECT FORMAT_DATE('%Y', '0099-01-02') a, FORMAT_DATE('%F', '2024-03-05') b,"
                + " FORMAT_TIMESTAMP('%Y-%m-%d %H', '2024-03-05 07:08:09+00') c, FORMAT_DATETIME('%Y %H', '2024-03-05 07:08:09') d,"
                + " FORMAT_TIME('%H:%M', '07:08:09') e", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("99", "2024-03-05", "2024-03-05 07", "2024 07", "07:08")));
        query(Map.of("query", "SELECT FORMAT_DATE('%F', @s) a", "useLegacySql", false, "parameterMode", "NAMED",
                "queryParameters", List.of(Map.of("name", "s", "parameterType", Map.of("type", "STRING"),
                        "parameterValue", Map.of("value", "0099-01-02")))))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("99-01-02"));
        query(Map.of("query", "SELECT FORMAT_DATE('%Y', NULL) y, FORMAT_DATE('%G', NULL) g, FORMAT_DATE('%F', NULL) f,"
                + " FORMAT_DATE('%C', NULL) c, FORMAT_DATE('%e', NULL) e, FORMAT_DATE('%d', NULL) d,"
                + " FORMAT_TIMESTAMP('%Y', NULL) t, FORMAT_DATETIME('%Y', NULL) dt, FORMAT_TIME('%H', NULL) tm",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(Arrays.asList(null, null, null, null, null, null, null, null, null)));
        query(Map.of("query", "SELECT FORMAT_DATE('%Y', null) a, FORMAT_DATE('%Y', (NULL)) b, FORMAT_DATE('%F', ((null))) c,"
                + " FORMAT_TIMESTAMP('%Y', Null) d, FORMAT_TIME('%H', (null)) e", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(Arrays.asList(null, null, null, null, null)));
    }

    @Test
    @Order(5)
    void nullsSortFirstAscendingAndLastDescendingLikeBigQuery() {
        String[][] cases = {
                {"SELECT n FROM UNNEST([1, 3, NULL]) n ORDER BY n", "rows.collect { it.f[0].v }", "NULL,1,3"},
                {"SELECT n FROM UNNEST([1, 3, NULL]) n ORDER BY n DESC", "rows.collect { it.f[0].v }", "3,1,NULL"},
                {"SELECT n FROM UNNEST([1, 3, NULL]) n ORDER BY n ASC NULLS LAST", "rows.collect { it.f[0].v }", "1,3,NULL"},
                {"SELECT n FROM UNNEST([1, 3, NULL]) n ORDER BY n DESC NULLS FIRST", "rows.collect { it.f[0].v }", "NULL,3,1"},
                {"SELECT n, ROW_NUMBER() OVER (ORDER BY n) AS r FROM UNNEST([1, 3, NULL]) n ORDER BY r", "rows.collect { it.f[0].v }", "NULL,1,3"},
                {"SELECT a, b FROM UNNEST([STRUCT(1 AS a, CAST(NULL AS INT64) AS b), STRUCT(1, 2), STRUCT(NULL, 3)]) ORDER BY a, b DESC", "rows.collect { it.f[1].v }", "3,2,NULL"},
        };
        for (String[] c : cases) {
            List<String> expected = Arrays.stream(c[2].split(",")).map(v -> v.equals("NULL") ? null : v).toList();
            query(Map.of("query", c[0], "useLegacySql", false))
                    .then().statusCode(200)
                    .body(c[1], equalTo(expected));
        }
    }

    @Test
    @Order(5)
    void stringParameterWithATimeZoneNameIsCoercedToTimestampLikeBigQuery() {
        for (String[] c : new String[][] {
                {"SELECT TIMESTAMP '2014-09-27 20:00:00+00' > @a AS gt, TIMESTAMP '2014-09-27 19:30:00.45+00' = @a AS eq",
                        "2014-09-27 12:30:00.45 America/Los_Angeles", "true,true"},
                {"SELECT TIMESTAMP '2014-09-27 20:00:00+00' > @a AS gt", "2014-09-27 12:30:00 Etc/GMT+7", "true"},
                {"SELECT TIMESTAMP '2014-09-27 20:00:00+00' > @a AS gt", "2014-09-27 12:30:00 EST5EDT", "true"},
                {"SELECT TIMESTAMP '2014-09-27 20:00:00+00' > @a AS gt", "2014-09-27 12:30:00 GB", "true"},
                {"SELECT @a AS v", "2014-09-27 12:30:00.45 America/Los_Angeles", "2014-09-27 12:30:00.45 America/Los_Angeles"},
        }) {
            query(Map.of("query", c[0], "useLegacySql", false, "parameterMode", "NAMED",
                    "queryParameters", List.of(Map.of("name", "a", "parameterType", Map.of("type", "STRING"),
                            "parameterValue", Map.of("value", c[1])))))
                    .then().statusCode(200)
                    .body("rows[0].f.v", equalTo(List.of(c[2].split(","))));
        }
    }

    @Test
    @Order(5)
    void orderByAMissingFieldOfASourceStructNamesTheFieldLikeBigQuery() {
        String source = "(SELECT STRUCT(1 AS n, STRUCT('a' AS k) AS m) AS s) t";
        for (String[] c : new String[][] {
                {"SELECT t.s AS s FROM " + source + " ORDER BY s.zz", "Field name zz does not exist in STRUCT<n INT64, m STRUCT<k STRING>>"},
                {"SELECT t.s AS r FROM " + source + " ORDER BY r.zz", "Field name zz does not exist in STRUCT<n INT64, m STRUCT<k STRING>>"},
                {"SELECT t.s AS s FROM " + source + " ORDER BY s.m.zz", "Field name zz does not exist in STRUCT<k STRING>"},
                {"SELECT t.s AS s FROM " + source + " ORDER BY s.n.zz", "Cannot access field zz on a value with type INT64"},
                {"SELECT * FROM (SELECT STRUCT(x AS n) AS s FROM UNNEST([2, 1]) x) ORDER BY s.zz",
                        "Field name zz does not exist in STRUCT<n INT64>"},
                {"SELECT STRUCT(1 AS n) AS s FROM UNNEST([1]) UNION ALL SELECT STRUCT(2 AS n) FROM UNNEST([1]) ORDER BY s.zz",
                        "Field name zz does not exist in STRUCT<n INT64>"},
        }) {
            query(Map.of("query", c[0], "useLegacySql", false))
                    .then().statusCode(400)
                    .body("error.message", equalTo(c[1]));
        }
        query(Map.of("query", "SELECT t.s AS s FROM " + source + " ORDER BY s.zz", "useLegacySql", false, "dryRun", true))
                .then().statusCode(400)
                .body("error.message", equalTo("Field name zz does not exist in STRUCT<n INT64, m STRUCT<k STRING>>"));
        query(Map.of("query", "SELECT t.s AS s FROM (SELECT STRUCT(2 AS n) AS s UNION ALL SELECT STRUCT(1 AS n)) t ORDER BY s.n",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.collect { it.f[0].v.f[0].v }", equalTo(List.of("1", "2")));
    }

    @Test
    @Order(5)
    void setOperationStructsTakeTheFirstQuerysFieldNamesLikeBigQuery() {
        for (String[] c : new String[][] {
                {"SELECT STRUCT(1 AS n) AS s UNION ALL SELECT STRUCT(0)", "n", "1,0"},
                {"SELECT STRUCT(1 AS n) AS s UNION ALL SELECT STRUCT(2 AS q) UNION ALL SELECT STRUCT(3)", "n", "1,2,3"},
                {"(SELECT STRUCT(1 AS n) AS s) UNION ALL (SELECT STRUCT(0 AS z))", "n", "1,0"},
                {"SELECT STRUCT(1 AS n) AS s UNION ALL SELECT (STRUCT(2 AS q))", "n", "1,2"},
                {"SELECT STRUCT(1 AS n) AS s UNION ALL SELECT ((STRUCT(2 AS q))) AS t", "n", "1,2"},
        }) {
            List<String> values = query(Map.of("query", c[0], "useLegacySql", false))
                    .then().statusCode(200)
                    .body("schema.fields[0].fields.name", equalTo(List.of(c[1])))
                    .extract().path("rows.collect { it.f[0].v.f[0].v }");
            assertEquals(List.of(c[2].split(",")), values);
        }
        query(Map.of("query", "SELECT STRUCT(1 AS n, 'a' AS m) AS s UNION ALL SELECT STRUCT(2 AS x, 'b' AS y)",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("n", "m")))
                .body("rows.collect { it.f[0].v.f.v }", equalTo(List.of(List.of("1", "a"), List.of("2", "b"))));
        query(Map.of("query", "SELECT s.n FROM (SELECT STRUCT(1 AS n) AS s UNION ALL SELECT STRUCT(0)) ORDER BY s.n",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.collect { it.f[0].v }", equalTo(List.of("0", "1")));
        query(Map.of("query", "SELECT 1 AS k, STRUCT('a' AS n) AS s UNION ALL SELECT 2, STRUCT('b' AS w) ORDER BY k",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[1].fields.name", equalTo(List.of("n")))
                .body("rows.collect { it.f[1].v.f[0].v }", equalTo(List.of("a", "b")));
    }

    @Test
    @Order(5)
    void orderByAMissingStructAliasFieldNamesTheFieldLikeBigQuery() {
        for (String[] c : new String[][] {
                {"SELECT STRUCT(x AS n) AS s FROM UNNEST([3, 1, 2]) x ORDER BY s.zz", "zz", "STRUCT<n INT64>"},
                {"SELECT STRUCT(x AS n) AS s FROM UNNEST([3, 1, 2]) x ORDER BY s.ZZ", "ZZ", "STRUCT<n INT64>"},
                {"SELECT STRUCT(x AS n, 'a' AS m) AS s FROM UNNEST([3, 1, 2]) x ORDER BY s.zz", "zz",
                        "STRUCT<n INT64, m STRING>"},
                {"SELECT STRUCT(x AS n) AS s FROM UNNEST(ARRAY<INT64>[]) x ORDER BY s.zz", "zz", "STRUCT<n INT64>"},
                {"SELECT (SELECT AS STRUCT x AS n) AS s FROM UNNEST([3, 1, 2]) x ORDER BY s.zz", "zz", "STRUCT<n INT64>"},
                {"SELECT STRUCT(s.x AS n) AS s FROM (SELECT 1 AS x, 2 AS zz) s ORDER BY s.zz", "zz", "STRUCT<n INT64>"},
                {"SELECT x, STRUCT(x AS n) AS s FROM UNNEST([2, 1, 3]) x QUALIFY ROW_NUMBER() OVER (ORDER BY s.zz) <= 2",
                        "zz", "STRUCT<n INT64>"},
                {"SELECT STRUCT(t.n) AS s FROM (SELECT 1 AS n) t ORDER BY s.t", "t", "STRUCT<n INT64>"},
                {"SELECT STRUCT(x + 1) AS s FROM UNNEST([2, 1]) x ORDER BY s.x", "x", "STRUCT<INT64>"},
                {"SELECT STRUCT(x + 1, 'a' AS m) AS s FROM UNNEST([2, 1]) x ORDER BY s.x", "x", "STRUCT<INT64, m STRING>"},
                {"SELECT STRUCT(x + 1, x AS n) AS s FROM UNNEST([2, 1]) x ORDER BY s.zz", "zz", "STRUCT<INT64, n INT64>"},
                {"SELECT STRUCT(x + 1, x AS n) AS s FROM UNNEST([2, 1]) x ORDER BY s._field_1", "_field_1",
                        "STRUCT<INT64, n INT64>"},
                {"SELECT STRUCT(STRUCT(x + 1), x AS n) AS s FROM UNNEST([2, 1]) x ORDER BY s.zz", "zz",
                        "STRUCT<STRUCT<INT64>, n INT64>"},
                {"SELECT STRUCT(STRUCT(x + 1, x AS k), x AS n) AS s FROM UNNEST([2, 1]) x ORDER BY s.zz", "zz",
                        "STRUCT<STRUCT<INT64, k INT64>, n INT64>"},
                {"SELECT STRUCT(STRUCT(x + 1) AS m, x AS n) AS s FROM UNNEST([2, 1]) x ORDER BY s.zz", "zz",
                        "STRUCT<m STRUCT<INT64>, n INT64>"},
                {"SELECT STRUCT(STRUCT(x AS _field_1) AS m) AS s FROM UNNEST([2, 1]) x ORDER BY s.zz", "zz",
                        "STRUCT<m STRUCT<_field_1 INT64>>"},
                {"SELECT STRUCT(STRUCT(x + 1) AS m, x AS `m._field_1`) AS s FROM UNNEST([2, 1]) x ORDER BY s.zz", "zz",
                        "STRUCT<m STRUCT<INT64>, `m._field_1` INT64>"},
        }) {
            query(Map.of("query", c[0], "useLegacySql", false))
                    .then().statusCode(400)
                    .body("error.message", equalTo("Field name " + c[1] + " does not exist in " + c[2]));
        }
        query(Map.of("query", "SELECT STRUCT(t.n, t.m) AS s FROM (SELECT 2 AS n, 'a' AS m UNION ALL SELECT 1, 'b') t"
                + " ORDER BY s.n", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.collect { it.f[0].v.f[0].v }", equalTo(List.of("1", "2")));
        query(Map.of("query", "SELECT STRUCT(x + 1, x AS n) AS s FROM UNNEST([2, 1]) x ORDER BY s.n",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.collect { it.f[0].v.f[1].v }", equalTo(List.of("1", "2")));
        query(Map.of("query", "SELECT t.s.* EXCEPT(n) FROM (SELECT STRUCT(x + 1, x AS n) AS s FROM UNNEST([1]) x) t",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("2")));
        query(Map.of("query", "SELECT t.s.* FROM (SELECT STRUCT(x + 1, x AS n) AS s FROM UNNEST([1]) x) t",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("2", "1")));
        for (String sql : List.of("SELECT STRUCT(t.n) AS s FROM (SELECT 1 AS n UNION ALL SELECT 0) t ORDER BY s.n",
                "SELECT STRUCT(t.u.a) AS s FROM (SELECT STRUCT(1 AS a) AS u UNION ALL SELECT STRUCT(0 AS a)) t ORDER BY s.a")) {
            query(Map.of("query", sql, "useLegacySql", false))
                    .then().statusCode(200)
                    .body("rows.collect { it.f[0].v.f[0].v }", equalTo(List.of("0", "1")));
        }
        query(Map.of("query", "SELECT STRUCT(x AS `a.b`) AS s FROM UNNEST([2, 1]) x ORDER BY s.`a.b`",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.collect { it.f[0].v.f[0].v }", equalTo(List.of("1", "2")));
        for (String sql : List.of("WITH q AS (SELECT STRUCT(x AS n) AS s FROM UNNEST([1]) x ORDER BY s.zz)"
                + " SELECT STRUCT('a' AS m) AS s FROM q",
                "SELECT STRUCT('a' AS m) AS s FROM (SELECT STRUCT(x AS n) AS s FROM UNNEST([1]) x ORDER BY s.zz)")) {
            query(Map.of("query", sql, "useLegacySql", false))
                    .then().statusCode(400)
                    .body("error.message", equalTo("Field name zz does not exist in STRUCT"));
        }
        query(Map.of("query", "SELECT STRUCT(x AS n) AS s FROM UNNEST([3, 1, 2]) x ORDER BY s.zz",
                "useLegacySql", false, "dryRun", true))
                .then().statusCode(400)
                .body("error.message", equalTo("Field name zz does not exist in STRUCT<n INT64>"));
    }

    @Test
    @Order(5)
    void arraySliceAndRegexpExtractAllFollowBigQuery() {
        query(Map.of("query", "SELECT ARRAY_SLICE([1, 2, 3], 1, 2) AS a, ARRAY_SLICE([1, 2, 3, 4, 5], -3, -1) AS b,"
                + " ARRAY_SLICE([1, 2, 3], 1, 10) AS c, ARRAY_SLICE([1, 2, 3], -10, 1) AS d,"
                + " ARRAY_SLICE([1, 2, 3, 4, 5], -2, 1) AS e, ARRAY_SLICE([1, 2, 3], 2, 1) AS f,"
                + " ARRAY_SLICE([1, 2, 3], 0, 0) AS g, ARRAY_SLICE([1, 2, 3, 4, 5], 1, -2) AS h", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.collect { it.v.collect { e -> e.v } }", equalTo(List.of(List.of("2", "3"),
                        List.of("3", "4", "5"), List.of("2", "3"), List.of("1", "2"), List.of(), List.of(), List.of("1"),
                        List.of("2", "3", "4"))));
        query(Map.of("query", "SELECT REGEXP_EXTRACT_ALL('a1b22', r'[a-z]([0-9]+)') AS a,"
                + " REGEXP_EXTRACT_ALL('a1b22', r'(?:[a-z])([0-9]+)') AS b, REGEXP_EXTRACT_ALL('a1b2', r'[0-9]') AS c,"
                + " REGEXP_EXTRACT_ALL('abc', r'[0-9]') AS d", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.collect { it.v.collect { e -> e.v } }", equalTo(List.of(List.of("1", "22"),
                        List.of("1", "22"), List.of("1", "2"), List.of())));
        query(Map.of("query", "SELECT REGEXP_EXTRACT_ALL('a1b2', r'[0-9]')[0] AS a, REGEXP_EXTRACT_ALL('a1b2', r'[0-9]')[1] AS b,"
                + " ARRAY_SLICE([1, 2, 3], 1, 2)[0] AS c, ARRAY_SLICE([1, 2, 3], 1, 2)[1] AS d", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("1", "2", "2", "3")));
        query(Map.of("query", "SELECT REGEXP_EXTRACT_ALL('a1b2', r'[0-9]')[2] AS a", "useLegacySql", false))
                .then().statusCode(400)
                .body("error.message", containsString("Array index 2 is out of bounds (overflow)"));
        query(Map.of("query", "SELECT ARRAY_SLICE([1, 2, 3], 0, @p) AS a", "useLegacySql", false, "parameterMode", "NAMED",
                "queryParameters", List.of(Map.of("name", "p", "parameterType", Map.of("type", "INT64"),
                        "parameterValue", Map.of("value", "9223372036854775807")))))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("1", "2", "3")));
        query(Map.of("query", "SELECT ARRAY_SLICE([1, 2, 3], 1, 9223372036854775807) AS a", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("2", "3")));
        query(Map.of("query", "SELECT ARRAY_SLICE([1, 2, 3], IF(RAND() < 0.5, -2, 1), 1) AS a"
                + " FROM UNNEST(GENERATE_ARRAY(1, 20))", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.collect { it.f[0].v.collect { e -> e.v } }.unique()", equalTo(List.of(List.of("2"))));
        query(Map.of("query", "SELECT REGEXP_EXTRACT_ALL('a(b', r'[(]') AS a, REGEXP_EXTRACT('a(b', r'[(]') AS b,"
                + " REGEXP_EXTRACT_ALL('a1b22', r'[a-z](?P<d>[0-9]+)') AS c, REGEXP_EXTRACT_ALL('a]1', r'[]a]') AS d",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("(")))
                .body("rows[0].f[1].v", equalTo("("))
                .body("rows[0].f[2].v.v", equalTo(List.of("1", "22")))
                .body("rows[0].f[3].v.v", equalTo(List.of("a", "]")));
        query(Map.of("query", "SELECT REGEXP_EXTRACT_ALL('x(1(', r'\\Q(\\E') AS a, REGEXP_EXTRACT('x(1', r'\\Q(\\E') AS b,"
                + " REGEXP_EXTRACT_ALL('a(1', r'[[:alpha:](]') AS c, REGEXP_EXTRACT_ALL('a1b2', r'\\Q(\\E?([0-9])') AS d,"
                + " REGEXP_EXTRACT_ALL('a1b2', r'[[:alpha:]]([0-9])') AS e", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("(", "(")))
                .body("rows[0].f[1].v", equalTo("("))
                .body("rows[0].f[2].v.v", equalTo(List.of("a", "(")))
                .body("rows[0].f[3].v.v", equalTo(List.of("1", "2")))
                .body("rows[0].f[4].v.v", equalTo(List.of("1", "2")));
        query(Map.of("query", "SELECT REGEXP_EXTRACT('a1', CASE WHEN TRUE THEN r'a([0-9])' ELSE r'a([0-9])' END) AS a,"
                + " REGEXP_EXTRACT_ALL('a1a2', CASE WHEN TRUE THEN r'a([0-9])' ELSE r'a([0-9])' END) AS b", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1"))
                .body("rows[0].f[1].v.v", equalTo(List.of("1", "2")));
        query(Map.of("query", "SELECT REGEXP_EXTRACT_ALL('a1b22', @p) AS a, REGEXP_EXTRACT('a1b22', @p) AS b",
                "useLegacySql", false, "parameterMode", "NAMED",
                "queryParameters", List.of(Map.of("name", "p", "parameterType", Map.of("type", "STRING"),
                        "parameterValue", Map.of("value", "[a-z]([0-9]+)")))))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("1", "22")))
                .body("rows[0].f[1].v", equalTo("1"));
    }

    @Test
    @Order(5)
    void nanSortsJustAfterNullLikeBigQuery() {
        String values = "UNNEST([1.0, CAST('NaN' AS FLOAT64), NULL, -1.0]) x";
        String[][] cases = {
                {"SELECT x FROM " + values + " ORDER BY x", "NULL,NaN,-1.0,1.0"},
                {"SELECT x FROM " + values + " ORDER BY x DESC", "1.0,-1.0,NaN,NULL"},
                {"SELECT x FROM " + values + " ORDER BY x ASC NULLS LAST", "NaN,-1.0,1.0,NULL"},
                {"SELECT x FROM " + values + " ORDER BY x DESC NULLS FIRST", "NULL,1.0,-1.0,NaN"},
                {"SELECT DISTINCT x FROM " + values + " ORDER BY x", "NULL,NaN,-1.0,1.0"},
                {"SELECT x * 2 AS y FROM " + values + " ORDER BY y", "NULL,NaN,-2.0,2.0"},
                {"SELECT x FROM (SELECT x, ROW_NUMBER() OVER (ORDER BY x) AS r FROM " + values + ") ORDER BY r",
                        "NULL,NaN,-1.0,1.0"},
        };
        for (String[] c : cases) {
            List<String> expected = Arrays.stream(c[1].split(",")).map(v -> v.equals("NULL") ? null : v).toList();
            query(Map.of("query", c[0], "useLegacySql", false))
                    .then().statusCode(200)
                    .body("rows.collect { it.f[0].v }", equalTo(expected));
        }
        query(Map.of("query", "SELECT ARRAY_AGG(x IGNORE NULLS ORDER BY x) AS a FROM " + values, "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.v", equalTo(List.of("NaN", "-1.0", "1.0")));
        query(Map.of("query", "CREATE TABLE shop.nan_values AS SELECT x FROM " + values, "useLegacySql", false))
                .then().statusCode(200);
        query(Map.of("query", "SELECT x FROM shop.nan_values ORDER BY x", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.collect { it.f[0].v }", equalTo(Arrays.asList(null, "NaN", "-1.0", "1.0")));
        query(Map.of("query", "SELECT COALESCE(x, 0) AS x FROM UNNEST([NULL, -1, CAST('NaN' AS FLOAT64)]) x ORDER BY x",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.collect { it.f[0].v }", equalTo(List.of("NaN", "-1.0", "0.0")));
        query(Map.of("query", "SELECT x * 2 `y` FROM UNNEST([1.0, CAST('NaN' AS FLOAT64), NULL]) x ORDER BY y",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.collect { it.f[0].v }", equalTo(Arrays.asList(null, "NaN", "2.0")));
        query(Map.of("query", "SELECT SUM(x) OVER (w RANGE BETWEEN 1 PRECEDING AND CURRENT ROW) AS s,"
                + " CAST('NaN' AS FLOAT64) AS n FROM UNNEST([1, 2]) x WINDOW w AS (ORDER BY x)", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.collect { it.f[0].v }", equalTo(List.of("1", "3")));
        for (String sql : List.of("SELECT x FROM " + values + " GROUP BY ALL ORDER BY x",
                "SELECT x, COUNT(*) AS c FROM " + values + " GROUP BY ALL ORDER BY x DESC",
                "SELECT x * 2 AS y FROM " + values + " GROUP BY ALL ORDER BY y")) {
            query(Map.of("query", sql, "useLegacySql", false))
                    .then().statusCode(200)
                    .body("rows", hasSize(4));
        }
        List<String> random = query(Map.of("query", "SELECT IF(RAND() < 0.5, NULL, 1.0) AS y, CAST('NaN' AS FLOAT64) AS n"
                + " FROM UNNEST(GENERATE_ARRAY(1, 100)) i ORDER BY y", "useLegacySql", false))
                .then().statusCode(200)
                .extract().path("rows.collect { it.f[0].v }");
        int nulls = Collections.frequency(random, null);
        assertEquals(Collections.nCopies(nulls, (String) null), random.subList(0, nulls));
        query(Map.of("query", "SELECT r FROM (SELECT STRUCT(x AS `limit`) AS r FROM UNNEST([1.0, CAST('NaN' AS FLOAT64)]) x)"
                + " ORDER BY r.limit", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.collect { it.f[0].v.f[0].v }", equalTo(List.of("NaN", "1.0")));
        query(Map.of("query", "SELECT t.rows AS r FROM UNNEST([STRUCT(1.0 AS `rows`), STRUCT(CAST('NaN' AS FLOAT64) AS `rows`)]) t"
                + " ORDER BY t.rows DESC", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows.collect { it.f[0].v }", equalTo(List.of("1.0", "NaN")));
    }

    @Test
    @Order(5)
    void typedStructConstructorBuildsTheDeclaredFieldsLikeBigQuery() {
        query(Map.of("query", "SELECT STRUCT<n INT64, m STRING>(x, 'a') AS s FROM UNNEST([2, 1]) x ORDER BY s.n",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("n", "m")))
                .body("rows.collect { it.f[0].v.f.v }", equalTo(List.of(List.of("1", "a"), List.of("2", "a"))));
        query(Map.of("query", "SELECT STRUCT<n FLOAT64>(x) AS s FROM UNNEST([2, 1]) x ORDER BY s.n", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].fields.type", equalTo(List.of("FLOAT")))
                .body("rows.collect { it.f[0].v.f[0].v }", equalTo(List.of("1.0", "2.0")));
        query(Map.of("query", "SELECT STRUCT<INT64, STRING>(x, 'a') AS s FROM UNNEST([1]) x", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("_field_1", "_field_2")))
                .body("rows[0].f[0].v.f.v", equalTo(List.of("1", "a")));
        query(Map.of("query", "SELECT STRUCT<a STRUCT<b INT64>, c ARRAY<STRING>>(STRUCT(x AS b), ['z']) AS s"
                + " FROM UNNEST([1]) x", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.f[0].v.f[0].v", equalTo("1"))
                .body("rows[0].f[0].v.f[1].v.v", equalTo(List.of("z")));
        query(Map.of("query", "SELECT TO_JSON_STRING(STRUCT<n INT64, m STRING>(1, 'x')) AS v,"
                + " IF(TRUE, STRUCT<n INT64, m STRING>(1, 'x'), NULL).m AS w", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("{\"n\":1,\"m\":\"x\"}", "x")));
        query(Map.of("query", "SELECT STRUCT<n INT64, n STRING>(1, 'a') AS s", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("n", "_field_2")))
                .body("rows[0].f[0].v.f.v", equalTo(List.of("1", "a")));
        query(Map.of("query", "SELECT STRUCT<n INT64>(1) AS s FROM (SELECT 1 AS n) a CROSS JOIN (SELECT 2 AS n) b",
                "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v.f[0].v", equalTo("1"));
        for (String[] c : new String[][] {
                {"SELECT STRUCT<n INT64>(,1) AS s", "Syntax error: Unexpected \",\""},
                {"SELECT STRUCT<n INT64, m INT64>(1,,2) AS s", "Syntax error: Unexpected \",\""},
                {"SELECT STRUCT<n INT64>(1,) AS s", "Syntax error: Unexpected \")\""},
                {"SELECT STRUCT<n INT64, m STRING>(1) AS s", "STRUCT type has 2 fields but constructor call has 1 fields"},
                {"SELECT STRUCT<n INT64>(1, 'x') AS s", "STRUCT type has 1 fields but constructor call has 2 fields"},
        }) {
            query(Map.of("query", c[0], "useLegacySql", false))
                    .then().statusCode(400)
                    .body("error.message", equalTo(c[1]));
        }
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
                .body("rows", hasSize(2));
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

    @Test
    @Order(5)
    void duplicateOutputNamesTakeBigQuerySuffixes() {
        assertResultColumns("SELECT 5 AS b, 6 AS b", List.of("b", "b_1"), List.of("5", "6"));
        assertResultColumns("SELECT 5 AS b, 6 AS b_1, 7 AS b", List.of("b", "b_1", "b_2"),
                List.of("5", "6", "7"));
        assertResultColumns("SELECT 5 AS f0_, 6 AS f0_", List.of("f0_", "f0__1"), List.of("5", "6"));
        assertResultColumns("SELECT 5 AS b, 6 AS b, 1 + 1", List.of("b", "b_1", "f0_"), List.of("5", "6", "2"));
        assertResultColumns("SELECT 5 AS b, 6 AS b FROM (SELECT 1) ORDER BY 1", List.of("b", "b_1"),
                List.of("5", "6"));
        assertResultColumns("SELECT * FROM (SELECT 5 AS b, 7 AS b, 6 AS b_1)", List.of("b", "b_2", "b_1"),
                List.of("5", "7", "6"));
        assertResultColumns("SELECT 9 AS b, * FROM (SELECT 5 AS b, 7 AS b, 6 AS b_1)",
                List.of("b", "b_2", "b_3", "b_1"), List.of("9", "5", "7", "6"));
        assertResultColumns("SELECT * FROM (SELECT 5 AS b, 6 AS b)", List.of("b", "b_1"), List.of("5", "6"));
        assertResultColumns("SELECT * EXCEPT(b) FROM (SELECT 5 AS b, 7 AS b, 6 AS b_1)",
                List.of("b_1"), List.of("6"));
        assertResultColumns("SELECT t.* EXCEPT(b) FROM (SELECT 5 AS b, 7 AS b, 6 AS b_1) t",
                List.of("b_1"), List.of("6"));
        assertResultColumns("SELECT * EXCEPT(b_1) FROM (SELECT 5 AS b, 7 AS b, 6 AS b_1)",
                List.of("b", "b_1"), List.of("5", "7"));
        query("{\"query\": \"SELECT * REPLACE(9 AS b)"
                + " FROM (SELECT 5 AS b, 7 AS b, 6 AS b_1)\", \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", containsString(
                        "Column b in SELECT * REPLACE list is ambiguous"));
        query("{\"query\": \"SELECT * EXCEPT(b_2)"
                + " FROM (SELECT 5 AS b, 7 AS b, 6 AS b_1)\", \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", containsString(
                        "Column b_2 in SELECT * EXCEPT list does not exist"));
        assertResultColumns("WITH x AS (SELECT 5 AS b, 7 AS b, 6 AS b_1) SELECT * FROM x",
                List.of("b", "b_2", "b_1"), List.of("5", "7", "6"));
        query("""
                {"query": "SELECT 5 AS b, 6 AS b", "dryRun": true, "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "b_1")))
                .body("rows", nullValue());
        query(Map.of("query", "CREATE TABLE shop.dup_as_select AS SELECT 5 AS b, 6 AS b",
                        "useLegacySql", false))
                .then().statusCode(400)
                .body("error.message", containsString("CREATE TABLE has columns with duplicate name b"));
        query(Map.of("query", "CREATE VIEW shop.dup_view AS SELECT 5 AS b, 6 AS b", "useLegacySql", false))
                .then().statusCode(400)
                .body("error.message", containsString("CREATE VIEW has columns with duplicate name b"));
        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "dup_api_view"},
                 "view": {"query": "SELECT 5 AS b, 6 AS b", "useLegacySql": false}}
                """).when().post(BASE + "/datasets/shop/tables").then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "b_1")));
        query(Map.of("query", "SELECT * FROM shop.dup_api_view", "useLegacySql", false))
                .then().statusCode(400)
                .body("error.message", containsString(
                        "Duplicate column name 'b' in definition of view 'shop.dup_api_view'"));
    }

    @Test
    @Order(5)
    void pivotUnpivotAndMultiSourceStarsMatchBigQuery() {
        assertResultColumns("SELECT * FROM (SELECT 8 AS c) UNPIVOT(v FOR n IN (c))",
                List.of("v", "n"), List.of("8", "c"));
        query(Map.of("query", "SELECT * FROM (SELECT 5 AS b, 7 AS b, 8 AS c, 9 AS d)"
                        + " UNPIVOT(v FOR n IN (c, d))", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "b_1", "v", "n")))
                .body("rows[0].f.v", equalTo(List.of("5", "7", "8", "c")))
                .body("rows[1].f.v", equalTo(List.of("5", "7", "9", "d")));
        assertResultColumns("SELECT * EXCEPT(v) FROM (SELECT 8 AS c) UNPIVOT(v FOR n IN (c))",
                List.of("n"), List.of("c"));
        assertResultColumns("SELECT * REPLACE(9 AS v) FROM (SELECT 8 AS c) UNPIVOT(v FOR n IN (c))",
                List.of("v", "n"), List.of("9", "c"));
        assertResultColumns("SELECT * EXCEPT(n) REPLACE(9 AS v)"
                        + " FROM (SELECT 8 AS c) UNPIVOT(v FOR n IN (c))",
                List.of("v"), List.of("9"));
        query("{\"query\": \"SELECT * REPLACE(9 AS b)"
                + " FROM (SELECT 5 AS b, 7 AS b, 8 AS c) UNPIVOT(v FOR n IN (c))\","
                + " \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", containsString("Column b in SELECT * REPLACE list is ambiguous"));
        assertResultColumns("SELECT * FROM (SELECT 1 AS a1, 2 AS a2, 3 AS b1, 4 AS b2, 5 AS r)"
                        + " UNPIVOT((v1, v2) FOR n IN ((a1, a2), (b1, b2)))",
                List.of("r", "v1", "v2", "n"), List.of("5", "1", "2", "a1_a2"));
        assertResultColumns("SELECT * FROM (SELECT 5 AS b, 7 AS b, 'x' AS k, 1 AS v)"
                        + " PIVOT(SUM(v) FOR k IN ('x'))",
                List.of("b", "b_1", "x"), List.of("5", "7", "1"));
        assertResultColumns("SELECT * FROM (SELECT 'x' AS k, 1 AS v)"
                        + " PIVOT(SUM(v) AS total, MAX(v) AS m FOR k IN ('x'))",
                List.of("total_x", "m_x"), List.of("1", "1"));
        assertResultColumns("SELECT * EXCEPT(m_x) FROM (SELECT 'x' AS k, 1 AS v)"
                        + " PIVOT(SUM(v) AS total, MAX(v) AS m FOR k IN ('x'))",
                List.of("total_x"), List.of("1"));
        for (String sql : List.of(
                "SELECT * EXCEPT(nope) FROM (SELECT 'x' AS k, 1 AS v) PIVOT(SUM(v) FOR k IN ('x'))",
                "SELECT * EXCEPT(k) FROM (SELECT 'x' AS k, 1 AS v) PIVOT(SUM(v) FOR k IN ('x'))")) {
            query("{\"query\": \"" + sql + "\", \"useLegacySql\": false}")
                    .then().statusCode(400)
                    .body("error.message", containsString("in SELECT * EXCEPT list does not exist"));
        }
        assertResultColumns("SELECT * EXCEPT(b) FROM (SELECT 5 AS b, 7 AS b, 'x' AS k, 1 AS v)"
                        + " PIVOT(SUM(v) FOR k IN ('x'))",
                List.of("x"), List.of("1"));
        assertResultColumns("SELECT * EXCEPT(x) FROM (SELECT 5 AS b, 7 AS b, 'x' AS k, 1 AS v)"
                        + " PIVOT(SUM(v) FOR k IN ('x'))",
                List.of("b", "b_1"), List.of("5", "7"));
        query("{\"query\": \"SELECT * EXCEPT(b_1)"
                + " FROM (SELECT 5 AS b, 7 AS b, 'x' AS k, 1 AS v) PIVOT(SUM(v) FOR k IN ('x'))\","
                + " \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", containsString(
                        "Column b_1 in SELECT * EXCEPT list does not exist"));
        for (String sql : List.of(
                "SELECT * FROM (SELECT 5 AS b, 7 AS b, 8 AS c) UNPIVOT(v FOR n IN (b))",
                "SELECT * FROM (SELECT 5 AS b, 7 AS b, 'x' AS k, 1 AS v) PIVOT(SUM(b) FOR k IN ('x'))",
                "SELECT * FROM (SELECT 5 AS b, 7 AS b, 'x' AS k, 1 AS v) PIVOT(SUM(v) FOR b IN (5, 7))")) {
            query("{\"query\": \"" + sql + "\", \"useLegacySql\": false}")
                    .then().statusCode(400)
                    .body("error.message", containsString("Column name b is ambiguous"));
        }
        assertResultColumns("SELECT * EXCEPT(b) FROM (SELECT 5 AS b, 7 AS b) x, (SELECT 1 AS c) y",
                List.of("c"), List.of("1"));
        assertResultColumns("SELECT * EXCEPT(c) FROM (SELECT 5 AS b, 7 AS b) x, (SELECT 1 AS c) y",
                List.of("b", "b_1"), List.of("5", "7"));
        assertResultColumns("SELECT x.* EXCEPT(b)"
                        + " FROM (SELECT 5 AS b, 7 AS b, 8 AS c) x, (SELECT 1 AS y) y2",
                List.of("c"), List.of("8"));
        assertResultColumns("SELECT y2.* EXCEPT(c)"
                        + " FROM (SELECT 5 AS b) x, (SELECT 1 AS c, 2 AS c, 3 AS d) y2",
                List.of("d"), List.of("3"));
    }

    @Test
    @Order(5)
    void usingJoinsAndUnpivotInAJoinMatchBigQuery() {
        assertResultColumns("SELECT * EXCEPT(id) FROM (SELECT 1 AS id, 2 AS x) a"
                + " JOIN (SELECT 1 AS id, 3 AS y) b USING(id)", List.of("x", "y"), List.of("2", "3"));
        assertResultColumns("SELECT * EXCEPT(c) FROM (SELECT 1 AS id, 2 AS c, 3 AS c) a"
                + " JOIN (SELECT 1 AS id, 4 AS d) b USING(id)",
                List.of("id", "d"), List.of("1", "4"));
        assertResultColumns("SELECT * EXCEPT(id, c) FROM (SELECT 1 AS id, 2 AS c, 3 AS c) a"
                + " JOIN (SELECT 1 AS id, 4 AS d) b USING(id)", List.of("d"), List.of("4"));
        assertResultColumns("SELECT a.* EXCEPT(c) FROM (SELECT 1 AS id, 2 AS c, 3 AS c) a"
                + " JOIN (SELECT 1 AS id, 4 AS d) b USING(id)", List.of("id"), List.of("1"));
        query(Map.of("query", "SELECT * EXCEPT(id) FROM (SELECT 1 AS id, 2 AS x) a"
                + " RIGHT JOIN (SELECT 2 AS id, 3 AS y) b USING(id)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x", "y")))
                .body("rows[0].f.v", equalTo(Arrays.asList(null, "3")));
        assertResultColumns("SELECT * FROM (SELECT 1 AS m) a,"
                        + " (SELECT 5 AS b, 7 AS b, 8 AS c) u UNPIVOT(v FOR n IN (c))",
                List.of("m", "b", "b_1", "v", "n"), List.of("1", "5", "7", "8", "c"));
        assertResultColumns("SELECT * FROM (SELECT 1 AS m) a"
                        + " JOIN (SELECT 5 AS b, 7 AS b, 8 AS c) u UNPIVOT(v FOR n IN (c)) ON a.m > 0",
                List.of("m", "b", "b_1", "v", "n"), List.of("1", "5", "7", "8", "c"));
        assertResultColumns("SELECT * EXCEPT(v) FROM (SELECT 1 AS m) a,"
                        + " (SELECT 5 AS b, 7 AS b, 8 AS c) u UNPIVOT(v FOR n IN (c))",
                List.of("m", "b", "b_1", "n"), List.of("1", "5", "7", "c"));
    }

    @Test
    @Order(5)
    void greptileFollowUpsMatchBigQuery() {
        assertResultColumns("SELECT * EXCEPT(b) REPLACE(9 AS c)"
                        + " FROM (SELECT 5 AS b, 7 AS b, 8 AS c)",
                List.of("c"), List.of("9"));
        assertResultColumns("SELECT * EXCEPT(b) REPLACE(9 AS c)"
                        + " FROM (SELECT 5 AS b, 7 AS b, 8 AS c, 3 AS c_1)",
                List.of("c", "c_1"), List.of("9", "3"));
        query("{\"query\": \"SELECT * EXCEPT(b) REPLACE(9 AS b)"
                + " FROM (SELECT 5 AS b, 7 AS b, 8 AS c)\", \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", containsString(
                        "Column b cannot occur in both SELECT * EXCEPT and REPLACE"));

        assertResultColumns("SELECT 5 AS x, 6 AS x, ROW_NUMBER() OVER (ORDER BY x) AS rn"
                        + " FROM (SELECT 1 AS x)",
                List.of("x", "x_1", "rn"), List.of("5", "6", "1"));
        query("{\"query\": \"SELECT 5 AS x, 6 AS x FROM (SELECT 1 AS x)"
                + " ORDER BY ROW_NUMBER() OVER (ORDER BY x)\", \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", containsString("Column name x is ambiguous"));
        query("{\"query\": \"SELECT 5 AS x, 6 AS x, ROW_NUMBER() OVER (ORDER BY x) AS rn"
                + " FROM (SELECT 2 AS x, 3 AS x)\", \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", containsString("Column name x is ambiguous"));
        assertResultColumns("SELECT 1 AS x FROM (SELECT 2 AS x, 3 AS x)"
                        + " ORDER BY ROW_NUMBER() OVER (ORDER BY x)",
                List.of("x"), List.of("1"));
        assertResultColumns("SELECT 1 AS x FROM (SELECT 2 AS x, 3 AS x)"
                        + " QUALIFY ROW_NUMBER() OVER (ORDER BY x) = 1",
                List.of("x"), List.of("1"));

        assertResultColumns("SELECT 1 AS b, COUNT(*) AS n FROM (SELECT 2 AS b, 3 AS b) GROUP BY b",
                List.of("b", "n"), List.of("1", "1"));
        for (String sql : List.of(
                "SELECT COUNT(*) FROM (SELECT 2 AS b, 3 AS b) GROUP BY b",
                "SELECT 1 AS b, COUNT(*) FROM (SELECT 2 AS b, 3 AS b) GROUP BY b + 0")) {
            query("{\"query\": \"" + sql + "\", \"useLegacySql\": false}")
                    .then().statusCode(400)
                    .body("error.message", containsString("Column name b is ambiguous"));
        }
        query("{\"query\": \"SELECT 1 AS b, COUNT(*) FROM (SELECT 2 AS b, 3 AS b) t GROUP BY t.b\","
                + " \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", containsString("Name b is ambiguous inside t"));
        query("{\"query\": \"SELECT 1 AS y, COUNT(*) FROM (SELECT 1 AS b, 2 AS b) GROUP BY b_1\","
                + " \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", containsString("Unrecognized name: b_1"));

        assertResultColumns("SELECT * FROM (SELECT 1 AS a) UNPIVOT(v FOR n IN(a)),"
                        + " (SELECT 2 AS b) UNPIVOT(w FOR m IN(b))",
                List.of("v", "n", "w", "m"), List.of("1", "a", "2", "b"));
        assertResultColumns("SELECT * FROM (SELECT 1 AS a) UNPIVOT(v FOR n IN(a)),"
                        + " (SELECT 2 AS b)",
                List.of("v", "n", "b"), List.of("1", "a", "2"));
    }

    @Test
    @Order(5)
    void sourceReferencesRejectADuplicatedColumnName() {
        for (String sql : List.of(
                "SELECT b FROM (SELECT 5 AS b, 7 AS b)",
                "SELECT SUM(b) FROM (SELECT 5 AS b, 7 AS b)",
                "SELECT ROW_NUMBER() OVER (PARTITION BY b) FROM (SELECT 5 AS b, 7 AS b)",
                "SELECT * FROM (SELECT 5 AS b, 7 AS b) WHERE b > 0",
                "SELECT * FROM (SELECT 5 AS b, 7 AS b) x JOIN (SELECT 1 AS c) y ON b > 0",
                "SELECT COUNT(*) FROM (SELECT 5 AS b, 7 AS b) GROUP BY b + 0")) {
            query("{\"query\": \"" + sql + "\", \"useLegacySql\": false}")
                    .then().statusCode(400)
                    .body("error.message", containsString("Column name b is ambiguous"));
        }
        query("{\"query\": \"SELECT t.b FROM (SELECT 5 AS b, 7 AS b) t\", \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", containsString("Name b is ambiguous inside t"));
        query("{\"query\": \"SELECT 5 AS b, 6 AS b FROM (SELECT 1 AS c) WHERE b > 0\","
                + " \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", containsString("Unrecognized name: b"));
        query("{\"query\": \"SELECT b_2 FROM (SELECT 5 AS b, 7 AS b, 6 AS b_1)\","
                + " \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", containsString("Unrecognized name: b_2"));
        assertResultColumns("SELECT b_1 FROM (SELECT 5 AS b, 7 AS b, 6 AS b_1) WHERE b_1 > 0",
                List.of("b_1"), List.of("6"));
        assertResultColumns("SELECT id FROM (SELECT 1 AS id, 2 AS x) a"
                + " JOIN (SELECT 1 AS id, 3 AS y) b USING(id)", List.of("id"), List.of("1"));
    }

    @Test
    @Order(5)
    void orderByRejectsARepeatedSelectNameAndAGeneratedSuffix() {
        for (String sql : List.of(
                "SELECT 5 AS b, 6 AS b FROM (SELECT 1 AS c) ORDER BY b",
                "SELECT 5 AS b, 6 AS b FROM (SELECT 1 AS c) ORDER BY IF(b > 0, 1, 0)",
                "SELECT 5 AS b, t.s.* EXCEPT(a) FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t"
                        + " GROUP BY ALL ORDER BY b",
                "SELECT t.* FROM (SELECT 5 AS b, 7 AS b, 6 AS b_1) t ORDER BY b",
                "SELECT ROW_NUMBER() OVER (ORDER BY b) FROM (SELECT 5 AS b, 6 AS b)",
                "SELECT 5 AS b, 6 AS b FROM (SELECT 1 AS c) HAVING b > 0",
                "SELECT 5 AS b, 6 AS b FROM (SELECT 1 AS c) QUALIFY ROW_NUMBER() OVER (ORDER BY b) > 0")) {
            query("{\"query\": \"" + sql + "\", \"useLegacySql\": false}")
                    .then().statusCode(400)
                    .body("error.message", containsString("Column name b is ambiguous"));
        }
        query("{\"query\": \"SELECT 5 AS b, 6 AS b FROM (SELECT 1 AS c) GROUP BY b\","
                + " \"useLegacySql\": false}")
                .then().statusCode(400)
                .body("error.message", containsString("Name b in GROUP BY clause is ambiguous;"
                        + " it may refer to multiple columns in the SELECT-list"));
        for (String sql : List.of(
                "SELECT 5 AS b, t.s.* EXCEPT(a) FROM (SELECT STRUCT(1 AS a, 2 AS b, 3 AS b_1) s) t"
                        + " ORDER BY b_2",
                "SELECT 5 AS b, t.s.* EXCEPT(a) FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t"
                        + " GROUP BY ALL ORDER BY b_1",
                "SELECT * FROM (SELECT 5 AS b, 6 AS b) ORDER BY b_1")) {
            query("{\"query\": \"" + sql + "\", \"useLegacySql\": false}")
                    .then().statusCode(400)
                    .body("error.message", containsString("Unrecognized name"));
        }
        assertResultColumns("SELECT 5 AS b, 6 AS b FROM (SELECT 1 AS b_1) ORDER BY b_1",
                List.of("b", "b_1"), List.of("5", "6"));
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
                .body("schema.fields.name", equalTo(List.of("n")))
                .body("rows[0].f[0].v", equalTo("2"));

        query("""
                {"query": "SELECT AS STRUCT x + 1 FROM UNNEST([3, 1]) x ORDER BY 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields", hasSize(1))
                .body("rows.f.v", equalTo(List.of(List.of("2"), List.of("4"))));
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
    void selectAsStructNestedFunctionsKeepComputedFieldWithStarExcept() {
        Response result = query(Map.of("query", """
                CREATE TEMP FUNCTION add_digits(digit_a STRING, digit_b STRING) RETURNS STRING AS (
                  CAST(MOD((CAST(digit_a AS INT64) + CAST(digit_b AS INT64)), 10) AS STRING)
                );
                CREATE TEMP FUNCTION reverse_suffix(value STRING) RETURNS STRING AS (
                  REVERSE(SUBSTR(value, 2, 10))
                );
                CREATE TEMP FUNCTION reverse_identifier(value STRING) RETURNS STRING AS (
                  CONCAT(SUBSTR(value, 1, 1), reverse_suffix(value))
                );
                CREATE TEMP FUNCTION encode_identifier(value STRING, code STRING) RETURNS STRING AS (
                  CONCAT(
                    SUBSTR(value, 1, 1),
                    add_digits(SUBSTR(value, 2, 1), SUBSTR(code, 1, 1)),
                    add_digits(SUBSTR(value, 3, 1), SUBSTR(code, 2, 1)),
                    add_digits(SUBSTR(value, 4, 1), SUBSTR(code, 3, 1)),
                    add_digits(SUBSTR(value, 5, 1), SUBSTR(code, 1, 1)),
                    add_digits(SUBSTR(value, 6, 1), SUBSTR(code, 2, 1)),
                    add_digits(SUBSTR(value, 7, 1), SUBSTR(code, 3, 1)),
                    add_digits(SUBSTR(value, 8, 1), SUBSTR(code, 1, 1)),
                    add_digits(SUBSTR(value, 9, 1), SUBSTR(code, 2, 1)),
                    add_digits(SUBSTR(value, 10, 1), SUBSTR(code, 3, 1))
                  )
                );
                CREATE TEMP FUNCTION normalize_identifier(value STRING, code STRING) RETURNS STRING AS (
                  CASE WHEN REGEXP_CONTAINS(value, r"^[0-9]{10}$")
                    THEN encode_identifier(reverse_identifier(value), code) ELSE NULL END
                );
                CREATE TEMP FUNCTION denormalize_identifier(value STRING, code STRING) RETURNS STRING AS (
                  CONCAT(SUBSTR(value, 1, 1), REVERSE(CONCAT(
                    CAST(MOD(CAST(SUBSTR(value, 2, 1) AS INT64) + 10 - CAST(SUBSTR(code, 1, 1) AS INT64), 10) AS STRING),
                    CAST(MOD(CAST(SUBSTR(value, 3, 1) AS INT64) + 10 - CAST(SUBSTR(code, 2, 1) AS INT64), 10) AS STRING),
                    CAST(MOD(CAST(SUBSTR(value, 4, 1) AS INT64) + 10 - CAST(SUBSTR(code, 3, 1) AS INT64), 10) AS STRING),
                    CAST(MOD(CAST(SUBSTR(value, 5, 1) AS INT64) + 10 - CAST(SUBSTR(code, 1, 1) AS INT64), 10) AS STRING),
                    CAST(MOD(CAST(SUBSTR(value, 6, 1) AS INT64) + 10 - CAST(SUBSTR(code, 2, 1) AS INT64), 10) AS STRING),
                    CAST(MOD(CAST(SUBSTR(value, 7, 1) AS INT64) + 10 - CAST(SUBSTR(code, 3, 1) AS INT64), 10) AS STRING),
                    CAST(MOD(CAST(SUBSTR(value, 8, 1) AS INT64) + 10 - CAST(SUBSTR(code, 1, 1) AS INT64), 10) AS STRING),
                    CAST(MOD(CAST(SUBSTR(value, 9, 1) AS INT64) + 10 - CAST(SUBSTR(code, 2, 1) AS INT64), 10) AS STRING),
                    CAST(MOD(CAST(SUBSTR(value, 10, 1) AS INT64) + 10 - CAST(SUBSTR(code, 3, 1) AS INT64), 10) AS STRING)
                  ))));
                CREATE TEMP FUNCTION process_records(records ANY TYPE) AS ((
                  ARRAY(
                    SELECT AS STRUCT normalize_identifier(denormalize_identifier(id, '123'), '123') AS id,
                      * EXCEPT(id)
                    FROM UNNEST(records)
                    WHERE kind IN ('A', 'B')
                    UNION ALL
                    SELECT AS STRUCT *
                    FROM UNNEST(records)
                    WHERE NOT kind IN ('A', 'B')
                  )
                ));
                SELECT process_records([
                  STRUCT('0123456789' AS id, 'A' AS kind, 'Record' AS label),
                  STRUCT('bad' AS id, 'C' AS kind, 'Other' AS label)
                ]) AS result;
                """, "useLegacySql", false));
        result.then().log().ifValidationFails().statusCode(200)
                .body("schema.fields[0].type", equalTo("RECORD"))
                .body("schema.fields[0].mode", equalTo("REPEATED"))
                .body("schema.fields[0].fields.name", equalTo(List.of("id", "kind", "label")))
                .body("rows", hasSize(1));
        List<List<String>> records = result.jsonPath().getList("rows[0].f[0].v.v.f.v");
        assertEquals(List.of("0123456789", "A", "Record"), records.stream()
                .filter(record -> "A".equals(record.get(1))).findFirst().orElseThrow());
        assertEquals(List.of("bad", "C", "Other"), records.stream()
                .filter(record -> "C".equals(record.get(1))).findFirst().orElseThrow());
    }

    @Test
    @Order(5)
    void mixedStructStarsExpandIntoSiblingFields() {
        for (String sql : List.of(
                "SELECT AS STRUCT t.*, 2 AS y FROM (SELECT 1 AS x) t",
                "SELECT AS STRUCT t.*, u.* FROM (SELECT 1 AS x) t CROSS JOIN (SELECT 2 AS y) u",
                "SELECT AS STRUCT t.* FROM (SELECT 1 AS x, 2 AS y) t",
                "SELECT AS STRUCT t.* EXCEPT(secret), 2 AS y FROM (SELECT 1 AS x, 9 AS secret) t",
                "SELECT DISTINCT AS STRUCT t.*, 2 y FROM (SELECT 1 AS x UNION ALL SELECT 1 AS x) t ORDER BY x LIMIT 1")) {
            query(Map.of("query", sql, "useLegacySql", false))
                    .then().log().ifValidationFails().statusCode(200)
                    .body("schema.fields.name", equalTo(List.of("x", "y")))
                    .body("rows", hasSize(1))
                    .body("rows[0].f.v", equalTo(List.of("1", "2")));
        }
        for (String sql : List.of(
                "SELECT (SELECT AS STRUCT t.*, 2 AS y FROM (SELECT 1 AS x) t) AS record",
                "SELECT (SELECT AS STRUCT t.*, u.* FROM (SELECT 1 AS x) t CROSS JOIN (SELECT 2 AS y) u) AS record",
                "SELECT (SELECT AS STRUCT t.* EXCEPT(secret), 2 AS y FROM (SELECT 1 AS x, 9 AS secret) t) AS record")) {
            query(Map.of("query", sql, "useLegacySql", false))
                    .then().log().ifValidationFails().statusCode(200)
                    .body("schema.fields", hasSize(1))
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
                    .body("schema.fields.name", equalTo(List.of("sample", "y")))
                    .body("rows[0].f.v", equalTo(List.of("1", "2")));
        }
        query(Map.of("query", "SELECT AS STRUCT t.*, 2 AS y FROM"
                        + " (SELECT STRUCT(MAX(x) AS n) AS sample FROM UNNEST([1]) x HAVING sample.n = 1) t",
                        "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("sample", "y")))
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v.f[0].v", equalTo("1"))
                .body("rows[0].f[1].v", equalTo("2"));
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
                .body("rows[0].f[0].v", equalTo("1"));

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

        query(Map.of("query", "SELECT ARRAY(SELECT AS STRUCT * FROM `shop.reviewed_star_*`) AS arr",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("id")));

        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "reviewed_partition"},
                 "timePartitioning": {"type": "DAY"},
                 "schema": {"fields": [
                   {"name": "id", "type": "INT64"},
                   {"name": "name", "type": "STRING"},
                   {"name": "secret", "type": "STRING"}
                 ]}}
                """).when().post(BASE + "/datasets/shop/tables").then().statusCode(200);
        given().contentType("application/json").body("""
                {"rows": [{"json": {"id": 1, "name": "ana", "secret": "hidden"}}]}
                """).when().post(BASE + "/datasets/shop/tables/reviewed_partition/insertAll")
                .then().statusCode(200).body("insertErrors", nullValue());

        query(Map.of("query", """
                BEGIN
                  CREATE TEMP TABLE correlated_projection AS
                    SELECT id, (SELECT AS STRUCT input.* EXCEPT(id)) AS original
                    FROM shop.reviewed_partition input;
                  CREATE TEMP TABLE result AS
                    SELECT v.id, ANY_VALUE(v.original).* EXCEPT(secret), COUNT(*) AS secret
                    FROM correlated_projection v
                    GROUP BY v.id;
                  SELECT * FROM result;
                END
                """, "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "name", "secret")))
                .body("rows[0].f.v", equalTo(List.of("1", "ana", "1")));

        query(Map.of("query", "SELECT q.original FROM (SELECT (SELECT AS STRUCT input.* EXCEPT(id)) AS original"
                        + " FROM shop.reviewed_partition input) q", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("name", "secret")))
                .body("rows[0].f[0].v.f.v", equalTo(List.of("ana", "hidden")));

        query(Map.of("query", "SELECT ((SELECT AS STRUCT input.* EXCEPT(id))) AS original"
                        + " FROM shop.reviewed_partition input", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("name", "secret")))
                .body("rows[0].f[0].v.f.v", equalTo(List.of("ana", "hidden")));

        query(Map.of("query", "CREATE VIEW shop.reviewed_partition_times AS"
                        + " SELECT _PARTITIONTIME AS pt FROM shop.reviewed_partition",
                        "useLegacySql", false))
                .then().statusCode(200);
        query(Map.of("query", "SELECT pt FROM shop.reviewed_partition_times", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("pt")))
                .body("rows[0].f[0].v", notNullValue());

        query(Map.of("query", "SELECT * EXCEPT(secret) REPLACE(id + 1 AS id)"
                        + " FROM shop.reviewed_partition", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "name")))
                .body("rows[0].f.v", equalTo(List.of("2", "ana")));

        query(Map.of("query", "SELECT * FROM (SELECT 'derived' AS marker) d"
                        + " CROSS JOIN shop.reviewed_partition e", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("marker", "id", "name", "secret")))
                .body("rows[0].f.v", equalTo(List.of("derived", "1", "ana", "hidden")));

        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "reviewed_plain"},
                 "schema": {"fields": [{"name": "marker", "type": "STRING"}]}}
                """).when().post(BASE + "/datasets/shop/tables").then().statusCode(200);
        given().contentType("application/json").body("""
                {"rows": [{"json": {"marker": "physical"}}]}
                """).when().post(BASE + "/datasets/shop/tables/reviewed_plain/insertAll")
                .then().statusCode(200).body("insertErrors", nullValue());
        query(Map.of("query", "SELECT * FROM shop.reviewed_plain p"
                        + " CROSS JOIN shop.reviewed_partition e", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("marker", "id", "name", "secret")))
                .body("rows[0].f.v", equalTo(List.of("physical", "1", "ana", "hidden")));

        query(Map.of("query", "SELECT * FROM (SELECT 2 AS id, 'left' AS label) d"
                        + " LEFT JOIN shop.reviewed_partition e USING(id)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "label", "name", "secret")))
                .body("rows[0].f[0].v", equalTo("2"))
                .body("rows[0].f[1].v", equalTo("left"));

        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "reviewed_left"},
                 "schema": {"fields": [
                   {"name": "id", "type": "INT64"},
                   {"name": "label", "type": "STRING"}
                 ]}}
                """).when().post(BASE + "/datasets/shop/tables").then().statusCode(200);
        given().contentType("application/json").body("""
                {"rows": [{"json": {"id": 2, "label": "left"}}]}
                """).when().post(BASE + "/datasets/shop/tables/reviewed_left/insertAll")
                .then().statusCode(200).body("insertErrors", nullValue());
        query(Map.of("query", "SELECT * FROM (SELECT * FROM shop.reviewed_left) d"
                        + " LEFT JOIN shop.reviewed_partition e USING(id)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "label", "name", "secret")))
                .body("rows[0].f[0].v", equalTo("2"))
                .body("rows[0].f[1].v", equalTo("left"));

        query(Map.of("query", "SELECT * FROM shop.reviewed_left l CROSS JOIN"
                        + " (SELECT DATE '2026-10-01' AS _PARTITIONDATE) d", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "label", "_PARTITIONDATE")))
                .body("rows[0].f.v", equalTo(List.of("2", "left", "2026-10-01")));

        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "reviewed_upper"},
                 "schema": {"fields": [
                   {"name": "ID", "type": "INT64"},
                   {"name": "name", "type": "STRING"}
                 ]}}
                """).when().post(BASE + "/datasets/shop/tables").then().statusCode(200);
        given().contentType("application/json").body("""
                {"rows": [{"json": {"ID": 1, "name": "ana"}}]}
                """).when().post(BASE + "/datasets/shop/tables/reviewed_upper/insertAll")
                .then().statusCode(200).body("insertErrors", nullValue());
        query(Map.of("query", "SELECT * FROM (SELECT 1 AS ID, 'left' AS label) d"
                        + " JOIN shop.reviewed_upper a USING(id)", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("ID", "label", "name")))
                .body("rows[0].f.v", equalTo(List.of("1", "left", "ana")));

        query(Map.of("query", "SELECT * FROM (SELECT 1 AS id) d"
                        + " JOIN shop.reviewed_partition a USING(id)"
                        + " JOIN shop.reviewed_partition b ON b.id = a.id", "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "name", "secret",
                        "id_1", "name_1", "secret_1")))
                .body("rows[0].f.v", equalTo(List.of("1", "ana", "hidden", "1", "ana", "hidden")));

        query(Map.of("query", "SELECT e.* REPLACE(id + 1 AS id),"
                        + " DATE '2026-10-01' AS _PARTITIONDATE FROM shop.reviewed_partition e",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "name", "secret", "_PARTITIONDATE")))
                .body("rows[0].f.v", equalTo(List.of("2", "ana", "hidden", "2026-10-01")));
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
                .body("rows[0].f[0].v", equalTo("2"));
    }

    @Test
    @Order(5)
    void selectAsStructFieldsRewriteNestedQueries() {
        query(Map.of("query", "SELECT AS STRUCT (SELECT 1 sample) AS child", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1"));
        query(Map.of("query", "SELECT AS STRUCT (SELECT total_x FROM (SELECT 'x' AS k, 5 AS v)"
                        + " PIVOT(SUM(v) AS total FOR k IN ('x'))) AS child", "useLegacySql", false))
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("5"));
    }

    @Test
    @Order(5)
    void selectAsStructExpandsIndexedRecordStars() {
        query(Map.of("query", "SELECT AS STRUCT [STRUCT(1 AS x, 2 AS y)][OFFSET(0)].*",
                        "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x", "y")))
                .body("schema.fields.type", equalTo(List.of("INTEGER", "INTEGER")))
                .body("rows[0].f.v", equalTo(List.of("1", "2")));
    }

    @Test
    @Order(5)
    void outermostSelectAsStructReturnsItsFieldsAsColumns() {
        for (String sql : List.of(
                "SELECT AS STRUCT 1 AS a, 2 AS b",
                "SELECT AS STRUCT x AS a, 2 AS b FROM UNNEST([1]) AS x",
                "SELECT AS STRUCT t.* FROM (SELECT 1 AS a, 2 AS b) t",
                "SELECT AS STRUCT [STRUCT(1 AS a, 2 AS b)][OFFSET(0)].*",
                "SELECT AS STRUCT x AS a, x * 2 AS b FROM UNNEST([2, 1]) x WHERE x < 2 ORDER BY a",
                "SELECT AS STRUCT COUNT(*) AS a, 2 AS b FROM UNNEST([1]) x HAVING a > 0 ORDER BY a",
                "SELECT AS STRUCT x AS a, COUNT(*) AS b FROM UNNEST([1, 1, 2]) x GROUP BY x HAVING COUNT(*) > 1")) {
            query(Map.of("query", sql, "useLegacySql", false))
                    .then().log().ifValidationFails().statusCode(200)
                    .body("schema.fields.name", equalTo(List.of("a", "b")))
                    .body("schema.fields.type", equalTo(List.of("INTEGER", "INTEGER")))
                    .body("rows", hasSize(1))
                    .body("rows[0].f.v", equalTo(List.of("1", "2")));
        }
        query(Map.of("query", "SELECT AS STRUCT 1 AS a, 2 AS b UNION ALL (SELECT AS STRUCT 3 AS a, 4 AS b)",
                        "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b")))
                .body("schema.fields.type", equalTo(List.of("INTEGER", "INTEGER")))
                .body("rows", hasSize(2));
        query(Map.of("query", "SELECT AS STRUCT 1 AS a UNION ALL SELECT AS STRUCT 2 AS a",
                        "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows", hasSize(2));
        query(Map.of("query", "SELECT (SELECT AS STRUCT 1 AS d, 2 AS e) AS s", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("s")))
                .body("schema.fields[0].type", equalTo("RECORD"))
                .body("schema.fields[0].fields.name", equalTo(List.of("d", "e")));
    }

    @Test
    @Order(5)
    void arraySubquerySelectAsStructKeepsAliasesForOrderBy() {
        query(Map.of("query", "SELECT ARRAY(SELECT AS STRUCT x AS a FROM UNNEST([1, 2]) AS x) AS arr",
                        "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields[0].name", equalTo("arr"))
                .body("schema.fields[0].mode", equalTo("REPEATED"))
                .body("schema.fields[0].fields.name", equalTo(List.of("a")))
                .body("rows[0].f[0].v.v.f.v", equalTo(List.of(List.of("1"), List.of("2"))));
        query(Map.of("query", "SELECT ARRAY(SELECT AS STRUCT x AS v, x * 2 AS d"
                        + " FROM UNNEST([3, 1, 2]) x ORDER BY d DESC) AS arr", "useLegacySql", false))
                .then().log().ifValidationFails().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("v", "d")))
                .body("rows[0].f[0].v.v.f.v",
                        equalTo(List.of(List.of("3", "6"), List.of("2", "4"), List.of("1", "2"))));
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
        query(Map.of("query", "SELECT ARRAY(SELECT AS STRUCT input.* EXCEPT(id)) AS arr"
                        + " FROM (SELECT 1 AS id, 'a' AS name UNION ALL SELECT 2, 'b') input ORDER BY id",
                        "useLegacySql", false))
                .then().statusCode(200)
                .body("schema.fields[0].type", equalTo("RECORD"))
                .body("schema.fields[0].mode", equalTo("REPEATED"))
                .body("schema.fields[0].fields.name", equalTo(List.of("name")))
                .body("rows[0].f[0].v.v[0].f[0].v", equalTo("a"))
                .body("rows[1].f[0].v.v[0].f[0].v", equalTo("b"));
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
                .body("rows", hasSize(2));
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
    void aliasesAfterNullTrueAndFalseMatchBigQuery() {
        query("""
                {"query": "SELECT x IS NOT NULL c, x IS NULL d, NULL n, TRUE t, FALSE f, x IS TRUE it, x IS NOT FALSE nf FROM UNNEST([true, NULL]) x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "d", "n", "t", "f", "it", "nf")))
                .body("rows[0].f.v", equalTo(Arrays.asList("true", "false", null, "true", "false", "true", "true")))
                .body("rows[1].f.v", equalTo(Arrays.asList("false", "true", null, "true", "false", "false", "true")));
        query("""
                {"query": "SELECT x IS NOT NULL, NULL, TRUE, x IS TRUE FROM UNNEST([true]) x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("f0_", "f1_", "f2_", "f3_")));
        query("""
                {"query": "SELECT CAST(NULL AS INT64) i, IF(x IS NULL, 1, 0) k FROM UNNEST([true]) x", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("i", "k")));
    }

    @Test
    @Order(5)
    void isDistinctFromMatchesBigQuery() {
        String t = "UNNEST([STRUCT(1 AS a, 1 AS b), (1, 2), (NULL, 2), (NULL, NULL)])";
        query("{\"query\": \"SELECT a, b, a IS DISTINCT FROM b d, a IS NOT DISTINCT FROM b nd, a IS NOT DISTINCT FROM NULL AS c"
                + " FROM " + t + " ORDER BY a NULLS FIRST, b NULLS FIRST\", \"useLegacySql\": false}")
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b", "d", "nd", "c")))
                .body("rows.f.v", equalTo(List.of(
                        Arrays.asList(null, null, "false", "true", "true"),
                        Arrays.asList(null, "2", "true", "false", "true"),
                        List.of("1", "1", "false", "true", "false"),
                        List.of("1", "2", "true", "false", "false"))));
        query("{\"query\": \"SELECT a IS DISTINCT FROM b, a IS NOT DISTINCT FROM NULL FROM " + t + "\", \"useLegacySql\": false}")
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("f0_", "f1_")));
        query("{\"query\": \"SELECT COUNT(*) n FROM " + t + " WHERE a IS DISTINCT FROM b\", \"useLegacySql\": false}")
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2"));
        query("{\"query\": \"SELECT COUNT(*) n FROM " + t + " t1 JOIN " + t + " t2 ON t1.a IS NOT DISTINCT FROM t2.a\","
                + " \"useLegacySql\": false}")
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("8"));
        query("{\"query\": \"SELECT CASE WHEN a IS DISTINCT FROM b THEN 'diff' ELSE 'same' END k FROM " + t
                + " ORDER BY a NULLS FIRST, b NULLS FIRST\", \"useLegacySql\": false}")
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("same"), List.of("diff"), List.of("same"), List.of("diff"))));
        query("""
                {"query": "SELECT (SELECT AS STRUCT 1 IS DISTINCT FROM 2 AS d, 1 IS NOT DISTINCT FROM 1 AS e) s", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("d", "e")))
                .body("rows[0].f[0].v.f.v", equalTo(List.of("true", "true")));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT x IS DISTINCT FROM 2 AS d, x FROM UNNEST([1, 2]) x ORDER BY x) a", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("d", "x")))
                .body("rows[0].f[0].v.v.f.v", equalTo(List.of(List.of("true", "1"), List.of("false", "2"))));
        query("{\"query\": \"SELECT a IS DISTINCT FROM b AS d, t.* FROM " + t + " t ORDER BY a NULLS FIRST, b NULLS FIRST\","
                + " \"useLegacySql\": false}")
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("d", "a", "b")))
                .body("rows.f.v", equalTo(List.of(Arrays.asList("false", null, null), Arrays.asList("true", null, "2"),
                        List.of("false", "1", "1"), List.of("true", "1", "2"))));
        query("{\"query\": \"SELECT t.*, a IS DISTINCT FROM b AS d FROM " + t + " t ORDER BY a NULLS FIRST, b NULLS FIRST\","
                + " \"useLegacySql\": false}")
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b", "d")))
                .body("rows.f.v", equalTo(List.of(Arrays.asList(null, null, "false"), Arrays.asList(null, "2", "true"),
                        List.of("1", "1", "false"), List.of("1", "2", "true"))));
        query("""
                {"query": "SELECT x IS DISTINCT FROM 2 AS d, * FROM UNNEST([1, 2]) x WITH OFFSET o ORDER BY o", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("d", "x", "o")))
                .body("rows.f.v", equalTo(List.of(List.of("true", "1", "0"), List.of("false", "2", "1"))));
        query("""
                {"query": "SELECT 1 IS DISTINCT FROM 2 AS d, STRUCT(MAX(x) AS n) AS s FROM UNNEST([1, 2]) x HAVING s.n > 0", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("d", "s")))
                .body("rows[0].f[0].v", equalTo("true"))
                .body("rows[0].f[1].v.f.v", equalTo(List.of("2")));
        query("""
                {"query": "SELECT *, x IS DISTINCT FROM 2 AS d FROM UNNEST([1, 2]) x WITH OFFSET o ORDER BY o", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x", "o", "d")))
                .body("rows.f.v", equalTo(List.of(List.of("1", "0", "true"), List.of("2", "1", "false"))));
        query("""
                {"query": "SELECT * EXCEPT (o), x IS DISTINCT FROM 2 AS d FROM UNNEST([1, 2]) x WITH OFFSET o ORDER BY o", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x", "d")))
                .body("rows.f.v", equalTo(List.of(List.of("1", "true"), List.of("2", "false"))));
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
    void generateTimestampArrayMatchesBigQuery() {
        query("""
                {"query": "SELECT GENERATE_TIMESTAMP_ARRAY(TIMESTAMP '2026-01-01 00:00:00+00', TIMESTAMP '2026-01-03 00:00:00+00', INTERVAL 1 DAY) a, GENERATE_TIMESTAMP_ARRAY('2016-10-05 00:00:00', '2016-10-05 00:00:02', INTERVAL 1 SECOND) b, GENERATE_TIMESTAMP_ARRAY('2016-10-06 00:00:00', '2016-10-01 00:00:00', INTERVAL -2 DAY) c, GENERATE_TIMESTAMP_ARRAY('2016-10-05 00:00:00', '2016-10-01 00:00:00', INTERVAL 1 DAY) d, GENERATE_TIMESTAMP_ARRAY('2016-10-05 00:00:00', NULL, INTERVAL 1 DAY) n", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("TIMESTAMP", "TIMESTAMP", "TIMESTAMP", "TIMESTAMP", "TIMESTAMP")))
                .body("schema.fields[0].mode", equalTo("REPEATED"))
                .body("rows[0].f[0].v.v", equalTo(List.of("1767225600", "1767312000", "1767398400")))
                .body("rows[0].f[1].v.v", equalTo(List.of("1475625600", "1475625601", "1475625602")))
                .body("rows[0].f[2].v.v", equalTo(List.of("1475712000", "1475539200", "1475366400")))
                .body("rows[0].f[3].v", equalTo(List.of()))
                .body("rows[0].f[4].v", equalTo(List.of()));
        query("""
                {"query": "SELECT GENERATE_TIMESTAMP_ARRAY('2016-10-05 00:00:00', NULL, INTERVAL 1 DAY) IS NULL, ARRAY_LENGTH(GENERATE_TIMESTAMP_ARRAY('2016-10-05 00:00:00', '2016-10-01 00:00:00', INTERVAL 1 DAY))", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("true", "0")));
        query("""
                {"query": "SELECT t FROM UNNEST(GENERATE_TIMESTAMP_ARRAY(TIMESTAMP '2026-01-01 00:00:00+00', TIMESTAMP '2026-01-01 12:00:00+00', INTERVAL 6 HOUR)) t ORDER BY t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("1767225600"), List.of("1767247200"), List.of("1767268800"))));
        query("""
                {"query": "SELECT GENERATE_TIMESTAMP_ARRAY('2016-10-05 00:00:00', '2016-10-07 00:00:00', INTERVAL 0 DAY)", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("step cannot be 0"));
    }

    @Test
    @Order(5)
    void structDotStarReplaceAndNestedQueriesExecute() {
        query("""
                {"query": "SELECT t.s.* REPLACE (UPPER(t.s.b) AS b) FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b")))
                .body("rows[0].f.v", equalTo(List.of("1", "X")));
        query("""
                {"query": "SELECT t.s.* REPLACE ('y' AS b) FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b")))
                .body("rows[0].f.v", equalTo(List.of("1", "y")));
        query("""
                {"query": "SELECT t.s.* EXCEPT (a) REPLACE ('y' AS b) FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows[0].f.v", equalTo(List.of("y")));
        query("""
                {"query": "SELECT t.s.* REPLACE (t.id + 1 AS a), t.id FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s, 5 AS id) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b", "id")))
                .body("rows[0].f.v", equalTo(List.of("6", "x", "5")));
        query("""
                {"query": "WITH c AS (SELECT t.s.* EXCEPT (a) FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s) t) SELECT * FROM c", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows[0].f.v", equalTo(List.of("x")));
        query("""
                {"query": "WITH c AS (SELECT t.s.* REPLACE ('y' AS b) FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s) t) SELECT * FROM c", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b")))
                .body("rows[0].f.v", equalTo(List.of("1", "y")));
        query("""
                {"query": "SELECT * FROM (SELECT t.s.* EXCEPT (a) FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s) t)", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows[0].f.v", equalTo(List.of("x")));
        query("""
                {"query": "SELECT (SELECT AS STRUCT input.* REPLACE (UPPER(name) AS name)) AS r FROM (SELECT 'ana' AS name, 'hidden' AS secret) input", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("name", "secret")))
                .body("rows[0].f[0].v.f.v", equalTo(List.of("ANA", "hidden")));
        query("""
                {"query": "SELECT DISTINCT t.s.* EXCEPT(a), t.id FROM (SELECT STRUCT(1 AS a, 5 AS b) AS s, 8 AS id UNION ALL SELECT STRUCT(1 AS a, 3 AS b), 7) t ORDER BY t.s.b DESC", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "id")))
                .body("rows.f.v", equalTo(List.of(List.of("5", "8"), List.of("3", "7"))));
        query("""
                {"query": "SELECT DISTINCT t.name FROM (SELECT STRUCT(1 AS a) AS s, 'bo' AS name UNION ALL SELECT STRUCT(1 AS a), 'ana') t ORDER BY UPPER(name)", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("name")))
                .body("rows.f.v", equalTo(List.of(List.of("ana"), List.of("bo"))));
        query("""
                {"query": "SELECT t.s.* REPLACE (t.amount * 2 AS a) FROM (SELECT STRUCT(1 AS a) s, 5 AS amount) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f[0].v", equalTo("10"));
        query("""
                {"query": "SELECT t.s.* REPLACE (amount * 2 AS a) FROM (SELECT STRUCT(1 AS a) s, 5 AS amount) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("10"));
        query("""
                {"query": "SELECT t.s.* EXCEPT (c) REPLACE (b * 10 AS b) FROM (SELECT STRUCT(1 AS a, 2 AS b, 3 AS c) s) t", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("Unrecognized name: b"));
        query("""
                {"query": "SELECT t.s.* EXCEPT (z) FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", equalTo("Column z in SELECT * EXCEPT list does not exist"));
        query("""
                {"query": "SELECT t.s.* REPLACE (10 AS z) FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", equalTo("Column z in SELECT * REPLACE list does not exist"));
        query("""
                {"query": "SELECT t.s.* EXCEPT (a) FROM (SELECT STRUCT(1 AS a, 2 AS b, 3 AS c) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "c")))
                .body("rows[0].f.v", equalTo(List.of("2", "3")));
        query("""
                {"query": "SELECT t.s.* REPLACE (10 AS a) FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b")))
                .body("rows[0].f.v", equalTo(List.of("10", "2")));
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
                {"query": "SELECT id, (SELECT AS STRUCT input.* EXCEPT(id)) AS original FROM UNNEST([STRUCT(1 AS id, 'keep' AS retained)]) input", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "original")))
                .body("rows[0].f[1].v.f[0].v", equalTo("keep"));
        query("""
                {"query": "SELECT g, ANY_VALUE(STRUCT(x AS v, x * 10 AS w)).*, MAX(x) m FROM UNNEST([STRUCT('a' AS g, 1 AS x), ('b', 3)]) GROUP BY g ORDER BY g", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("g", "v", "w", "m")))
                .body("rows.f.v", equalTo(List.of(List.of("a", "1", "10", "1"), List.of("b", "3", "30", "3"))));
        query("""
                {"query": "SELECT v.group_id, ANY_VALUE(v.original).* EXCEPT (omitted), COUNT(*) AS omitted FROM UNNEST([STRUCT(1 AS group_id, STRUCT('drop' AS omitted, 'keep' AS retained) AS original)]) v GROUP BY v.group_id", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("group_id", "retained", "omitted")))
                .body("rows[0].f.v", equalTo(List.of("1", "keep", "1")));
        query("""
                {"query": "SELECT v.group_id, ANY_VALUE(v.original).* EXCEPT (omitted), COUNT(*) AS omitted FROM UNNEST([STRUCT('a' AS group_id, STRUCT('zzz' AS omitted, 'keep' AS retained) AS original), ('b', STRUCT('a1' AS omitted, 'keep' AS retained)), ('b', STRUCT('a2' AS omitted, 'keep' AS retained))]) v GROUP BY v.group_id ORDER BY omitted", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("a", "keep", "1"), List.of("b", "keep", "2"))));
        query("""
                {"query": "SELECT v.group_id, ANY_VALUE(v.original).* EXCEPT (omitted), COUNT(*) AS omitted FROM UNNEST([STRUCT('a' AS group_id, STRUCT('zzz' AS omitted, 'keep' AS retained) AS original), ('b', STRUCT('a1' AS omitted, 'keep' AS retained)), ('b', STRUCT('a2' AS omitted, 'keep' AS retained))]) v GROUP BY v.group_id HAVING omitted > 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("b", "keep", "2"))));
        query("""
                {"query": "SELECT DISTINCT t.s.* EXCEPT(a) FROM (SELECT STRUCT(1 AS a, 1 AS b) AS s UNION ALL SELECT STRUCT(1 AS a, 1 AS b) UNION ALL SELECT STRUCT(1 AS a, 2 AS b)) t LIMIT 2", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows", hasSize(2));
        query("""
                {"query": "SELECT DISTINCT t.s.* EXCEPT(a) FROM (SELECT STRUCT(1 AS a, 1 AS b) AS s UNION ALL SELECT STRUCT(1 AS a, 1 AS b) UNION ALL SELECT STRUCT(1 AS a, 2 AS b)) t ORDER BY 1 LIMIT 2", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows.f.v", equalTo(List.of(List.of("1"), List.of("2"))));
        query("""
                {"query": "SELECT DISTINCT t.s.* EXCEPT(a) FROM (SELECT STRUCT(1 AS a, 1 AS b) AS s UNION ALL SELECT STRUCT(1 AS a, 1 AS b) UNION ALL SELECT STRUCT(1 AS a, 2 AS b)) t ORDER BY b LIMIT 2", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows.f.v", equalTo(List.of(List.of("1"), List.of("2"))));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a) FROM (SELECT STRUCT(2 AS a, 1 AS b) AS s UNION ALL SELECT STRUCT(1 AS a, 2 AS b)) t ORDER BY 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows.f.v", equalTo(List.of(List.of("1"), List.of("2"))));
        query("""
                {"query": "SELECT * EXCEPT(_TABLE_SUFFIX, secret) REPLACE(UPPER(name) AS name) FROM (SELECT 'x' AS _TABLE_SUFFIX, 'hidden' AS secret, 'ana' AS name) d", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("name")))
                .body("rows[0].f[0].v", equalTo("ANA"));
        query("""
                {"query": "SELECT ANY_VALUE(v.s).* EXCEPT(count), COUNT(*) AS count FROM (SELECT 1 AS id, STRUCT('x' AS count, 'kept' AS keep) AS s UNION ALL SELECT 1, STRUCT('z' AS count, 'kept' AS keep)) v GROUP BY v.id HAVING COUNT(*) > 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("keep", "count")))
                .body("rows[0].f.v", equalTo(List.of("kept", "2")));
        query("""
                {"query": "SELECT (SELECT MAX(id) FROM UNNEST([STRUCT(1 AS id, 2 AS other)]) input) AS n, (SELECT AS STRUCT input.* EXCEPT(id)) AS original FROM UNNEST([STRUCT(1 AS id, 'keep' AS retained)]) input", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("pageToken", nullValue())
                .body("schema.fields.name", equalTo(List.of("n", "original")))
                .body("schema.fields[1].fields.name", equalTo(List.of("retained")))
                .body("rows[0].f[0].v", equalTo("1"))
                .body("rows[0].f[1].v.f[0].v", equalTo("keep"));
        query("""
                {"query": "SELECT DISTINCT t.s.* EXCEPT(a), t.id FROM (SELECT STRUCT(1 AS a, 2 AS b) AS s, 7 AS id UNION ALL SELECT STRUCT(1 AS a, 2 AS b), 8) t ORDER BY t.id", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "id")))
                .body("rows.f.v", equalTo(List.of(List.of("2", "7"), List.of("2", "8"))));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s UNION ALL SELECT STRUCT(2 AS a, 'x' AS b)) t GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f.v", equalTo(List.of("x", "2")));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s UNION ALL SELECT STRUCT(2 AS a, 'x' AS b)) t GROUP BY 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("x", "2")));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s UNION ALL SELECT STRUCT(2 AS a, 'x' AS b) UNION ALL SELECT STRUCT(3 AS a, 'y' AS b)) t GROUP BY 1 ORDER BY 2 DESC", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("x", "2"), List.of("y", "1"))));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s UNION ALL SELECT STRUCT(3 AS a, 'y' AS b)) t GROUP BY 1 LIMIT 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows", hasSize(1));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s, 5 AS id UNION ALL SELECT STRUCT(2 AS a, 'x' AS b), -1) t WHERE t.id > 0 GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("x", "1")));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a), SUM(t.s.c) AS total FROM (SELECT STRUCT(1 AS a, 'x' AS b, 5 AS c) AS s UNION ALL SELECT STRUCT(2 AS a, 'x' AS b, 7 AS c)) t GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "c", "total")))
                .body("rows.f.v", equalTo(List.of(List.of("x", "5", "5"), List.of("x", "7", "7"))));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a), MAX(u.name) AS top FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s, 1 AS id UNION ALL SELECT STRUCT(2 AS a, 'y' AS b), 2) t JOIN (SELECT 1 AS id, 'ana' AS name UNION ALL SELECT 2, 'bo') u ON u.id = t.id GROUP BY 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "top")))
                .body("rows.f.v", equalTo(List.of(List.of("x", "ana"), List.of("y", "bo"))));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s UNION ALL SELECT STRUCT(2 AS a, 'x' AS b)) t GROUP BY 1 HAVING n > 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("x", "2"))));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s UNION ALL SELECT STRUCT(3 AS a, 'y' AS b) UNION ALL SELECT STRUCT(4 AS a, 'y' AS b)) t GROUP BY 1 ORDER BY 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("x", "1"), List.of("y", "2"))));
        query("""
                {"query": "WITH c AS (SELECT t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s UNION ALL SELECT STRUCT(2 AS a, 'x' AS b)) t GROUP BY 1) SELECT * FROM c", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f.v", equalTo(List.of("x", "2")));
        query("""
                {"query": "SELECT v.s.* EXCEPT(o), COUNT(*) AS o FROM (SELECT STRUCT(1 AS g, 'k' AS o) AS s UNION ALL SELECT STRUCT(1 AS g, 'k' AS o) UNION ALL SELECT STRUCT(2 AS g, 'z' AS o)) v GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("g", "o")))
                .body("rows.f.v", equalTo(List.of(List.of("1", "2"), List.of("2", "1"))));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a), SUM(amount) AS total FROM (SELECT STRUCT(1 AS a, 'x' AS b) AS s, 5 AS amount) t GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "total")))
                .body("rows[0].f.v", equalTo(List.of("x", "5")));
        query("""
                {"query": "SELECT t.id, t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT 7 AS id, STRUCT(1 AS a, 'x' AS b) AS s) t GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "b", "n")))
                .body("rows[0].f.v", equalTo(List.of("7", "x", "1")));
        query("""
                {"query": "SELECT t.id, t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT 7 AS id, STRUCT(1 AS a, 'x' AS b) AS s) t GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "b", "n")))
                .body("rows[0].f.v", equalTo(List.of("7", "x", "1")));
        query("""
                {"query": "SELECT t.s.* REPLACE (DATE_ADD(t.s.d, INTERVAL 1 DAY) AS d) FROM (SELECT STRUCT(DATE '2024-01-01' AS d) AS s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("d")))
                .body("rows[0].f[0].v", equalTo("2024-01-02"));
        query("""
                {"query": "SELECT t.s.* REPLACE (t.`amount total` * 2 AS a) FROM (SELECT STRUCT(1 AS a) s, 5 AS `amount total`) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f[0].v", equalTo("10"));
        query("""
                {"query": "SELECT t.s.* REPLACE(t.id AS a), t.id + 100 AS id FROM (SELECT STRUCT(1 AS a) s, 5 AS id) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "id")))
                .body("rows[0].f.v", equalTo(List.of("5", "105")));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t GROUP BY 1 ORDER BY b", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f.v", equalTo(List.of("x", "1")));
        query("""
                {"query": "WITH c AS (SELECT * FROM (SELECT STRUCT(1 AS a) s, 5 AS amount)) SELECT t.s.* REPLACE (amount * 2 AS a) FROM c t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f[0].v", equalTo("10"));
        query("""
                {"query": "SELECT (SELECT t.s.* REPLACE (10 AS a) FROM (SELECT STRUCT(1 AS a) s) t) AS r", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("r")))
                .body("schema.fields.type", equalTo(List.of("INTEGER")))
                .body("rows[0].f[0].v", equalTo("10"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "f0_")))
                .body("rows[0].f.v", equalTo(List.of("x", "1")));
        query("""
                {"query": "WITH c AS (SELECT 0 AS dummy) SELECT * FROM (WITH c AS (SELECT STRUCT(1 AS a) s, 7 AS amount) SELECT t.s.* REPLACE(amount AS a) FROM c t) q", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f[0].v", equalTo("7"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s UNION ALL SELECT STRUCT(2 AS a, 'x' AS b) UNION ALL SELECT STRUCT(3 AS a, 'y' AS b)) t GROUP BY ALL QUALIFY ROW_NUMBER() OVER (ORDER BY n DESC) = 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows", hasSize(1))
                .body("rows[0].f[1].v", equalTo("3"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT (a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s UNION ALL SELECT STRUCT(2 AS a, 'x' AS b) UNION ALL SELECT STRUCT(3 AS a, 'y' AS b)) t GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows", hasSize(1))
                .body("rows[0].f[1].v", equalTo("3"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT (a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s UNION ALL SELECT STRUCT(2 AS a, 'x' AS b) UNION ALL SELECT STRUCT(3 AS a, 'y' AS b)) t GROUP BY ALL HAVING n > 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[1].v", equalTo("3"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT (a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s UNION ALL SELECT STRUCT(2 AS a, 'x' AS b) UNION ALL SELECT STRUCT(3 AS a, 'y' AS b)) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows", hasSize(1))
                .body("rows[0].f[1].v", equalTo("3"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT (a), COUNT(*) AS a FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "a")))
                .body("rows[0].f.v", equalTo(List.of("x", "1")));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), 1 AS k FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "k")))
                .body("rows[0].f.v", equalTo(List.of("x", "1")));
        query("""
                {"query": "SELECT sum.s.* EXCEPT(a) FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) sum", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows[0].f[0].v", equalTo("x"));
        query("""
                {"query": "SELECT (SELECT ANY_VALUE(s) FROM (SELECT STRUCT(1 AS a, 2 AS b) AS s)).* EXCEPT(a)", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows[0].f[0].v", equalTo("2"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), (SELECT COUNT(*) FROM (SELECT 1) z) AS k FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "k")))
                .body("rows[0].f.v", equalTo(List.of("x", "1")));
        query("""
                {"query": "SELECT (SELECT STRUCT(1 AS a, 2 AS b)).* REPLACE(t.id AS b) FROM (SELECT 9 AS id) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b")))
                .body("rows[0].f.v", equalTo(List.of("1", "9")));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t ORDER BY COUNT(*)", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f.v", equalTo(List.of("x", "1")));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t ORDER BY n DESC LIMIT 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("x", "1")));
        query("""
                {"query": "SELECT (ANY_VALUE(t.s) OVER ()).* EXCEPT(a) FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows[0].f[0].v", equalTo("x"));
        query("""
                {"query": "SELECT (ANY_VALUE(t.s)).* EXCEPT(a) FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows[0].f[0].v", equalTo("x"));
        query("""
                {"query": "SELECT (SELECT STRUCT(1 AS a, 2 AS b)).* EXCEPT(a), COUNT(*) AS n FROM (SELECT 9 AS id) t GROUP BY 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f.v", equalTo(List.of("2", "1")));
        query("""
                {"query": "SELECT u.s.* REPLACE(amount AS a) FROM (SELECT t.payload.* FROM (SELECT STRUCT(STRUCT(1 AS a) AS s, 5 AS amount) AS payload) t) u", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f[0].v", equalTo("5"));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* EXCEPT(a) FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields[0].mode", equalTo("REPEATED"))
                .body("schema.fields[0].fields.name", equalTo(List.of("b")))
                .body("rows[0].f[0].v.v[0].f[0].v", equalTo("2"));
        query("""
                {"query": "SELECT (SELECT AS STRUCT t.s.* EXCEPT(a)) AS r FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields[0].fields.name", equalTo(List.of("b")))
                .body("rows[0].f[0].v.f[0].v", equalTo("2"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s UNION ALL SELECT STRUCT(3 AS a, 'x' AS b)) t GROUP BY ALL HAVING b = 'x'", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows", hasSize(1))
                .body("rows[0].f[1].v", equalTo("2"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t GROUP BY ALL HAVING b = 'nope'", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("totalRows", equalTo("0"))
                .body("rows", nullValue());
        query("""
                {"query": "SELECT t.s.* REPLACE(other.b AS b) FROM (SELECT STRUCT(1 AS b) s, STRUCT(9 AS b) other) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows[0].f[0].v", equalTo("9"));
        query("""
                {"query": "SELECT t.s.* EXCEPT(b), MAX(other.b) AS top FROM (SELECT STRUCT(1 AS b, 2 AS c) s, STRUCT(9 AS b) other) t GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "top")))
                .body("rows[0].f.v", equalTo(List.of("2", "9")));
        query("""
                {"query": "SELECT t.s.* REPLACE(DATE_ADD(t.s.d, INTERVAL 1 DAY) AS d) FROM (SELECT STRUCT(DATE '2024-01-01' AS d) s, 5 AS day) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2024-01-02"));
        query("""
                {"query": "SELECT t.s.* REPLACE(EXTRACT(DAY FROM t.s.d) AS d) FROM (SELECT STRUCT(DATE '2024-01-01' AS d) s, 5 AS day) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("1"));
        query("""
                {"query": "SELECT t.s.* REPLACE(day AS d) FROM (SELECT STRUCT(DATE '2024-01-01' AS d) s, 5 AS day) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("5"));
        query("""
                {"query": "SELECT t.s.* REPLACE(ABS(day) AS a) FROM (SELECT STRUCT(1 AS a) s, -5 AS day) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f[0].v", equalTo("5"));
        query("""
                {"query": "SELECT t.s.* REPLACE(DATE_TRUNC(t.s.d, MONTH) AS d) FROM (SELECT STRUCT(DATE '2024-03-05' AS d) s, 5 AS month) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2024-03-01"));
        query("""
                {"query": "SELECT t.s.* REPLACE(DATE_DIFF(t.s.d, DATE '2024-01-01', DAY) AS d) FROM (SELECT STRUCT(DATE '2024-03-05' AS d) s, 5 AS day) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("64"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t GROUP BY ALL HAVING n > 0 QUALIFY ROW_NUMBER() OVER () > 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("totalRows", equalTo("0"))
                .body("rows", nullValue());
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s UNION ALL SELECT STRUCT(2 AS a, 'x' AS b)) t GROUP BY ALL QUALIFY ROW_NUMBER() OVER (PARTITION BY b) = 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f[1].v", equalTo("2"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t GROUP BY ALL HAVING COUNT(*) > 0 AND b = 'x'", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t GROUP BY ALL HAVING COUNT(*) > 0 AND b = 'nope'", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("totalRows", equalTo("0"))
                .body("rows", nullValue());
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t GROUP BY ALL HAVING COUNT(*) > 0 AND (SELECT b FROM (SELECT 'x' AS b) u) = 'x'", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f[1].v", equalTo("1"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t GROUP BY ALL HAVING COUNT(*) > 0 AND (SELECT b FROM (SELECT 'nope' AS b) u) = 'x'", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("totalRows", equalTo("0"))
                .body("rows", nullValue());
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE(10 AS a) FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("10", "2")));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* EXCEPT(a) REPLACE(10 AS b) FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("10")));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE(t.s.a + 100 AS a) FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("101", "2")));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE(a + 10 AS a) FROM (SELECT STRUCT(1 AS a) s, 5 AS a) t) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("15")));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* EXCEPT(a) REPLACE(a + 10 AS b) FROM (SELECT STRUCT(1 AS a, 2 AS b) s, 5 AS a) t) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("15")));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE((SELECT MAX(id) FROM (SELECT 9 AS id) AS u) AS a) FROM (SELECT STRUCT(1 AS a) s, 5 AS id) t) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("9")));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE(amount AS a) FROM (SELECT STRUCT(1 AS a) s) t CROSS JOIN (SELECT 5 AS amount) u) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("5")));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* EXCEPT(a) REPLACE(amount AS b) FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t CROSS JOIN (SELECT 5 AS amount) u) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("5")));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE(t.s.a + 10 AS a) FROM (SELECT STRUCT(1 AS a) s, 5 AS a) t) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("11")));
        query("""
                {"query": "SELECT t.s.* REPLACE(amount AS a) FROM (SELECT * EXCEPT(amount) FROM (SELECT STRUCT(1 AS a) s, 0 AS amount)) t CROSS JOIN (SELECT 5 AS amount) u", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f[0].v", equalTo("5"));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE(amount AS a) FROM (SELECT * EXCEPT(amount) FROM (SELECT STRUCT(1 AS a) s, 0 AS amount)) t CROSS JOIN (SELECT 5 AS amount) u) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("5")));
        query("""
                {"query": "SELECT t.s.* REPLACE(amount AS a) FROM (SELECT * EXCEPT(amount) FROM (SELECT STRUCT(1 AS a) s, 0 AS amount)) t", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", equalTo("Unrecognized name: amount"));
        query("""
                {"query": "SELECT t.s.* REPLACE(-t.s.a AS a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a) s UNION ALL SELECT STRUCT(2 AS a)) t GROUP BY 1, t.s.a ORDER BY t.s.a LIMIT 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("-1"));
        query("""
                {"query": "SELECT t.s.* REPLACE(-t.s.a AS a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a) s UNION ALL SELECT STRUCT(2 AS a)) t GROUP BY 1, t.s.a ORDER BY t.s.a DESC", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("-2"))
                .body("rows[1].f[0].v", equalTo("-1"));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE(a + 10 AS a) FROM (SELECT STRUCT(1 AS a) s) t) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", equalTo("Unrecognized name: a"));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE(x + 1 AS a) FROM (SELECT STRUCT(1 AS a) s) t) AS arr FROM (SELECT 5 AS x) outer_q", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("6")));
        query("""
                {"query": "SELECT t.s.* REPLACE ((SELECT t.s.a FROM (SELECT STRUCT(9 AS a) s) t) AS a) FROM (SELECT STRUCT(1 AS a) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("9"));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE ((SELECT t.s.a FROM (SELECT STRUCT(9 AS a) s) t) AS a) FROM (SELECT STRUCT(1 AS a) s) t) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("9")));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE(amount AS a) FROM (SELECT STRUCT(1 AS a) s) t CROSS JOIN (SELECT 5 AS amount)) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("5")));
        query("""
                {"query": "SELECT ARRAY(SELECT AS STRUCT t.s.* REPLACE(x AS a) FROM (SELECT STRUCT(1 AS a, 2 AS x) s) t, UNNEST([7]) x) AS arr", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.v[0].f.v", equalTo(List.of("7", "2")));
        query("""
                {"query": "SELECT (SELECT AS STRUCT t.s.* REPLACE(10 AS a)) AS r FROM (SELECT STRUCT(1 AS a, 2 AS b) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v.f.v", equalTo(List.of("10", "2")));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a) FROM (SELECT STRUCT(1 AS a) s) t", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", equalTo("SELECT .* expands to zero columns after applying EXCEPT"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM shop.structs t GROUP BY ALL QUALIFY ROW_NUMBER() OVER (PARTITION BY b) = 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f[1].v", equalTo("2"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM shop.structs t GROUP BY ALL QUALIFY ROW_NUMBER() OVER (PARTITION BY b) = 2", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("totalRows", equalTo("0"))
                .body("rows", nullValue());
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM shop.structs t GROUP BY ALL HAVING COUNT(*) > 0 AND b = 'x'", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[1].v", equalTo("2"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM shop.structs t GROUP BY ALL HAVING COUNT(*) > 0 AND b = 'nope' ORDER BY b", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("totalRows", equalTo("0"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM shop.structs t GROUP BY ALL HAVING SUM(amount) > 0", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f[1].v", equalTo("2"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM shop.structs t GROUP BY ALL HAVING SUM(amount) > 100", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("totalRows", equalTo("0"))
                .body("rows", nullValue());
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), SUM(amount) AS n FROM shop.structs t GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[1].v", equalTo("12"));
        query("""
                {"query": "SELECT t.s.* REPLACE(t.other.b AS b) FROM (SELECT STRUCT(1 AS b) s, STRUCT(9 AS b) other) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows[0].f[0].v", equalTo("9"));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a), MAX(t.other.b) AS top FROM (SELECT STRUCT(1 AS a, 2 AS c) s, STRUCT(9 AS b) other) t GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("c", "top")))
                .body("rows[0].f.v", equalTo(List.of("2", "9")));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS a FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t GROUP BY ALL HAVING a > 0", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "a")))
                .body("rows[0].f.v", equalTo(List.of("x", "1")));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS a FROM (SELECT STRUCT(1 AS a, 'x' AS b) s UNION ALL SELECT STRUCT(2 AS a, 'y' AS b)) t GROUP BY ALL QUALIFY ROW_NUMBER() OVER (ORDER BY a DESC) = 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "a")))
                .body("rows", hasSize(1))
                .body("rows[0].f[1].v", equalTo("2"));
        query("""
                {"query": "SELECT t.s.* REPLACE (-t.s.a AS a) FROM (SELECT STRUCT(1 AS a) s UNION ALL SELECT STRUCT(2 AS a) UNION ALL SELECT STRUCT(3 AS a)) t ORDER BY t.s.a", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows.f.v", equalTo(List.of(List.of("-1"), List.of("-2"), List.of("-3"))));
        query("""
                {"query": "SELECT t.s.* REPLACE (-t.s.a AS a) FROM (SELECT STRUCT(1 AS a) s UNION ALL SELECT STRUCT(2 AS a) UNION ALL SELECT STRUCT(3 AS a)) t ORDER BY a", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("-3"), List.of("-2"), List.of("-1"))));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a) REPLACE(t.id AS b), t.id AS a FROM (SELECT STRUCT(1 AS a, 2 AS b) s, 5 AS id) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "a")))
                .body("rows[0].f.v", equalTo(List.of("5", "5")));
        query("""
                {"query": "SELECT t.id AS x, t.id AS y, t.s.* EXCEPT(a), COUNT(*) AS n FROM (SELECT 5 AS id, STRUCT(1 AS a, 'keep' AS b) s) t GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("x", "y", "b", "n")))
                .body("rows[0].f.v", equalTo(List.of("5", "5", "keep", "1")));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a) REPLACE ((SELECT MAX(id) FROM (SELECT 1 AS id UNION ALL SELECT 9) u) AS b) FROM (SELECT STRUCT(1 AS a, 2 AS b) s, 5 AS id) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b")))
                .body("rows[0].f[0].v", equalTo("9"));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a) REPLACE ((SELECT MAX(id) FROM (SELECT 1 AS id UNION ALL SELECT 9) u) AS b), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 2 AS b) s, 5 AS id) t GROUP BY 1 HAVING (SELECT MAX(id) FROM (SELECT 1 AS id UNION ALL SELECT 9) u) > 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f.v", equalTo(List.of("9", "1")));
        query("""
                {"query": "SELECT t.s.* REPLACE ((SELECT t.id) AS a) FROM (SELECT STRUCT(1 AS a) s, 5 AS id) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("5"));
        query("""
                {"query": "WITH c AS (SELECT STRUCT(1 AS a) s, 5 AS amount) SELECT t.s.* REPLACE (amount * 2 AS a) FROM c t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("10"));
        query("""
                {"query": "SELECT t.s.* REPLACE (-t.s.b AS b) FROM (SELECT STRUCT(1 AS b) s UNION ALL SELECT STRUCT(2 AS b)) t ORDER BY b LIMIT 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("-2"));
        query("""
                {"query": "SELECT t.s.* REPLACE(t.amount AS a), 99 AS _floci_source_0 FROM (SELECT STRUCT(1 AS a) s, 5 AS amount) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "_floci_source_0")))
                .body("rows[0].f.v", equalTo(List.of("5", "99")));
        query("""
                {"query": "SELECT t.s.* REPLACE (10 AS z), 5 AS z FROM (SELECT STRUCT(1 AS a) s) t", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", equalTo("Column z in SELECT * REPLACE list does not exist"));
        query("""
                {"query": "SELECT t.s.* REPLACE (10 AS a), 5 AS a FROM (SELECT STRUCT(1 AS a) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("10", "5")));
        query("""
                {"query": "SELECT t.s.* REPLACE ((SELECT t.id FROM (SELECT 9 AS id) t) AS a) FROM (SELECT STRUCT(1 AS a) s, 5 AS id) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("9"));
        query("""
                {"query": "SELECT t.s.* REPLACE (TO_JSON_STRING(t.s) AS b) FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "b")))
                .body("rows[0].f.v", equalTo(List.of("1", "{\"a\":1,\"b\":\"x\"}")));
        query("""
                {"query": "SELECT t.s.* EXCEPT(a) REPLACE (TO_JSON_STRING(t.s) AS b), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s) t GROUP BY 1", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f.v", equalTo(List.of("{\"a\":1,\"b\":\"x\"}", "1")));
        query("""
                {"query": "SELECT t.s.* REPLACE ((SELECT 7 AS x) AS a), 5 AS x FROM (SELECT STRUCT(1 AS a) s) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a", "x")))
                .body("rows[0].f.v", equalTo(List.of("7", "5")));
        query("""
                {"query": "SELECT t.s.* REPLACE (10 AS a) FROM (SELECT STRUCT(1 AS a) s, 5 AS id) t ORDER BY t.id", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("a")))
                .body("rows[0].f[0].v", equalTo("10"));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s UNION ALL SELECT STRUCT(2 AS a, 'x' AS b)) t GROUP BY ALL", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("b", "n")))
                .body("rows[0].f.v", equalTo(List.of("x", "2")));
        query("""
                {"query": "SELECT ANY_VALUE(t.s).* EXCEPT(a), COUNT(*) AS n FROM (SELECT STRUCT(1 AS a, 'x' AS b) s UNION ALL SELECT STRUCT(2 AS a, 'x' AS b)) t GROUP BY 1", "useLegacySql": false}
                """)
                .then().statusCode(400);
        query("""
                {"query": "BEGIN CREATE TEMP TABLE input_data AS SELECT 1 AS id, STRUCT('drop' AS omitted, 'keep' AS retained) AS original; CREATE TEMP TABLE intermediate AS SELECT id, ANY_VALUE(original).* EXCEPT (omitted), COUNT(*) AS omitted FROM input_data GROUP BY id; SELECT * FROM intermediate; END", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("schema.fields.name", equalTo(List.of("id", "retained", "omitted")))
                .body("rows[0].f.v", equalTo(List.of("1", "keep", "1")));
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
    void initcapMatchesBigQuery() {
        query("""
                {"query": "SELECT INITCAP('hello world-everyone!') a, INITCAP('SEARCH_DISPLAY') b, INITCAP('Apples1oranges2pears', '12') c, INITCAP('tHisEisEaESentence', 'E') d, INITCAP('a-b c', '') e, INITCAP(' x  y ') f", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("Hello World-Everyone!", "Search_Display", "Apples1Oranges2Pears",
                        "ThisEIsEAESentence", "A-b c", " X  Y ")));
        query("""
                {"query": "SELECT INITCAP('a[b]c(d)e{f}g/h|i<k>l!m?n@o^q#r$s&t~u_v,w.x:y;z*a%b+c-d') a, INITCAP('a=b`d1e') b, INITCAP('ÉCOLE são paulo') c, INITCAP('3rd place') d, INITCAP(REPLACE('SEARCH_DISPLAY_SELECT', '_', ' ')) e", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("A[B]C(D)E{F}G/H|I<K>L!M?N@O^Q#R$S&T~U_V,W.X:Y;Z*A%B+C-D", "A=b`d1e",
                        "École São Paulo", "3rd Place", "Search Display Select")));
        query("""
                {"query": "SELECT INITCAP(NULL) a, INITCAP('ab', NULL) b, INITCAP('') c", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", nullValue())
                .body("rows[0].f[1].v", nullValue())
                .body("rows[0].f[2].v", equalTo(""));
        query("""
                {"query": "SELECT INITCAP((SELECT 'hELLO wORLD')) a, INITCAP('a1b2c', (SELECT '12')) b", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("Hello World", "A1B2C")));
        query("""
                {"query": "SELECT INITCAP(__i) a FROM (SELECT 'hELLO' AS __i) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("Hello"));
    }

    @Test
    @Order(5)
    void anyValueHavingMatchesBigQuery() {
        String t = "UNNEST([STRUCT('a' AS g, 'x1' AS x, 10 AS y), ('a', 'x2', 30), ('a', 'x3', 20), ('b', 'y1', 5),"
                + " ('b', CAST(NULL AS STRING), 9), ('c', 'z1', NULL), ('c', 'z2', 4), ('d', 'w1', NULL),"
                + " ('e', CAST(NULL AS STRING), 1), ('e', CAST(NULL AS STRING), 1)])";
        query("{\"query\": \"SELECT g, ANY_VALUE(x HAVING MAX y) mx, ANY_VALUE(x HAVING MIN y) mn FROM " + t
                + " GROUP BY g ORDER BY g\", \"useLegacySql\": false}")
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(
                        List.of("a", "x2", "x1"),
                        Arrays.asList("b", null, "y1"),
                        List.of("c", "z2", "z2"),
                        Arrays.asList("d", null, null),
                        Arrays.asList("e", null, null))));
        query("""
                {"query": "SELECT ANY_VALUE(x HAVING MAX y) v FROM UNNEST([STRUCT(CAST(NULL AS STRING) AS x, 9 AS y), ('k', 9), ('j', 1)])", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("k"));
        query("{\"query\": \"SELECT ANY_VALUE(UPPER(x) HAVING MAX y * -1) a, ANY_VALUE(x HAVING MAX IF(y > 15, y, 0)) b,"
                + " ANY_VALUE(x) IS NOT NULL AS c FROM " + t + " WHERE g = 'a'\", \"useLegacySql\": false}")
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("X1", "x2", "true")));
        query("""
                {"query": "SELECT ANY_VALUE(x HAVING MAX y) OVER () FROM UNNEST([STRUCT('a' AS x, 1 AS y)])", "useLegacySql": false}
                """)
                .then().statusCode(400)
                .body("error.message", containsString("HAVING modifier is not supported on analytic functions"));
        query("""
                {"query": "SELECT ANY_VALUE(x HAVING MAX (SELECT 1) * y) v FROM UNNEST([STRUCT('a' AS x, 1 AS y), ('b', 2)])", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("b"));
        query("""
                {"query": "SELECT g, ANY_VALUE(x HAVING MAX y) mx, ANY_VALUE(x HAVING MIN y) mn FROM UNNEST([STRUCT('p' AS g, 'a' AS x, CAST('NaN' AS FLOAT64) AS y), ('p', 'b', 1.0), ('q', 'c', 5.0), ('q', 'd', 7.0)]) GROUP BY g ORDER BY g", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(Arrays.asList("p", null, null), List.of("q", "d", "c"))));
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
    void compositeFormatSpecifiersMatchBigQuery() {
        query("""
                {"query": "SELECT FORMAT_DATE('%F', DATE '2026-03-05') f, FORMAT_DATE('%D', DATE '2026-03-05') d, FORMAT_TIMESTAMP('%F %R', TIMESTAMP '2026-03-05 07:08:09') fr, FORMAT_TIMESTAMP('%F %T', TIMESTAMP '2026-03-05 07:08:09') ft, FORMAT_DATE('%%F', DATE '2026-03-05') escaped, PARSE_DATE('%F', '2026-03-05') parsed, PARSE_DATE('%D', '03/05/26') parsed_d", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("2026-03-05", "03/05/26", "2026-03-05 07:08", "2026-03-05 07:08:09",
                        "%F", "2026-03-05", "2026-03-05")));
        query("""
                {"query": "SELECT FORMAT_DATE(@f, DATE '2026-03-05') AS a, FORMAT_TIMESTAMP(@g, TIMESTAMP '2026-03-05 07:08:09') AS b, PARSE_DATE(@f2, '2026-03-05') AS c, FORMAT_DATE(@n, DATE '2026-03-05') AS nul",
                 "parameterMode": "NAMED", "useLegacySql": false,
                 "queryParameters": [
                   {"name": "f", "parameterType": {"type": "STRING"}, "parameterValue": {"value": "%F %%F %D"}},
                   {"name": "g", "parameterType": {"type": "STRING"}, "parameterValue": {"value": "%R"}},
                   {"name": "f2", "parameterType": {"type": "STRING"}, "parameterValue": {"value": "%F"}},
                   {"name": "n", "parameterType": {"type": "STRING"}, "parameterValue": {}}]}
                """)
                .then().statusCode(200)
                .body("rows[0].f[0].v", equalTo("2026-03-05 %F 03/05/26"))
                .body("rows[0].f[1].v", equalTo("07:08"))
                .body("rows[0].f[2].v", equalTo("2026-03-05"))
                .body("rows[0].f[3].v", nullValue());
        query("""
                {"query": "SELECT FORMAT_DATE('%e/%m %Q', (SELECT DATE '2024-03-05')) a, SAFE.PARSE_DATE('%F', '2024-01-05') b, SAFE.PARSE_DATE('%F', 'nope') c, SAFE.PARSE_DATE('%D', '01/05/24') d", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(Arrays.asList(" 5/03 1", "2024-01-05", null, "2024-01-05")));
        query("""
                {"query": "SELECT f, FORMAT_DATE(f, (SELECT DATE '2024-03-05')) a FROM UNNEST(['%F', '%e %Q', '%Y']) f ORDER BY f", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("%F", "2024-03-05"), List.of("%Y", "2024"), List.of("%e %Q", " 5 1"))));
        query("""
                {"query": "SELECT FORMAT_TIMESTAMP('%E13S', t) a, FORMAT_TIMESTAMP('%E20S', t) b, FORMAT_TIMESTAMP('%E100S', t) c, FORMAT_TIMESTAMP('%E00S', t) d FROM UNNEST([TIMESTAMP '2026-03-05 07:08:09.123456']) t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("09.1234560000000", "09.123456000000000000", "09.123456000000000000", "09")));
        query("""
                {"query": "SELECT f, FORMAT_TIMESTAMP(f, TIMESTAMP '2026-03-05 07:08:09.123456') v FROM UNNEST(['%E12S', '%E13S', '%E20S', '%E99S', '%E100S']) f ORDER BY f", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("%E100S", "09.123456000000000000"), List.of("%E12S", "09.123456000000"),
                        List.of("%E13S", "09.1234560000000"), List.of("%E20S", "09.123456000000000000"),
                        List.of("%E99S", "09.123456000000000000"))));
        query("""
                {"query": "SELECT FORMAT_TIMESTAMP('%s', TIMESTAMP '9999-12-31 23:59:59.999999') mx, FORMAT_TIMESTAMP('%s', TIMESTAMP '0001-01-01 00:00:00') mn, FORMAT_TIMESTAMP('%s', TIMESTAMP '1969-12-31 23:59:59.5') neg, FORMAT_DATETIME('%s', DATETIME '9999-12-31 23:59:59.999999') dt", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("253402300799", "-62135596800", "-1", "253402300799")));
        query("""
                {"query": "SELECT FORMAT_DATE('%H|%M|%S|%s|%E3S|%z|%T|%c|%k|%P', DATE '2024-03-05') d, FORMAT_TIME('%Y|%d|%z|%H|%s|%c', TIME '07:08:09.123456') t, FORMAT_DATETIME('%H|%s|%z|%Ez|%Z', DATETIME '2024-03-05 00:00:00') dt", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("%H|%M|%S|%s|%E3S|%z|%T|%c|%k|%P", "%Y|%d|%z|07|%s|%c", "00|1709596800|%z|%Ez|%Z")));
        query("""
                {"query": "SELECT f, FORMAT_DATE(f, DATE '2024-03-05') d, FORMAT_TIME(f, TIME '07:08:09.123456') t FROM UNNEST(['%s', '%H', '%E3S', '%Y', '%E4Y']) f WITH OFFSET o ORDER BY o", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("%s", "%s", "%s"), List.of("%H", "%H", "07"), List.of("%E3S", "%E3S", "09.123"),
                        List.of("%Y", "2024", "%Y"), List.of("%E4Y", "2024", "%E4Y"))));
        query("""
                {"query": "SELECT d, FORMAT_DATE('%J', d) j FROM UNNEST([DATE '2024-03-05', DATE '2024-12-30', DATE '2021-01-01', DATE '2020-12-31', DATE '2026-01-01']) d ORDER BY d", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("2020-12-31", "368"), List.of("2021-01-01", "369"), List.of("2024-03-05", "065"),
                        List.of("2024-12-30", "001"), List.of("2026-01-01", "004"))));
        query("""
                {"query": "SELECT FORMAT_DATETIME('%J', DATETIME '2024-03-05 07:08:09') dt, FORMAT_TIMESTAMP('%J', TIMESTAMP '2024-03-05 07:08:09') ts, FORMAT_TIME('%J', TIME '07:08:09') t", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("065", "065", "%J")));
        query("""
                {"query": "SELECT f, FORMAT_DATE(f, DATE '2021-01-01') d, FORMAT_TIME(f, TIME '07:08:09') t FROM UNNEST(['%J', '%j %J']) f ORDER BY f", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.f.v", equalTo(List.of(List.of("%J", "369", "%J"), List.of("%j %J", "001 369", "%j %J"))));
    }

    @Test
    @Order(5)
    void everyFormatElementMatchesBigQuery() {
        List<String> elements = List.of("%A", "%a", "%B", "%b", "%C", "%c", "%D", "%d", "%e", "%F", "%G", "%g", "%H", "%h", "%I", "%j",
                "%k", "%l", "%M", "%m", "%n", "%P", "%p", "%Q", "%R", "%r", "%S", "%s", "%T", "%t", "%U", "%u",
                "%V", "%W", "%w", "%X", "%x", "%Y", "%y", "%Z", "%z", "%%", "%Ez", "%E4Y", "%E3S", "%E6S", "%E*S");
        Map<String, List<String>> constantFormats = Map.of(
                "2026-03-05 07:08:09.123456", List.of("Thursday", "Thu", "March", "Mar", "20", "Thu Mar  5 07:08:09 2026", "03/05/26", "05", " 5",
                "2026-03-05", "2026", "26", "07", "Mar", "07", "064", " 7", " 7", "08", "03", "\n", "am", "AM",
                "1", "07:08", "07:08:09 AM", "09", "1772694489", "07:08:09", "\t", "09", "4", "10", "09", "4",
                "07:08:09", "03/05/26", "2026", "26", "UTC", "+0000", "%", "+00:00", "2026", "09.123", "09.123456",
                "09.123456"),
                "2026-12-31 23:59:59", List.of("Thursday", "Thu", "December", "Dec", "20", "Thu Dec 31 23:59:59 2026", "12/31/26", "31", "31",
                "2026-12-31", "2026", "26", "23", "Dec", "11", "365", "23", "11", "59", "12", "\n", "pm", "PM",
                "4", "23:59", "11:59:59 PM", "59", "1798761599", "23:59:59", "\t", "52", "4", "53", "52", "4",
                "23:59:59", "12/31/26", "2026", "26", "UTC", "+0000", "%", "+00:00", "2026", "59.000", "59.000000",
                "59"));
        Map<String, List<String>> perRowFormats = Map.of(
                "2026-03-05 07:08:09.123456", List.of("Thursday", "Thu", "March", "Mar", "20", "Thu Mar  5 07:08:09 2026", "03/05/26", "05", " 5",
                "2026-03-05", "2026", "26", "07", "Mar", "07", "064", " 7", " 7", "08", "03", "\n", "am", "AM",
                "1", "07:08", "07:08:09 AM", "09", "1772694489", "07:08:09", "\t", "09", "4", "10", "09", "4",
                "07:08:09", "03/05/26", "2026", "26", "UTC", "+0000", "%", "+00:00", "2026", "09.123", "09.123456",
                "09.123456"),
                "2021-01-01 00:00:00", List.of("Friday", "Fri", "January", "Jan", "20", "Fri Jan  1 00:00:00 2021", "01/01/21", "01", " 1",
                "2021-01-01", "2020", "20", "00", "Jan", "12", "001", " 0", "12", "00", "01", "\n", "am", "AM",
                "1", "00:00", "12:00:00 AM", "00", "1609459200", "00:00:00", "\t", "00", "5", "53", "00", "5",
                "00:00:00", "01/01/21", "2021", "21", "UTC", "+0000", "%", "+00:00", "2021", "00.000", "00.000000",
                "00"));
        for (Map.Entry<String, List<String>> expected : constantFormats.entrySet()) {
            List<String> columns = new ArrayList<>();
            for (int i = 0; i < elements.size(); i++) {
                columns.add("FORMAT_TIMESTAMP('" + elements.get(i) + "', TIMESTAMP '" + expected.getKey() + "') AS c" + i);
            }
            query("{\"query\": \"SELECT " + String.join(", ", columns) + "\", \"useLegacySql\": false}")
                    .then().statusCode(200)
                    .body("rows[0].f.v", equalTo(expected.getValue()));
        }
        for (Map.Entry<String, List<String>> expected : perRowFormats.entrySet()) {
            List<String> rows = new ArrayList<>();
            for (int i = 0; i < elements.size(); i++) {
                rows.add("STRUCT(" + i + " AS o, '" + elements.get(i) + "' AS f)");
            }
            query("{\"query\": \"SELECT FORMAT_TIMESTAMP(f, TIMESTAMP '" + expected.getKey() + "') AS v FROM UNNEST(["
                    + String.join(", ", rows) + "]) ORDER BY o\", \"useLegacySql\": false}")
                    .then().statusCode(200)
                    .body("rows.collect { it.f[0].v }", equalTo(expected.getValue()));
        }
        query("""
                {"query": "SELECT FORMAT_DATE(f, d) AS v FROM UNNEST([STRUCT(0 AS o, CAST(NULL AS STRING) AS f, DATE '2026-01-02' AS d), STRUCT(1 AS o, '%Y' AS f, CAST(NULL AS DATE) AS d), STRUCT(2 AS o, '%Y/%m/%d' AS f, DATE '2026-03-05' AS d), STRUCT(3 AS o, '%e|%F|%%F|%Q|%C' AS f, DATE '2026-12-31' AS d)]) ORDER BY o", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows.collect { it.f[0].v }", equalTo(Arrays.asList(null, null, "2026/03/05", "31|2026-12-31|%F|4|20")));
        query("""
                {"query": "SELECT FORMAT_TIMESTAMP('%E0S', t) AS e0, FORMAT_TIMESTAMP('%E1S', t) AS e1, FORMAT_TIMESTAMP('%E9S', t) AS e9, FORMAT_TIMESTAMP('%E12S', t) AS e12, FORMAT_TIMESTAMP('%K', t) AS unknown, FORMAT_TIMESTAMP('a%', t) AS trailing FROM (SELECT TIMESTAMP '2026-03-05 07:08:09.123456' AS t)", "useLegacySql": false}
                """)
                .then().statusCode(200)
                .body("rows[0].f.v", equalTo(List.of("09", "09.1", "09.123456000", "09.123456000000", "%K", "a%")));
    }

    @Test
    @Order(5)
    void temporalStringParametersCoerceLikeBigQuery() {
        query("""
                {"query": "SELECT DATE '2026-03-05' BETWEEN @from_d AND @to_d AS in_range, TIMESTAMP '2026-03-05 10:00:00' >= @ts AS ts_ge, @from_d AS raw, @from_d = '2026-03-01' AS str_eq",
                 "parameterMode": "NAMED", "useLegacySql": false,
                 "queryParameters": [
                   {"name": "from_d", "parameterType": {"type": "STRING"}, "parameterValue": {"value": "2026-03-01"}},
                   {"name": "to_d", "parameterType": {"type": "STRING"}, "parameterValue": {"value": "2026-03-31"}},
                   {"name": "ts", "parameterType": {"type": "STRING"}, "parameterValue": {"value": "2026-03-05 09:00:00"}}]}
                """)
                .then().statusCode(200)
                .body("schema.fields.type", equalTo(List.of("BOOLEAN", "BOOLEAN", "STRING", "BOOLEAN")))
                .body("rows[0].f.v", equalTo(List.of("true", "true", "2026-03-01", "true")));
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

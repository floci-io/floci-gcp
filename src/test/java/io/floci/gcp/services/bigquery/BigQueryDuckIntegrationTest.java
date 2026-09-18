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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
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
                  {"json": {"id": 2, "name": "bo", "joined": 1704164645, "tags": [], "address": null}}
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
                .body("rows[1].f[2].v", nullValue());

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
}

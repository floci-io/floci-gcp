package io.floci.gcp.services.cloudlogging;

import io.restassured.path.json.JsonPath;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudLoggingBigQueryExportIntegrationTest {
    private static final String PARENT = "projects/test-project";
    private static final String DAY = DateTimeFormatter.ofPattern("uuuuMMdd").withZone(ZoneOffset.UTC)
            .format(Instant.parse("2026-11-17T10:00:00Z"));

    @Test
    void entriesLandInADatedTablePerLogAndANewFieldExtendsTheSchema() {
        String id = id("dated");
        String dataset = dataset("test-project", id);
        String log = PARENT + "/logs/app-" + id;
        sink(id, "bigquery.googleapis.com/projects/test-project/datasets/" + dataset, log, false);
        try {
            write(log, Map.of("severity", "ERROR", "timestamp", "2026-11-17T10:00:00Z",
                    "jsonPayload", Map.of("Status", 500, "path", "/a"), "labels", Map.of("Env", "prod")));
            write(log, Map.of("severity", "WARNING", "timestamp", "2026-11-17T11:00:00Z",
                    "jsonPayload", Map.of("Status", 404, "user", "u")));
            String table = "app_" + id.replace("-", "_") + "_" + DAY;

            JsonPath schema = given().get(tableUrl("test-project", dataset, table)).then().statusCode(200).extract().jsonPath();
            List<String> top = schema.getList("schema.fields.name");
            assertTrue(top.containsAll(List.of("logName", "resource", "timestamp", "receiveTimestamp", "severity",
                    "insertId", "jsonPayload", "labels")), top.toString());
            assertTrue(schema.getList("schema.fields.find { it.name == 'jsonPayload' }.fields.name")
                    .containsAll(List.of("status", "path", "user")));
            assertEquals("TIMESTAMP", schema.getString("schema.fields.find { it.name == 'timestamp' }.type"));

            given().get(tableUrl("test-project", dataset, table) + "/data").then().statusCode(200)
                    .body("totalRows", equalTo("2")).body("rows", hasSize(2))
                    .body("rows[0].f[0].v", equalTo(log));
        } finally {
            deleteSink(id);
        }
    }

    @Test
    void partitionedSinksWriteOneUnsuffixedTablePartitionedOnTimestamp() {
        String id = id("partitioned");
        String dataset = dataset("test-project", id);
        String log = PARENT + "/logs/" + id;
        sink(id, "bigquery.googleapis.com/projects/test-project/datasets/" + dataset, log, true);
        try {
            write(log, Map.of("textPayload", "one", "timestamp", "2026-11-17T10:00:00Z"));
            write(log, Map.of("textPayload", "two", "timestamp", "2026-11-18T10:00:00Z"));
            String table = id.replace("-", "_");
            given().get(tableUrl("test-project", dataset, table)).then().statusCode(200)
                    .body("timePartitioning.field", equalTo("timestamp"));
            given().get(tableUrl("test-project", dataset, table) + "/data").then().statusCode(200)
                    .body("totalRows", equalTo("2"));
        } finally {
            deleteSink(id);
        }
    }

    @Test
    void anotherProjectsDatasetReceivesTheRowsUnderThatProject() {
        String id = id("cross");
        String dataset = dataset("bq-destination", id);
        String log = PARENT + "/logs/" + id;
        sink(id, "bigquery.googleapis.com/projects/bq-destination/datasets/" + dataset, log, false);
        try {
            write(log, Map.of("textPayload", "cross", "timestamp", "2026-11-17T10:00:00Z"));
            String table = id.replace("-", "_") + "_" + DAY;
            given().get(tableUrl("bq-destination", dataset, table) + "/data").then().statusCode(200)
                    .body("totalRows", equalTo("1"));
            given().get(tableUrl("test-project", dataset, table)).then().statusCode(404);
        } finally {
            deleteSink(id);
        }
    }

    @Test
    void aFieldThatChangesTypeGoesToTheErrorTableAndAMissingDatasetKeepsTheWrite() {
        String id = id("errors");
        String dataset = dataset("test-project", id);
        String log = PARENT + "/logs/" + id;
        sink(id, "bigquery.googleapis.com/projects/test-project/datasets/" + dataset, log, false);
        try {
            write(log, Map.of("timestamp", "2026-11-17T10:00:00Z", "jsonPayload", Map.of("count", 1)));
            write(log, Map.of("timestamp", "2026-11-17T10:05:00Z", "jsonPayload", Map.of("count", "one")));
            given().get(tableUrl("test-project", dataset, id.replace("-", "_") + "_" + DAY) + "/data")
                    .then().statusCode(200).body("totalRows", equalTo("1"));
            JsonPath errors = given().get(tableUrl("test-project", dataset, "export_errors_" + DAY) + "/data")
                    .then().statusCode(200).body("totalRows", equalTo("1")).extract().jsonPath();
            assertTrue(errors.getString("rows[0].f[0].v").contains("\"one\""));
            assertTrue(errors.getString("rows[0].f[1].v").contains("jsonPayload.count"));
        } finally {
            deleteSink(id);
        }

        String missing = id("missing");
        sink(missing, "bigquery.googleapis.com/projects/test-project/datasets/does_not_exist", PARENT + "/logs/" + missing, false);
        try {
            write(PARENT + "/logs/" + missing, Map.of("textPayload", "kept"));
        } finally {
            deleteSink(missing);
        }
    }

    @Test
    void nullsAndArrayItemsWithDifferentKeysStillLandAndMixedArraysGoToTheErrorTable() {
        String id = id("arrays");
        String dataset = dataset("test-project", id);
        String log = PARENT + "/logs/" + id;
        sink(id, "bigquery.googleapis.com/projects/test-project/datasets/" + dataset, log, false);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", "ok");
            payload.put("optional", null);
            payload.put("empty", Map.of());
            payload.put("items", List.of(Map.of("a", 1), Map.of("b", "two")));
            write(log, Map.of("timestamp", "2026-11-17T10:00:00Z", "jsonPayload", payload));
            write(log, Map.of("timestamp", "2026-11-17T10:05:00Z",
                    "jsonPayload", Map.of("message", "mixed", "tags", List.of(1, "one"))));
            String table = tableUrl("test-project", dataset, id.replace("-", "_") + "_" + DAY);

            given().get(table + "/data").then().statusCode(200).body("totalRows", equalTo("1"));
            JsonPath schema = given().get(table).then().statusCode(200).extract().jsonPath();
            assertEquals(List.of("items", "message"), schema.getList(
                    "schema.fields.find { it.name == 'jsonPayload' }.fields.name", String.class).stream().sorted().toList());
            assertEquals(List.of("a", "b"), schema.getList(
                    "schema.fields.find { it.name == 'jsonPayload' }.fields.find { it.name == 'items' }.fields.name",
                    String.class).stream().sorted().toList());
            JsonPath errors = given().get(tableUrl("test-project", dataset, "export_errors_" + DAY) + "/data")
                    .then().statusCode(200).body("totalRows", equalTo("1")).extract().jsonPath();
            assertTrue(errors.getString("rows[0].f[0].v").contains("\"mixed\""));
            assertTrue(errors.getString("rows[0].f[1].v").contains("jsonPayload.tags"));
        } finally {
            deleteSink(id);
        }
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String dataset(String project, String id) {
        String dataset = "logs_" + id.replace("-", "_");
        given().contentType("application/json")
                .body(Map.of("datasetReference", Map.of("projectId", project, "datasetId", dataset)))
                .post("/bigquery/v2/projects/" + project + "/datasets").then().statusCode(200);
        return dataset;
    }

    private static void sink(String id, String destination, String log, boolean partitioned) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("name", id, "destination", destination,
                "filter", "logName=\"" + log + "\""));
        if (partitioned) {
            body.put("bigqueryOptions", Map.of("usePartitionedTables", true));
        }
        given().queryParam("uniqueWriterIdentity", true).contentType("application/json").body(body)
                .post("/v2/" + PARENT + "/sinks").then().statusCode(200);
    }

    private static void deleteSink(String id) {
        given().delete("/v2/" + PARENT + "/sinks/" + id).then().statusCode(200);
    }

    private static void write(String log, Map<String, Object> entry) {
        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("logName", log, "resource", Map.of("type", "global"), "entries", List.of(entry)))
                .post("/v2/entries:write").then().statusCode(200);
    }

    private static String tableUrl(String project, String dataset, String table) {
        return "/bigquery/v2/projects/" + project + "/datasets/" + dataset + "/tables/" + table;
    }
}

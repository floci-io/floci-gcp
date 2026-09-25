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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Load jobs end to end on the real floci-duck sidecar: CSV (explicit schema and auto-detection),
 * newline-delimited JSON auto-detection and Parquet, from floci's GCS and from media uploads.
 * Needs a floci-duck image that reports column types, which the default image does, so this
 * runs whenever Docker is available. {@code -Dfloci.duck.image=...} selects another image.
 */
@QuarkusTest
@TestProfile(BigQueryDuckLoadIntegrationTest.LoadProfile.class)
@EnabledIf("io.floci.gcp.services.bigquery.BigQueryDuckIntegrationTest#dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BigQueryDuckLoadIntegrationTest {

    private static final String PROJECT = "bq-load-it";
    private static final String BASE = "/bigquery/v2/projects/" + PROJECT;
    private static final String BUCKET = "bq-load-it-data";

    public static class LoadProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.services.bigquery.mock", "false",
                    "floci-gcp.services.bigquery.duck.default-image",
                    System.getProperty("floci.duck.image", "floci/floci-duck:latest"),
                    "quarkus.http.test-port", "18590",
                    "floci-gcp.port", "18590",
                    "floci-gcp.docker.resource-namespace", "bq-load-it");
        }
    }

    private static void putObject(String name, String contentType, byte[] data) {
        given().contentType(contentType).body(data)
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=" + name)
                .then().statusCode(200);
    }

    private static Response load(Map<String, Object> config) {
        return given().contentType("application/json")
                .body(Map.of("configuration", Map.of("load", config)))
                .when().post(BASE + "/jobs");
    }

    private static List<List<Object>> rows(String table) {
        return given().when().get(BASE + "/datasets/raw/tables/" + table + "/data")
                .then().statusCode(200).extract().jsonPath().getList("rows.f.v");
    }

    private static Map<String, Object> destination(String table) {
        return Map.of("datasetId", "raw", "tableId", table);
    }

    @Test
    @Order(1)
    void seed() {
        given().contentType("application/json").body("{\"datasetReference\": {\"datasetId\": \"raw\"}}")
                .when().post(BASE + "/datasets").then().statusCode(200);
        given().contentType("application/json").body("{\"name\":\"" + BUCKET + "\",\"location\":\"US\"}")
                .when().post("/storage/v1/b?project=" + PROJECT).then().statusCode(200);
        putObject("people.csv", "text/csv",
                "name,age,joined\nana,30,2024-01-02 03:04:05\nbo,,2024-02-03T04:05:06Z\n\"c, d\",7,\n"
                        .getBytes(StandardCharsets.UTF_8));
        putObject("parts/part-1.csv", "text/csv", "1,x\n2,y\n".getBytes(StandardCharsets.UTF_8));
        putObject("parts/part-2.csv", "text/csv", "3,z\n".getBytes(StandardCharsets.UTF_8));
        putObject("events.json", "application/json",
                "{\"id\": 1, \"tags\": [\"a\"], \"meta\": {\"ok\": true}}\n{\"id\": 2, \"tags\": [], \"meta\": null}\n"
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @Order(2)
    void csvWithExplicitSchemaFromGcs() {
        load(Map.of("sourceUris", List.of("gs://" + BUCKET + "/people.csv"), "skipLeadingRows", 1,
                "destinationTable", destination("people"),
                "schema", Map.of("fields", List.of(
                        Map.of("name", "name", "type", "STRING"),
                        Map.of("name", "age", "type", "INTEGER"),
                        Map.of("name", "joined", "type", "TIMESTAMP")))))
                .then().statusCode(200)
                .body("status.errorResult", nullValue())
                .body("statistics.load.outputRows", equalTo("3"))
                .body("statistics.load.inputFiles", equalTo("1"));
        assertEquals(List.of(
                        java.util.Arrays.asList("ana", "30", "1704164645"),
                        java.util.Arrays.asList("bo", null, "1706933106"),
                        java.util.Arrays.asList("c, d", "7", null)),
                rows("people"));
    }

    @Test
    @Order(3)
    void wildcardUrisLoadEveryMatchingObject() {
        load(Map.of("sourceUris", List.of("gs://" + BUCKET + "/parts/part-*.csv"),
                "destinationTable", destination("parts"),
                "schema", Map.of("fields", List.of(Map.of("name", "id", "type", "INT64"),
                        Map.of("name", "code", "type", "STRING")))))
                .then().statusCode(200)
                .body("status.errorResult", nullValue())
                .body("statistics.load.inputFiles", equalTo("2"))
                .body("statistics.load.outputRows", equalTo("3"));
    }

    @Test
    @Order(4)
    void csvAutodetectWithAndWithoutHeader() {
        load(Map.of("sourceUris", List.of("gs://" + BUCKET + "/people.csv"), "autodetect", true,
                "destinationTable", destination("people_auto")))
                .then().statusCode(200).body("status.errorResult", nullValue());
        given().when().get(BASE + "/datasets/raw/tables/people_auto").then()
                .body("schema.fields.name", equalTo(List.of("name", "age", "joined")))
                .body("schema.fields.type", equalTo(List.of("STRING", "INTEGER", "TIMESTAMP")));

        load(Map.of("sourceUris", List.of("gs://" + BUCKET + "/parts/part-1.csv"), "autodetect", true,
                "skipLeadingRows", 0, "destinationTable", destination("headerless")))
                .then().statusCode(200).body("status.errorResult", nullValue());
        given().when().get(BASE + "/datasets/raw/tables/headerless").then()
                .body("schema.fields.name", equalTo(List.of("int64_field_0", "string_field_1")));
    }

    @Test
    @Order(5)
    void jsonAutodetectKeepsNestedAndRepeatedFields() {
        load(Map.of("sourceUris", List.of("gs://" + BUCKET + "/events.json"), "autodetect", true,
                "sourceFormat", "NEWLINE_DELIMITED_JSON", "destinationTable", destination("events")))
                .then().statusCode(200).body("status.errorResult", nullValue());
        given().when().get(BASE + "/datasets/raw/tables/events").then()
                .body("schema.fields.name", equalTo(List.of("id", "tags", "meta")))
                .body("schema.fields[1].mode", equalTo("REPEATED"))
                .body("schema.fields[2].type", equalTo("RECORD"));
        given().when().get(BASE + "/datasets/raw/tables/events/data").then()
                .body("rows[0].f[1].v[0].v", equalTo("a"))
                .body("rows[0].f[2].v.f[0].v", equalTo("true"));
    }

    @Test
    @Order(6)
    void parquetUploadCarriesItsOwnSchema() throws IOException {
        byte[] parquet;
        try (InputStream in = getClass().getResourceAsStream("/bigquery/people.parquet")) {
            parquet = in.readAllBytes();
        }
        String boundary = "floci_parquet";
        byte[] head = ("--" + boundary + "\r\nContent-Type: application/json\r\n\r\n"
                + "{\"configuration\": {\"load\": {\"sourceFormat\": \"PARQUET\","
                + " \"destinationTable\": {\"datasetId\": \"raw\", \"tableId\": \"people_parquet\"}}}}"
                + "\r\n--" + boundary + "\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + parquet.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(parquet, 0, body, head.length, parquet.length);
        System.arraycopy(tail, 0, body, head.length + parquet.length, tail.length);

        given().contentType("multipart/related; boundary=" + boundary).body(body)
                .when().post("/upload/bigquery/v2/projects/" + PROJECT + "/jobs?uploadType=multipart")
                .then().statusCode(200)
                .body("status.errorResult", nullValue())
                .body("statistics.load.outputRows", equalTo("2"));
        given().when().get(BASE + "/datasets/raw/tables/people_parquet").then()
                .body("schema.fields.name", equalTo(List.of("id", "name", "seen_at", "local_time", "amount")))
                .body("schema.fields.type", equalTo(List.of("INTEGER", "STRING", "TIMESTAMP", "DATETIME", "NUMERIC")));
        assertEquals(List.of("1", "ana", "1704164645", "2024-01-02T03:04:05", "1.25"), rows("people_parquet").get(0));

        // A second Parquet load appends into the existing table, matching columns by name.
        given().contentType("multipart/related; boundary=" + boundary).body(body)
                .when().post("/upload/bigquery/v2/projects/" + PROJECT + "/jobs?uploadType=multipart")
                .then().statusCode(200).body("status.errorResult", nullValue());
        assertEquals(4, rows("people_parquet").size());
    }

    @Test
    @Order(7)
    void badCsvDataFailsTheJob() {
        putObject("bad.csv", "text/csv", "id\nnot-a-number\n".getBytes(StandardCharsets.UTF_8));
        load(Map.of("sourceUris", List.of("gs://" + BUCKET + "/bad.csv"), "skipLeadingRows", 1,
                "destinationTable", destination("bad"),
                "schema", Map.of("fields", List.of(Map.of("name", "id", "type", "INT64")))))
                .then().statusCode(200)
                .body("status.errorResult.reason", equalTo("invalid"));
        load(Map.of("sourceUris", List.of("gs://" + BUCKET + "/missing.csv"), "autodetect", true,
                "destinationTable", destination("missing")))
                .then().statusCode(200)
                .body("status.errorResult.reason", equalTo("notFound"));
    }

    @Test
    @Order(8)
    void autodetectAppendCannotChangeAnExistingColumnType() {
        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "typed"}, "schema": {"fields": [
                  {"name": "id", "type": "INTEGER"}, {"name": "city", "type": "STRING"}]}}
                """)
                .when().post(BASE + "/datasets/raw/tables").then().statusCode(200);

        // Autodetect infers `id` as STRING from this file. Appending it must not store text in the
        // INTEGER column the table already declares.
        putObject("typed-text.csv", "text/csv", "id,city\nabc,Lima\n".getBytes(StandardCharsets.UTF_8));
        load(Map.of("sourceUris", List.of("gs://" + BUCKET + "/typed-text.csv"), "autodetect", true,
                "writeDisposition", "WRITE_APPEND", "destinationTable", destination("typed")))
                .then().statusCode(200)
                .body("status.errorResult.reason", equalTo("invalid"));
        assertEquals(0, rows("typed") == null ? 0 : rows("typed").size());
        given().when().get(BASE + "/datasets/raw/tables/typed").then().statusCode(200)
                .body("schema.fields[0].type", equalTo("INTEGER"));

        // Numeric text is coerced to the declared type, as the destination schema requires.
        putObject("typed-number.csv", "text/csv", "id,city\n7,Quito\n".getBytes(StandardCharsets.UTF_8));
        load(Map.of("sourceUris", List.of("gs://" + BUCKET + "/typed-number.csv"), "autodetect", true,
                "writeDisposition", "WRITE_APPEND", "destinationTable", destination("typed")))
                .then().statusCode(200)
                .body("status.errorResult", nullValue());
        assertEquals(List.of(List.of("7", "Quito")), rows("typed"));
    }
}

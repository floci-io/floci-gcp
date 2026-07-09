package io.floci.gcp.services.bigquery;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BigQueryRestIntegrationTest {

    private static final String PROJECT = "bq-rest-it";
    private static final String BASE = "/bigquery/v2/projects/" + PROJECT;

    private static String queryJobId;

    @Test
    @Order(1)
    void createDatasetAndTableWireShapes() {
        given()
                .contentType("application/json")
                .body("""
                        {"datasetReference": {"datasetId": "ds1"}, "friendlyName": "DS One"}
                        """)
                .when().post(BASE + "/datasets")
                .then()
                .statusCode(200)
                .body("kind", equalTo("bigquery#dataset"))
                .body("id", equalTo(PROJECT + ":ds1"))
                .body("datasetReference.projectId", equalTo(PROJECT));

        // Duplicate → 409 with legacy reason "duplicate"
        given()
                .contentType("application/json")
                .body("""
                        {"datasetReference": {"datasetId": "ds1"}}
                        """)
                .when().post(BASE + "/datasets")
                .then()
                .statusCode(409)
                .body("error.errors[0].reason", equalTo("duplicate"));

        given()
                .contentType("application/json")
                .body("""
                        {"tableReference": {"tableId": "t1"},
                         "schema": {"fields": [
                            {"name": "name", "type": "STRING"},
                            {"name": "age", "type": "INT64"},
                            {"name": "tags", "type": "STRING", "mode": "REPEATED"}]}}
                        """)
                .when().post(BASE + "/datasets/ds1/tables")
                .then()
                .statusCode(200)
                .body("kind", equalTo("bigquery#table"))
                .body("id", equalTo(PROJECT + ":ds1.t1"))
                .body("type", equalTo("TABLE"))
                .body("schema.fields[1].type", equalTo("INTEGER"))
                .body("schema.fields[0].mode", equalTo("NULLABLE"));
    }

    @Test
    @Order(2)
    void insertAllValidRowsAndPerRowErrors() {
        given()
                .contentType("application/json")
                .body("""
                        {"rows": [
                            {"json": {"name": "ana", "age": 30, "tags": ["a", "b"]}},
                            {"json": {"name": "bo", "age": 25, "tags": []}}]}
                        """)
                .when().post(BASE + "/datasets/ds1/tables/t1/insertAll")
                .then()
                .statusCode(200)
                .body("kind", equalTo("bigquery#tableDataInsertAllResponse"))
                .body("insertErrors", nullValue());

        // Unknown field without ignoreUnknownValues → HTTP 200 + per-row error
        given()
                .contentType("application/json")
                .body("""
                        {"rows": [{"json": {"name": "x", "bogus": 1}}]}
                        """)
                .when().post(BASE + "/datasets/ds1/tables/t1/insertAll")
                .then()
                .statusCode(200)
                .body("insertErrors", hasSize(1))
                .body("insertErrors[0].index", equalTo(0))
                .body("insertErrors[0].errors[0].reason", equalTo("invalid"));
    }

    @Test
    @Order(3)
    void tableDataListEncodesRowsAndPages() {
        given()
                .queryParam("maxResults", 1)
                .when().get(BASE + "/datasets/ds1/tables/t1/data")
                .then()
                .statusCode(200)
                .body("kind", equalTo("bigquery#tableDataList"))
                .body("totalRows", equalTo("2"))
                .body("rows", hasSize(1))
                .body("rows[0].f[0].v", equalTo("ana"))
                .body("rows[0].f[1].v", equalTo("30"))
                .body("rows[0].f[2].v[0].v", equalTo("a"))
                .body("pageToken", notNullValue());

        // maxResults=0 must return zero rows (Job.waitFor contract)
        given()
                .queryParam("maxResults", 0)
                .when().get(BASE + "/datasets/ds1/tables/t1/data")
                .then()
                .statusCode(200)
                .body("totalRows", equalTo("2"))
                .body("rows", nullValue());
    }

    @Test
    @Order(4)
    void queryFastPathReturnsCompleteResponse() {
        queryJobId = given()
                .contentType("application/json")
                .body("""
                        {"query": "SELECT name FROM ds1.t1 WHERE age = 30", "useLegacySql": false}
                        """)
                .when().post(BASE + "/queries")
                .then()
                .statusCode(200)
                .body("kind", equalTo("bigquery#queryResponse"))
                .body("jobComplete", equalTo(true))
                .body("schema.fields", hasSize(1))
                .body("totalRows", equalTo("1"))
                .body("rows[0].f[0].v", equalTo("ana"))
                .body("jobReference.projectId", equalTo(PROJECT))
                .extract().path("jobReference.jobId");
    }

    @Test
    @Order(5)
    void getQueryResultsAndJobCarryDestinationTable() {
        given()
                .when().get(BASE + "/queries/" + queryJobId)
                .then()
                .statusCode(200)
                .body("kind", equalTo("bigquery#getQueryResultsResponse"))
                .body("jobComplete", equalTo(true))
                .body("totalRows", equalTo("1"));

        given()
                .when().get(BASE + "/jobs/" + queryJobId)
                .then()
                .statusCode(200)
                .body("kind", equalTo("bigquery#job"))
                .body("status.state", equalTo("DONE"))
                .body("configuration.query.destinationTable.datasetId", equalTo("_floci_anon"))
                .body("statistics.creationTime", notNullValue());
    }

    @Test
    @Order(6)
    void invalidSqlPathsDiffer() {
        // jobs.query → HTTP 400 invalidQuery
        given()
                .contentType("application/json")
                .body("""
                        {"query": "SELECT name FROM ds1.t1 GROUP BY name"}
                        """)
                .when().post(BASE + "/queries")
                .then()
                .statusCode(400)
                .body("error.errors[0].reason", equalTo("invalidQuery"));

        // jobs.insert → HTTP 200 DONE job with errorResult
        given()
                .contentType("application/json")
                .body("""
                        {"configuration": {"query": {"query": "SELECT name FROM ds1.t1 ORDER BY name"}}}
                        """)
                .when().post(BASE + "/jobs")
                .then()
                .statusCode(200)
                .body("status.state", equalTo("DONE"))
                .body("status.errorResult.reason", equalTo("invalidQuery"));
    }

    @Test
    @Order(7)
    void jobLifecycleCancelAndDelete() {
        String jobId = given()
                .contentType("application/json")
                .body("""
                        {"configuration": {"query": {"query": "SELECT COUNT(*) FROM ds1.t1"}}}
                        """)
                .when().post(BASE + "/jobs")
                .then()
                .statusCode(200)
                .body("status.state", equalTo("DONE"))
                .extract().path("jobReference.jobId");

        given()
                .when().post(BASE + "/jobs/" + jobId + "/cancel")
                .then()
                .statusCode(200)
                .body("kind", equalTo("bigquery#jobCancelResponse"))
                .body("job.jobReference.jobId", equalTo(jobId));

        given()
                .when().delete(BASE + "/jobs/" + jobId + "/delete")
                .then()
                .statusCode(204);

        given()
                .when().get(BASE + "/jobs/" + jobId)
                .then()
                .statusCode(404)
                .body("error.errors[0].reason", equalTo("notFound"));
    }

    @Test
    @Order(8)
    void deleteSemantics() {
        given()
                .when().delete(BASE + "/datasets/ds1")
                .then()
                .statusCode(400)
                .body("error.errors[0].reason", equalTo("resourceInUse"));

        given()
                .queryParam("deleteContents", true)
                .when().delete(BASE + "/datasets/ds1")
                .then()
                .statusCode(204);

        given()
                .when().get(BASE + "/datasets/ds1")
                .then()
                .statusCode(404)
                .body("error.errors[0].reason", equalTo("notFound"));
    }
}

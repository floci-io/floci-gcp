package io.floci.gcp.services.bigquery;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BigQueryRestIntegrationTest {

    private static final String PROJECT = "bq-rest-it";
    private static final String BASE = "/bigquery/v2/projects/" + PROJECT;

    private static String queryJobId;

    @Test
    @Order(0)
    void datasetAccessEntriesRoundTripOverTheWire() {
        // The hashicorp/google provider writes access[] on create and reads it
        // back on every refresh. If the emulator drops it, `terraform plan`
        // reports a permanent diff on a resource nobody changed.
        given()
                .contentType("application/json")
                .body("""
                        {"datasetReference": {"datasetId": "ds_access"},
                         "access": [
                           {"role": "READER", "userByEmail": "analyst@example.com"},
                           {"role": "READER", "groupByEmail": "team@example.com"},
                           {"role": "WRITER", "specialGroup": "projectWriters"},
                           {"role": "READER", "iamMember": "serviceAccount:svc@example.iam.gserviceaccount.com"},
                           {"role": "READER", "domain": "example.com"}]}
                        """)
                .when().post(BASE + "/datasets")
                .then()
                .statusCode(200)
                .body("access", hasSize(5))
                .body("access[0].userByEmail", equalTo("analyst@example.com"));

        // A GET is the call the provider actually makes on refresh.
        given()
                .when().get(BASE + "/datasets/ds_access")
                .then()
                .statusCode(200)
                .body("access", hasSize(5))
                .body("access[1].groupByEmail", equalTo("team@example.com"))
                .body("access[2].specialGroup", equalTo("projectWriters"))
                .body("access[3].iamMember",
                        equalTo("serviceAccount:svc@example.iam.gserviceaccount.com"))
                .body("access[4].domain", equalTo("example.com"));

        // PATCH omitting access[] must not clear it. The provider PATCHes
        // when only an unrelated attribute such as friendlyName changes.
        given()
                .contentType("application/json")
                .body("""
                        {"friendlyName": "Access DS"}
                        """)
                .when().patch(BASE + "/datasets/ds_access")
                .then()
                .statusCode(200)
                .body("friendlyName", equalTo("Access DS"))
                .body("access", hasSize(5));

        // PATCH carrying access[] replaces it wholesale.
        given()
                .contentType("application/json")
                .body("""
                        {"access": [{"role": "OWNER", "userByEmail": "owner@example.com"}]}
                        """)
                .when().patch(BASE + "/datasets/ds_access")
                .then()
                .statusCode(200)
                .body("access", hasSize(1))
                .body("access[0].role", equalTo("OWNER"));

        // PUT is a full replacement, so an omitted access[] is a cleared one.
        given()
                .contentType("application/json")
                .body("""
                        {"friendlyName": "Replaced"}
                        """)
                .when().put(BASE + "/datasets/ds_access")
                .then()
                .statusCode(200)
                .body("access", nullValue());
    }

    @Test
    @Order(0)
    void updateModeOverTheWireProtectsTheAclOnAMetadataOnlyWrite() {
        given()
                .contentType("application/json")
                .body("""
                        {"datasetReference": {"datasetId": "ds_mode"},
                         "description": "original",
                         "access": [{"role": "READER", "userByEmail": "analyst@example.com"}]}
                        """)
                .when().post(BASE + "/datasets")
                .then().statusCode(200).body("access", hasSize(1));

        // A PUT is a full replacement, so without updateMode this body would
        // clear the ACL. That is the case the parameter exists for.
        given()
                .contentType("application/json")
                .body("""
                        {"friendlyName": "metadata only"}
                        """)
                .when().put(BASE + "/datasets/ds_mode?updateMode=UPDATE_METADATA")
                .then()
                .statusCode(200)
                .body("friendlyName", equalTo("metadata only"))
                .body("description", nullValue())
                .body("access", hasSize(1));

        // UPDATE_ACL is the mirror: the ACL is replaced, metadata is untouched.
        given()
                .contentType("application/json")
                .body("""
                        {"access": [{"role": "OWNER", "userByEmail": "owner@example.com"}]}
                        """)
                .when().put(BASE + "/datasets/ds_mode?updateMode=UPDATE_ACL")
                .then()
                .statusCode(200)
                .body("friendlyName", equalTo("metadata only"))
                .body("access[0].role", equalTo("OWNER"));

        // An unknown value is rejected rather than guessed at.
        given()
                .contentType("application/json")
                .body("{}")
                .when().put(BASE + "/datasets/ds_mode?updateMode=UPDATE_SOMETHING")
                .then().statusCode(400);
    }

    @Test
    @Order(0)
    void nestedAccessVariantsRoundTripOverTheWire() {
        given()
                .contentType("application/json")
                .body("""
                        {"datasetReference": {"datasetId": "ds_nested"},
                         "access": [
                           {"view": {"projectId": "p", "datasetId": "d", "tableId": "auth_view"}},
                           {"routine": {"projectId": "p", "datasetId": "d", "routineId": "auth_routine"}},
                           {"dataset": {"dataset": {"projectId": "p", "datasetId": "linked"},
                                        "targetTypes": ["VIEWS"]}}]}
                        """)
                .when().post(BASE + "/datasets")
                .then().statusCode(200);

        given()
                .when().get(BASE + "/datasets/ds_nested")
                .then()
                .statusCode(200)
                .body("access", hasSize(3))
                .body("access[0].view.tableId", equalTo("auth_view"))
                .body("access[1].routine.routineId", equalTo("auth_routine"))
                .body("access[2].dataset.dataset.datasetId", equalTo("linked"))
                .body("access[2].dataset.targetTypes[0]", equalTo("VIEWS"));
    }

    @Test
    @Order(0)
    void accessConditionRoundTripsOverTheWire() {
        // A conditional binding is what google_bigquery_dataset emits for an
        // access block with a condition {}. The provider sends all four Expr
        // fields and reads them back, so dropping any of them is a permanent
        // diff on an ACL that otherwise looks right.
        given()
                .contentType("application/json")
                .body("""
                        {"datasetReference": {"datasetId": "ds_condition"},
                         "access": [
                           {"role": "READER",
                            "userByEmail": "analyst@example.com",
                            "condition": {
                              "expression": "request.time < timestamp('2030-01-01T00:00:00Z')",
                              "title": "expires_2030",
                              "description": "temporary access for the analyst",
                              "location": "dataset.tf:12"}},
                           {"role": "OWNER", "userByEmail": "owner@example.com"}]}
                        """)
                .when().post(BASE + "/datasets")
                .then()
                .statusCode(200)
                .body("access", hasSize(2));

        given()
                .when().get(BASE + "/datasets/ds_condition")
                .then()
                .statusCode(200)
                .body("access[0].condition.expression",
                        equalTo("request.time < timestamp('2030-01-01T00:00:00Z')"))
                .body("access[0].condition.title", equalTo("expires_2030"))
                .body("access[0].condition.description",
                        equalTo("temporary access for the analyst"))
                .body("access[0].condition.location", equalTo("dataset.tf:12"))
                // an unconditional entry stays unconditional rather than
                // growing an empty condition object
                .body("access[1].condition", nullValue());

        // The provider also sends condition on update, not only on create.
        given()
                .contentType("application/json")
                .body("""
                        {"access": [
                           {"role": "WRITER",
                            "userByEmail": "analyst@example.com",
                            "condition": {"expression": "request.time < timestamp('2031-01-01T00:00:00Z')",
                                          "title": "expires_2031"}}]}
                        """)
                .when().patch(BASE + "/datasets/ds_condition")
                .then()
                .statusCode(200)
                .body("access", hasSize(1))
                .body("access[0].condition.title", equalTo("expires_2031"))
                .body("access[0].condition.description", nullValue());
    }

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

        // getQueryResults on the failed job → HTTP 200, jobComplete with embedded errors
        String failedJobId = given()
                .contentType("application/json")
                .body("""
                        {"configuration": {"query": {"query": "SELECT name FROM ds1.t1 ORDER BY name"}}}
                        """)
                .when().post(BASE + "/jobs")
                .then()
                .statusCode(200)
                .extract().path("jobReference.jobId");
        given()
                .when().get(BASE + "/queries/" + failedJobId)
                .then()
                .statusCode(200)
                .body("jobComplete", equalTo(true))
                .body("errors[0].reason", equalTo("invalidQuery"));
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
    void nullCellsKeepTheirValueKey() {
        given().contentType("application/json")
                .body("{\"tableReference\": {\"tableId\": \"t_null\"}, \"schema\": {\"fields\": ["
                        + "{\"name\": \"name\", \"type\": \"STRING\"}, {\"name\": \"age\", \"type\": \"INT64\"}]}}")
                .when().post(BASE + "/datasets/ds1/tables").then().statusCode(200);
        given().contentType("application/json")
                .body("{\"rows\": [{\"json\": {\"name\": \"nul\"}}]}")
                .when().post(BASE + "/datasets/ds1/tables/t_null/insertAll").then().statusCode(200);

        // A NULL cell is {"v": null}; the Python client reads cell["v"] unconditionally.
        given()
                .when().get(BASE + "/datasets/ds1/tables/t_null/data")
                .then()
                .statusCode(200)
                .body("rows[0].f[1]", hasKey("v"))
                .body("rows[0].f[1].v", nullValue());
    }

    @Test
    @Order(9)
    void internalNdjsonRouteAnswersGetAndHead() {
        String path = "/_floci-gcp/bigquery/projects/" + PROJECT + "/datasets/ds1/tables/t1/rows.ndjson";
        String body = given().when().get(path).then().statusCode(200).extract().asString();
        assertTrue(body.contains("\"name\":\"ana\""), body);

        // DuckDB's httpfs probes with HEAD before reading. Deriving HEAD from the streaming GET
        // left that probe hanging until httpfs timed out, so the route answers HEAD itself.
        given().when().head(path).then().statusCode(200).body(emptyOrNullString());
    }

    @Test
    @Order(10)
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

    @Test
    @Order(9)
    void tableMetadataRoundTripsOverRest() {
        given().contentType("application/json")
                .body("""
                        {"datasetReference": {"datasetId": "meta"}, "defaultTableExpirationMs": 7200000,
                         "defaultCollation": "und:ci"}
                        """)
                .when().post(BASE + "/datasets")
                .then().statusCode(200)
                .body("defaultTableExpirationMs", equalTo("7200000"))
                .body("maxTimeTravelHours", equalTo("168"))
                .body("type", equalTo("DEFAULT"));

        given().contentType("application/json")
                .body("""
                        {"tableReference": {"tableId": "events"},
                         "schema": {"fields": [{"name": "day", "type": "DATE"}]},
                         "timePartitioning": {"type": "DAY", "field": "day", "expirationMs": null},
                         "clustering": {"fields": ["day"]}, "numRows": "999"}
                        """)
                .when().post(BASE + "/datasets/meta/tables")
                .then().statusCode(200)
                .body("timePartitioning.type", equalTo("DAY"))
                .body("timePartitioning.expirationMs", nullValue())
                .body("clustering.fields[0]", equalTo("day"))
                .body("expirationTime", notNullValue())
                .body("location", equalTo("US"))
                .body("numRows", equalTo("0"));

        // PATCH with an explicit null clears the field.
        given().contentType("application/json")
                .body("""
                        {"clustering": null}
                        """)
                .when().patch(BASE + "/datasets/meta/tables/events")
                .then().statusCode(200)
                .body("clustering", nullValue())
                .body("timePartitioning.field", equalTo("day"));

        given().contentType("application/json")
                .body("""
                        {"timePartitioning": {"field": "day"}}
                        """)
                .when().patch(BASE + "/datasets/meta/tables/events")
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalid"));
    }
}

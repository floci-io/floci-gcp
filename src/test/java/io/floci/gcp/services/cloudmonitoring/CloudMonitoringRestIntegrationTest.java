package io.floci.gcp.services.cloudmonitoring;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;

@QuarkusTest
class CloudMonitoringRestIntegrationTest {

    private static final String PROJECT = "monitoring-rest-it";
    private static final String METRIC = "custom.googleapis.com/rest_it/requests";
    private static final String BASE = "/v3/projects/" + PROJECT;

    @Test
    void metricDescriptorAndTimeSeriesRestLifecycle() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant older = now.minusSeconds(120);
        Instant newer = now.minusSeconds(60);

        // Create descriptor with one label
        given()
                .contentType("application/json")
                .body("""
                        {
                          "type": "%s",
                          "metricKind": "GAUGE",
                          "valueType": "DOUBLE",
                          "labels": [{"key": "env", "description": "environment"}]
                        }
                        """.formatted(METRIC))
                .when().post(BASE + "/metricDescriptors")
                .then()
                .statusCode(200)
                .body("name", equalTo(PROJECT_NAME + "/metricDescriptors/" + METRIC))
                .body("type", equalTo(METRIC))
                .body("labels", hasSize(1));

        // Upsert with a new label: labels are unioned, never removed
        given()
                .contentType("application/json")
                .body("""
                        {
                          "type": "%s",
                          "metricKind": "GAUGE",
                          "valueType": "DOUBLE",
                          "labels": [{"key": "region", "description": "region"}]
                        }
                        """.formatted(METRIC))
                .when().post(BASE + "/metricDescriptors")
                .then()
                .statusCode(200)
                .body("labels", hasSize(2));

        // Second descriptor, then page through with pageSize=1
        given()
                .contentType("application/json")
                .body("""
                        {"type": "custom.googleapis.com/rest_it/other", "metricKind": "GAUGE", "valueType": "INT64"}
                        """)
                .when().post(BASE + "/metricDescriptors")
                .then()
                .statusCode(200);

        String nextPageToken = given()
                .queryParam("pageSize", 1)
                .when().get(BASE + "/metricDescriptors")
                .then()
                .statusCode(200)
                .body("metricDescriptors", hasSize(1))
                .body("nextPageToken", not(emptyString()))
                .extract().path("nextPageToken");

        given()
                .queryParam("pageSize", 1)
                .queryParam("pageToken", nextPageToken)
                .when().get(BASE + "/metricDescriptors")
                .then()
                .statusCode(200)
                .body("metricDescriptors", hasSize(1));

        // Write two chronological points
        writePoint(3.5, older);
        writePoint(4.5, newer);

        // Read them back newest-first
        given()
                .queryParam("filter", "metric.type = \"" + METRIC + "\"")
                .queryParam("interval.startTime", now.minusSeconds(3600).toString())
                .queryParam("interval.endTime", now.toString())
                .when().get(BASE + "/timeSeries")
                .then()
                .statusCode(200)
                .body("timeSeries", hasSize(1))
                .body("timeSeries[0].points", hasSize(2))
                .body("timeSeries[0].points[0].interval.endTime", equalTo(newer.toString()))
                .body("timeSeries[0].points[0].value.doubleValue", equalTo(4.5f))
                .body("timeSeries[0].points[1].interval.endTime", equalTo(older.toString()));

        // Aggregated query via aggregation.* query params
        given()
                .queryParam("filter", "metric.type = \"" + METRIC + "\"")
                .queryParam("interval.startTime", now.minusSeconds(3600).toString())
                .queryParam("interval.endTime", now.toString())
                .queryParam("aggregation.alignmentPeriod", "3600s")
                .queryParam("aggregation.perSeriesAligner", "ALIGN_SUM")
                .queryParam("aggregation.crossSeriesReducer", "REDUCE_SUM")
                .when().get(BASE + "/timeSeries")
                .then()
                .statusCode(200)
                .body("timeSeries", hasSize(1))
                .body("timeSeries[0].points", hasSize(1))
                .body("timeSeries[0].points[0].value.doubleValue", equalTo(8.0f));
    }

    @Test
    void fractionalAlignmentPeriodIsAccepted() {
        given()
                .queryParam("filter", "metric.type = \"custom.googleapis.com/fractional\"")
                .queryParam("interval.startTime", Instant.now().minusSeconds(600).toString())
                .queryParam("interval.endTime", Instant.now().toString())
                .queryParam("aggregation.alignmentPeriod", "60.5s")
                .queryParam("aggregation.perSeriesAligner", "ALIGN_SUM")
                .when().get(BASE + "/timeSeries")
                .then()
                .statusCode(200);
    }

    @Test
    void listTimeSeriesWithoutFilterReturnsInvalidArgument() {
        given()
                .queryParam("interval.endTime", Instant.now().toString())
                .when().get(BASE + "/timeSeries")
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void deleteSystemMetricReturnsInvalidArgument() {
        given()
                .when().delete(BASE + "/metricDescriptors/compute.googleapis.com/instance/cpu/usage_time")
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    private static final String PROJECT_NAME = "projects/" + PROJECT;

    private static void writePoint(double value, Instant end) {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "timeSeries": [{
                            "metric": {"type": "%s", "labels": {"env": "local"}},
                            "resource": {"type": "global"},
                            "points": [{
                              "interval": {"endTime": "%s"},
                              "value": {"doubleValue": %s}
                            }]
                          }]
                        }
                        """.formatted(METRIC, end, value))
                .when().post(BASE + "/timeSeries")
                .then()
                .statusCode(200);
    }
}

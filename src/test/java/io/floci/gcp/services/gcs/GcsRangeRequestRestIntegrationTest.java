package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Range requests: the byte ranges that are served, the ones GCS ignores rather than rejects, and
 * the two that stay errors. Only the XML API advertises range support.
 */
@QuarkusTest
class GcsRangeRequestRestIntegrationTest {

    private static final String BUCKET = "range-request-bucket";
    private static final String OBJECT = "r.txt";
    private static final String BODY = "0123456789ab";

    private static void ensureObject() {
        given().contentType("application/json").body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project");
        given().contentType("text/plain").body(BODY)
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=" + OBJECT)
                .then().statusCode(200);
    }

    @Test
    void xmlByteRangesAreServedAndAdvertised() {
        ensureObject();

        given().header("Range", "bytes=0-4")
                .when().get("/" + BUCKET + "/" + OBJECT)
                .then().statusCode(206)
                .header("Accept-Ranges", equalTo("bytes"))
                .header("Content-Range", equalTo("bytes 0-4/" + BODY.length()))
                .header("Content-Length", equalTo("5"))
                .body(equalTo("01234"));

        // A suffix range and an open-ended range both end on the last byte of the object.
        for (String range : new String[] {"bytes=-5", "bytes=7-"}) {
            given().header("Range", range)
                    .when().get("/" + BUCKET + "/" + OBJECT)
                    .then().statusCode(206)
                    .header("Content-Range", equalTo("bytes 7-11/" + BODY.length()))
                    .body(equalTo("789ab"));
        }

        // The unit is case-insensitive and tolerates whitespace around the separator.
        for (String range : new String[] {"BYTES=0-4", "bytes = 0-4"}) {
            given().header("Range", range)
                    .when().get("/" + BUCKET + "/" + OBJECT)
                    .then().statusCode(206)
                    .header("Accept-Ranges", equalTo("bytes"))
                    .body(equalTo("01234"));
        }
    }

    @Test
    void headReportsTheRangeItWouldServe() {
        ensureObject();

        given().header("Range", "bytes=0-4")
                .when().head("/" + BUCKET + "/" + OBJECT)
                .then().statusCode(206)
                .header("Accept-Ranges", equalTo("bytes"))
                .header("Content-Range", equalTo("bytes 0-4/" + BODY.length()))
                .header("Content-Length", equalTo("5"));
    }

    @Test
    void jsonMediaResponsesServeRangesWithoutAdvertisingThem() {
        ensureObject();

        given().header("Range", "bytes=0-4")
                .when().get("/download/storage/v1/b/" + BUCKET + "/o/" + OBJECT + "?alt=media")
                .then().statusCode(206)
                .header("Content-Range", equalTo("bytes 0-4/" + BODY.length()))
                .header("Accept-Ranges", nullValue())
                .body(equalTo("01234"));

        given().when().get("/storage/v1/b/" + BUCKET + "/o/" + OBJECT + "?alt=media")
                .then().statusCode(200)
                .header("Accept-Ranges", nullValue())
                .body(equalTo(BODY));
    }

    @Test
    void unparseableAndOverlappingRangesServeTheWholeObject() {
        ensureObject();

        String[] ignored = {
                "bytes=abc", "bytes=", "bytes=5-1", "bytes=0-4,", "items=0-4", "invalid",
                "bytes=0-4,3-6", "bytes=0-9,2-3", "bytes=0-4,0-4"
        };
        for (String range : ignored) {
            Response response = given().header("Range", range).when().get("/" + BUCKET + "/" + OBJECT);
            assertEquals(200, response.statusCode(), range);
            assertEquals(BODY, response.asString(), range);
            assertNull(response.header("Content-Range"), range);
            assertEquals("bytes", response.header("Accept-Ranges"), range);
        }
    }

    @Test
    void disjointRangeSetsAndUnsatisfiableRangesAreRejected() {
        ensureObject();

        for (String range : new String[] {"bytes=0-4,5-9", "bytes=0-1,3-4"}) {
            given().header("Range", range)
                    .when().get("/" + BUCKET + "/" + OBJECT)
                    .then().statusCode(400)
                    .body(containsString("<Code>InvalidArgument</Code>"));
        }

        // A first byte past the end and a zero-length suffix are unsatisfiable, not malformed.
        for (String range : new String[] {"bytes=" + BODY.length() + "-", "bytes=-0"}) {
            given().header("Range", range)
                    .when().get("/" + BUCKET + "/" + OBJECT)
                    .then().statusCode(416)
                    .body(containsString("<Code>InvalidRange</Code>"));
        }
    }
}

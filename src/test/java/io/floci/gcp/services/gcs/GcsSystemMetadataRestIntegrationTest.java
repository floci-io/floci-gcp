package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * System metadata a client sets at upload time (contentEncoding, customTime, ...) and
 * zero-length objects, which the Node SDK writes through a resumable session.
 */
@QuarkusTest
class GcsSystemMetadataRestIntegrationTest {

    private static final String BUCKET = "system-metadata-bucket";

    private static void ensureBucket() {
        given().contentType("application/json").body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project");
    }

    @Test
    void mediaUploadHonorsContentEncodingQueryParameter() {
        ensureBucket();
        given().contentType("text/plain").body("compressed-bytes")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=ce-media&contentEncoding=gzip")
                .then().statusCode(200)
                .body("contentEncoding", equalTo("gzip"));
    }

    /**
     * objects.insert takes contentEncoding on the URL but no other system metadata. GCS
     * silently ignores the rest, so an object uploaded this way lands without them.
     */
    @Test
    void mediaUploadIgnoresOtherSystemMetadataQueryParameters() {
        ensureBucket();
        given().contentType("text/plain").body("x")
                .when().post("/upload/storage/v1/b/" + BUCKET
                        + "/o?uploadType=media&name=cc-media&cacheControl=max-age=3600"
                        + "&customTime=2026-01-15T10:30:00Z&contentLanguage=ja&storageClass=NEARLINE")
                .then().statusCode(200)
                .body("cacheControl", nullValue())
                .body("customTime", nullValue())
                .body("contentLanguage", nullValue())
                .body("storageClass", equalTo("STANDARD"));

        given().when().get("/storage/v1/b/" + BUCKET + "/o/cc-media")
                .then().statusCode(200)
                .body("cacheControl", nullValue())
                .body("customTime", nullValue());
    }

    @Test
    void patchSetsCacheControl() {
        ensureBucket();
        given().contentType("text/plain").body("x")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=cc-patch")
                .then().statusCode(200);
        given().contentType("application/json").body("{\"cacheControl\":\"no-store\"}")
                .when().patch("/storage/v1/b/" + BUCKET + "/o/cc-patch")
                .then().statusCode(200)
                .body("cacheControl", equalTo("no-store"));
    }

    /** GCS renders customTime in UTC with 0, 3, 6 or 9 fraction digits, whatever was sent. */
    @Test
    void customTimeIsRenderedTheWayGcsRendersIt() {
        ensureBucket();
        given().header("Content-Type", "multipart/related; boundary=ct")
                .body(multipart("ct", "{\"name\":\"ct-render\",\"customTime\":\"2026-01-15T19:30:00.120+09:00\"}"))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=multipart")
                .then().statusCode(200)
                .body("customTime", equalTo("2026-01-15T10:30:00.120Z"));

        given().contentType("application/json").body(Map.of("customTime", "2026-01-15T10:30:01.123456Z"))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/ct-render")
                .then().statusCode(200)
                .body("customTime", equalTo("2026-01-15T10:30:01.123456Z"));
    }

    @Test
    void patchWithANullCustomTimeKeepsTheValue() {
        ensureBucket();
        given().header("Content-Type", "multipart/related; boundary=ct")
                .body(multipart("ct", "{\"name\":\"ct-keep\",\"customTime\":\"2026-02-01T00:00:00Z\"}"))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=multipart")
                .then().statusCode(200);

        given().contentType("application/json").body("{\"customTime\":null}")
                .when().patch("/storage/v1/b/" + BUCKET + "/o/ct-keep")
                .then().statusCode(200)
                .body("customTime", equalTo("2026-02-01T00:00:00Z"));
    }

    @Test
    void patchRejectsADecreasedCustomTime() {
        ensureBucket();
        given().header("Content-Type", "multipart/related; boundary=ct")
                .body(multipart("ct", "{\"name\":\"ct-decrease\",\"customTime\":\"2026-02-01T00:00:00.5Z\"}"))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=multipart")
                .then().statusCode(200);

        String message = """
                Custom time cannot be decreased. Previously: 2026-02-01T00:00:00.5+00:00. \
                Attempting to set: 2026-02-01T00:00:00.25+00:00.""";
        given().contentType("application/json").body(Map.of("customTime", "2026-02-01T00:00:00.25Z"))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/ct-decrease")
                .then().statusCode(400)
                .body("error.message", equalTo(message))
                .body("error.errors[0].reason", equalTo("invalid"));

        given().contentType("application/json").body(Map.of("customTime", "2026-02-01T00:00:00.500Z"))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/ct-decrease")
                .then().statusCode(200)
                .body("customTime", equalTo("2026-02-01T00:00:00.500Z"));
    }

    @Test
    void unparsableCustomTimeIsRejectedWithTheGcsMessage() {
        ensureBucket();
        String message = """
                Parse Error: Invalid value for type.googleapis.com/google.protobuf.Timestamp field: \
                'Field 'customTime', Illegal timestamp format; timestamps must end with 'Z' or have \
                a valid timezone offset.'.""";
        given().header("Content-Type", "multipart/related; boundary=ct")
                .body(multipart("ct", "{\"name\":\"ct-invalid\",\"customTime\":\"\"}"))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=multipart")
                .then().statusCode(400)
                .body("error.message", equalTo(message));

        given().contentType("text/plain").body("x")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=ct-invalid")
                .then().statusCode(200);
        given().contentType("application/json").body(Map.of("customTime", "2027-02-01T00:00:00"))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/ct-invalid")
                .then().statusCode(400)
                .body("error.message", equalTo(message))
                .body("error.errors[0].reason", equalTo("invalid"));
    }

    private static byte[] multipart(String boundary, String metadataJson) {
        return """
                --%1$s
                Content-Type: application/json

                %2$s
                --%1$s
                Content-Type: text/plain

                x
                --%1$s--
                """.formatted(boundary, metadataJson).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void multipartUploadHonorsContentEncodingInTheMetadataPart() {
        ensureBucket();
        given().header("Content-Type", "multipart/related; boundary=sysmeta")
                .body(multipart("sysmeta", """
                        {"name":"ce-multipart","contentEncoding":"gzip","customTime":"2026-01-15T10:30:00.000Z"}"""))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=multipart")
                .then().statusCode(200)
                .body("contentEncoding", equalTo("gzip"))
                .body("customTime", equalTo("2026-01-15T10:30:00Z"));
    }

    @Test
    void patchSetsCustomTime() {
        ensureBucket();
        given().contentType("text/plain").body("x")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=ct-patch");

        given().contentType("application/json").body(Map.of("customTime", "2026-06-20T08:00:00.000Z"))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/ct-patch")
                .then().statusCode(200)
                .body("customTime", equalTo("2026-06-20T08:00:00Z"));
    }

    @Test
    void resumableSessionCarriesSystemMetadataToTheFinalizedObject() {
        ensureBucket();
        String location = given()
                .contentType("application/json")
                .body("{\"name\":\"ce-resumable\",\"contentEncoding\":\"gzip\"}")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable")
                .then().statusCode(200)
                .extract().header("Location");

        given().header("Content-Range", "bytes 0-4/5").body("hello")
                .when().put(location)
                .then().statusCode(200)
                .body("contentEncoding", equalTo("gzip"));
    }

    /**
     * The Node SDK streams every write through a resumable session. With no bytes to size the
     * range from it sends the open-ended "bytes 0-*&#47;*" with an empty body, which used to be
     * rejected outright, so file.save("") could never create an object. Empty objects are
     * common in practice: Spark _SUCCESS markers, .keep files, directory placeholders.
     */
    @Test
    void resumableSessionAcceptsAnOpenEndedRangeWithAnEmptyBody() {
        ensureBucket();
        String location = given()
                .contentType("application/json").body("{\"name\":\"empty-resumable\"}")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable")
                .then().statusCode(200)
                .extract().header("Location");

        given().header("Content-Range", "bytes 0-*/*").body(new byte[0])
                .when().put(location)
                .then().statusCode(200)
                .body("size", equalTo("0"));

        given().when().get("/storage/v1/b/" + BUCKET + "/o/empty-resumable")
                .then().statusCode(200)
                .body("size", equalTo("0"));
    }

    @Test
    void openEndedRangeWithAPayloadStillFinalizesAtTheRightSize() {
        ensureBucket();
        String location = given()
                .contentType("application/json").body("{\"name\":\"open-ended\"}")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable")
                .then().statusCode(200)
                .extract().header("Location");

        given().header("Content-Range", "bytes 0-*/*").body("hello")
                .when().put(location)
                .then().statusCode(200)
                .body("size", equalTo("5"));
    }
}

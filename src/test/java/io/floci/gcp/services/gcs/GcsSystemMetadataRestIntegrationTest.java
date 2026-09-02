package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

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

    @Test
    void mediaUploadHonorsCacheControlQueryParameter() {
        // cacheControl is in the accepted system-metadata set, so it has to persist rather
        // than being silently dropped on the way through.
        ensureBucket();
        given().contentType("text/plain").body("x")
                .when().post("/upload/storage/v1/b/" + BUCKET
                        + "/o?uploadType=media&name=cc-media&cacheControl=max-age=3600")
                .then().statusCode(200)
                .body("cacheControl", equalTo("max-age=3600"));

        given().when().get("/storage/v1/b/" + BUCKET + "/o/cc-media")
                .then().statusCode(200)
                .body("cacheControl", equalTo("max-age=3600"));
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

    @Test
    void mediaUploadHonorsCustomTimeQueryParameter() {
        ensureBucket();
        given().contentType("text/plain").body("x")
                .when().post("/upload/storage/v1/b/" + BUCKET
                        + "/o?uploadType=media&name=ct-media&customTime=2026-01-15T10:30:00.000Z")
                .then().statusCode(200)
                .body("customTime", equalTo("2026-01-15T10:30:00.000Z"));
    }

    @Test
    void multipartUploadHonorsContentEncodingInTheMetadataPart() {
        ensureBucket();
        var boundary = "sysmeta";
        var body = "--" + boundary + "\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + "{\"name\":\"ce-multipart\",\"contentEncoding\":\"gzip\",\"customTime\":\"2026-01-15T10:30:00.000Z\"}\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "compressed-bytes\r\n"
                + "--" + boundary + "--\r\n";

        given().header("Content-Type", "multipart/related; boundary=" + boundary)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=multipart")
                .then().statusCode(200)
                .body("contentEncoding", equalTo("gzip"))
                .body("customTime", equalTo("2026-01-15T10:30:00.000Z"));
    }

    @Test
    void patchSetsCustomTime() {
        ensureBucket();
        given().contentType("text/plain").body("x")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=ct-patch");

        given().contentType("application/json").body(Map.of("customTime", "2026-06-20T08:00:00.000Z"))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/ct-patch")
                .then().statusCode(200)
                .body("customTime", equalTo("2026-06-20T08:00:00.000Z"));
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

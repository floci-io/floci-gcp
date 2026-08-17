package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class GcsUploadMetadataRestIntegrationTest {

    private static final String BUCKET = "upload-metadata-bucket";

    private static void ensureBucket() {
        given()
                .contentType("application/json")
                .body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project");
    }

    @Test
    void multipartUploadPersistsCustomMetadata() {
        ensureBucket();
        var boundary = "boundary123";
        var body = "--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                + "{\"name\":\"multipart-obj\",\"contentType\":\"application/json\","
                + "\"metadata\":{\"originalname\":\"test.json\"}}\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + "{\"a\":1}\r\n"
                + "--" + boundary + "--\r\n";

        given()
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=multipart")
                .then().statusCode(200)
                .body("metadata.originalname", equalTo("test.json"));

        given()
                .when().get("/storage/v1/b/" + BUCKET + "/o/multipart-obj")
                .then().statusCode(200)
                .body("contentType", equalTo("application/json"))
                .body("metadata.originalname", equalTo("test.json"));
    }

    @Test
    void resumableUploadPersistsCustomMetadata() {
        ensureBucket();
        var location = given()
                .contentType("application/json")
                .body(Map.of(
                        "name", "resumable-obj",
                        "contentType", "text/plain",
                        "metadata", Map.of("origname", "test.txt")))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable")
                .then().statusCode(200)
                .extract().header("Location");

        var uploadId = location.substring(location.indexOf("upload_id=") + "upload_id=".length());

        given()
                .contentType("text/plain")
                .body("hello")
                .when().put("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable&upload_id=" + uploadId)
                .then().statusCode(200)
                .body("metadata.origname", equalTo("test.txt"));

        given()
                .when().get("/storage/v1/b/" + BUCKET + "/o/resumable-obj")
                .then().statusCode(200)
                .body("contentType", equalTo("text/plain"))
                .body("metadata.origname", equalTo("test.txt"));
    }

    @Test
    void resumableInitFallsBackToRequestPortWhenHostHasNoPort() {
        ensureBucket();

        given()
                .header("Host", "localhost")
                .contentType("application/json")
                .body(Map.of("name", "portless-host-obj"))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable")
                .then().statusCode(200)
                .header("Location", matchesPattern(
                        "http://localhost:4588/upload/storage/v1/b/" + BUCKET
                                + "/o\\?uploadType=resumable&upload_id=.*"));
    }

    @Test
    void resumableUploadAcceptsIntermediateChunkWithKnownTotalSize() {
        ensureBucket();
        var location = given()
                .contentType("application/json")
                .body(Map.of("name", "known-total-chunks-obj"))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable")
                .then().statusCode(200)
                .extract().header("Location");

        var uploadId = location.substring(location.indexOf("upload_id=") + "upload_id=".length());

        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 0-3/6")
                .body("test".getBytes(StandardCharsets.UTF_8))
                .when().put("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable&upload_id=" + uploadId)
                .then().statusCode(308)
                .header("Range", equalTo("bytes=0-3"));

        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 4-5/6")
                .body("ok".getBytes(StandardCharsets.UTF_8))
                .when().put("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable&upload_id=" + uploadId)
                .then().statusCode(200)
                .body("size", equalTo("6"));
    }

    @Test
    void resumableUploadRejectsInconsistentStatusQueryTotal() {
        ensureBucket();
        var location = given()
                .contentType("application/json")
                .body(Map.of("name", "inconsistent-total-obj"))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable")
                .then().statusCode(200)
                .extract().header("Location");

        var uploadId = location.substring(location.indexOf("upload_id=") + "upload_id=".length());

        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 0-3/6")
                .body("test".getBytes(StandardCharsets.UTF_8))
                .when().put("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable&upload_id=" + uploadId)
                .then().statusCode(308)
                .header("Range", equalTo("bytes=0-3"));

        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes */4")
                .body(new byte[0])
                .when().put("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable&upload_id=" + uploadId)
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void resumableUploadRejectsBodyOnlyCompletionWithInconsistentRetainedTotal() {
        ensureBucket();
        var location = given()
                .contentType("application/json")
                .body(Map.of("name", "body-only-inconsistent-total-obj"))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable")
                .then().statusCode(200)
                .extract().header("Location");

        var uploadId = location.substring(location.indexOf("upload_id=") + "upload_id=".length());

        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 0-3/6")
                .body("test".getBytes(StandardCharsets.UTF_8))
                .when().put("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable&upload_id=" + uploadId)
                .then().statusCode(308)
                .header("Range", equalTo("bytes=0-3"));

        given()
                .contentType("application/octet-stream")
                .body("x".getBytes(StandardCharsets.UTF_8))
                .when().put("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable&upload_id=" + uploadId)
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void xmlPutPersistsGoogMetaHeaders() {
        ensureBucket();
        given()
                .header("x-goog-meta-origname", "test.txt")
                .header("X-Goog-Meta-Reviewer", "jane")
                .contentType("text/plain")
                .body("xml")
                .when().put("/" + BUCKET + "/xml-obj")
                .then().statusCode(200)
                .body("metadata.origname", equalTo("test.txt"))
                .body("metadata.reviewer", equalTo("jane"));

        given()
                .when().get("/storage/v1/b/" + BUCKET + "/o/xml-obj")
                .then().statusCode(200)
                .body("metadata.origname", equalTo("test.txt"))
                .body("metadata.reviewer", equalTo("jane"));
    }

    @Test
    void multipartUploadRejectsScalarMetadata() {
        ensureBucket();
        var boundary = "boundary123";
        var body = "--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                + "{\"name\":\"invalid-scalar-obj\",\"metadata\":\"not-an-object\"}\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "data\r\n"
                + "--" + boundary + "--\r\n";

        given()
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=multipart")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void multipartUploadRejectsNonStringMetadataValues() {
        ensureBucket();
        var boundary = "boundary123";
        var body = "--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                + "{\"name\":\"invalid-value-obj\",\"metadata\":{\"count\":1}}\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "data\r\n"
                + "--" + boundary + "--\r\n";

        given()
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=multipart")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void resumableInitRejectsNonStringMetadataValues() {
        ensureBucket();
        given()
                .contentType("application/json")
                .body("{\"name\":\"invalid-resumable-obj\",\"metadata\":{\"count\":1}}")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void resumableInitKeepsMetadataWhenSiblingFieldsAreNotStrings() {
        ensureBucket();
        var location = given()
                .contentType("application/json")
                .body("{\"contentType\":42,\"metadata\":{\"origname\":\"kept.txt\"}}")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable&name=tolerant-obj")
                .then().statusCode(200)
                .extract().header("Location");

        var uploadId = location.substring(location.indexOf("upload_id=") + "upload_id=".length());

        given()
                .contentType("text/plain")
                .body("hello")
                .when().put("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable&upload_id=" + uploadId)
                .then().statusCode(200)
                .body("metadata.origname", equalTo("kept.txt"));

        given()
                .when().get("/storage/v1/b/" + BUCKET + "/o/tolerant-obj")
                .then().statusCode(200)
                .body("metadata.origname", equalTo("kept.txt"));
    }

    @Test
    void multipartUploadSkipsNullMetadataValues() {
        ensureBucket();
        var boundary = "boundary123";
        var body = "--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                + "{\"name\":\"null-value-obj\",\"metadata\":{\"kept\":\"yes\",\"dropped\":null}}\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "data\r\n"
                + "--" + boundary + "--\r\n";

        given()
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=multipart")
                .then().statusCode(200)
                .body("metadata.kept", equalTo("yes"))
                .body("metadata.dropped", nullValue());
    }

    @Test
    void uploadWithoutMetadataOmitsMetadataField() {
        ensureBucket();
        given()
                .contentType("text/plain")
                .body("plain")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=no-metadata-obj")
                .then().statusCode(200)
                .body("metadata", nullValue());

        given()
                .when().get("/storage/v1/b/" + BUCKET + "/o/no-metadata-obj")
                .then().statusCode(200)
                .body("metadata", nullValue());
    }
}

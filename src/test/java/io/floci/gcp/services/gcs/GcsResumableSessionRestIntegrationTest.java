package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class GcsResumableSessionRestIntegrationTest {

    private static final String BUCKET = "resumable-session-bucket";

    private static void ensureBucket() {
        given()
                .contentType("application/json")
                .body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project");
    }

    private static String startUpload(String objectName) {
        var location = given()
                .contentType("application/json")
                .body(Map.of("name", objectName))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable")
                .then().statusCode(200)
                .extract().header("Location");
        return location.substring(location.indexOf("upload_id=") + "upload_id=".length());
    }

    private static String sessionPath(String uploadId) {
        return "/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable&upload_id=" + uploadId;
    }

    @Test
    void postChunksContinueTheSameSession() {
        ensureBucket();
        var uploadId = startUpload("post-chunked-obj");

        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 0-3/6")
                .body("test".getBytes(StandardCharsets.UTF_8))
                .when().post(sessionPath(uploadId))
                .then().statusCode(308)
                .header("Range", equalTo("bytes=0-3"));

        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 4-5/6")
                .body("ok".getBytes(StandardCharsets.UTF_8))
                .when().post(sessionPath(uploadId))
                .then().statusCode(200)
                .body("name", equalTo("post-chunked-obj"))
                .body("size", equalTo("6"));

        given()
                .when().get("/storage/v1/b/" + BUCKET + "/o/post-chunked-obj?alt=media")
                .then().statusCode(200)
                .body(equalTo("testok"));
    }

    @Test
    void postStatusQueryReportsReceivedBytes() {
        ensureBucket();
        var uploadId = startUpload("post-status-obj");

        given()
                .header("Content-Range", "bytes */6")
                .when().post(sessionPath(uploadId))
                .then().statusCode(308)
                .header("Range", equalTo(null));

        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 0-3/6")
                .body("test".getBytes(StandardCharsets.UTF_8))
                .when().post(sessionPath(uploadId))
                .then().statusCode(308);

        given()
                .header("Content-Range", "bytes */6")
                .when().post(sessionPath(uploadId))
                .then().statusCode(308)
                .header("Range", equalTo("bytes=0-3"));
    }

    @Test
    void resentChunkIsAcknowledgedWithoutAppending() {
        ensureBucket();
        var uploadId = startUpload("resent-chunk-obj");

        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 0-3/6")
                .body("test".getBytes(StandardCharsets.UTF_8))
                .when().post(sessionPath(uploadId))
                .then().statusCode(308)
                .header("Range", equalTo("bytes=0-3"));

        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 0-3/6")
                .body("test".getBytes(StandardCharsets.UTF_8))
                .when().post(sessionPath(uploadId))
                .then().statusCode(308)
                .header("Range", equalTo("bytes=0-3"));

        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 4-5/6")
                .body("ok".getBytes(StandardCharsets.UTF_8))
                .when().post(sessionPath(uploadId))
                .then().statusCode(200)
                .body("size", equalTo("6"));

        given()
                .when().get("/storage/v1/b/" + BUCKET + "/o/resent-chunk-obj?alt=media")
                .then().statusCode(200)
                .body(equalTo("testok"));
    }

    @Test
    void chunkPastTheReceivedBytesIsRetryable() {
        ensureBucket();
        var uploadId = startUpload("gap-chunk-obj");

        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 4-5/6")
                .body("ok".getBytes(StandardCharsets.UTF_8))
                .when().post(sessionPath(uploadId))
                .then().statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"));
    }

    @Test
    void completedSessionReplaysTheObjectMetadata() {
        ensureBucket();
        var uploadId = startUpload("completed-session-obj");

        var generation = given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 0-5/6")
                .body("testok".getBytes(StandardCharsets.UTF_8))
                .when().post(sessionPath(uploadId))
                .then().statusCode(200)
                .extract().path("generation").toString();

        given()
                .header("Content-Range", "bytes */6")
                .when().post(sessionPath(uploadId))
                .then().statusCode(200)
                .body("generation", equalTo(generation));

        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 0-5/6")
                .body("testok".getBytes(StandardCharsets.UTF_8))
                .when().put(sessionPath(uploadId))
                .then().statusCode(200)
                .body("generation", equalTo(generation));
    }

    @Test
    void resumeIncompleteIsOverriddenForClientsThatOptOutOf308() {
        ensureBucket();
        var uploadId = startUpload("no-308-obj");

        given()
                .contentType("application/octet-stream")
                .header("X-GUploader-No-308", "yes")
                .header("Content-Range", "bytes 0-3/6")
                .body("test".getBytes(StandardCharsets.UTF_8))
                .when().post(sessionPath(uploadId))
                .then().statusCode(200)
                .header("X-HTTP-Status-Code-Override", equalTo("308"))
                .header("Range", equalTo("bytes=0-3"));

        given()
                .header("X-GUploader-No-308", "yes")
                .header("Content-Range", "bytes */6")
                .when().post(sessionPath(uploadId))
                .then().statusCode(200)
                .header("X-HTTP-Status-Code-Override", equalTo("308"))
                .header("Range", equalTo("bytes=0-3"));

        given()
                .contentType("application/octet-stream")
                .header("X-GUploader-No-308", "yes")
                .header("Content-Range", "bytes 4-5/6")
                .body("ok".getBytes(StandardCharsets.UTF_8))
                .when().post(sessionPath(uploadId))
                .then().statusCode(200)
                .header("X-HTTP-Status-Code-Override", equalTo(null))
                .body("size", equalTo("6"));
    }

    @Test
    void optingOutOf308LeavesOtherStatusesAlone() {
        ensureBucket();
        var uploadId = startUpload("no-308-gap-obj");

        given()
                .contentType("application/octet-stream")
                .header("X-GUploader-No-308", "yes")
                .header("Content-Range", "bytes 4-5/6")
                .body("ok".getBytes(StandardCharsets.UTF_8))
                .when().post(sessionPath(uploadId))
                .then().statusCode(503);
    }

    @Test
    void resumableInitRejectsANonJsonBody() {
        ensureBucket();
        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 0-3/6")
                .body("test".getBytes(StandardCharsets.UTF_8))
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=resumable&name=stray-chunk-obj")
                .then().statusCode(400)
                .body("error.message", equalTo("Unsupported content with type: application/octet-stream"));
    }

    @Test
    void unknownUploadIdIsNotFound() {
        ensureBucket();
        given()
                .contentType("application/octet-stream")
                .header("Content-Range", "bytes 0-3/6")
                .body("test".getBytes(StandardCharsets.UTF_8))
                .when().post(sessionPath("does-not-exist"))
                .then().statusCode(404);
    }
}

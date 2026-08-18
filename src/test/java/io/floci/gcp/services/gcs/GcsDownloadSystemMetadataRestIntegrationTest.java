package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class GcsDownloadSystemMetadataRestIntegrationTest {

    private static final String BUCKET = "download-system-metadata-bucket";
    private static final String BODY = "hello system metadata";

    private static void ensureBucket() {
        given()
                .contentType("application/json")
                .body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project");
    }

    private static Map<String, Object> upload(String objectName) {
        ensureBucket();
        return given()
                .contentType("text/plain")
                .body(BODY)
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=" + objectName)
                .then().statusCode(200)
                .extract().body().jsonPath().getMap("$");
    }

    @Test
    void jsonAltMediaDownloadEmitsSystemMetadataHeaders() {
        var meta = upload("alt-media-obj");

        given()
                .when().get("/storage/v1/b/" + BUCKET + "/o/alt-media-obj?alt=media")
                .then().statusCode(200)
                .header("ETag", equalTo(meta.get("etag")))
                .header("x-goog-generation", equalTo(meta.get("generation")))
                .header("x-goog-metageneration", equalTo(meta.get("metageneration")))
                .header("x-goog-stored-content-length", equalTo(String.valueOf(BODY.length())))
                .header("x-goog-stored-content-encoding", equalTo("identity"))
                .header("x-goog-storage-class", equalTo("STANDARD"))
                .header("x-goog-hash", equalTo("crc32c=" + meta.get("crc32c") + ",md5=" + meta.get("md5Hash")));
    }

    @Test
    void downloadEndpointEmitsSystemMetadataHeaders() {
        var meta = upload("download-obj");

        given()
                .when().get("/download/storage/v1/b/" + BUCKET + "/o/download-obj?alt=media")
                .then().statusCode(200)
                .header("x-goog-generation", equalTo(meta.get("generation")))
                .header("x-goog-stored-content-length", equalTo(String.valueOf(BODY.length())))
                .header("x-goog-hash", notNullValue());
    }

    @Test
    void xmlDownloadEmitsSystemMetadataHeaders() {
        var meta = upload("xml-obj");

        given()
                .when().get("/" + BUCKET + "/xml-obj")
                .then().statusCode(200)
                .header("x-goog-generation", equalTo(meta.get("generation")))
                .header("x-goog-stored-content-length", equalTo(String.valueOf(BODY.length())))
                .header("x-goog-hash", notNullValue());
    }

    @Test
    void composedObjectOmitsMd5FromHashHeader() {
        upload("compose-src-1");
        upload("compose-src-2");

        var composed = given()
                .contentType("application/json")
                .body(Map.of("sourceObjects",
                        List.of(Map.of("name", "compose-src-1"), Map.of("name", "compose-src-2"))))
                .when().post("/storage/v1/b/" + BUCKET + "/o/composed-obj/compose")
                .then().statusCode(200)
                .body("componentCount", equalTo(2))
                .body("md5Hash", nullValue())
                .extract().body().jsonPath().getMap("$");

        given()
                .when().get("/storage/v1/b/" + BUCKET + "/o/composed-obj?alt=media")
                .then().statusCode(200)
                .header("x-goog-hash", equalTo("crc32c=" + composed.get("crc32c")));
    }

    @Test
    void rangeDownloadReportsFullStoredContentLength() {
        var meta = upload("range-obj");

        given()
                .header("Range", "bytes=0-4")
                .when().get("/" + BUCKET + "/range-obj")
                .then().statusCode(206)
                .header("x-goog-generation", equalTo(meta.get("generation")))
                .header("x-goog-stored-content-length", equalTo(String.valueOf(BODY.length())));
    }
}

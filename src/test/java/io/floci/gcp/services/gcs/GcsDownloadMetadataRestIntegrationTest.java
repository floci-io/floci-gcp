package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class GcsDownloadMetadataRestIntegrationTest {

    private static final String BUCKET = "download-metadata-bucket";

    private static void ensureBucket() {
        given()
                .contentType("application/json")
                .body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project");
    }

    private static void uploadWithMetadata(String objectName) {
        given()
                .header("x-goog-meta-origname", "test.txt")
                .header("x-goog-meta-reviewer", "jane")
                .contentType("text/plain")
                .body("hello metadata")
                .when().put("/" + BUCKET + "/" + objectName)
                .then().statusCode(200);
    }

    @Test
    void xmlDownloadEmitsGoogMetaHeaders() {
        ensureBucket();
        uploadWithMetadata("xml-read-obj");

        given()
                .when().get("/" + BUCKET + "/xml-read-obj")
                .then().statusCode(200)
                .header("x-goog-meta-origname", equalTo("test.txt"))
                .header("x-goog-meta-reviewer", equalTo("jane"));
    }

    @Test
    void jsonAltMediaDownloadEmitsGoogMetaHeaders() {
        ensureBucket();
        uploadWithMetadata("alt-media-obj");

        given()
                .when().get("/storage/v1/b/" + BUCKET + "/o/alt-media-obj?alt=media")
                .then().statusCode(200)
                .header("x-goog-meta-origname", equalTo("test.txt"))
                .header("x-goog-meta-reviewer", equalTo("jane"));
    }

    @Test
    void downloadEndpointEmitsGoogMetaHeaders() {
        ensureBucket();
        uploadWithMetadata("download-obj");

        given()
                .when().get("/download/storage/v1/b/" + BUCKET + "/o/download-obj?alt=media")
                .then().statusCode(200)
                .header("x-goog-meta-origname", equalTo("test.txt"))
                .header("x-goog-meta-reviewer", equalTo("jane"));
    }

    @Test
    void rangeDownloadEmitsGoogMetaHeaders() {
        ensureBucket();
        uploadWithMetadata("range-obj");

        given()
                .header("Range", "bytes=0-4")
                .when().get("/" + BUCKET + "/range-obj")
                .then().statusCode(206)
                .header("Content-Range", equalTo("bytes 0-4/14"))
                .header("x-goog-meta-origname", equalTo("test.txt"))
                .header("x-goog-meta-reviewer", equalTo("jane"));
    }

    @Test
    void downloadWithoutMetadataOmitsGoogMetaHeaders() {
        ensureBucket();
        given()
                .contentType("text/plain")
                .body("plain")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=plain-obj")
                .then().statusCode(200);

        var response = given()
                .when().get("/" + BUCKET + "/plain-obj")
                .then().statusCode(200)
                .extract();
        var hasMetaHeader = response.headers().asList().stream()
                .anyMatch(header -> header.getName().toLowerCase(Locale.ROOT).startsWith("x-goog-meta-"));
        assertFalse(hasMetaHeader, "response must not contain x-goog-meta-* headers");
    }
}

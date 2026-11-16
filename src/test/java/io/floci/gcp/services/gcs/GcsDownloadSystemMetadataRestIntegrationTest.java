package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GcsDownloadSystemMetadataRestIntegrationTest {

    private static final String BUCKET = "download-system-metadata-bucket";
    private static final String BODY = "hello system metadata";
    // IMF-fixdate, so a single-digit day must still be zero-padded.
    private static final String HTTP_DATE = "[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT";

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
                .header("x-goog-stored-content-length", equalTo(String.valueOf(BODY.length())));
    }

    /**
     * The hash header layout is per API: the XML API writes one field per algorithm, the JSON API a
     * single comma-separated field and none at all on a partial read, as GCS does.
     */
    @Test
    void hashHeadersFollowTheApiLayout() {
        var meta = upload("hash-layout-obj");
        List<String> separate = List.of("crc32c=" + meta.get("crc32c"), "md5=" + meta.get("md5Hash"));

        Response xmlFull = given()
                .when().get("/" + BUCKET + "/hash-layout-obj")
                .then().statusCode(200).extract().response();
        assertEquals(separate, xmlFull.headers().getValues("x-goog-hash"));

        Response xmlPartial = given()
                .header("Range", "bytes=0-4")
                .when().get("/" + BUCKET + "/hash-layout-obj")
                .then().statusCode(206).extract().response();
        assertEquals(separate, xmlPartial.headers().getValues("x-goog-hash"));

        Response jsonFull = given()
                .when().get("/storage/v1/b/" + BUCKET + "/o/hash-layout-obj?alt=media")
                .then().statusCode(200).extract().response();
        assertEquals(List.of(String.join(",", separate)), jsonFull.headers().getValues("x-goog-hash"));

        Response jsonPartial = given()
                .header("Range", "bytes=0-4")
                .when().get("/storage/v1/b/" + BUCKET + "/o/hash-layout-obj?alt=media")
                .then().statusCode(206).extract().response();
        assertNull(jsonPartial.header("x-goog-hash"));
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

    /**
     * Every media read carries the object's update time as an HTTP-date, as GCS does. A client that
     * takes it from the response header rather than the resource body fails the read when it is
     * absent, so the value is checked against the resource's {@code updated}, not merely for
     * presence.
     */
    @Test
    void mediaReadsReportTheUpdateTimeAsAnHttpDate() {
        var meta = upload("last-modified-obj");

        assertLastModified(given()
                .when().get("/storage/v1/b/" + BUCKET + "/o/last-modified-obj?alt=media")
                .then().statusCode(200).extract().response(), meta);
        assertLastModified(given()
                .when().get("/download/storage/v1/b/" + BUCKET + "/o/last-modified-obj?alt=media")
                .then().statusCode(200).extract().response(), meta);
        assertLastModified(given()
                .when().get("/" + BUCKET + "/last-modified-obj")
                .then().statusCode(200).extract().response(), meta);
        assertLastModified(given()
                .when().head("/" + BUCKET + "/last-modified-obj")
                .then().statusCode(200).extract().response(), meta);
        assertLastModified(given()
                .header("Range", "bytes=0-4")
                .when().get("/" + BUCKET + "/last-modified-obj")
                .then().statusCode(206).extract().response(), meta);
    }

    private static void assertLastModified(Response response, Map<String, Object> meta) {
        String header = response.header("Last-Modified");
        assertNotNull(header, "no Last-Modified on status " + response.statusCode());
        assertTrue(header.matches(HTTP_DATE), header);
        assertEquals(
                Instant.parse((String) meta.get("updated")).truncatedTo(ChronoUnit.SECONDS),
                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(), header);
    }

    /**
     * A HEAD answers with the Content-Length of the read it stands in for. The HTTP layer drops the
     * header it generates when it strips the body of a HEAD, so the media response sets it itself,
     * which is what makes a HEAD usable to read an object's size and metadata.
     */
    @Test
    void headRepliesCarryTheContentLengthOfTheRead() {
        upload("head-length-obj");

        assertContentLength(given()
                .when().head("/" + BUCKET + "/head-length-obj")
                .then().statusCode(200).extract().response(), BODY.length());
        assertContentLength(given()
                .when().head("/storage/v1/b/" + BUCKET + "/o/head-length-obj?alt=media")
                .then().statusCode(200).extract().response(), BODY.length());
        assertContentLength(given()
                .when().head("/download/storage/v1/b/" + BUCKET + "/o/head-length-obj?alt=media")
                .then().statusCode(200).extract().response(), BODY.length());

        Response partial = given()
                .header("Range", "bytes=0-4")
                .when().head("/" + BUCKET + "/head-length-obj")
                .then().statusCode(206)
                .header("Content-Range", equalTo("bytes 0-4/" + BODY.length()))
                .extract().response();
        assertContentLength(partial, 5);
    }

    /**
     * A transcoded HEAD reports the length of the bytes the matching GET would send, which is the
     * decompressed size, while one that asks for gzip gets the stored size.
     */
    @Test
    void transcodedHeadReportsTheLengthTheReadWouldSend() throws IOException {
        String content = "hello transcoded";
        var stored = gzipUpload("head-length-gz-obj", content);

        // RestAssured appends its own "gzip,deflate" to an Accept-Encoding it is given, so the
        // transcoded read refuses gzip with a qvalue, the way the existing transcoding tests do.
        assertContentLength(given()
                .header("Accept-Encoding", "gzip;q=0")
                .when().head("/" + BUCKET + "/head-length-gz-obj")
                .then().statusCode(200).extract().response(), content.length());
        assertContentLength(given()
                .header("Accept-Encoding", "gzip")
                .when().head("/" + BUCKET + "/head-length-gz-obj")
                .then().statusCode(200)
                .header("Content-Encoding", equalTo("gzip"))
                .extract().response(), Integer.parseInt(String.valueOf(stored.get("size"))));
        assertContentLength(given()
                .header("Accept-Encoding", "gzip;q=0")
                .when().get("/" + BUCKET + "/head-length-gz-obj")
                .then().statusCode(200).extract().response(), content.length());
    }

    private static Map<String, Object> gzipUpload(String objectName, String content) throws IOException {
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
            gzip.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        ensureBucket();
        return given()
                .contentType("text/plain")
                .body(gzipped.toByteArray())
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=" + objectName
                        + "&contentEncoding=gzip")
                .then().statusCode(200)
                .extract().body().jsonPath().getMap("$");
    }

    private static void assertContentLength(Response response, int expected) {
        assertEquals(String.valueOf(expected), response.header("Content-Length"),
                "status " + response.statusCode());
    }
}

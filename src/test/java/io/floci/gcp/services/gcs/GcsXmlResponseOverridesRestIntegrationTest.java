package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class GcsXmlResponseOverridesRestIntegrationTest {

    private static final String BUCKET = "response-overrides-bucket";

    private static void uploadPdf(String objectName) {
        given()
                .contentType("application/json")
                .body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project");
        given()
                .contentType("application/pdf")
                .body("%PDF".getBytes(StandardCharsets.US_ASCII))
                .when().put("/" + BUCKET + "/" + objectName)
                .then().statusCode(200);
    }

    @Test
    void responseContentDispositionSetsTheHeader() {
        uploadPdf("report.pdf");

        given()
                .queryParam("response-content-disposition", "attachment; filename=\"report.pdf\"")
                .when().get("/" + BUCKET + "/report.pdf")
                .then().statusCode(200)
                .header("Content-Disposition", equalTo("attachment; filename=\"report.pdf\""))
                .header("Content-Type", startsWith("application/pdf"))
                .body(equalTo("%PDF"));
    }

    @Test
    void responseContentTypeOverridesTheStoredType() {
        uploadPdf("inline.pdf");

        given()
                .queryParam("response-content-type", "application/octet-stream")
                .when().get("/" + BUCKET + "/inline.pdf")
                .then().statusCode(200)
                .header("Content-Type", startsWith("application/octet-stream"));
    }

    @Test
    void rangeReadKeepsPartialContentWithOverride() {
        uploadPdf("range.pdf");

        given()
                .header("Range", "bytes=0-1")
                .queryParam("response-content-disposition", "attachment")
                .when().get("/" + BUCKET + "/range.pdf")
                .then().statusCode(206)
                .header("Content-Range", equalTo("bytes 0-1/4"))
                .header("Content-Disposition", equalTo("attachment"))
                .body(equalTo("%P"));
    }

    @Test
    void transcodedReadKeepsOverride() throws IOException {
        ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
            gzip.write("hello transcoded".getBytes(StandardCharsets.US_ASCII));
        }
        given()
                .contentType("application/json")
                .body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project");
        given()
                .contentType("text/plain")
                .body(gzipped.toByteArray())
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=notes.txt&contentEncoding=gzip")
                .then().statusCode(200);

        given()
                .header("Accept-Encoding", "gzip;q=0")
                .queryParam("response-content-type", "application/octet-stream")
                .when().get("/" + BUCKET + "/notes.txt")
                .then().statusCode(200)
                .header("Content-Type", startsWith("application/octet-stream"))
                .header("Content-Encoding", nullValue())
                .body(equalTo("hello transcoded"));
    }

    @Test
    void withoutOverridesTheStoredHeadersAreKept() {
        uploadPdf("plain.pdf");

        given()
                .when().get("/" + BUCKET + "/plain.pdf")
                .then().statusCode(200)
                .header("Content-Disposition", nullValue())
                .header("Content-Type", startsWith("application/pdf"));
    }
}

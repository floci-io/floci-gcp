package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * XML API PUT Object answers the way GCS does (probed live on 2026-10-28): no body, the entity
 * tag in quotes, and the generation and hash headers. S3-compatible clients such as DuckDB
 * httpfs read the ETag header to confirm the upload and fail without it.
 */
@QuarkusTest
class GcsXmlPutObjectRestIntegrationTest {

    private String bucket;

    @BeforeEach
    void createBucket() {
        bucket = "xml-put-" + System.nanoTime();
        given().contentType("application/json").queryParam("project", "test-project")
                .body(Map.of("name", bucket)).post("/storage/v1/b").then().statusCode(200);
    }

    @Test
    void putObjectAnswersWithGcsHeadersAndNoBody() {
        Response response = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=any/20261027//s3/aws4_request, "
                        + "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, Signature=0")
                .header("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
                .header("x-amz-date", "20261027T000000Z")
                .contentType("application/octet-stream")
                .body("hello world\n".getBytes(StandardCharsets.UTF_8))
                .when().put("/{bucket}/plain.txt", bucket);

        response.then().statusCode(200)
                .header("Content-Type", equalTo("text/html; charset=UTF-8"))
                .header("ETag", equalTo("\"6f5902ac237024bdd0c176cb93063dc4\""))
                .header("x-goog-metageneration", equalTo("1"))
                .header("x-goog-hash", equalTo("crc32c=8P9ykg==,md5=b1kCrCNwJL3QwXbLkwY9xA=="))
                .header("x-goog-stored-content-length", equalTo("12"))
                .header("x-goog-stored-content-encoding", equalTo("identity"))
                .header("x-goog-storage-class", nullValue())
                .body(emptyString());

        given().when().get("/storage/v1/b/{bucket}/o/plain.txt", bucket)
                .then().statusCode(200)
                .body("generation", equalTo(response.header("x-goog-generation")))
                .body("size", equalTo("12"));
        given().when().get("/{bucket}/plain.txt", bucket)
                .then().statusCode(200)
                .body(equalTo("hello world\n"));
    }

    @Test
    void emptyObjectReportsTheEmptyHashes() {
        given()
                .contentType("application/octet-stream")
                .when().put("/{bucket}/empty.bin", bucket)
                .then().statusCode(200)
                .header("ETag", equalTo("\"d41d8cd98f00b204e9800998ecf8427e\""))
                .header("x-goog-hash", equalTo("crc32c=AAAAAA==,md5=1B2M2Y8AsgTpgAmY7PhCfg=="))
                .header("x-goog-stored-content-length", equalTo("0"))
                .body(emptyString());
    }

    @Test
    void bucketNamedLikeServiceUsageVersionStaysReachable() {
        String versionBucket = "v1beta1";
        given().contentType("application/json").queryParam("project", "test-project")
                .body(Map.of("name", versionBucket)).post("/storage/v1/b").then().statusCode(200);
        try {
            given().contentType("text/plain").body("beta\n".getBytes(StandardCharsets.UTF_8))
                    .when().put("/{bucket}/obj.txt", versionBucket)
                    .then().statusCode(200);
            given().when().get("/{bucket}/obj.txt", versionBucket)
                    .then().statusCode(200)
                    .body(equalTo("beta\n"));
        } finally {
            given().delete("/storage/v1/b/{bucket}/o/obj.txt", versionBucket);
            given().delete("/storage/v1/b/{bucket}", versionBucket);
        }
    }
}

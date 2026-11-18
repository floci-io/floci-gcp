package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@QuarkusTest
class GcsCopyRestIntegrationTest {

    @Test
    void copyToWithSourceGenerationCopiesRequestedNoncurrentVersion() {
        String bucket = "copy-source-generation-bucket";
        createVersionedBucket(bucket);

        String firstGeneration = upload(bucket, "source.txt", "first");
        String secondGeneration = upload(bucket, "source.txt", "second");

        given()
                .queryParam("sourceGeneration", firstGeneration)
                .queryParam("ifGenerationMatch", 0)
                .when().post("/storage/v1/b/{bucket}/o/source.txt/copyTo/b/{bucket}/o/restored.txt", bucket, bucket)
                .then().statusCode(200)
                .body("name", equalTo("restored.txt"))
                .body("generation", not(equalTo(firstGeneration)));

        given()
                .queryParam("alt", "media")
                .when().get("/storage/v1/b/{bucket}/o/restored.txt", bucket)
                .then().statusCode(200)
                .body(equalTo("first"));

        // Restoring an older generation back onto the live object name (the documented
        // GCP version-restore flow) preserves destination preconditions independently.
        given()
                .queryParam("sourceGeneration", firstGeneration)
                .queryParam("ifGenerationMatch", secondGeneration)
                .when().post("/storage/v1/b/{bucket}/o/source.txt/copyTo/b/{bucket}/o/source.txt", bucket, bucket)
                .then().statusCode(200);

        given()
                .queryParam("alt", "media")
                .when().get("/storage/v1/b/{bucket}/o/source.txt", bucket)
                .then().statusCode(200)
                .body(equalTo("first"));
    }

    @Test
    void copyToWithDeletedLiveObjectStillCopiesArchivedSourceGeneration() {
        String bucket = "copy-deleted-live-generation-bucket";
        createVersionedBucket(bucket);

        String generation = upload(bucket, "deleted.txt", "archived-bytes");
        given()
                .when().delete("/storage/v1/b/{bucket}/o/deleted.txt", bucket)
                .then().statusCode(204);

        given()
                .queryParam("sourceGeneration", generation)
                .when().post("/storage/v1/b/{bucket}/o/deleted.txt/copyTo/b/{bucket}/o/recovered.txt", bucket, bucket)
                .then().statusCode(200);

        given()
                .queryParam("alt", "media")
                .when().get("/storage/v1/b/{bucket}/o/recovered.txt", bucket)
                .then().statusCode(200)
                .body(equalTo("archived-bytes"));
    }

    @Test
    void copyToWithUnknownSourceGenerationReturns404() {
        String bucket = "copy-unknown-generation-bucket";
        createVersionedBucket(bucket);
        upload(bucket, "source.txt", "live");

        given()
                .queryParam("sourceGeneration", "999999999999")
                .when().post("/storage/v1/b/{bucket}/o/source.txt/copyTo/b/{bucket}/o/dst.txt", bucket, bucket)
                .then().statusCode(404);
    }

    @Test
    void rewriteToWithSourceGenerationCopiesRequestedNoncurrentVersion() {
        String bucket = "rewrite-source-generation-bucket";
        createVersionedBucket(bucket);

        String firstGeneration = upload(bucket, "source.txt", "first-rewrite");
        String secondGeneration = upload(bucket, "source.txt", "second-rewrite");

        given()
                .queryParam("sourceGeneration", firstGeneration)
                .queryParam("ifGenerationMatch", 0)
                .when().post("/storage/v1/b/{bucket}/o/source.txt/rewriteTo/b/{bucket}/o/restored.txt", bucket, bucket)
                .then().statusCode(200)
                .body("done", equalTo(true))
                .body("resource.name", equalTo("restored.txt"));

        given()
                .queryParam("alt", "media")
                .when().get("/storage/v1/b/{bucket}/o/restored.txt", bucket)
                .then().statusCode(200)
                .body(equalTo("first-rewrite"));

        // Restoring onto the live object via rewriteTo (the path used by Node.js and Java SDKs).
        given()
                .queryParam("sourceGeneration", firstGeneration)
                .queryParam("ifGenerationMatch", secondGeneration)
                .when().post("/storage/v1/b/{bucket}/o/source.txt/rewriteTo/b/{bucket}/o/source.txt", bucket, bucket)
                .then().statusCode(200)
                .body("done", equalTo(true));

        given()
                .queryParam("alt", "media")
                .when().get("/storage/v1/b/{bucket}/o/source.txt", bucket)
                .then().statusCode(200)
                .body(equalTo("first-rewrite"));
    }

    @Test
    void chunkedRewriteWithSourceGenerationRemainsValidWhenLiveObjectChanges() {
        String srcBucket = "rewrite-chunked-ver-src";
        String dstBucket = "rewrite-chunked-ver-dst";
        int oneMiB = 1024 * 1024;
        byte[] firstBytes = new byte[2 * oneMiB];
        firstBytes[0] = 'A';
        firstBytes[firstBytes.length - 1] = 'Z';

        createVersionedBucket(srcBucket);
        given()
                .contentType("application/json")
                .body(Map.of("name", dstBucket, "storageClass", "NEARLINE"))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);

        String firstGen = given()
                .queryParam("uploadType", "media")
                .queryParam("name", "blob.bin")
                .contentType("application/octet-stream")
                .body(firstBytes)
                .when().post("/upload/storage/v1/b/{bucket}/o", srcBucket)
                .then().statusCode(200)
                .extract().path("generation");

        String token = given()
                .queryParam("sourceGeneration", firstGen)
                .queryParam("maxBytesRewrittenPerCall", oneMiB)
                .when().post("/storage/v1/b/{src}/o/blob.bin/rewriteTo/b/{dst}/o/out.bin", srcBucket, dstBucket)
                .then().statusCode(200)
                .body("done", equalTo(false))
                .extract().path("rewriteToken");

        // Overwrite the live object while the chunked rewrite of firstGen is in flight.
        String liveGeneration = upload(srcBucket, "blob.bin", "newer-live-version");

        given()
                .queryParam("rewriteToken", token)
                .queryParam("sourceGeneration", liveGeneration)
                .queryParam("maxBytesRewrittenPerCall", oneMiB)
                .when().post("/storage/v1/b/{src}/o/blob.bin/rewriteTo/b/{dst}/o/out.bin", srcBucket, dstBucket)
                .then().statusCode(400);

        given()
                .queryParam("rewriteToken", token)
                .queryParam("sourceGeneration", firstGen)
                .queryParam("maxBytesRewrittenPerCall", oneMiB)
                .when().post("/storage/v1/b/{src}/o/blob.bin/rewriteTo/b/{dst}/o/out.bin", srcBucket, dstBucket)
                .then().statusCode(200)
                .body("done", equalTo(true));

        byte[] copied = given()
                .queryParam("alt", "media")
                .when().get("/storage/v1/b/{bucket}/o/out.bin", dstBucket)
                .then().statusCode(200)
                .extract().asByteArray();
        assertArrayEquals(firstBytes, copied);
    }

    private static void createVersionedBucket(String bucket) {
        given()
                .contentType("application/json")
                .body(Map.of("name", bucket, "versioning", Map.of("enabled", true)))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);
    }

    private static String upload(String bucket, String objectName, String content) {
        return given()
                .queryParam("uploadType", "media")
                .queryParam("name", objectName)
                .contentType("text/plain")
                .body(content)
                .when().post("/upload/storage/v1/b/{bucket}/o", bucket)
                .then().statusCode(200)
                .extract().path("generation");
    }
}

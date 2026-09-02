package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/** Soft delete: retention policy, softDeleted listing, and objects.restore. */
@QuarkusTest
class GcsSoftDeleteRestIntegrationTest {

    private static final String BUCKET = "soft-delete-bucket";

    private static void ensureBucket() {
        given().contentType("application/json")
                .body(Map.of("name", BUCKET,
                        "softDeletePolicy", Map.of("retentionDurationSeconds", "604800")))
                .when().post("/storage/v1/b?project=test-project");
    }

    private static String writeObject(String name) {
        return given().contentType("text/plain").body("bytes")
                .queryParam("uploadType", "media").queryParam("name", name)
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .extract().path("generation");
    }

    @Test
    void bucketReportsItsSoftDeletePolicy() {
        ensureBucket();
        given().when().get("/storage/v1/b/" + BUCKET)
                .then().statusCode(200)
                .body("softDeletePolicy.retentionDurationSeconds", equalTo("604800"));
    }

    @Test
    void aDeletedObjectLeavesTheLiveListing() {
        ensureBucket();
        writeObject("gone");
        given().when().delete("/storage/v1/b/" + BUCKET + "/o/gone").then().statusCode(204);
        given().when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items.name", not(hasItem("gone")));
    }

    @Test
    void aDeletedObjectAppearsInTheSoftDeletedListingWithTimestamps() {
        ensureBucket();
        String generation = writeObject("listed");
        given().when().delete("/storage/v1/b/" + BUCKET + "/o/listed").then().statusCode(204);

        given().queryParam("softDeleted", true)
                .when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items.name", hasItem("listed"))
                .body("items.find { it.name == 'listed' }.generation", equalTo(generation))
                .body("items.find { it.name == 'listed' }.softDeleteTime", notNullValue())
                .body("items.find { it.name == 'listed' }.hardDeleteTime", notNullValue());
    }

    @Test
    void restoreBringsTheObjectAndItsBytesBack() {
        ensureBucket();
        String generation = writeObject("restore-me");
        given().when().delete("/storage/v1/b/" + BUCKET + "/o/restore-me").then().statusCode(204);

        given().queryParam("generation", generation)
                .when().post("/storage/v1/b/" + BUCKET + "/o/restore-me/restore")
                .then().statusCode(200)
                .body("name", equalTo("restore-me"));

        given().when().get("/storage/v1/b/" + BUCKET + "/o/restore-me/?alt=media")
                .then().statusCode(200);
        given().when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items.name", hasItem("restore-me"));
    }

    @Test
    void restoreRequiresAGeneration() {
        ensureBucket();
        writeObject("needs-generation");
        given().when().delete("/storage/v1/b/" + BUCKET + "/o/needs-generation").then().statusCode(204);
        given().when().post("/storage/v1/b/" + BUCKET + "/o/needs-generation/restore")
                .then().statusCode(400);
    }

    @Test
    void restoringAnUnknownGenerationIs404() {
        ensureBucket();
        given().queryParam("generation", "1")
                .when().post("/storage/v1/b/" + BUCKET + "/o/never-existed/restore")
                .then().statusCode(404);
    }

    @Test
    void aBucketWithoutAPolicyHardDeletes() {
        String plain = "no-soft-delete-bucket";
        given().contentType("application/json").body(Map.of("name", plain))
                .when().post("/storage/v1/b?project=test-project");
        given().contentType("text/plain").body("bytes")
                .queryParam("uploadType", "media").queryParam("name", "vanishes")
                .when().post("/upload/storage/v1/b/" + plain + "/o");
        given().when().delete("/storage/v1/b/" + plain + "/o/vanishes").then().statusCode(204);

        given().queryParam("softDeleted", true)
                .when().get("/storage/v1/b/" + plain + "/o")
                .then().statusCode(200)
                .body("items", org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.nullValue(), org.hamcrest.Matchers.empty()));
    }

    @Test
    void generationScopedDeleteIsAlsoRetained() {
        // "When you delete a noncurrent object, it becomes soft-deleted"
        // (https://cloud.google.com/storage/docs/soft-delete). google-cloud-storage for Python
        // sends the loaded generation on every blob.delete(), so a generation-scoped delete that
        // skipped retention would bypass soft delete entirely for that SDK.
        ensureBucket();
        String generation = writeObject("generation-scoped.txt");

        given().queryParam("generation", generation)
                .when().delete("/storage/v1/b/" + BUCKET + "/o/generation-scoped.txt")
                .then().statusCode(204);

        given().queryParam("softDeleted", true)
                .when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items.name", hasItem("generation-scoped.txt"));

        given().queryParam("generation", generation)
                .when().post("/storage/v1/b/" + BUCKET + "/o/generation-scoped.txt/restore")
                .then().statusCode(200)
                .body("name", equalTo("generation-scoped.txt"));

        given().when().get("/storage/v1/b/" + BUCKET + "/o/generation-scoped.txt")
                .then().statusCode(200);
    }

    @Test
    void restoringOverALiveObjectRetainsTheDisplacedOne() {
        // Soft delete exists to stop a delete destroying data, so a restore must not destroy the
        // live generation it displaces: it goes through the ordinary delete path instead.
        ensureBucket();
        String first = writeObject("displaced.txt");
        given().when().delete("/storage/v1/b/" + BUCKET + "/o/displaced.txt")
                .then().statusCode(204);
        String second = writeObject("displaced.txt");

        given().queryParam("generation", first)
                .when().post("/storage/v1/b/" + BUCKET + "/o/displaced.txt/restore")
                .then().statusCode(200);

        // The generation that was live at restore time is retained, not gone.
        given().queryParam("softDeleted", true)
                .when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items.generation", hasItem(second));
    }
}

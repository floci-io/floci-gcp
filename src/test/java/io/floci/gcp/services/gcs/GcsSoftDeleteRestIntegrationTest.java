package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Soft delete: retention policy, softDeleted listing, and objects.restore. */
@QuarkusTest
class GcsSoftDeleteRestIntegrationTest {

    private static final String BUCKET = "soft-delete-bucket";

    @Inject
    GcsSoftDeleteReaper reaper;

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
    void aBucketCreatedWithoutAPolicyGetsTheSevenDayDefault() {
        String plain = "default-soft-delete-bucket";
        given().contentType("application/json").body(Map.of("name", plain))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200)
                .body("softDeletePolicy.retentionDurationSeconds", equalTo("604800"))
                .body("softDeletePolicy.effectiveTime", notNullValue());
        String generation = given().contentType("text/plain").body("bytes")
                .queryParam("uploadType", "media").queryParam("name", "kept")
                .when().post("/upload/storage/v1/b/" + plain + "/o")
                .then().statusCode(200).extract().path("generation");
        given().when().delete("/storage/v1/b/" + plain + "/o/kept").then().statusCode(204);

        given().queryParam("generation", generation)
                .when().post("/storage/v1/b/" + plain + "/o/kept/restore")
                .then().statusCode(200)
                .body("name", equalTo("kept"));
    }

    @Test
    void aZeroRetentionHardDeletes() {
        String plain = "no-soft-delete-bucket";
        given().contentType("application/json")
                .body(Map.of("name", plain, "softDeletePolicy", Map.of("retentionDurationSeconds", "0")))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200)
                .body("softDeletePolicy.retentionDurationSeconds", equalTo("0"))
                .body("softDeletePolicy.effectiveTime", nullValue());
        given().contentType("text/plain").body("bytes")
                .queryParam("uploadType", "media").queryParam("name", "vanishes")
                .when().post("/upload/storage/v1/b/" + plain + "/o");
        given().when().delete("/storage/v1/b/" + plain + "/o/vanishes").then().statusCode(204);

        given().queryParam("softDeleted", true)
                .when().get("/storage/v1/b/" + plain + "/o")
                .then().statusCode(200)
                .body("items", anyOf(nullValue(), empty()));
    }

    @Test
    void aRetentionOutsideSevenToNinetyDaysIsRejected() {
        for (String retention : List.of("86400", "7776001", "seven days")) {
            given().contentType("application/json")
                    .body(Map.of("name", "invalid-soft-delete-bucket",
                            "softDeletePolicy", Map.of("retentionDurationSeconds", retention)))
                    .when().post("/storage/v1/b?project=test-project")
                    .then().statusCode(400)
                    .body("error.errors[0].reason", equalTo("invalid"));
        }
        given().contentType("application/json")
                .body(Map.of("name", "ninety-day-soft-delete-bucket",
                        "softDeletePolicy", Map.of("retentionDurationSeconds", 7776000)))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200)
                .body("softDeletePolicy.retentionDurationSeconds", equalTo("7776000"));
    }

    @Test
    void aPatchWithAnInvalidRetentionChangesNothing() {
        String bucket = "rejected-patch-soft-delete-bucket";
        given().contentType("application/json").body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);

        given().contentType("application/json")
                .body(Map.of("versioning", Map.of("enabled", true),
                        "softDeletePolicy", Map.of("retentionDurationSeconds", "86400")))
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("invalid"));

        given().when().get("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("versioning", nullValue())
                .body("metageneration", equalTo("1"))
                .body("softDeletePolicy.retentionDurationSeconds", equalTo("604800"));
    }

    @Test
    void effectiveTimeMovesWhenTheRetentionGrows() {
        String bucket = "effective-time-soft-delete-bucket";
        String created = given().contentType("application/json").body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200).extract().path("softDeletePolicy.effectiveTime");

        String grown = given().contentType("application/json")
                .body(Map.of("softDeletePolicy", Map.of("retentionDurationSeconds", "1209600")))
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("softDeletePolicy.retentionDurationSeconds", equalTo("1209600"))
                .extract().path("softDeletePolicy.effectiveTime");
        assertNotEquals(created, grown);

        given().contentType("application/json")
                .body(Map.of("softDeletePolicy", Map.of("retentionDurationSeconds", "604800")))
                .when().patch("/storage/v1/b/" + bucket)
                .then().statusCode(200)
                .body("softDeletePolicy.effectiveTime", equalTo(grown));
    }

    @Test
    void theSweepPurgesObjectsPastTheirHardDeleteTime() {
        ensureBucket();
        String generation = writeObject("expires");
        given().when().delete("/storage/v1/b/" + BUCKET + "/o/expires").then().statusCode(204);

        reaper.sweep(Instant.now());
        assertTrue(softDeletedNames().contains("expires"));
        assertTrue(reaper.sweep(Instant.now().plus(Duration.ofDays(8))) > 0);
        assertFalse(softDeletedNames().contains("expires"));
        given().queryParam("generation", generation)
                .when().post("/storage/v1/b/" + BUCKET + "/o/expires/restore")
                .then().statusCode(404);
    }

    private static List<String> softDeletedNames() {
        List<String> names = given().queryParam("softDeleted", true)
                .when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200).extract().path("items.name");
        return names == null ? List.of() : names;
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

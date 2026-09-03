package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

@QuarkusTest
class GcsBucketDeletionRestIntegrationTest {

    private static final String BUCKET = "non-empty-bucket-delete";

    @Test
    void rejectsDeletionOfNonEmptyBucketWithBucketNotEmpty() {
        given()
                .contentType("application/json")
                .body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);

        given()
                .queryParam("uploadType", "media")
                .queryParam("name", "object.txt")
                .contentType("text/plain")
                .body("contents")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200);

        given()
                .when().delete("/storage/v1/b/" + BUCKET)
                .then()
                .statusCode(409)
                .body("error.errors[0].reason", org.hamcrest.Matchers.equalTo("BucketNotEmpty"));
    }
}

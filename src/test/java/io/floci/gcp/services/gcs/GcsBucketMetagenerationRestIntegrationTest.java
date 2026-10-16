package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class GcsBucketMetagenerationRestIntegrationTest {

    private static final String BUCKET = "metageneration-rest-bucket";

    @Test
    void patchBucketBumpsMetageneration() {
        given()
                .contentType("application/json")
                .body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200)
                .body("metageneration", equalTo("1"));

        given()
                .contentType("application/json")
                .body(Map.of("labels", Map.of("env", "dev")))
                .when().patch("/storage/v1/b/" + BUCKET)
                .then().statusCode(200)
                .body("metageneration", equalTo("2"));

        given().when().get("/storage/v1/b/" + BUCKET)
                .then().statusCode(200)
                .body("metageneration", equalTo("2"));

        given()
                .contentType("application/json")
                .body(Map.of("labels", Map.of("env", "prod")))
                .when().patch("/storage/v1/b/" + BUCKET)
                .then().statusCode(200)
                .body("metageneration", equalTo("3"));

        given().when().get("/storage/v1/b/" + BUCKET)
                .then().statusCode(200)
                .body("metageneration", equalTo("3"));
    }
}

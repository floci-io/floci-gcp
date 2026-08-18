package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class GcsMoveRestIntegrationTest {

    private String bucket;

    @BeforeEach
    void createBucket() {
        bucket = "move-objects-" + UUID.randomUUID().toString().substring(0, 8);
        given()
                .contentType("application/json")
                .body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);
    }

    @Test
    void movesUriEncodedObjectName() {
        Response source = upload("source/name", "content");
        String sourceGeneration = source.path("generation");

        given()
                .urlEncodingEnabled(false)
                .queryParam("ifGenerationMatch", 0)
                .queryParam("ifSourceGenerationMatch", sourceGeneration)
                .when().post("/storage/v1/b/" + bucket + "/o/source%2Fname/moveTo/o/destination%2Fname")
                .then().statusCode(200)
                .body("name", equalTo("destination/name"));

        given()
                .urlEncodingEnabled(false)
                .when().get("/storage/v1/b/" + bucket + "/o/source%2Fname")
                .then().statusCode(404);
        given()
                .urlEncodingEnabled(false)
                .queryParam("alt", "media")
                .when().get("/storage/v1/b/" + bucket + "/o/destination%2Fname")
                .then().statusCode(200)
                .body(equalTo("content"));
    }

    @Test
    void movesObjectWhenDestinationContainsRouteDelimiter() {
        upload("source", "content");

        given()
                .urlEncodingEnabled(false)
                .when().post("/storage/v1/b/" + bucket + "/o/source/moveTo/o/destination%2FmoveTo%2Fo%2Fname")
                .then().statusCode(200)
                .body("name", equalTo("destination/moveTo/o/name"));

        given()
                .urlEncodingEnabled(false)
                .when().get("/storage/v1/b/" + bucket + "/o/source")
                .then().statusCode(404);
        given()
                .urlEncodingEnabled(false)
                .queryParam("alt", "media")
                .when().get("/storage/v1/b/" + bucket + "/o/destination%2FmoveTo%2Fo%2Fname")
                .then().statusCode(200)
                .body(equalTo("content"));
    }

    @Test
    void failedDestinationPreconditionLeavesBothObjectsUnchanged() {
        upload("destination-precondition-source", "source");
        upload("destination-precondition-target", "target");

        given()
                .queryParam("ifGenerationMatch", 0)
                .when().post("/storage/v1/b/" + bucket
                        + "/o/destination-precondition-source/moveTo/o/destination-precondition-target")
                .then().statusCode(412);

        assertContent("destination-precondition-source", "source");
        assertContent("destination-precondition-target", "target");
    }

    @Test
    void failedSourcePreconditionLeavesSourceAndDestinationUnchanged() {
        upload("source-precondition-source", "source");

        given()
                .queryParam("ifGenerationMatch", 0)
                .queryParam("ifSourceGenerationMatch", 1)
                .when().post("/storage/v1/b/" + bucket
                        + "/o/source-precondition-source/moveTo/o/source-precondition-target")
                .then().statusCode(412);

        assertContent("source-precondition-source", "source");
        given()
                .when().get("/storage/v1/b/" + bucket + "/o/source-precondition-target")
                .then().statusCode(404);
    }

    @Test
    void rejectsIdenticalObjectNames() {
        upload("same-name", "content");

        given()
                .when().post("/storage/v1/b/" + bucket + "/o/same-name/moveTo/o/same-name")
                .then().statusCode(400);

        assertContent("same-name", "content");
    }

    private Response upload(String objectName, String content) {
        return given()
                .queryParam("uploadType", "media")
                .queryParam("name", objectName)
                .contentType("text/plain")
                .body(content)
                .when().post("/upload/storage/v1/b/" + bucket + "/o")
                .then().statusCode(200)
                .extract().response();
    }

    private void assertContent(String objectName, String content) {
        given()
                .queryParam("alt", "media")
                .when().get("/storage/v1/b/" + bucket + "/o/" + objectName)
                .then().statusCode(200)
                .body(equalTo(content));
    }
}

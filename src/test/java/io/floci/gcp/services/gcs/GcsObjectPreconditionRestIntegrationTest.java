package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

@QuarkusTest
class GcsObjectPreconditionRestIntegrationTest {

    private static final String BUCKET = "object-preconditions";

    @Test
    void enforcesMutationPreconditions() {
        given()
                .contentType("application/json")
                .body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);

        Response upload = upload("delete-target", "data");
        String generation = upload.path("generation");
        String metageneration = upload.path("metageneration");

        assertDeleteFails("ifGenerationMatch", "0");
        assertDeleteFails("ifGenerationNotMatch", generation);
        assertDeleteFails("ifMetagenerationMatch", "999");
        assertDeleteFails("ifMetagenerationNotMatch", metageneration);

        upload("compose-source", "source");
        upload("compose-target", "target");

        given()
                .queryParam("ifGenerationMatch", 0)
                .contentType("application/json")
                .body(Map.of("sourceObjects", List.of(Map.of("name", "compose-source"))))
                .when().post("/storage/v1/b/" + BUCKET + "/o/compose-target/compose")
                .then().statusCode(412);

        upload("copy-source", "source");
        upload("copy-target", "target");

        given()
                .queryParam("ifGenerationMatch", 0)
                .when().post("/storage/v1/b/" + BUCKET + "/o/copy-source/copyTo/b/" + BUCKET + "/o/copy-target")
                .then().statusCode(412);

        upload("rewrite-source", "source");
        upload("rewrite-target", "target");

        given()
                .queryParam("ifGenerationMatch", 0)
                .when().post("/storage/v1/b/" + BUCKET + "/o/rewrite-source/rewriteTo/b/" + BUCKET + "/o/rewrite-target")
                .then().statusCode(412);
    }

    private static Response upload(String objectName, String content) {
        return given()
                .queryParam("uploadType", "media")
                .queryParam("name", objectName)
                .contentType("text/plain")
                .body(content)
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .extract().response();
    }

    private static void assertDeleteFails(String precondition, String value) {
        given()
                .queryParam(precondition, value)
                .when().delete("/storage/v1/b/" + BUCKET + "/o/delete-target")
                .then().statusCode(412);
    }
}

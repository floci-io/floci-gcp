package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class GcsObjectMetadataPatchRestIntegrationTest {

    private static final String BUCKET = "object-metadata-patch-bucket";

    @Test
    void patchMergesMetadataAndNullRemovesAKeyWhileUpdateReplacesIt() {
        given()
                .contentType("application/json")
                .body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);
        given()
                .contentType("text/plain")
                .body("data")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=obj.txt")
                .then().statusCode(200);
        given()
                .contentType("application/json")
                .body(Map.of("metadata", Map.of("env", "compat", "team", "data")))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/obj.txt")
                .then().statusCode(200);

        given()
                .contentType("application/json")
                .body(Map.of("metadata", Map.of("team", "ml")))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/obj.txt")
                .then().statusCode(200)
                .body("metadata.env", equalTo("compat"))
                .body("metadata.team", equalTo("ml"));

        Map<String, Object> removeTeam = new HashMap<>();
        removeTeam.put("team", null);
        given()
                .contentType("application/json")
                .body(Map.of("metadata", removeTeam))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/obj.txt")
                .then().statusCode(200)
                .body("metadata.env", equalTo("compat"))
                .body("metadata", not(hasKey("team")));

        given()
                .contentType("application/json")
                .body(Map.of("metadata", Map.of("owner", "ops")))
                .when().put("/storage/v1/b/" + BUCKET + "/o/obj.txt")
                .then().statusCode(200)
                .body("metadata.owner", equalTo("ops"))
                .body("metadata", not(hasKey("env")));

        Map<String, Object> removeOwner = new HashMap<>();
        removeOwner.put("owner", null);
        given()
                .contentType("application/json")
                .body(Map.of("metadata", removeOwner))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/obj.txt")
                .then().statusCode(200)
                .body("metadata", nullValue());
    }
}

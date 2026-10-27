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
class GcsBucketLabelsRestIntegrationTest {

    private static final String BUCKET = "labels-rest-bucket";

    @Test
    void patchMergesLabelsAndNullRemovesOne() {
        given()
                .contentType("application/json")
                .body(Map.of("name", BUCKET, "labels", Map.of("env", "compat", "team", "data")))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);

        given()
                .contentType("application/json")
                .body(Map.of("labels", Map.of("team", "ml")))
                .when().patch("/storage/v1/b/" + BUCKET)
                .then().statusCode(200)
                .body("labels.env", equalTo("compat"))
                .body("labels.team", equalTo("ml"));

        Map<String, Object> removeTeam = new HashMap<>();
        removeTeam.put("team", null);
        given()
                .contentType("application/json")
                .body(Map.of("labels", removeTeam))
                .when().patch("/storage/v1/b/" + BUCKET)
                .then().statusCode(200)
                .body("labels.env", equalTo("compat"))
                .body("labels", not(hasKey("team")));

        Map<String, Object> removeEnv = new HashMap<>();
        removeEnv.put("env", null);
        given()
                .contentType("application/json")
                .body(Map.of("labels", removeEnv))
                .when().patch("/storage/v1/b/" + BUCKET)
                .then().statusCode(200)
                .body("labels", nullValue());

        given().when().get("/storage/v1/b/" + BUCKET)
                .then().statusCode(200)
                .body("labels", nullValue());
    }
}

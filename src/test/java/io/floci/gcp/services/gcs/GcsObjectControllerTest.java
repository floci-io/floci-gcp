package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
public class GcsObjectControllerTest {

    @Test
    void preservesUriEncodedObjectNames() {
        String bucket = "encoded-object-" + UUID.randomUUID();
        String objectName = "literal%2Fslash";

        given()
            .contentType("application/json")
            .body("{\"name\":\"" + bucket + "\"}")
            .when().post("/storage/v1/b?project=test-project")
            .then().statusCode(200);

        given()
            .urlEncodingEnabled(false)
            .contentType("text/plain")
            .body("encoded object")
            .when().post("/upload/storage/v1/b/" + bucket + "/o?uploadType=media&name=literal%252Fslash")
            .then()
            .statusCode(200)
            .body("name", equalTo(objectName));

        given()
            .urlEncodingEnabled(false)
            .when().get("/storage/v1/b/" + bucket + "/o/literal%252Fslash")
            .then()
            .statusCode(200)
            .body("name", equalTo(objectName));

        given()
            .urlEncodingEnabled(false)
            .when().get("/download/storage/v1/b/" + bucket + "/o/literal%252Fslash")
            .then()
            .statusCode(200)
            .body(equalTo("encoded object"));

        given()
            .urlEncodingEnabled(false)
            .when().get("/" + bucket + "/literal%252Fslash")
            .then()
            .statusCode(200)
            .body(equalTo("encoded object"));
    }
}

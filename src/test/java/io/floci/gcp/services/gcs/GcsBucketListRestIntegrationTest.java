package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class GcsBucketListRestIntegrationTest {

    @Test
    void listWithoutProjectIsRejected() {
        given().when().get("/storage/v1/b")
                .then().statusCode(400)
                .body("error.code", equalTo(400))
                .body("error.message", equalTo("Required parameter: project"))
                .body("error.errors[0].reason", equalTo("required"));

        given().queryParam("project", " ")
                .when().get("/storage/v1/b")
                .then().statusCode(400)
                .body("error.errors[0].reason", equalTo("required"));
    }

    @Test
    void listReturnsOnlyTheRequestedProjectsBuckets() {
        given().contentType("application/json").body(Map.of("name", "list-scope-alpha-bucket"))
                .when().post("/storage/v1/b?project=list-scope-alpha")
                .then().statusCode(200);
        given().contentType("application/json").body(Map.of("name", "list-scope-beta-bucket"))
                .when().post("/storage/v1/b?project=list-scope-beta")
                .then().statusCode(200);

        given().queryParam("project", "list-scope-alpha")
                .when().get("/storage/v1/b")
                .then().statusCode(200)
                .body("items.name", equalTo(List.of("list-scope-alpha-bucket")));

        given().queryParam("project", "list-scope-beta")
                .when().get("/storage/v1/b")
                .then().statusCode(200)
                .body("items.name", equalTo(List.of("list-scope-beta-bucket")));
    }
}

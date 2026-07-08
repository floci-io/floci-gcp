package io.floci.gcp.services.resourcemanager;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class ResourceManagerRestIntegrationTest {

    @Test
    void getProjectReportsActiveWithStableProjectNumber() {
        String projectNumber = given()
                .when().get("/v1/projects/crm-test")
                .then()
                .statusCode(200)
                .body("projectId", equalTo("crm-test"))
                .body("name", equalTo("crm-test"))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("projectNumber", matchesPattern("\\d+"))
                .body("createTime", notNullValue())
                .extract().path("projectNumber");

        given()
                .when().get("/v1/projects/crm-test")
                .then()
                .statusCode(200)
                .body("projectNumber", equalTo(projectNumber));
    }
}

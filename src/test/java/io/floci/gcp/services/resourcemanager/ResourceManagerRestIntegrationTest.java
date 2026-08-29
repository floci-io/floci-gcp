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

    @Test
    void projectIamPolicyUsesPostForEveryMixinMethod() {
        String base = "/v1/projects/crm-iam-test";

        String etag = given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"policy\":{\"bindings\":[{\"role\":\"roles/pubsub.publisher\",\"members\":[\"user:publisher@example.com\"]}]}}")
                .when().post(base + ":setIamPolicy")
                .then().statusCode(200)
                .body("bindings[0].role", equalTo("roles/pubsub.publisher"))
                .extract().path("etag");

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + ":getIamPolicy")
                .then().statusCode(200)
                .body("etag", equalTo(etag))
                .body("bindings[0].members[0]", equalTo("user:publisher@example.com"));

        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"permissions\":[\"resourcemanager.projects.get\",\"resourcemanager.projects.delete\"]}")
                .when().post(base + ":testIamPermissions")
                .then().statusCode(200)
                .body("permissions[0]", equalTo("resourcemanager.projects.get"))
                .body("permissions[1]", equalTo("resourcemanager.projects.delete"));

        given().urlEncodingEnabled(false).when().get(base + ":getIamPolicy").then().statusCode(405);
        given().urlEncodingEnabled(false).when().get(base + ":setIamPolicy").then().statusCode(405);
        given().urlEncodingEnabled(false).when().get(base + ":testIamPermissions").then().statusCode(405);
    }

    @Test
    void staleProjectIamWriteLeavesCurrentPolicyIntact() {
        String base = "/v1/projects/crm-iam-etag-test";

        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"policy\":{\"bindings\":[{\"role\":\"roles/viewer\",\"members\":[\"user:reader@example.com\"]}]}}")
                .when().post(base + ":setIamPolicy").then().statusCode(200);

        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"policy\":{\"etag\":\"stale\",\"bindings\":[]}}")
                .when().post(base + ":setIamPolicy")
                .then().statusCode(409).body("error.status", equalTo("ABORTED"));

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + ":getIamPolicy")
                .then().statusCode(200)
                .body("bindings[0].role", equalTo("roles/viewer"));
    }
}

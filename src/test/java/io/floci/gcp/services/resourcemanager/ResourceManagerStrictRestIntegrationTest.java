package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.core.common.ProjectNumbers;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(ResourceManagerStrictRestIntegrationTest.StrictProjectsProfile.class)
class ResourceManagerStrictRestIntegrationTest {

    @Test
    void unknownProjectIsPermissionDeniedEverywhere() {
        given().when().get("/v1/projects/strict-unknown")
                .then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo("The caller does not have permission"));

        given().when().get("/v1/projects/strict-unknown/topics")
                .then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));

        given().queryParam("project", "strict-unknown")
                .when().get("/storage/v1/b")
                .then().statusCode(403);

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post("/v1/projects/strict-unknown:getIamPolicy")
                .then().statusCode(403);

        given().when().get("/v1/projects/strict-unknown/billingInfo")
                .then().statusCode(403);

        given().queryParam("filter", "id:strict-unknown")
                .when().get("/v1/projects")
                .then().statusCode(200)
                .body("projects.projectId", not(hasItem("strict-unknown")));
    }

    @Test
    void defaultProjectAlwaysExists() {
        given().when().get("/v1/projects/test-project")
                .then().statusCode(200)
                .body("projectId", equalTo("test-project"));
        given().when().get("/v1/projects/" + ProjectNumbers.of("test-project"))
                .then().statusCode(200)
                .body("projectId", equalTo("test-project"));
        given().when().get("/v1/projects/test-project/topics")
                .then().statusCode(200);
    }

    @Test
    void createdProjectIsUsableByIdAndNumber() {
        given().contentType("application/json")
                .body("{\"projectId\":\"strict-created\",\"name\":\"Strict Created\"}")
                .when().post("/v1/projects")
                .then().statusCode(200);

        given().when().get("/v1/projects/strict-created/topics")
                .then().statusCode(200);
        given().contentType("application/json").body("{}")
                .when().put("/v1/projects/" + ProjectNumbers.of("strict-created") + "/topics/numbered")
                .then().statusCode(200);
        given().when().get("/v1/projects/strict-created/billingInfo")
                .then().statusCode(200)
                .body("billingEnabled", equalTo(false));
    }

    public static class StrictProjectsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.projects.strict", "true");
        }
    }
}

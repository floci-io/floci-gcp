package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.ProjectNumbers;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** HMAC keys, the credentials S3-compatible clients use against GCS. */
@QuarkusTest
class GcsHmacKeyControllerTest {

    private static final String PROJECT = "test-project";
    private static final String EMAIL = "compat@test-project.iam.gserviceaccount.com";

    private String createKey() {
        return given().queryParam("serviceAccountEmail", EMAIL)
                .when().post("/storage/v1/projects/" + PROJECT + "/hmacKeys")
                .then().statusCode(200)
                .body("kind", equalTo("storage#hmacKey"))
                .body("secret", notNullValue())
                .body("metadata.state", equalTo("ACTIVE"))
                .extract().path("metadata.accessId");
    }

    @Test
    void createReturnsAnAccessIdAndSecret() {
        String accessId = createKey();
        org.junit.jupiter.api.Assertions.assertTrue(accessId.startsWith("GOOG"));
    }

    @Test
    void createRequiresAServiceAccountEmail() {
        given().when().post("/storage/v1/projects/" + PROJECT + "/hmacKeys")
                .then().statusCode(400);
    }

    @Test
    void theProjectNumberAndTheProjectIdAddressTheSameKeys() {
        String number = ProjectNumbers.of(PROJECT);
        String byId = createKey();
        String byNumber = given().queryParam("serviceAccountEmail", EMAIL)
                .when().post("/storage/v1/projects/" + number + "/hmacKeys")
                .then().statusCode(200)
                .body("metadata.projectId", equalTo(PROJECT))
                .extract().path("metadata.accessId");

        given().when().get("/storage/v1/projects/" + number + "/hmacKeys/" + byId)
                .then().statusCode(200)
                .body("projectId", equalTo(PROJECT));
        given().when().get("/storage/v1/projects/" + PROJECT + "/hmacKeys/" + byNumber)
                .then().statusCode(200);
        given().when().get("/storage/v1/projects/" + number + "/hmacKeys")
                .then().statusCode(200)
                .body("items.accessId", hasItem(byId))
                .body("items.accessId", hasItem(byNumber));
    }

    @Test
    void listIncludesTheCreatedKey() {
        String accessId = createKey();
        given().when().get("/storage/v1/projects/" + PROJECT + "/hmacKeys")
                .then().statusCode(200)
                .body("kind", equalTo("storage#hmacKeysMetadata"))
                .body("items.accessId", hasItem(accessId));
    }

    @Test
    void getReturnsTheMetadataWithoutTheSecret() {
        String accessId = createKey();
        given().when().get("/storage/v1/projects/" + PROJECT + "/hmacKeys/" + accessId)
                .then().statusCode(200)
                .body("accessId", equalTo(accessId))
                .body("state", equalTo("ACTIVE"))
                .body("serviceAccountEmail", equalTo(EMAIL))
                .body("secret", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void anActiveKeyCannotBeDeleted() {
        String accessId = createKey();
        given().when().delete("/storage/v1/projects/" + PROJECT + "/hmacKeys/" + accessId)
                .then().statusCode(400);
    }

    @Test
    void deactivateThenDelete() {
        String accessId = createKey();
        given().contentType("application/json").body(Map.of("state", "INACTIVE"))
                .when().put("/storage/v1/projects/" + PROJECT + "/hmacKeys/" + accessId)
                .then().statusCode(200)
                .body("state", equalTo("INACTIVE"));

        given().when().delete("/storage/v1/projects/" + PROJECT + "/hmacKeys/" + accessId)
                .then().statusCode(204);

        given().when().get("/storage/v1/projects/" + PROJECT + "/hmacKeys/" + accessId)
                .then().statusCode(404);
    }

    @Test
    void anInvalidStateIsRejected() {
        String accessId = createKey();
        given().contentType("application/json").body(Map.of("state", "BANANA"))
                .when().put("/storage/v1/projects/" + PROJECT + "/hmacKeys/" + accessId)
                .then().statusCode(400);
    }

    @Test
    void anUnknownKeyIs404() {
        given().when().get("/storage/v1/projects/" + PROJECT + "/hmacKeys/GOOG1ENOPE")
                .then().statusCode(404);
    }
}

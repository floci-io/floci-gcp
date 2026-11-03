package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.ProjectNumbers;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class GcsProjectControllerTest {

    @Test
    void serviceAccountReturnsAnEmailForTheProject() {
        given().when().get("/storage/v1/projects/test-project/serviceAccount")
                .then().statusCode(200)
                .body("kind", equalTo("storage#serviceAccount"))
                .body("email_address", containsString("@"));
    }

    @Test
    void serviceAccountIsNamedByProjectNumber() {
        String number = ProjectNumbers.of("test-project");

        given().when().get("/storage/v1/projects/test-project/serviceAccount")
                .then().statusCode(200)
                .body("email_address", equalTo("service-" + number + "@gs-project-accounts.iam.gserviceaccount.com"));
        given().when().get("/storage/v1/projects/" + number + "/serviceAccount")
                .then().statusCode(200)
                .body("email_address", equalTo("service-" + number + "@gs-project-accounts.iam.gserviceaccount.com"));
    }

    @Test
    void serviceAccountOnUnknownProjectNumberReturnsPermissionDenied() {
        given().when().get("/storage/v1/projects/00000000000/serviceAccount")
                .then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void serviceAccountIsStableForTheSameProject() {
        String first = given().when().get("/storage/v1/projects/test-project/serviceAccount")
                .then().statusCode(200).extract().path("email_address");
        String second = given().when().get("/storage/v1/projects/test-project/serviceAccount")
                .then().statusCode(200).extract().path("email_address");
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
    }

    @Test
    void differentProjectsGetDifferentServiceAccounts() {
        String a = given().when().get("/storage/v1/projects/project-a/serviceAccount")
                .then().extract().path("email_address");
        String b = given().when().get("/storage/v1/projects/project-b/serviceAccount")
                .then().extract().path("email_address");
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }
}

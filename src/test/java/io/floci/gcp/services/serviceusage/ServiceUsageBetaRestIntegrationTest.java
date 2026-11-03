package io.floci.gcp.services.serviceusage;

import io.floci.gcp.core.common.ProjectNumbers;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

@QuarkusTest
class ServiceUsageBetaRestIntegrationTest {

    @Test
    void generateServiceIdentityReturnsCompletedOperationWithServiceAgent() {
        String number = ProjectNumbers.of("su-identity");

        generateServiceIdentity("su-identity", "pubsub.googleapis.com")
                .statusCode(200)
                .body("name", matchesPattern("operations/.+"))
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.protobuf.Empty"))
                .body("response.'@type'", equalTo("type.googleapis.com/google.api.serviceusage.v1beta1.ServiceIdentity"))
                .body("response.email", equalTo("service-" + number + "@gcp-sa-pubsub.iam.gserviceaccount.com"))
                .body("response.uniqueId", matchesPattern("\\d{21}"));
    }

    @Test
    void generateServiceIdentityReturnsTheSameIdentityEveryTime() {
        String first = generateServiceIdentity("su-identity-stable", "secretmanager.googleapis.com")
                .statusCode(200)
                .extract().path("response.uniqueId");

        generateServiceIdentity("su-identity-stable", "secretmanager.googleapis.com")
                .statusCode(200)
                .body("response.uniqueId", equalTo(first));
    }

    @Test
    void generateServiceIdentityUsesTheServiceAgentDomainOfServicesWithOwnNaming() {
        String number = ProjectNumbers.of("su-identity-named");

        generateServiceIdentity("su-identity-named", "compute.googleapis.com")
                .statusCode(200)
                .body("response.email", equalTo("service-" + number + "@compute-system.iam.gserviceaccount.com"));
        generateServiceIdentity("su-identity-named", "storage.googleapis.com")
                .statusCode(200)
                .body("response.email", equalTo("service-" + number + "@gs-project-accounts.iam.gserviceaccount.com"));
    }

    @Test
    void storageServiceIdentityMatchesTheGcsProjectServiceAccount() {
        String gcsAgent = given()
                .when().get("/storage/v1/projects/su-identity-gcs/serviceAccount")
                .then().statusCode(200)
                .extract().path("email_address");

        generateServiceIdentity("su-identity-gcs", "storage.googleapis.com")
                .statusCode(200)
                .body("response.email", equalTo(gcsAgent));
    }

    @Test
    void generateServiceIdentityByProjectNumberNamesTheSameAgent() {
        String number = ProjectNumbers.of("test-project");

        generateServiceIdentity(number, "pubsub.googleapis.com")
                .statusCode(200)
                .body("response.email", equalTo("service-" + number + "@gcp-sa-pubsub.iam.gserviceaccount.com"));
    }

    @Test
    void generateServiceIdentityOperationCanBePolled() {
        String operation = generateServiceIdentity("su-identity-poll", "pubsub.googleapis.com")
                .statusCode(200)
                .extract().path("name");

        given()
                .when().get("/v1beta1/" + operation)
                .then()
                .statusCode(200)
                .body("name", equalTo(operation))
                .body("done", equalTo(true))
                .body("response.'@type'", equalTo("type.googleapis.com/google.api.serviceusage.v1beta1.ServiceIdentity"));
    }

    @Test
    void generateServiceIdentityOnUnknownProjectNumberReturnsPermissionDenied() {
        generateServiceIdentity("00000000000", "pubsub.googleapis.com")
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    private static ValidatableResponse generateServiceIdentity(String project, String service) {
        return given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v1beta1/projects/" + project + "/services/" + service + ":generateServiceIdentity")
                .then();
    }
}

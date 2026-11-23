package io.floci.gcp.services.serviceusage;

import io.floci.gcp.core.common.ProjectNumbers;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
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
    void generateServiceIdentityAcceptsTheEmptyFormBodyTheJavaSdkSends() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/x-www-form-urlencoded")
                .when().post("/v1beta1/projects/su-identity-form/services/pubsub.googleapis.com:generateServiceIdentity")
                .then()
                .statusCode(200)
                .body("done", equalTo(true));
    }

    @Test
    void generateServiceIdentityOnUnknownProjectNumberReturnsPermissionDenied() {
        generateServiceIdentity("00000000000", "pubsub.googleapis.com")
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void enableGetListAndDisableUnderV1beta1() {
        String base = "/v1beta1/projects/su-beta-lifecycle/services";

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + "/storage.googleapis.com:enable")
                .then().statusCode(200)
                .body("done", equalTo(true))
                .body("response.'@type'", equalTo("type.googleapis.com/google.protobuf.Empty"))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.api.serviceusage.v1beta1.OperationMetadata"))
                .body("metadata.resourceNames[0]",
                        equalTo("projects/su-beta-lifecycle/services/storage.googleapis.com"));

        given().when().get(base + "/storage.googleapis.com")
                .then().statusCode(200)
                .body("name", equalTo("projects/su-beta-lifecycle/services/storage.googleapis.com"))
                .body("parent", equalTo("projects/su-beta-lifecycle"))
                .body("config.name", equalTo("storage.googleapis.com"))
                .body("state", equalTo("ENABLED"));

        given().queryParam("filter", "state:ENABLED")
                .when().get(base)
                .then().statusCode(200)
                .body("services.name", hasItem("projects/su-beta-lifecycle/services/storage.googleapis.com"));

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + "/storage.googleapis.com:disable")
                .then().statusCode(200)
                .body("done", equalTo(true))
                .body("response.'@type'", equalTo("type.googleapis.com/google.protobuf.Empty"));

        given().when().get(base + "/storage.googleapis.com")
                .then().statusCode(200)
                .body("state", equalTo("DISABLED"));
    }

    @Test
    void batchEnableUnderV1beta1ChangesStateSeenByV1() {
        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"serviceIds\": [\"pubsub.googleapis.com\", \"run.googleapis.com\"]}")
                .when().post("/v1beta1/projects/su-beta-batch/services:batchEnable")
                .then().statusCode(200)
                .body("done", equalTo(true))
                .body("response.'@type'", equalTo("type.googleapis.com/google.protobuf.Empty"))
                .body("metadata.resourceNames", hasSize(2));

        given().when().get("/v1/projects/su-beta-batch/services/run.googleapis.com")
                .then().statusCode(200)
                .body("state", equalTo("ENABLED"));
    }

    @Test
    void disableUnderV1beta1OfNotEnabledServiceFailsPrecondition() {
        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post("/v1beta1/projects/su-beta-disable/services/pubsub.googleapis.com:disable")
                .then().statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"));
    }

    @Test
    void getUnderV1beta1OnUnknownProjectNumberReturnsPermissionDenied() {
        given().when().get("/v1beta1/projects/00000000000/services/serviceusage.googleapis.com")
                .then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void batchGetIsNotServedUnderV1beta1() {
        given().queryParam("names", "projects/p/services/storage.googleapis.com")
                .when().get("/v1beta1/projects/p/services:batchGet")
                .then().statusCode(404);
    }

    @Test
    void generateServiceIdentityIsNotServedUnderV1() {
        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post("/v1/projects/p/services/pubsub.googleapis.com:generateServiceIdentity")
                .then().statusCode(404);
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

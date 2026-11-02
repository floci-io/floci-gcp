package io.floci.gcp.services.serviceusage;

import io.floci.gcp.core.common.ProjectNumbers;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class ServiceUsageRestIntegrationTest {

    /** The dummy project the Google provider probes before creating a project with billing. */
    private static final String UNKNOWN_PROJECT_NUMBER = "00000000000";

    @Test
    void enableGetDisableRoundtripUsesServiceUsageShapes() {
        String base = "/v1/projects/su-roundtrip/services";

        String operation = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post(base + "/run.googleapis.com:enable")
                .then()
                .statusCode(200)
                .body("name", startsWith("operations/"))
                .body("done", equalTo(true))
                .body("response.'@type'", equalTo("type.googleapis.com/google.api.serviceusage.v1.EnableServiceResponse"))
                .body("response.service.name", equalTo("projects/su-roundtrip/services/run.googleapis.com"))
                .body("response.service.parent", equalTo("projects/su-roundtrip"))
                .body("response.service.config.name", equalTo("run.googleapis.com"))
                .body("response.service.state", equalTo("ENABLED"))
                .extract().path("name");

        given()
                .when().get("/v1/" + operation)
                .then()
                .statusCode(200)
                .body("name", equalTo(operation))
                .body("done", equalTo(true));

        given()
                .when().get(base + "/run.googleapis.com")
                .then()
                .statusCode(200)
                .body("name", equalTo("projects/su-roundtrip/services/run.googleapis.com"))
                .body("state", equalTo("ENABLED"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post(base + "/run.googleapis.com:disable")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.'@type'", equalTo("type.googleapis.com/google.api.serviceusage.v1.DisableServiceResponse"))
                .body("response.service.state", equalTo("DISABLED"));

        given()
                .when().get(base + "/run.googleapis.com")
                .then()
                .statusCode(200)
                .body("state", equalTo("DISABLED"));
    }

    @Test
    void getUntrackedServiceReportsDisabled() {
        given()
                .when().get("/v1/projects/su-untracked/services/compute.googleapis.com")
                .then()
                .statusCode(200)
                .body("name", equalTo("projects/su-untracked/services/compute.googleapis.com"))
                .body("state", equalTo("DISABLED"));
    }

    @Test
    void disableNotEnabledServiceReturnsFailedPrecondition() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v1/projects/su-precondition/services/run.googleapis.com:disable")
                .then()
                .statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"));
    }

    @Test
    void listFiltersByState() {
        String base = "/v1/projects/su-list/services";
        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + "/run.googleapis.com:enable").then().statusCode(200);
        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + "/pubsub.googleapis.com:enable").then().statusCode(200);
        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + "/pubsub.googleapis.com:disable").then().statusCode(200);

        given()
                .queryParam("filter", "state:ENABLED")
                .when().get(base)
                .then()
                .statusCode(200)
                .body("services", hasSize(1))
                .body("services[0].name", equalTo("projects/su-list/services/run.googleapis.com"))
                .body("services[0].state", equalTo("ENABLED"));

        given()
                .queryParam("filter", "state:BROKEN")
                .when().get(base)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void batchEnableAndBatchGetOperateOnMultipleServices() {
        String project = "su-batch";
        String base = "/v1/projects/" + project + "/services";

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"serviceIds\": [\"run.googleapis.com\", \"pubsub.googleapis.com\"]}")
                .when().post(base + ":batchEnable")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.'@type'", equalTo("type.googleapis.com/google.api.serviceusage.v1.BatchEnableServicesResponse"))
                .body("response.services", hasSize(2));

        given()
                .urlEncodingEnabled(false)
                .queryParam("names", "projects/" + project + "/services/run.googleapis.com")
                .queryParam("names", "projects/" + project + "/services/compute.googleapis.com")
                .when().get(base + ":batchGet")
                .then()
                .statusCode(200)
                .body("services", hasSize(2))
                .body("services[0].state", equalTo("ENABLED"))
                .body("services[1].state", equalTo("DISABLED"));
    }

    @Test
    void getOnUnknownProjectNumberReturnsPermissionDenied() {
        given()
                .when().get("/v1/projects/" + UNKNOWN_PROJECT_NUMBER + "/services/serviceusage.googleapis.com")
                .then()
                .statusCode(403)
                .body("error.code", equalTo(403))
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void listOnUnknownProjectNumberReturnsPermissionDenied() {
        given()
                .when().get("/v1/projects/" + UNKNOWN_PROJECT_NUMBER + "/services")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void batchGetOnUnknownProjectNumberReturnsPermissionDenied() {
        given()
                .urlEncodingEnabled(false)
                .queryParam("names", "projects/" + UNKNOWN_PROJECT_NUMBER + "/services/run.googleapis.com")
                .when().get("/v1/projects/" + UNKNOWN_PROJECT_NUMBER + "/services:batchGet")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void enableOnUnknownProjectNumberReturnsPermissionDenied() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v1/projects/" + UNKNOWN_PROJECT_NUMBER + "/services/run.googleapis.com:enable")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void disableOnUnknownProjectNumberReturnsPermissionDenied() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v1/projects/" + UNKNOWN_PROJECT_NUMBER + "/services/run.googleapis.com:disable")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void batchEnableOnUnknownProjectNumberReturnsPermissionDenied() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"serviceIds\": [\"run.googleapis.com\"]}")
                .when().post("/v1/projects/" + UNKNOWN_PROJECT_NUMBER + "/services:batchEnable")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void getOnKnownProjectNumberStillResolves() {
        String number = ProjectNumbers.of("test-project");
        given()
                .when().get("/v1/projects/" + number + "/services/compute.googleapis.com")
                .then()
                .statusCode(200)
                .body("name", equalTo("projects/" + number + "/services/compute.googleapis.com"));
    }
}

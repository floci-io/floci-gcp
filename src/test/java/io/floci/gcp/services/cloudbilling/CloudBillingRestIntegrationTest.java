package io.floci.gcp.services.cloudbilling;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class CloudBillingRestIntegrationTest {

    @Test
    void linkAndUnlinkBillingAccount() {
        String base = "/v1/projects/billing-link-test/billingInfo";

        given().when().get(base)
                .then().statusCode(200)
                .body("name", equalTo("projects/billing-link-test/billingInfo"))
                .body("projectId", equalTo("billing-link-test"))
                .body("billingAccountName", equalTo(""))
                .body("billingEnabled", equalTo(false));

        given().contentType("application/json")
                .body("{\"billingAccountName\":\"billingAccounts/000000-AAAAAA-BBBBBB\"}")
                .when().put(base)
                .then().statusCode(200)
                .body("billingAccountName", equalTo("billingAccounts/000000-AAAAAA-BBBBBB"))
                .body("billingEnabled", equalTo(true));

        given().when().get(base)
                .then().statusCode(200)
                .body("billingAccountName", equalTo("billingAccounts/000000-AAAAAA-BBBBBB"))
                .body("billingEnabled", equalTo(true));

        given().contentType("application/json").body("{}")
                .when().put(base)
                .then().statusCode(200)
                .body("billingAccountName", equalTo(""))
                .body("billingEnabled", equalTo(false));
    }

    @Test
    void rejectsMalformedBillingAccountName() {
        given().contentType("application/json")
                .body("{\"billingAccountName\":\"000000-AAAAAA-BBBBBB\"}")
                .when().put("/v1/projects/billing-bad-test/billingInfo")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void billingAccountTestIamPermissionsEchoesPermissions() {
        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"permissions\":[\"billing.resourceAssociations.create\"]}")
                .when().post("/v1/billingAccounts/000000-AAAAAA-BBBBBB:testIamPermissions")
                .then().statusCode(200)
                .body("permissions", contains("billing.resourceAssociations.create"));
    }
}

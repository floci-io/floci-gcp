package io.floci.gcp.services.cloudbilling;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class CloudBillingRestIntegrationTest {

    @Test
    void projectWithoutAssociationReportsBillingDisabled() {
        given().when().get("/v1/projects/billing-none/billingInfo?alt=json")
                .then().statusCode(200)
                .body("name", equalTo("projects/billing-none/billingInfo"))
                .body("projectId", equalTo("billing-none"))
                .body("billingEnabled", equalTo(false))
                .body("billingAccountName", nullValue());
    }

    @Test
    void updateBillingInfoAssociatesAndDisassociatesAccount() {
        String path = "/v1/projects/billing-update/billingInfo";

        given().contentType("application/json")
                .body("{\"billingAccountName\":\"billingAccounts/ABCDEF-123456-7890AB\"}")
                .when().put(path)
                .then().statusCode(200)
                .body("billingAccountName", equalTo("billingAccounts/ABCDEF-123456-7890AB"))
                .body("billingEnabled", equalTo(true));

        given().when().get(path)
                .then().statusCode(200)
                .body("billingAccountName", equalTo("billingAccounts/ABCDEF-123456-7890AB"))
                .body("billingEnabled", equalTo(true));

        given().contentType("application/json").body("{\"billingAccountName\":\"\"}")
                .when().put(path)
                .then().statusCode(200)
                .body("billingEnabled", equalTo(false))
                .body("billingAccountName", nullValue());
    }

    @Test
    void updateBillingInfoRejectsMalformedAccountName() {
        given().contentType("application/json").body("{\"billingAccountName\":\"billingAccounts/nope\"}")
                .when().put("/v1/projects/billing-bad/billingInfo")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void billingAccountsGetListAndProjects() {
        String account = "billingAccounts/AAAAAA-BBBBBB-CCCCCC";
        given().contentType("application/json").body("{\"billingAccountName\":\"" + account + "\"}")
                .when().put("/v1/projects/billing-listed/billingInfo")
                .then().statusCode(200);

        given().when().get("/v1/billingAccounts/AAAAAA-BBBBBB-CCCCCC")
                .then().statusCode(200)
                .body("name", equalTo(account))
                .body("open", equalTo(true));

        given().when().get("/v1/billingAccounts")
                .then().statusCode(200)
                .body("billingAccounts.name", hasItems("billingAccounts/000000-000000-000000", account));

        given().when().get("/v1/billingAccounts/AAAAAA-BBBBBB-CCCCCC/projects")
                .then().statusCode(200)
                .body("projectBillingInfo.projectId", hasItem("billing-listed"))
                .body("projectBillingInfo.projectId", not(hasItem("billing-none")));

        given().when().get("/v1/billingAccounts/bad")
                .then().statusCode(400);
    }

    @Test
    void billingInfoCoexistsWithResourceManagerProjectGet() {
        given().when().get("/v1/projects/billing-rm")
                .then().statusCode(200)
                .body("projectId", equalTo("billing-rm"));
        given().when().get("/v1/projects/billing-rm/billingInfo")
                .then().statusCode(200)
                .body("projectId", equalTo("billing-rm"));
    }
}

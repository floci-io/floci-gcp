package io.floci.gcp.services.cloudbilling;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

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
    void getReturnsAnOpenBillingAccount() {
        given().when().get("/v1/billingAccounts/000000-000000-000000")
                .then().statusCode(200)
                .body("name", equalTo("billingAccounts/000000-000000-000000"))
                .body("open", equalTo(true))
                .body("displayName", equalTo("My Billing Account"))
                .body("masterBillingAccount", equalTo(""))
                .body("parent", equalTo(""))
                .body("currencyCode", equalTo("USD"));

        given().when().get("/v1/billingAccounts/111111-CCCCCC-DDDDDD")
                .then().statusCode(200)
                .body("name", equalTo("billingAccounts/111111-CCCCCC-DDDDDD"))
                .body("open", equalTo(true));
    }

    @Test
    void listIncludesTheDefaultAccountAndEveryLinkedAccount() {
        linkProject("billing-list-test", "billingAccounts/222222-EEEEEE-FFFFFF");

        given().when().get("/v1/billingAccounts")
                .then().statusCode(200)
                .body("billingAccounts.name", hasItems(
                        "billingAccounts/000000-000000-000000", "billingAccounts/222222-EEEEEE-FFFFFF"));
    }

    @Test
    void listPagesWithPageSizeAndPageToken() {
        linkProject("billing-page-test", "billingAccounts/333333-GGGGGG-HHHHHH");

        String token = given().when().get("/v1/billingAccounts?pageSize=1")
                .then().statusCode(200)
                .body("billingAccounts", hasSize(1))
                .body("nextPageToken", not(equalTo("")))
                .extract().path("nextPageToken");

        given().when().get("/v1/billingAccounts?pageSize=1&pageToken=" + token)
                .then().statusCode(200)
                .body("billingAccounts", hasSize(1));
    }

    @Test
    void malformedBillingAccountIdIsAnInvalidArgument() {
        given().when().get("/v1/billingAccounts/not-an-id")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));

        given().when().get("/v1/billingAccounts/not-an-id/projects")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void projectsListReturnsOnlyProjectsLinkedToTheAccount() {
        String account = "billingAccounts/444444-IIIIII-JJJJJJ";
        linkProject("billing-projects-b", account);
        linkProject("billing-projects-a", account);
        linkProject("billing-projects-other", "billingAccounts/555555-KKKKKK-LLLLLL");

        given().when().get("/v1/" + account + "/projects")
                .then().statusCode(200)
                .body("projectBillingInfo.projectId", contains("billing-projects-a", "billing-projects-b"))
                .body("projectBillingInfo.billingEnabled", contains(true, true));

        given().contentType("application/json").body("{}")
                .when().put("/v1/projects/billing-projects-a/billingInfo")
                .then().statusCode(200);

        given().when().get("/v1/" + account + "/projects")
                .then().statusCode(200)
                .body("projectBillingInfo.projectId", contains("billing-projects-b"));
    }

    @Test
    void projectsListOfAnAccountWithNoProjectsIsEmpty() {
        given().when().get("/v1/billingAccounts/666666-MMMMMM-NNNNNN/projects")
                .then().statusCode(200)
                .body("projectBillingInfo", empty())
                .body("nextPageToken", equalTo(""));
    }

    private static void linkProject(String project, String account) {
        given().contentType("application/json")
                .body("{\"billingAccountName\":\"" + account + "\"}")
                .when().put("/v1/projects/" + project + "/billingInfo")
                .then().statusCode(200);
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

package io.floci.gcp.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class IamServiceAccountRestIntegrationTest {

    private static final String PROJECT = "iam-sa-it";
    private static final String ACCOUNTS = "/v1/projects/" + PROJECT + "/serviceAccounts";

    private static String create(String accountId, Map<String, Object> serviceAccount) {
        given().contentType("application/json")
                .body(Map.of("accountId", accountId, "serviceAccount", serviceAccount))
                .when().post(ACCOUNTS)
                .then().statusCode(200);
        return ACCOUNTS + "/" + accountId + "@" + PROJECT + ".iam.gserviceaccount.com";
    }

    @Test
    void createWithoutDisplayNameReturnsNoDisplayName() {
        String sa = create("no-name", Map.of());

        given().when().get(sa)
                .then().statusCode(200)
                .body("$", not(hasKey("displayName")));
    }

    // hashicorp/google 8.6.0 clears display_name with a PatchServiceAccountRequest whose
    // serviceAccount omits displayName and whose updateMask names it in snake_case.
    @Test
    void patchWithSnakeCaseMaskClearsDisplayName() {
        String sa = create("clear-snake", Map.of("displayName", "Named", "description", "Kept"));

        given().contentType("application/json")
                .body(Map.of("serviceAccount", Map.of("description", "Unmasked"), "updateMask", "display_name"))
                .when().patch(sa)
                .then().statusCode(200)
                .body("$", not(hasKey("displayName")))
                .body("description", equalTo("Kept"));

        given().when().get(sa)
                .then().statusCode(200)
                .body("$", not(hasKey("displayName")))
                .body("description", equalTo("Kept"));
    }

    @Test
    void patchWithCamelCaseMaskAtTopLevelClearsDisplayName() {
        String sa = create("clear-camel", Map.of("displayName", "Named"));

        given().contentType("application/json")
                .body(Map.of("displayName", "", "updateMask", "displayName"))
                .when().patch(sa)
                .then().statusCode(200)
                .body("$", not(hasKey("displayName")));
    }

    @Test
    void recreatedAccountHasANewUniqueIdAndNoKeysOfTheDeletedAccount() {
        String sa = create("recreated", Map.of());
        String oldUniqueId = given().when().get(sa).then().statusCode(200).extract().path("uniqueId");
        String oldKeyName = given().contentType("application/json").body(Map.of())
                .when().post(sa + "/keys")
                .then().statusCode(200)
                .extract().path("name");

        given().when().delete(sa).then().statusCode(200);
        create("recreated", Map.of());

        given().when().get(sa)
                .then().statusCode(200)
                .body("uniqueId", not(equalTo(oldUniqueId)));
        given().when().get(sa + "/keys")
                .then().statusCode(200)
                .body("keys", anyOf(nullValue(), empty()));
        given().when().get("/v1/" + oldKeyName)
                .then().statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
    }

    @Test
    void patchWithoutMaskLeavesOmittedFieldsAlone() {
        String sa = create("no-mask", Map.of("displayName", "Named", "description", "Kept"));

        given().contentType("application/json")
                .body(Map.of("serviceAccount", Map.of("description", "Changed")))
                .when().patch(sa)
                .then().statusCode(200)
                .body("displayName", equalTo("Named"))
                .body("description", equalTo("Changed"));
    }

    // https://docs.cloud.google.com/iam/docs/reference/rest/v1/projects.serviceAccounts/disable and /enable:
    // empty response bodies, and the ServiceAccount "disabled" field ("Output only. Whether the service
    // account is disabled."), which proto3 JSON omits while false.
    @Test
    void disableAndEnableToggleTheDisabledField() {
        String sa = create("toggled", Map.of());

        given().contentType("application/json").body(Map.of())
                .when().post(sa + ":disable")
                .then().statusCode(200)
                .body("$", equalTo(Map.of()));
        given().when().get(sa)
                .then().statusCode(200)
                .body("disabled", equalTo(true));

        given().contentType("application/json").body(Map.of())
                .when().post(sa + ":enable")
                .then().statusCode(200)
                .body("$", equalTo(Map.of()));
        given().when().get(sa)
                .then().statusCode(200)
                .body("$", not(hasKey("disabled")));
    }

    // "If you attempt to disable an already disabled service account, it will have no effect", and the
    // same for enable (https://docs.cloud.google.com/iam/docs/service-accounts-disable-enable).
    @Test
    void disablingOrEnablingTwiceHasNoEffect() {
        String sa = create("twice", Map.of());

        for (String method : new String[] {"disable", "disable", "enable", "enable"}) {
            given().contentType("application/json").body(Map.of())
                    .when().post(sa + ":" + method)
                    .then().statusCode(200);
        }
        given().when().get(sa)
                .then().statusCode(200)
                .body("$", not(hasKey("disabled")));
    }

    @Test
    void disableAcceptsTheWildcardProjectAndReportsAMissingAccount() {
        create("wildcard", Map.of());

        given().contentType("application/json").body(Map.of())
                .when().post("/v1/projects/-/serviceAccounts/wildcard@" + PROJECT + ".iam.gserviceaccount.com:disable")
                .then().statusCode(200);
        given().when().get(ACCOUNTS + "/wildcard@" + PROJECT + ".iam.gserviceaccount.com")
                .then().statusCode(200)
                .body("disabled", equalTo(true));
        given().contentType("application/json").body(Map.of())
                .when().post(ACCOUNTS + "/missing@" + PROJECT + ".iam.gserviceaccount.com:disable")
                .then().statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
    }
}

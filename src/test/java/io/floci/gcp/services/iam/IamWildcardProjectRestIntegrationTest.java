package io.floci.gcp.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class IamWildcardProjectRestIntegrationTest {

    private static final String PROJECT = "iam-wildcard-it";
    private static final String EMAIL = "tf-sa@" + PROJECT + ".iam.gserviceaccount.com";
    private static final String WILDCARD_SA = "/v1/projects/-/serviceAccounts/" + EMAIL;

    @Test
    void serviceAccountAndKeysAreReachableThroughProjectWildcard() {
        given()
                .contentType("application/json")
                .body(Map.of("accountId", "tf-sa", "serviceAccount", Map.of("displayName", "TF")))
                .when().post("/v1/projects/" + PROJECT + "/serviceAccounts")
                .then()
                .statusCode(200);

        given()
                .when().get(WILDCARD_SA)
                .then()
                .statusCode(200)
                .body("name", equalTo("projects/" + PROJECT + "/serviceAccounts/" + EMAIL))
                .body("projectId", equalTo(PROJECT));

        String keyId = given()
                .contentType("application/json")
                .body("{}")
                .when().post(WILDCARD_SA + "/keys")
                .then()
                .statusCode(200)
                .body("name", startsWith("projects/" + PROJECT + "/serviceAccounts/" + EMAIL + "/keys/"))
                .extract().path("keyId");

        given()
                .when().get(WILDCARD_SA + "/keys")
                .then()
                .statusCode(200)
                .body("keys", hasSize(1));

        given()
                .when().get(WILDCARD_SA + "/keys/" + keyId)
                .then()
                .statusCode(200)
                .body("keyId", equalTo(keyId));

        given()
                .contentType("application/json")
                .body("{}")
                .when().post(WILDCARD_SA + ":getIamPolicy")
                .then()
                .statusCode(200);

        given()
                .when().delete(WILDCARD_SA)
                .then()
                .statusCode(200);

        given()
                .when().get("/v1/projects/" + PROJECT + "/serviceAccounts/" + EMAIL)
                .then()
                .statusCode(404);
    }

    @Test
    void missingServiceAccountUnderWildcardIsPermissionDenied() {
        given()
                .when().get("/v1/projects/-/serviceAccounts/fake@example.com")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @ParameterizedTest
    @CsvSource({"GET, /keys", "POST, /keys", "GET, /keys/fake-key", "DELETE, /keys/fake-key", "GET, ''",
            "PUT, ''", "PATCH, ''", "DELETE, ''"})
    void documentedMethodsDenyAMissingAccountUnderWildcard(String method, String suffix) {
        given()
                .contentType("application/json")
                .body("{}")
                .when().request(method, "/v1/projects/-/serviceAccounts/fake@example.com" + suffix)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "DELETE"})
    void missingKeyOfAnExistingAccountUnderWildcardIsPermissionDenied(String method) {
        Map<String, String> account = createAccount("wild-" + method.toLowerCase(Locale.ROOT));

        for (String address : List.of(account.get("email"), account.get("uniqueId"))) {
            String name = "/v1/projects/-/serviceAccounts/" + address + "/keys/fake-key";
            given()
                    .contentType("application/json")
                    .when().request(method, name)
                    .then()
                    .statusCode(403)
                    .body("error.code", equalTo(403))
                    .body("error.status", equalTo("PERMISSION_DENIED"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "DELETE"})
    void missingKeyUnderARealProjectIsNotFound(String method) {
        Map<String, String> account = createAccount("real-" + method.toLowerCase(Locale.ROOT));

        for (String address : List.of(account.get("email"), account.get("uniqueId"))) {
            given()
                    .contentType("application/json")
                    .when().request(method, "/v1/projects/" + PROJECT + "/serviceAccounts/" + address + "/keys/fake-key")
                    .then()
                    .statusCode(404)
                    .body("error.status", equalTo("NOT_FOUND"));
        }
    }

    @Test
    void aKeyDeletedThroughTheWildcardIsThenDeniedThereAndMissingInItsProject() {
        String email = createAccount("wild-deleted").get("email");
        String wildcardAccount = "/v1/projects/-/serviceAccounts/" + email;
        String keyId = given()
                .contentType("application/json")
                .body("{}")
                .when().post(wildcardAccount + "/keys")
                .then()
                .statusCode(200)
                .extract().path("keyId");

        given()
                .when().delete(wildcardAccount + "/keys/" + keyId)
                .then()
                .statusCode(200);

        given()
                .when().delete(wildcardAccount + "/keys/" + keyId)
                .then()
                .statusCode(403);
        given()
                .when().get("/v1/projects/" + PROJECT + "/serviceAccounts/" + email + "/keys/" + keyId)
                .then()
                .statusCode(404);
    }

    private static Map<String, String> createAccount(String accountId) {
        return given()
                .contentType("application/json")
                .body(Map.of("accountId", accountId))
                .when().post("/v1/projects/" + PROJECT + "/serviceAccounts")
                .then()
                .statusCode(200)
                .extract().jsonPath().getMap("$");
    }
}

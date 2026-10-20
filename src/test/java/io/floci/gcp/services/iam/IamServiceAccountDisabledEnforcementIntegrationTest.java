package io.floci.gcp.services.iam;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.iam.model.StoredServiceAccount;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestProfile(IamServiceAccountDisabledEnforcementIntegrationTest.DisabledProfile.class)
class IamServiceAccountDisabledEnforcementIntegrationTest {

    @Inject
    CredentialTokenService tokens;
    @Inject
    IamService iam;

    @Test
    void disabledModeKeepsServiceAccountAndCredentialOperationsPermissive() {
        String project = "iam-disabled-" + UUID.randomUUID().toString().substring(0, 8);
        String email = "target@" + project + ".iam.gserviceaccount.com";

        String authorization = "Bearer " + tokens.mintImpersonatedToken("caller@" + project + ".iam.gserviceaccount.com",
                Instant.now().plusSeconds(300)).getTokenValue();

        given().header("Authorization", authorization).contentType("application/json").body(Map.of("accountId", "target"))
                .when().post("/v1/projects/" + project + "/serviceAccounts")
                .then().statusCode(200);

        given().header("Authorization", authorization).urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("scope", List.of("https://www.googleapis.com/auth/cloud-platform")))
                .when().post("/v1/projects/-/serviceAccounts/" + email + ":generateAccessToken")
                .then().statusCode(200).body("accessToken", startsWith("floci-gcp-impersonated-"));
    }

    @Test
    void permissionTestsReturnEmptyForDeletedAccountsWhenDisabled() {
        String project = "iam-disabled-missing-" + UUID.randomUUID().toString().substring(0, 8);
        StoredServiceAccount account = iam.createServiceAccount(project, "target", "target", "");
        iam.deleteServiceAccount(project, account.getEmail());
        String authorization = "Bearer " + tokens.mintImpersonatedToken("caller@" + project + ".iam.gserviceaccount.com",
                Instant.now().plusSeconds(300)).getTokenValue();
        for (String identifier : List.of(account.getEmail(), "target", account.getUniqueId())) {
            String resource = "projects/" + project + "/serviceAccounts/" + identifier;
            given().header("Authorization", authorization).contentType("application/json")
                    .body(Map.of("permissions", List.of("iam.serviceAccounts.get")))
                    .when().post("/v1/" + resource + ":testIamPermissions")
                    .then().statusCode(200).body("permissions", empty());
        }
        given().header("Authorization", authorization).contentType("application/json").body(Map.of())
                .when().post("/v1/" + account.getName() + ":getIamPolicy")
                .then().statusCode(404);
        given().header("Authorization", authorization).contentType("application/json")
                .body(Map.of("policy", Map.of()))
                .when().post("/v1/" + account.getName() + ":setIamPolicy")
                .then().statusCode(404);
    }

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "disabled");
        }
    }
}

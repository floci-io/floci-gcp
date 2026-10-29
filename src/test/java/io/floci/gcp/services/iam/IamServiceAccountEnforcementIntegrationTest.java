package io.floci.gcp.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamServiceAccountEnforcementIntegrationTest.EnforceProfile.class)
class IamServiceAccountEnforcementIntegrationTest {

    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    @Inject
    CredentialTokenService tokens;
    @Inject
    IamService iam;

    @Inject
    StorageFactory storageFactory;

    private StorageBackend<String, StoredCredentialToken> credentialStore;
    private String project;
    private String callerEmail;
    private String callerAuthorization;
    private String targetEmail;

    @BeforeEach
    void setUp() {
        credentialStore = storageFactory.createGlobal("credential-tokens", "credential-tokens.json",
                new TypeReference<Map<String, StoredCredentialToken>>() {});
        project = "iam-sa-" + UUID.randomUUID().toString().substring(0, 8);
        callerEmail = "caller@" + project + ".iam.gserviceaccount.com";
        targetEmail = "target@" + project + ".iam.gserviceaccount.com";
        callerAuthorization = "Bearer " + tokens.mintImpersonatedToken(callerEmail,
                Instant.now().plusSeconds(300)).getTokenValue();
    }

    @Test
    void permissionTestsReturnEmptyForMissingAccountsWithoutProjectGrants() {
        String resource = "projects/" + project + "/serviceAccounts/" + targetEmail;
        given().header("Authorization", callerAuthorization).contentType("application/json")
                .body(Map.of("permissions", List.of("iam.serviceAccounts.get", "iam.serviceAccounts.update")))
                .when().post("/v1/" + resource + ":testIamPermissions")
                .then().statusCode(200).body("permissions", empty());
    }

    @Test
    void serviceAccountCreateRequiresProjectPermissionAndLeavesNoAccountOnDenial() {
        String createPath = "/v1/projects/" + project + "/serviceAccounts";
        Map<String, Object> body = Map.of("accountId", "target");

        given().header("Authorization", callerAuthorization).contentType("application/json").body(body)
                .when().post(createPath)
                .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));

        given().when().get("/v1/projects/" + project + "/serviceAccounts/" + targetEmail)
                .then().statusCode(404);

        given().header("Authorization", callerAuthorization).when().get(createPath)
                .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));

        setPolicy("projects/" + project, "roles/iam.serviceAccountAdmin", callerEmail,
                "resource.name == 'projects/" + project + "'");

        given().header("Authorization", callerAuthorization).contentType("application/json").body(body)
                .when().post(createPath)
                .then().statusCode(200).body("email", equalTo(targetEmail));

        given().header("Authorization", callerAuthorization)
                .when().get("/v1/projects/" + project + "/serviceAccounts")
                .then().statusCode(200);
        given().header("Authorization", callerAuthorization)
                .when().get("/v1/projects/" + project + "/serviceAccounts/" + targetEmail)
                .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));

        String targetResource = "projects/" + project + "/serviceAccounts/" + targetEmail;
        setPolicy(targetResource, "roles/iam.serviceAccountAdmin", callerEmail, null);
        given().header("Authorization", callerAuthorization)
                .when().get("/v1/projects/" + project + "/serviceAccounts/" + targetEmail)
                .then().statusCode(200).body("email", equalTo(targetEmail));
        given().header("Authorization", callerAuthorization).contentType("application/json")
                .body(Map.of("displayName", "updated target"))
                .when().put("/v1/projects/" + project + "/serviceAccounts/" + targetEmail)
                .then().statusCode(200).body("displayName", equalTo("updated target"));

        given().header("Authorization", callerAuthorization)
                .when().get("/v1/projects/" + project + "/serviceAccounts/" + targetEmail + "/keys")
                .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
        setPolicy(targetResource, "roles/iam.serviceAccountKeyAdmin", callerEmail, null);
        given().header("Authorization", callerAuthorization).urlEncodingEnabled(false)
                .contentType("application/json").body(Map.of())
                .when().post("/v1/projects/" + project + "/serviceAccounts/" + targetEmail + "/keys")
                .then().statusCode(200);
    }

    @Test
    void accessTokenRequiresTokenCreatorAndIamPolicyReportsOnlyGrantedPermissions() {
        iam.createServiceAccount(project, "target", "target account", "");
        String path = "/v1/projects/-/serviceAccounts/" + targetEmail + ":generateAccessToken";
        String signBlobPath = "/v1/projects/" + project + "/serviceAccounts/" + targetEmail + ":signBlob";
        Map<String, Object> request = Map.of("scope", List.of(CLOUD_PLATFORM_SCOPE));

        given().header("Authorization", callerAuthorization).urlEncodingEnabled(false)
                .contentType("application/json").body(request).when().post(path)
                .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
        given().header("Authorization", callerAuthorization).urlEncodingEnabled(false)
                .contentType("application/json").body(Map.of("bytesToSign", "aGVsbG8="))
                .when().post(signBlobPath)
                .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));

        assertTrue(credentialStore.scan(key -> true).stream()
                .noneMatch(token -> targetEmail.equals(token.getPrincipal())));
        String targetResource = "projects/" + project + "/serviceAccounts/" + targetEmail;
        setPolicy(targetResource, "roles/iam.serviceAccountTokenCreator", callerEmail, null);

        given().header("Authorization", callerAuthorization).urlEncodingEnabled(false)
                .contentType("application/json").body(request).when().post(path)
                .then().statusCode(200).body("accessToken", startsWith(CredentialTokenService.IMPERSONATED_TOKEN_PREFIX));
        given().header("Authorization", callerAuthorization).urlEncodingEnabled(false)
                .contentType("application/json").body(Map.of("bytesToSign", "aGVsbG8="))
                .when().post(signBlobPath)
                .then().statusCode(200);
        assertEquals(1, credentialStore.scan(key -> true).stream()
                .filter(token -> targetEmail.equals(token.getPrincipal())).count());

        given().header("Authorization", callerAuthorization).urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of("permissions", List.of("iam.serviceAccounts.getAccessToken", "iam.serviceAccounts.update")))
                .when().post("/v1/projects/" + project + "/serviceAccounts/" + targetEmail + ":testIamPermissions")
                .then().statusCode(200).body("permissions", contains("iam.serviceAccounts.getAccessToken"));
    }

    @Test
    void credentialsSigningRequiresTokenCreatorAndRejectsDelegation() {
        iam.createServiceAccount(project, "target", "target", "");
        String resource = "projects/" + project + "/serviceAccounts/" + targetEmail;
        String path = "/v1/projects/-/serviceAccounts/" + targetEmail;
        for (String method : List.of("signBlob", "signJwt")) {
            String payload = method.equals("signBlob") ? "aGVsbG8=" : "{}";
            given().header("Authorization", callerAuthorization).urlEncodingEnabled(false)
                    .contentType("application/json").body(Map.of("payload", payload))
                    .when().post(path + ":" + method)
                    .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
        }
        setPolicy(resource, "roles/iam.serviceAccountTokenCreator", callerEmail, null);
        for (String method : List.of("signBlob", "signJwt")) {
            String payload = method.equals("signBlob") ? "aGVsbG8=" : "{}";
            given().header("Authorization", callerAuthorization).urlEncodingEnabled(false)
                    .contentType("application/json").body(Map.of("payload", payload))
                    .when().post(path + ":" + method).then().statusCode(200);
            given().header("Authorization", callerAuthorization).urlEncodingEnabled(false)
                    .contentType("application/json").body(Map.of("payload", payload, "delegates", List.of(resource)))
                    .when().post(path + ":" + method)
                    .then().statusCode(400).body("error.status", equalTo("FAILED_PRECONDITION"));
        }
        given().header("Authorization", callerAuthorization).urlEncodingEnabled(false)
                .contentType("application/json").body(Map.of("bytesToSign", "aGVsbG8="))
                .when().post(path + ":signBlob").then().statusCode(200).body("signature", notNullValue());
    }

    @Test
    void deniedManagementMutationsLeaveAccountsKeysAndPoliciesUnchanged() {
        iam.createServiceAccount(project, "target", "original", "");
        String account = "projects/" + project + "/serviceAccounts/" + targetEmail;
        String path = "/v1/" + account;
        String keyName = iam.createKey(project, "target").getName();
        Map<String, Object> escalation = Map.of("policy", Map.of("bindings", List.of(Map.of(
                "role", "roles/iam.serviceAccountAdmin", "members", List.of("serviceAccount:" + callerEmail)))));
        // A known but wrong role must not authorize management or policy escalation.
        setPolicy(account, "roles/iam.serviceAccountUser", callerEmail, null);
        String originalEtag = iam.getPolicy(account).getEtag();
        for (String method : List.of("PUT", "PATCH", "DELETE")) {
            given().header("Authorization", callerAuthorization).contentType("application/json")
                    .body(Map.of("displayName", "escalated"))
                    .when().request(method, path)
                    .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
        }
        given().header("Authorization", callerAuthorization).contentType("application/json").body(escalation)
                .when().post(path + ":setIamPolicy")
                .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
        given().header("Authorization", callerAuthorization).contentType("application/json").body(Map.of())
                .when().post(path + ":getIamPolicy").then().statusCode(403);
        for (String method : List.of("GET", "DELETE")) {
            given().header("Authorization", callerAuthorization).when().request(method, "/v1/" + keyName)
                    .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
        }
        given().header("Authorization", callerAuthorization).contentType("application/json").body(Map.of())
                .when().post(path + "/keys").then().statusCode(403);
        assertEquals("original", iam.getServiceAccount(project, "target").getDisplayName());
        assertEquals(1, iam.listKeys(project, "target").size());
        assertEquals(originalEtag, iam.getPolicy(account).getEtag());

        setPolicy(account, "roles/iam.serviceAccountKeyAdmin", callerEmail, null);
        given().header("Authorization", callerAuthorization).when().get("/v1/" + keyName).then().statusCode(200);
        given().header("Authorization", callerAuthorization).when().get(path + "/keys").then().statusCode(200);
        given().header("Authorization", callerAuthorization).when().delete("/v1/" + keyName).then().statusCode(200);
        assertTrue(iam.listKeys(project, "target").isEmpty());
        setPolicy(account, "roles/iam.serviceAccountAdmin", callerEmail, null);
        given().header("Authorization", callerAuthorization).contentType("application/json").body(Map.of())
                .when().post(path + ":getIamPolicy").then().statusCode(200);
        given().header("Authorization", callerAuthorization).contentType("application/json")
                .body(Map.of("serviceAccount", Map.of("description", "patched"), "updateMask", "description"))
                .when().patch(path).then().statusCode(200).body("description", equalTo("patched"));
        given().header("Authorization", callerAuthorization).contentType("application/json").body(escalation)
                .when().post(path + ":setIamPolicy").then().statusCode(200);
        given().header("Authorization", callerAuthorization).when().delete(path).then().statusCode(200);
    }

    @Test
    void projectConditionExcludesSiblingAndOtherProjectAndRejectsDelegation() {
        iam.createServiceAccount(project, "target", "target", "");
        iam.createServiceAccount(project, "sibling", "sibling", "");
        iam.createServiceAccount(project + "-other", "target", "other", "");
        String resource = "projects/" + project + "/serviceAccounts/" + targetEmail;
        setPolicy("projects/" + project, "roles/iam.serviceAccountTokenCreator", callerEmail,
                "resource.name == '" + resource + "'");
        for (String email : List.of("sibling@" + project + ".iam.gserviceaccount.com",
                "target@" + project + "-other.iam.gserviceaccount.com")) {
            given().header("Authorization", callerAuthorization).urlEncodingEnabled(false).contentType("application/json")
                    .body(Map.of("scope", List.of(CLOUD_PLATFORM_SCOPE)))
                    .when().post("/v1/projects/-/serviceAccounts/" + email + ":generateAccessToken")
                    .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
        }
        given().header("Authorization", callerAuthorization).urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("scope", List.of(CLOUD_PLATFORM_SCOPE), "delegates", List.of(resource)))
                .when().post("/v1/projects/-/serviceAccounts/" + targetEmail + ":generateAccessToken")
                .then().statusCode(400).body("error.status", equalTo("FAILED_PRECONDITION"));
    }

    @Test
    void invalidCredentialsAndUncataloguedRolesCannotMutateAccounts() {
        iam.createServiceAccount(project, "target", "original", "");
        String account = "projects/" + project + "/serviceAccounts/" + targetEmail;
        // The malformed recognized token is rejected instead of becoming an external bypass identity.
        given().header("Authorization", "Bearer floci-gcp-impersonated-unknown").when().delete("/v1/" + account)
                .then().statusCode(401).body("error.status", equalTo("UNAUTHENTICATED"));
        StoredCredentialToken expired = tokens.mintImpersonatedToken(callerEmail, Instant.now().plusSeconds(300));
        expired.setExpireTime(Instant.now().minusSeconds(1));
        credentialStore.put(expired.getTokenValue(), expired);
        given().header("Authorization", "Bearer " + expired.getTokenValue()).when().delete("/v1/" + account)
                .then().statusCode(401).body("error.status", equalTo("UNAUTHENTICATED"));
        assertEquals("original", iam.getServiceAccount(project, "target").getDisplayName());
        setPolicy(account, "roles/compute.viewer", callerEmail, null);
        given().header("Authorization", callerAuthorization).when().get("/v1/" + account).then().statusCode(403);
        StoredPolicy policy = iam.getPolicy(account);
        policy.setBindings(List.of(policy.getBindings().getFirst(), Map.of("role", "roles/iam.serviceAccountUser",
                "members", List.of("serviceAccount:" + callerEmail))));
        iam.setPolicy(account, policy);
        given().header("Authorization", callerAuthorization).when().get("/v1/" + account).then().statusCode(200);
        setPolicy(account, "roles/iam.serviceAccountAdmin", callerEmail, "resource.name.matches('.*')");
        given().header("Authorization", callerAuthorization).when().delete("/v1/" + account)
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        assertEquals("original", iam.getServiceAccount(project, "target").getDisplayName());
    }

    @Test
    void credentialsAddressedByUniqueIdMintTheCanonicalEmailIdentity() {
        String id = iam.createServiceAccount(project, "target", "target", "").getUniqueId();
        String resource = "projects/" + project + "/serviceAccounts/" + targetEmail;
        setPolicy(resource, "roles/iam.serviceAccountTokenCreator", callerEmail, null);
        String token = given().header("Authorization", callerAuthorization).urlEncodingEnabled(false)
                .contentType("application/json").body(Map.of("scope", List.of(CLOUD_PLATFORM_SCOPE)))
                .when().post("/v1/projects/-/serviceAccounts/" + id + ":generateAccessToken")
                .then().statusCode(200).extract().path("accessToken");
        assertEquals(targetEmail, tokens.lookupBearerToken(token).orElseThrow().getPrincipal());
    }

    @Test
    void legacyAliasPoliciesAuthorizeWithoutAnAnonymousMigrationRead() {
        String id = iam.createServiceAccount(project, "target", "target", "").getUniqueId();
        String canonical = "projects/" + project + "/serviceAccounts/" + targetEmail;
        for (String legacy : List.of("projects/" + project + "/serviceAccounts/target",
                "projects/" + project + "/serviceAccounts/" + id,
                "projects/-/serviceAccounts/" + targetEmail, "projects/-/serviceAccounts/" + id)) {
            setPolicy(legacy, "roles/iam.serviceAccountAdmin", callerEmail, null);
            String legacyEtag = iam.getPolicy(legacy).getEtag();
            given().header("Authorization", callerAuthorization)
                    .when().get("/v1/" + canonical).then().statusCode(200);
            assertEquals(legacyEtag, iam.getPolicy(legacy).getEtag());
            assertTrue(iam.getPolicy(canonical).getBindings().isEmpty());
            given().header("Authorization", callerAuthorization).urlEncodingEnabled(false)
                    .contentType("application/json").body(Map.of("scope", List.of(CLOUD_PLATFORM_SCOPE)))
                    .when().post("/v1/projects/-/serviceAccounts/" + targetEmail + ":generateAccessToken")
                    .then().statusCode(403);
            assertEquals(legacyEtag, iam.getPolicy(legacy).getEtag());
            iam.deletePolicy(legacy);
        }
    }

    @Test
    void recreatedAccountDoesNotInheritPreviousTargetsImpersonationPolicy() {
        String resource = iam.createServiceAccount(project, "target", "target", "").getName();
        String previousId = iam.getServiceAccount(project, "target").getUniqueId();
        for (String alias : List.of(resource, "projects/" + project + "/serviceAccounts/target",
                "projects/" + project + "/serviceAccounts/" + previousId,
                "projects/-/serviceAccounts/" + targetEmail, "projects/-/serviceAccounts/" + previousId)) {
            setPolicy(alias, "roles/iam.serviceAccountTokenCreator", callerEmail, null);
        }
        iam.deleteServiceAccount(project, "target");
        iam.createServiceAccount(project, "target", "replacement", "");
        assertNotEquals(previousId, iam.getServiceAccount(project, "target").getUniqueId());
        given().header("Authorization", callerAuthorization).urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("scope", List.of(CLOUD_PLATFORM_SCOPE)))
                .when().post("/v1/projects/-/serviceAccounts/" + targetEmail + ":generateAccessToken")
                .then().statusCode(403);
    }

    private void setPolicy(String resource, String role, String member, String expression) {
        StoredPolicy policy = new StoredPolicy();
        policy.setVersion(expression == null ? 1 : 3);
        Map<String, Object> binding = expression == null
                ? Map.of("role", role, "members", List.of("serviceAccount:" + member))
                : Map.of("role", role, "members", List.of("serviceAccount:" + member),
                        "condition", Map.of("expression", expression, "title", "test scope"));
        policy.setBindings(List.of(binding));
        iam.setPolicy(resource, policy);
    }

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }
}

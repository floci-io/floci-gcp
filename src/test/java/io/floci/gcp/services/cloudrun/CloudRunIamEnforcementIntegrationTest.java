package io.floci.gcp.services.cloudrun;

import io.floci.gcp.core.common.TokenRegistry;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CloudRunIamEnforcementIntegrationTest.CtfIamProfile.class)
class CloudRunIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String LOCATION = "us-central1";
    private static final String SERVICES_PATH =
            "/v2/projects/" + PROJECT + "/locations/" + LOCATION + "/services";
    private static final String PLAYER_EMAIL = "player@test-project.iam.gserviceaccount.com";
    private static final String PLAYER_TOKEN = "player-token";
    private static final String ROOT_TOKEN = "root-token-test";

    @Inject
    TokenRegistry tokenRegistry;

    @Inject
    IamService iamService;

    @BeforeEach
    void setUp() {
        tokenRegistry.register(PLAYER_TOKEN, PLAYER_EMAIL);
        iamService.setPolicy("projects/" + PROJECT, new StoredPolicy());
    }

    @Test
    void playerWithoutBindingIsDeniedListServices() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(SERVICES_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'run.services.list' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithDeveloperBindingCanListServices() {
        bindPlayer("roles/run.developer");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(SERVICES_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithAdminBindingCanCreateGetAndDeleteService() {
        bindPlayer("roles/run.admin");
        String serviceId = "ctf-svc-" + UUID.randomUUID().toString().substring(0, 8);
        String servicePath = SERVICES_PATH + "/" + serviceId;

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .queryParam("serviceId", serviceId)
                .body("{\"template\":{\"containers\":[{\"image\":\"gcr.io/test-project/svc:latest\"}]}}")
                .when().post(SERVICES_PATH)
                .then()
                .statusCode(200)
                .body("done", equalTo(true));

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(servicePath)
                .then()
                .statusCode(200)
                .body("name", equalTo(
                        "projects/" + PROJECT + "/locations/" + LOCATION + "/services/" + serviceId));

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(servicePath + "/revisions")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().delete(servicePath)
                .then()
                .statusCode(200)
                .body("done", equalTo(true));
    }

    @Test
    void playerWithDeveloperBindingIsDeniedSetIamPolicy() {
        bindPlayer("roles/run.developer");
        String serviceId = "ctf-deny-iam-" + UUID.randomUUID().toString().substring(0, 8);
        String servicePath = SERVICES_PATH + "/" + serviceId;

        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .queryParam("serviceId", serviceId)
                .body("{\"template\":{\"containers\":[{\"image\":\"gcr.io/test-project/svc:latest\"}]}}")
                .when().post(SERVICES_PATH)
                .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("{\"policy\":{\"bindings\":[{\"role\":\"roles/run.invoker\",\"members\":[\"allUsers\"]}]}}")
                .when().post(servicePath + ":setIamPolicy")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'run.services.setIamPolicy' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithInvokerBindingIsDeniedListServices() {
        bindPlayer("roles/run.invoker");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(SERVICES_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'run.services.list' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void rootTokenAlwaysCanListServices() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(SERVICES_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithoutInvokerIsDeniedLegacyInvoke() {
        String serviceId = createServiceAsRoot("ctf-legacy-deny-");
        String invokePath = legacyInvokePath(serviceId);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(invokePath)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'run.routes.invoke' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithInvokerIsAllowedLegacyInvoke() {
        String serviceId = createServiceAsRoot("ctf-legacy-allow-");
        String invokePath = legacyInvokePath(serviceId);
        bindPlayer("roles/run.invoker");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(invokePath)
                .then()
                .statusCode(not(equalTo(403)));
    }

    @Test
    void playerWithoutInvokerIsDeniedHostRoutedInvoke() {
        CreatedService created = createServiceAsRootWithUri("ctf-host-deny-");
        String host = URI.create(created.uri()).getRawAuthority();
        assertTrue(host != null && host.contains(".run."),
                "expected local Cloud Run invocation host, got: " + created.uri());

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .header("Host", host)
                .when().get("/")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'run.routes.invoke' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithInvokerIsAllowedHostRoutedInvoke() {
        CreatedService created = createServiceAsRootWithUri("ctf-host-allow-");
        String host = URI.create(created.uri()).getRawAuthority();
        assertTrue(host != null && host.contains(".run."),
                "expected local Cloud Run invocation host, got: " + created.uri());
        bindPlayer("roles/run.invoker");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .header("Host", host)
                .when().get("/")
                .then()
                .statusCode(not(equalTo(403)));
    }

    private String createServiceAsRoot(String idPrefix) {
        return createServiceAsRootWithUri(idPrefix).serviceId();
    }

    private CreatedService createServiceAsRootWithUri(String idPrefix) {
        String serviceId = idPrefix + UUID.randomUUID().toString().substring(0, 8);
        String uri = given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .queryParam("serviceId", serviceId)
                .body("{\"template\":{\"containers\":[{\"image\":\"gcr.io/test-project/svc:latest\"}]}}")
                .when().post(SERVICES_PATH)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .extract().path("response.uri");
        return new CreatedService(serviceId, uri);
    }

    private static String legacyInvokePath(String serviceId) {
        return "/run/v2/projects/" + PROJECT + "/locations/" + LOCATION + "/services/" + serviceId + "/";
    }

    private void bindPlayer(String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", role,
                "members", List.of("serviceAccount:" + PLAYER_EMAIL))));
        iamService.setPolicy("projects/" + PROJECT, policy);
    }

    private record CreatedService(String serviceId, String uri) {}

    public static class CtfIamProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.auth.validate-tokens", "true",
                    "floci-gcp.auth.root-service-account",
                    "operator@test-project.iam.gserviceaccount.com",
                    "floci-gcp.auth.root-access-token", ROOT_TOKEN,
                    "floci-gcp.services.iam.enforcement-enabled", "true",
                    "floci-gcp.services.iam.strict-enforcement-enabled", "true");
        }
    }
}

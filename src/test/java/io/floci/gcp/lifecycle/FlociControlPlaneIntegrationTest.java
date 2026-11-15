package io.floci.gcp.lifecycle;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The control-plane contract of floci-loft decision 0022: the same endpoints and required fields
 * under the canonical {@code /_floci} and the legacy {@code /_floci-gcp}, and a JSON 404 for an
 * unknown path under either prefix instead of the GCS routes.
 */
@QuarkusTest
class FlociControlPlaneIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/health", "/_floci-gcp/health", "/health"})
    void healthAnswersTheRequiredFields(String path) {
        given()
                .when().get(path)
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("status", equalTo("UP"))
                .body("cloud", equalTo("gcp"))
                .body("version", notNullValue())
                .body("services.pubsub", equalTo("running"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/info", "/_floci-gcp/info"})
    void infoIsTheConsoleManifestAndKeepsItsOwnKeys(String path) {
        given()
                .when().get(path)
                .then()
                .statusCode(200)
                .body("manifest_version", equalTo(1))
                .body("console_contract", equalTo(2))
                .body("cloud", equalTo("gcp"))
                .body("emulator", equalTo("floci-gcp"))
                .body("version", notNullValue())
                .body("endpoint", notNullValue())
                .body("health_url", equalTo("/_floci/health"))
                .body("tls.enabled", equalTo(false))
                .body("services.pubsub", equalTo("enabled"))
                .body("defaults.project_id", equalTo("test-project"))
                .body("ui.enabled", equalTo(false))
                .body("port", equalTo(4588))
                .body("defaultProject", equalTo("test-project"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/init", "/_floci-gcp/init"})
    void initAnswersTheHookState(String path) {
        given()
                .when().get(path)
                .then()
                .statusCode(200)
                .body("completed.boot", equalTo(true))
                .body("completed", hasKey("ready"))
                .body("scripts", hasKey("ready"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/tls-cert", "/_floci-gcp/tls-cert"})
    void tlsCertAnswersTheJsonExplanationWhenTlsIsOff(String path) {
        given()
                .when().get(path)
                .then()
                .statusCode(404)
                .contentType("application/json")
                .body("tlsEnabled", equalTo(false))
                .body("error", equalTo("TLS is not enabled"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/state/reset", "/_floci/state/nuke", "/_floci-gcp/state/reset", "/_floci-gcp/state/nuke"})
    void resetAnswersOk(String path) {
        given()
                .when().post(path)
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("status", equalTo("OK"));
    }

    @Test
    void resetOnTheCanonicalPrefixClearsServiceState() {
        String project = "control-plane-reset";
        String topic = "/v1/projects/" + project + "/topics/to-be-reset";

        given()
                .contentType("application/json")
                .body("{}")
                .when().put(topic)
                .then()
                .statusCode(200);

        given()
                .when().post("/_floci/state/reset")
                .then()
                .statusCode(200);

        given()
                .when().get(topic)
                .then()
                .statusCode(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/x", "/_floci/health/extra", "/_floci-gcp/x"})
    void unknownPathAnswersTheJsonNotFound(String path) {
        given()
                .when().get(path)
                .then()
                .statusCode(404)
                .contentType("application/json")
                .body("error", equalTo("NotFound"))
                .body("path", equalTo(path));

        given()
                .contentType("application/json")
                .body("{}")
                .when().post(path)
                .then()
                .statusCode(404)
                .body("error", equalTo("NotFound"))
                .body("path", equalTo(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/health", "/_floci-gcp/health"})
    void servedEndpointWithTheWrongMethodStaysMethodNotAllowed(String path) {
        given()
                .when().post(path)
                .then()
                .statusCode(405)
                .header("Allow", equalTo("GET, HEAD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/_floci/state/reset", "/_floci-gcp/state/nuke"})
    void stateEndpointCalledWithGetNamesPostInAllow(String path) {
        given()
                .when().get(path)
                .then()
                .statusCode(405)
                .header("Allow", equalTo("POST"));
    }

    @Test
    void bucketStyleHostDoesNotCaptureTheControlPlane() {
        given()
                .header("Host", "mybucket.storage.localhost:4588")
                .when().get("/_floci/health")
                .then()
                .statusCode(200)
                .body("cloud", equalTo("gcp"));
    }

    @Test
    void internalRoutesStayUnderTheLegacyPrefixOnly() {
        given()
                .contentType("application/json")
                .body("{}")
                .when().post("/_floci/gke/token-webhook")
                .then()
                .statusCode(404)
                .body("error", equalTo("NotFound"));
    }
}

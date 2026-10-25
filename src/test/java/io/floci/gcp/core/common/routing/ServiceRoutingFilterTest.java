package io.floci.gcp.core.common.routing;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies the single-port routing of GKE ({@code container} hostToken, {@code /container} prefix):
 * host mode routes versioned {@code /v1/...} and {@code /v1beta1/...} requests, path mode routes
 * prefixed requests, unserved GKE paths get a GKE 404, and neither hijacks IAM's canonical
 * {@code /v1/projects} surface.
 */
@QuarkusTest
class ServiceRoutingFilterTest {

    private static final String CANONICAL_CLUSTERS =
            "/v1/projects/test-project/locations/us-central1/clusters";
    private static final String PREFIXED_CLUSTERS =
            "/container/v1/projects/test-project/locations/us-central1/clusters";

    @Test
    void hostModeRoutesCanonicalPathToGke() {
        given()
            .header("Host", "container.localhost")
        .when()
            .get(CANONICAL_CLUSTERS)
        .then()
            .statusCode(200)
            .body("clusters", notNullValue());
    }

    @Test
    void pathModeRoutesPrefixedPathToGke() {
        given()
        .when()
            .get(PREFIXED_CLUSTERS)
        .then()
            .statusCode(200)
            .body("clusters", notNullValue());
    }

    @Test
    void hostDisambiguatesGkeFromTheDefaultClustersHandler() {
        // The canonical /clusters path is shared with Managed Kafka and disambiguated only
        // by Host. A cluster created via the container host is visible to GKE but NOT to the
        // default (Kafka) handler, proving host-based routing rather than path hijacking.
        String name = "route-test-cluster";
        given()
            .header("Host", "container.localhost")
            .contentType("application/json")
            .body(Map.of("cluster", Map.of("name", name)))
        .when()
            .post(CANONICAL_CLUSTERS)
        .then()
            .statusCode(200);

        given()
            .header("Host", "container.localhost")
        .when()
            .get(CANONICAL_CLUSTERS + "/" + name)
        .then()
            .statusCode(200)
            .body("name", equalTo(name));

        // Without the container host the same GET falls to the Kafka handler, which has no
        // such cluster → 404.
        given()
        .when()
            .get(CANONICAL_CLUSTERS + "/" + name)
        .then()
            .statusCode(404);
    }

    @Test
    void hostModeRoutesV1beta1PathToGke() {
        given()
            .header("Host", "container.localhost")
        .when()
            .get("/v1beta1/projects/test-project/locations/us-central1/clusters")
        .then()
            .statusCode(200)
            .body("clusters", notNullValue());
    }

    @Test
    void unservedVersionOnContainerHostIsAGkeNotFound() {
        given()
            .header("Host", "container.localhost")
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v2/projects/test-project/locations/us-central1/clusters")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("error.status", equalTo("NOT_FOUND"))
            .body("error.message", containsString("/container/v2/projects/test-project"));
    }

    @Test
    void unservedPrefixedPathIsAGkeNotFound() {
        given()
        .when()
            .get("/container/v1beta2/projects/test-project/locations/us-central1/clusters")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("error.status", equalTo("NOT_FOUND"));
    }

    @Test
    void iamCanonicalPathStillWorks() {
        // A genuine IAM canonical /v1 request is unaffected by the routing filter.
        given()
        .when()
            .get("/v1/projects/test-project/serviceAccounts")
        .then()
            .statusCode(200)
            .body("accounts", notNullValue());
    }

    @Test
    void getWithMethodQueryParamDoesNotResetState() {
        String topic = createTopic("override-query-param");

        given()
        .when()
            .get("/_floci-gcp/state/reset?$method=POST")
        .then()
            .statusCode(anyOf(equalTo(404), equalTo(405)));

        assertTopicExists(topic);
    }

    @Test
    void getWithMethodOverrideHeaderDoesNotResetState() {
        String topic = createTopic("override-header-get");

        given()
            .header("X-HTTP-Method-Override", "POST")
        .when()
            .get("/_floci-gcp/state/reset")
        .then()
            .statusCode(anyOf(equalTo(404), equalTo(405)));

        assertTopicExists(topic);
    }

    @Test
    void postWithMethodOverrideIsIgnoredOnTheControlPlane() {
        given()
            .header("X-HTTP-Method-Override", "GET")
        .when()
            .post("/_floci-gcp/health")
        .then()
            .statusCode(405);
    }

    @Test
    void getWithMethodOverrideHeaderDoesNotDeleteApiResources() {
        String topic = createTopic("override-get-delete");

        given()
            .header("X-HTTP-Method-Override", "DELETE")
        .when()
            .get(topic)
        .then()
            .statusCode(200);

        assertTopicExists(topic);
    }

    @Test
    void postWithMethodOverridePatchUpdatesApiResource() {
        String topic = createTopic("override-post-patch");

        given()
            .header("X-HTTP-Method-Override", "PATCH")
            .contentType("application/json")
            .body(Map.of("topic", Map.of("labels", Map.of("env", "patched")), "updateMask", "labels"))
        .when()
            .post(topic)
        .then()
            .statusCode(200)
            .body("labels.env", equalTo("patched"));
    }

    private static String createTopic(String name) {
        String path = "/v1/projects/routing-override/topics/" + name;
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .put(path)
        .then()
            .statusCode(200);
        return path;
    }

    private static void assertTopicExists(String path) {
        given()
        .when()
            .get(path)
        .then()
            .statusCode(200);
    }
}

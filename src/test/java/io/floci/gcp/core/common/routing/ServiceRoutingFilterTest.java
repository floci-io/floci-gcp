package io.floci.gcp.core.common.routing;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies the single-port routing of GKE ({@code container} hostToken, {@code /container} prefix):
 * host mode routes canonical {@code /v1/...} requests, path mode routes prefixed requests, and
 * neither hijacks IAM's canonical {@code /v1/projects} surface.
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
    void iamCanonicalPathStillWorks() {
        // A genuine IAM canonical /v1 request is unaffected by the routing filter.
        given()
        .when()
            .get("/v1/projects/test-project/serviceAccounts")
        .then()
            .statusCode(200)
            .body("accounts", notNullValue());
    }
}

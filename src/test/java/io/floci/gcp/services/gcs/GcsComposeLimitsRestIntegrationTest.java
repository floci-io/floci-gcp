package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Compose source-count limits. GCS caps a single compose at 32 sources and requires at least one;
 * accepting more would let a client build a composite locally that production rejects.
 */
@QuarkusTest
class GcsComposeLimitsRestIntegrationTest {

    private static final String BUCKET = "compose-limits-bucket";
    private static boolean seeded;

    private static void seed() {
        if (seeded) {
            return;
        }
        seeded = true;
        given().contentType("application/json").body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project");
        for (int i = 0; i < 33; i++) {
            given().contentType("text/plain").body(i + ";")
                    .queryParam("uploadType", "media").queryParam("name", "part-" + i)
                    .when().post("/upload/storage/v1/b/" + BUCKET + "/o");
        }
    }

    private static Map<String, Object> sources(int count) {
        List<Map<String, String>> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(Map.of("name", "part-" + i));
        }
        return Map.of("sourceObjects", items);
    }

    @Test
    void thirtyTwoSourcesIsAccepted() {
        seed();
        given().contentType("application/json").body(sources(32))
                .when().post("/storage/v1/b/" + BUCKET + "/o/composed-32/compose")
                .then().statusCode(200)
                .body("componentCount", equalTo(32));
    }

    @Test
    void thirtyThreeSourcesIsRejected() {
        seed();
        given().contentType("application/json").body(sources(33))
                .when().post("/storage/v1/b/" + BUCKET + "/o/composed-33/compose")
                .then().statusCode(400);
    }

    @Test
    void zeroSourcesIsRejected() {
        seed();
        given().contentType("application/json").body(sources(0))
                .when().post("/storage/v1/b/" + BUCKET + "/o/composed-0/compose")
                .then().statusCode(400);
    }

    @Test
    void aSingleSourceIsAccepted() {
        seed();
        given().contentType("application/json").body(sources(1))
                .when().post("/storage/v1/b/" + BUCKET + "/o/composed-1/compose")
                .then().statusCode(200);
    }
}

package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * objects.list filtering: startOffset/endOffset, matchGlob and includeTrailingDelimiter.
 */
@QuarkusTest
class GcsListFilterRestIntegrationTest {

    private static final String BUCKET = "list-filter-bucket";

    private static boolean seeded;

    // Seeded lazily rather than in @BeforeAll: the RestAssured port is not bound
    // until the Quarkus test instance starts, so a static @BeforeAll would be
    // refused a connection.
    private static void seed() {
        if (seeded) {
            return;
        }
        seeded = true;
        given().contentType("application/json").body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project");

        for (String name : new String[] {
                "a/1.txt", "a/2.txt", "a/b/3.txt", "b/4.txt", "c.txt",
                "logs/2024-01.json", "logs/2024-02.json", "logs/2024-01.csv", "a/" }) {
            // queryParam, not a hand-encoded URL: RestAssured encodes what it is given,
            // so a pre-encoded "%2F" would arrive as a literal "%252F".
            given().contentType("text/plain").body("x")
                    .queryParam("uploadType", "media")
                    .queryParam("name", name)
                    .when().post("/upload/storage/v1/b/" + BUCKET + "/o");
        }
    }

    @Test
    void startOffsetIsInclusive() {
        seed();
        given().queryParam("startOffset", "b/").when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items.name", containsInAnyOrder(
                        "b/4.txt", "c.txt", "logs/2024-01.csv", "logs/2024-01.json", "logs/2024-02.json"));
    }

    @Test
    void endOffsetIsExclusive() {
        seed();
        given().queryParam("endOffset", "b/").when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items.name", containsInAnyOrder("a/", "a/1.txt", "a/2.txt", "a/b/3.txt"));
    }

    @Test
    void offsetsCombineIntoAHalfOpenRange() {
        seed();
        given().queryParam("startOffset", "b/").queryParam("endOffset", "d").when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items.name", contains("b/4.txt", "c.txt"));
    }

    @Test
    void matchGlobStarStaysWithinOnePathSegment() {
        seed();
        given().queryParam("matchGlob", "logs/*.json").when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items.name", contains("logs/2024-01.json", "logs/2024-02.json"));
    }

    @Test
    void matchGlobDoubleStarCrossesPathSegments() {
        seed();
        given().queryParam("matchGlob", "a/**").when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items.name", hasItem("a/b/3.txt"));
    }

    @Test
    void matchGlobSupportsBraceAlternation() {
        seed();
        given().queryParam("matchGlob", "logs/2024-01.{json,csv}").when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items.name", containsInAnyOrder("logs/2024-01.csv", "logs/2024-01.json"));
    }

    @Test
    void matchGlobThatMatchesNothingReturnsNoItems() {
        seed();
        given().queryParam("matchGlob", "nope/*.zip").when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items", org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.nullValue(), empty()));
    }

    @Test
    void trailingDelimiterPlaceholderIsRolledUpByDefault() {
        seed();
        given().queryParam("delimiter", "/").when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items.name", not(hasItem("a/")))
                .body("prefixes", hasItem("a/"));
    }

    @Test
    void includeTrailingDelimiterAlsoReturnsThePlaceholderAsAnItem() {
        seed();
        given().queryParam("delimiter", "/").queryParam("includeTrailingDelimiter", true).when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200)
                .body("items.name", hasItem("a/"))
                .body("prefixes", hasItem("a/"));
    }

    @Test
    void repeatedDoubleStarsAreCollapsedRatherThanCompounded() {
        // "**/**/x" means the same as "**/x"; emitting both groups multiplies the backtracking
        // the regex engine does on a name that does not match.
        seed();
        given().queryParam("matchGlob", "**/**/app.log")
                .when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200);
    }

    @Test
    void aRunawayGlobIsRefusedRatherThanStallingTheRequest() {
        // Request-controlled patterns must not be able to stall a request thread: unbounded,
        // this shape never finishes matching. The step budget turns it into a 400 in milliseconds.
        // Its own bucket: the long name below would otherwise show up in the shared fixture's
        // offset and glob assertions.
        String bucket = "glob-runaway-bucket";
        given().contentType("application/json").body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=test-project");
        // The blowup needs a long name to backtrack across, which is what an attacker uploads first.
        given().contentType("text/plain").body("x")
                .queryParam("uploadType", "media").queryParam("name", "a".repeat(120) + ".txt")
                .when().post("/upload/storage/v1/b/" + bucket + "/o")
                .then().statusCode(200);

        given().queryParam("matchGlob", "*a*a*a*a*a*a*a*a*a*a.log")
                .when().get("/storage/v1/b/" + bucket + "/o")
                .then().statusCode(400);
    }

    @Test
    void aComplexButHarmlessGlobIsStillAccepted() {
        // The budget bounds evaluation rather than banning shapes, so patterns real GCS accepts
        // keep working even when they use several wildcards.
        seed();
        given().queryParam("matchGlob", "**/*/*/*/*.log")
                .when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200);
    }

    @Test
    void ordinaryGlobsAreStillAccepted() {
        seed();
        given().queryParam("matchGlob", "logs/*/2026/*.log")
                .when().get("/storage/v1/b/" + BUCKET + "/o")
                .then().statusCode(200);
    }
}

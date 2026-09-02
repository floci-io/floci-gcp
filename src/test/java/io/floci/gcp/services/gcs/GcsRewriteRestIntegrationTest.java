package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/** Multi-call objects.rewrite: done:false + rewriteToken until the copy finishes. */
@QuarkusTest
class GcsRewriteRestIntegrationTest {

    private static final String SRC = "rewrite-src-bucket";
    private static final String DST = "rewrite-dst-bucket";
    private static final int SIZE = 4096;

    private static void seed() {
        for (String b : new String[] { SRC, DST }) {
            given().contentType("application/json").body(Map.of("name", b))
                    .when().post("/storage/v1/b?project=test-project");
        }
        given().contentType("text/plain").body("x".repeat(SIZE))
                .queryParam("uploadType", "media").queryParam("name", "big")
                .when().post("/upload/storage/v1/b/" + SRC + "/o");
    }

    private String rewrite(String dstObject, String extraQuery) {
        return given()
                .when().post("/storage/v1/b/" + SRC + "/o/big/rewriteTo/b/" + DST + "/o/" + dstObject + extraQuery)
                .then().statusCode(200)
                .extract().asString();
    }

    @Test
    void rewriteWithoutALimitCompletesInOneCall() {
        seed();
        given()
                .when().post("/storage/v1/b/" + SRC + "/o/big/rewriteTo/b/" + DST + "/o/one-shot")
                .then().statusCode(200)
                .body("kind", equalTo("storage#rewriteResponse"))
                .body("done", equalTo(true))
                .body("objectSize", equalTo(String.valueOf(SIZE)))
                .body("resource.name", equalTo("one-shot"))
                .body("rewriteToken", nullValue());
    }

    @Test
    void aLimitBelowTheObjectSizeYieldsARewriteToken() {
        seed();
        given()
                .queryParam("maxBytesRewrittenPerCall", 1024)
                .when().post("/storage/v1/b/" + SRC + "/o/big/rewriteTo/b/" + DST + "/o/chunked")
                .then().statusCode(200)
                .body("done", equalTo(false))
                .body("rewriteToken", notNullValue())
                .body("totalBytesRewritten", not(equalTo(String.valueOf(SIZE))))
                .body("resource", nullValue());
    }

    @Test
    void theDestinationIsNotVisibleUntilTheRewriteCompletes() {
        seed();
        given().queryParam("maxBytesRewrittenPerCall", 1024)
                .when().post("/storage/v1/b/" + SRC + "/o/big/rewriteTo/b/" + DST + "/o/partial")
                .then().statusCode(200).body("done", equalTo(false));

        given().when().get("/storage/v1/b/" + DST + "/o/partial").then().statusCode(404);
    }

    @Test
    void loopingOnTheTokenEventuallyCompletesTheCopy() {
        seed();
        String token = given().queryParam("maxBytesRewrittenPerCall", 1024)
                .when().post("/storage/v1/b/" + SRC + "/o/big/rewriteTo/b/" + DST + "/o/looped")
                .then().statusCode(200).body("done", equalTo(false))
                .extract().path("rewriteToken");

        boolean done = false;
        for (int i = 0; i < 16 && !done; i++) {
            var response = given()
                    .queryParam("maxBytesRewrittenPerCall", 1024)
                    .queryParam("rewriteToken", token)
                    .when().post("/storage/v1/b/" + SRC + "/o/big/rewriteTo/b/" + DST + "/o/looped")
                    .then().statusCode(200).extract();
            done = response.path("done");
            token = response.path("rewriteToken");
        }

        org.junit.jupiter.api.Assertions.assertTrue(done, "rewrite never completed");
        given().when().get("/storage/v1/b/" + DST + "/o/looped")
                .then().statusCode(200).body("size", equalTo(String.valueOf(SIZE)));
    }

    @Test
    void anUnknownRewriteTokenIsRejected() {
        seed();
        given().queryParam("rewriteToken", "not-a-real-token")
                .when().post("/storage/v1/b/" + SRC + "/o/big/rewriteTo/b/" + DST + "/o/bad-token")
                .then().statusCode(400);
    }

    @Test
    void aFailedCompletingCallLeavesTheTokenUsable() {
        // Retiring the token before the copy succeeds would make a failed destination
        // precondition unretryable: the client would be left holding a token the server no
        // longer knows, and would have to restart the whole rewrite.
        seed();
        // Preconditions are captured with the session, so the impossible one is set here and
        // applies on the call that completes. One chunk short, so the next call is that one.
        String first = rewrite("retry-dst", "?maxBytesRewrittenPerCall=3000&ifGenerationMatch=999999");
        String token = io.restassured.path.json.JsonPath.from(first).getString("rewriteToken");
        org.junit.jupiter.api.Assertions.assertNotNull(token);

        // The completing call fails on the captured precondition.
        given().when().post("/storage/v1/b/" + SRC + "/o/big/rewriteTo/b/" + DST
                        + "/o/retry-dst?rewriteToken=" + token)
                .then().statusCode(412);

        // The token must still be recognised: the same failure again, not "Invalid rewriteToken".
        given().when().post("/storage/v1/b/" + SRC + "/o/big/rewriteTo/b/" + DST
                        + "/o/retry-dst?rewriteToken=" + token)
                .then().statusCode(412);
    }
}

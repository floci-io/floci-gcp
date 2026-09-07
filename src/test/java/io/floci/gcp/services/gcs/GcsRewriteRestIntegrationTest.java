package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Multi-call objects.rewrite: done:false + rewriteToken until the copy finishes.
 *
 * <p>Per the storage/v1 discovery document, {@code maxBytesRewrittenPerCall} "only applies to
 * requests where the source and destination span locations and/or storage classes" and "must be
 * an integral multiple of 1 MiB". So the chunked cases here copy from a STANDARD bucket into a
 * NEARLINE one, and a same-class copy is asserted to finish in one call regardless of the limit.
 */
@QuarkusTest
class GcsRewriteRestIntegrationTest {

    private static final String SRC = "rewrite-src-bucket";
    private static final String NEARLINE_DST = "rewrite-nearline-dst-bucket";
    private static final String SAME_CLASS_DST = "rewrite-same-class-dst-bucket";
    private static final int ONE_MIB = 1024 * 1024;
    private static final int SIZE = 3 * ONE_MIB;
    private static boolean seeded;

    private static void seed() {
        if (seeded) {
            return;
        }
        seeded = true;
        given().contentType("application/json").body(Map.of("name", SRC))
                .when().post("/storage/v1/b?project=test-project");
        given().contentType("application/json").body(Map.of("name", NEARLINE_DST, "storageClass", "NEARLINE"))
                .when().post("/storage/v1/b?project=test-project");
        given().contentType("application/json").body(Map.of("name", SAME_CLASS_DST))
                .when().post("/storage/v1/b?project=test-project");
        given().contentType("application/octet-stream").body(new byte[SIZE])
                .queryParam("uploadType", "media").queryParam("name", "big")
                .when().post("/upload/storage/v1/b/" + SRC + "/o")
                .then().statusCode(200);
    }

    private static String rewritePath(String dstBucket, String dstObject) {
        return "/storage/v1/b/" + SRC + "/o/big/rewriteTo/b/" + dstBucket + "/o/" + dstObject;
    }

    @Test
    void rewriteWithoutALimitCompletesInOneCallAndTakesTheDestinationBucketClass() {
        seed();
        given()
                .when().post(rewritePath(NEARLINE_DST, "one-shot"))
                .then().statusCode(200)
                .body("kind", equalTo("storage#rewriteResponse"))
                .body("done", equalTo(true))
                .body("objectSize", equalTo(String.valueOf(SIZE)))
                .body("totalBytesRewritten", equalTo(String.valueOf(SIZE)))
                .body("resource.name", equalTo("one-shot"))
                .body("resource.storageClass", equalTo("NEARLINE"))
                .body("rewriteToken", nullValue());
    }

    @Test
    void aLimitBelowTheObjectSizeYieldsARewriteTokenWhenTheCopySpansStorageClasses() {
        seed();
        given()
                .queryParam("maxBytesRewrittenPerCall", ONE_MIB)
                .when().post(rewritePath(NEARLINE_DST, "chunked"))
                .then().statusCode(200)
                .body("done", equalTo(false))
                .body("rewriteToken", notNullValue())
                .body("totalBytesRewritten", equalTo(String.valueOf(ONE_MIB)))
                .body("objectSize", equalTo(String.valueOf(SIZE)))
                .body("resource", nullValue());
    }

    @Test
    void theLimitIsIgnoredWhenSourceAndDestinationShareLocationAndClass() {
        // "this only applies to requests where the source and destination span locations and/or
        // storage classes": a STANDARD to STANDARD copy in one location finishes in one call.
        seed();
        given()
                .queryParam("maxBytesRewrittenPerCall", ONE_MIB)
                .when().post(rewritePath(SAME_CLASS_DST, "same-class"))
                .then().statusCode(200)
                .body("done", equalTo(true))
                .body("rewriteToken", nullValue())
                .body("resource.storageClass", equalTo("STANDARD"));
    }

    @Test
    void aStorageClassInTheBodyMakesTheCopySpanClasses() {
        // The destination's class can come from the request body rather than the bucket default,
        // and then the copy spans classes even within one bucket.
        seed();
        String token = given()
                .contentType("application/json").body(Map.of("storageClass", "COLDLINE"))
                .queryParam("maxBytesRewrittenPerCall", ONE_MIB)
                .when().post(rewritePath(SAME_CLASS_DST, "body-class"))
                .then().statusCode(200)
                .body("done", equalTo(false))
                .extract().path("rewriteToken");

        boolean done = false;
        var response = given().queryParam("maxBytesRewrittenPerCall", ONE_MIB).queryParam("rewriteToken", token)
                .when().post(rewritePath(SAME_CLASS_DST, "body-class")).then().statusCode(200).extract();
        for (int i = 0; i < 8 && !(done = response.path("done")); i++) {
            response = given().queryParam("maxBytesRewrittenPerCall", ONE_MIB).queryParam("rewriteToken", token)
                    .when().post(rewritePath(SAME_CLASS_DST, "body-class")).then().statusCode(200).extract();
        }
        org.junit.jupiter.api.Assertions.assertTrue(done, "rewrite never completed");
        org.junit.jupiter.api.Assertions.assertEquals("COLDLINE", response.path("resource.storageClass"));
    }

    @Test
    void aLimitThatIsNotAMultipleOfOneMiBIsRejected() {
        // "If specified the value must be an integral multiple of 1 MiB (1048576)."
        seed();
        given()
                .queryParam("maxBytesRewrittenPerCall", 1024)
                .when().post(rewritePath(NEARLINE_DST, "bad-limit"))
                .then().statusCode(400);
    }

    @Test
    void theDestinationIsNotVisibleUntilTheRewriteCompletes() {
        seed();
        given().queryParam("maxBytesRewrittenPerCall", ONE_MIB)
                .when().post(rewritePath(NEARLINE_DST, "partial"))
                .then().statusCode(200).body("done", equalTo(false));

        given().when().get("/storage/v1/b/" + NEARLINE_DST + "/o/partial").then().statusCode(404);
    }

    @Test
    void loopingOnTheTokenEventuallyCompletesTheCopy() {
        seed();
        String token = given().queryParam("maxBytesRewrittenPerCall", ONE_MIB)
                .when().post(rewritePath(NEARLINE_DST, "looped"))
                .then().statusCode(200).body("done", equalTo(false))
                .extract().path("rewriteToken");

        boolean done = false;
        int calls = 1;
        for (int i = 0; i < 16 && !done; i++) {
            var response = given()
                    .queryParam("maxBytesRewrittenPerCall", ONE_MIB)
                    .queryParam("rewriteToken", token)
                    .when().post(rewritePath(NEARLINE_DST, "looped"))
                    .then().statusCode(200).extract();
            done = response.path("done");
            calls++;
        }

        org.junit.jupiter.api.Assertions.assertTrue(done, "rewrite never completed");
        org.junit.jupiter.api.Assertions.assertEquals(3, calls, "3 MiB at 1 MiB per call is three calls");
        given().when().get("/storage/v1/b/" + NEARLINE_DST + "/o/looped")
                .then().statusCode(200)
                .body("size", equalTo(String.valueOf(SIZE)))
                .body("storageClass", equalTo("NEARLINE"));
    }

    @Test
    void changingTheLimitMidRewriteInvalidatesTheToken() {
        // "this value must not change across rewrite calls else you'll get an error that the
        // rewriteToken is invalid".
        seed();
        String token = given().queryParam("maxBytesRewrittenPerCall", ONE_MIB)
                .when().post(rewritePath(NEARLINE_DST, "changed-limit"))
                .then().statusCode(200).extract().path("rewriteToken");

        given().queryParam("maxBytesRewrittenPerCall", 2 * ONE_MIB).queryParam("rewriteToken", token)
                .when().post(rewritePath(NEARLINE_DST, "changed-limit"))
                .then().statusCode(400);
    }

    @Test
    void anUnknownRewriteTokenIsRejected() {
        seed();
        given().queryParam("rewriteToken", "not-a-real-token")
                .when().post(rewritePath(NEARLINE_DST, "bad-token"))
                .then().statusCode(400);
    }

    @Test
    void aFailedCompletingCallLeavesTheTokenUsable() {
        // Retiring the token before the copy succeeds would make a failed destination
        // precondition unretryable: the client would be left holding a token the server no
        // longer knows, and would have to restart the whole rewrite.
        seed();
        // Preconditions are captured with the session, so the impossible one is set here and
        // applies on the call that completes. Two of three MiB, so the next call is that one.
        String token = given()
                .queryParam("maxBytesRewrittenPerCall", 2 * ONE_MIB)
                .queryParam("ifGenerationMatch", 999999)
                .when().post(rewritePath(NEARLINE_DST, "retry-dst"))
                .then().statusCode(200).body("done", equalTo(false))
                .extract().path("rewriteToken");

        // The completing call fails on the captured precondition.
        given().queryParam("rewriteToken", token)
                .when().post(rewritePath(NEARLINE_DST, "retry-dst"))
                .then().statusCode(412);

        // The token must still be recognised: the same failure again, not "Invalid rewriteToken".
        given().queryParam("rewriteToken", token)
                .when().post(rewritePath(NEARLINE_DST, "retry-dst"))
                .then().statusCode(412);
    }
}

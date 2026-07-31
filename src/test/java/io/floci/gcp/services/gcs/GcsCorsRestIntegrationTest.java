package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class GcsCorsRestIntegrationTest {

    private static final String BUCKET = "cors-repro";
    private static final String OBJECT = "hello.txt";
    private static final String ALLOWED_ORIGIN = "http://allowed.example";
    private static final String DISALLOWED_ORIGIN = "http://evil.example";

    private static boolean fixtureCreated;

    @BeforeEach
    void createBucketWithCors() {
        // The RestAssured port is only bound per-test by the Quarkus extension, so the
        // fixture has to be created from a @BeforeEach rather than a @BeforeAll.
        if (fixtureCreated) {
            return;
        }
        fixtureCreated = true;

        given()
                .contentType("application/json")
                .body("""
                        {
                          "name": "%s",
                          "cors": [
                            {
                              "origin": ["%s"],
                              "method": ["GET"],
                              "responseHeader": ["Content-Type"],
                              "maxAgeSeconds": 3600
                            }
                          ]
                        }
                        """.formatted(BUCKET, ALLOWED_ORIGIN))
                .when().post("/storage/v1/b?project=cors-test")
                .then().statusCode(200);

        given()
                .contentType("text/plain")
                .body("hello")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=" + OBJECT)
                .then().statusCode(200);
    }

    @Test
    void preflightFromAllowedOriginAdvertisesTheConfiguredMethodsAndHeaders() {
        given()
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .when().options("/" + BUCKET + "/" + OBJECT)
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo(ALLOWED_ORIGIN))
                .header("Access-Control-Allow-Methods", equalTo("GET"))
                .header("Access-Control-Allow-Headers", equalTo("Content-Type"))
                .header("Access-Control-Max-Age", equalTo("3600"))
                .header("Vary", equalTo("Origin"));
    }

    @Test
    void preflightFromDisallowedOriginGetsNoCorsHeaders() {
        given()
                .header("Origin", DISALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .when().options("/" + BUCKET + "/" + OBJECT)
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", nullValue())
                .header("Access-Control-Allow-Methods", nullValue());
    }

    @Test
    void preflightForDisallowedMethodGetsNoCorsHeaders() {
        given()
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "DELETE")
                .when().options("/" + BUCKET + "/" + OBJECT)
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", nullValue())
                .header("Access-Control-Allow-Methods", nullValue());
    }

    @Test
    void pathStyleGetFromAllowedOriginCarriesCorsHeaders() {
        given()
                .header("Origin", ALLOWED_ORIGIN)
                .when().get("/" + BUCKET + "/" + OBJECT)
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo(ALLOWED_ORIGIN))
                .header("Access-Control-Expose-Headers", equalTo("Content-Type"))
                .header("Vary", equalTo("Origin"));
    }

    @Test
    void pathStyleGetFromDisallowedOriginGetsNoCorsHeaders() {
        given()
                .header("Origin", DISALLOWED_ORIGIN)
                .when().get("/" + BUCKET + "/" + OBJECT)
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void jsonApiGetFromAllowedOriginCarriesCorsHeaders() {
        given()
                .header("Origin", ALLOWED_ORIGIN)
                .when().get("/storage/v1/b/" + BUCKET + "/o/" + OBJECT + "?alt=media")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo(ALLOWED_ORIGIN))
                .header("Vary", equalTo("Origin"));
    }

    @Test
    void jsonApiGetFromDisallowedOriginGetsNoCorsHeaders() {
        given()
                .header("Origin", DISALLOWED_ORIGIN)
                .when().get("/storage/v1/b/" + BUCKET + "/o/" + OBJECT + "?alt=media")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void bucketNamedLikeAnotherRouteDoesNotGovernThatRoute() {
        // A bucket may legally be named after the first path segment of an unrelated
        // endpoint (here the GCS JSON batch route). Its CORS configuration must not
        // leak onto that endpoint just because the names collide.
        given()
                .contentType("application/json")
                .body("""
                        {
                          "name": "batch",
                          "cors": [
                            {"origin": ["*"], "method": ["*"], "responseHeader": ["Content-Type"]}
                          ]
                        }
                        """)
                .when().post("/storage/v1/b?project=cors-test")
                .then().statusCode(200);

        given()
                .header("Origin", DISALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .when().options("/batch/storage/v1")
                .then()
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void requestToABucketWithoutCorsConfigGetsNoCorsHeaders() {
        given()
                .contentType("application/json")
                .body("{\"name\": \"cors-unconfigured\"}")
                .when().post("/storage/v1/b?project=cors-test")
                .then().statusCode(200);

        given()
                .header("Origin", ALLOWED_ORIGIN)
                .when().get("/storage/v1/b/cors-unconfigured")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", nullValue());
    }
}

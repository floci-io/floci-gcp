package io.floci.gcp.services.firebaseauth;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class FirebaseAuthCorsRestIntegrationTest {

    private static final String CLIENT = "/identitytoolkit.googleapis.com/v1/accounts";
    private static final String ORIGIN = "http://127.0.0.1:59301";

    @Test
    void preflightForSignInWithPasswordAdvertisesTheRequestedOrigin() {
        given()
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type")
                .when().options(CLIENT + ":signInWithPassword")
                .then()
                .statusCode(204)
                .header("Access-Control-Allow-Origin", equalTo(ORIGIN))
                .header("Access-Control-Allow-Methods", equalTo("GET,HEAD,PUT,PATCH,POST,DELETE"))
                .header("Access-Control-Allow-Headers", equalTo("Content-Type"))
                .header("Vary", equalTo("Origin, Access-Control-Request-Headers"));
    }

    @Test
    void actualSignInResponseAdvertisesTheRequestedOrigin() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .queryParam("key", "fake-api-key")
                .header("Origin", ORIGIN)
                .body("""
                        {"email":"cors-actual@example.com","password":"secret123"}
                        """)
                .when().post(CLIENT + ":signUp")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo(ORIGIN))
                .header("Vary", equalTo("Origin"));
    }

    @Test
    void preflightForSecureTokenRefreshAdvertisesTheRequestedOrigin() {
        given()
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type")
                .when().options("/securetoken.googleapis.com/v1/token")
                .then()
                .statusCode(204)
                .header("Access-Control-Allow-Origin", equalTo(ORIGIN))
                .header("Access-Control-Allow-Methods", equalTo("GET,HEAD,PUT,PATCH,POST,DELETE"))
                .header("Access-Control-Allow-Headers", equalTo("Content-Type"))
                .header("Vary", equalTo("Origin, Access-Control-Request-Headers"));
    }

    @Test
    void requestWithNoOriginGetsNoCorsHeaders() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .queryParam("key", "fake-api-key")
                .body("""
                        {"email":"cors-no-origin@example.com","password":"secret123"}
                        """)
                .when().post(CLIENT + ":signUp")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", nullValue());
    }
}

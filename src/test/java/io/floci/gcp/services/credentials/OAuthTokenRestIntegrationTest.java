package io.floci.gcp.services.credentials;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class OAuthTokenRestIntegrationTest {

    private static final String JWT_BEARER_GRANT_TYPE =
            "urn:ietf:params:oauth:grant-type:jwt-bearer";

    @Test
    void exchangesServiceAccountJwtForBearerToken() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", JWT_BEARER_GRANT_TYPE)
                .formParam("assertion", "header.payload.signature")
                .when().post("/token")
                .then()
                .statusCode(200)
                .body("access_token", not(emptyOrNullString()))
                .body("token_type", equalTo("Bearer"))
                .body("expires_in", equalTo(3600));
    }

    @Test
    void rejectsUnsupportedGrantType() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "invalid")
                .formParam("assertion", "header.payload.signature")
                .when().post("/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("unsupported_grant_type"));
    }

    @Test
    void rejectsInvalidAssertion() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", JWT_BEARER_GRANT_TYPE)
                .when().post("/token")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));
    }
}

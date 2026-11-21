package io.floci.gcp.services.credentials;

import io.floci.gcp.testing.GcsDisabledProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(GcsDisabledProfile.class)
class OAuthTokenGcsDisabledRestIntegrationTest {

    @Test
    void exchangesTokenWhenGcsIsDisabled() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .formParam("assertion", "header.payload.signature")
                .when().post("/token")
                .then()
                .statusCode(200)
                .body("token_type", equalTo("Bearer"));
    }

    @Test
    void legacyV4EndpointExchangesTokenWhenGcsIsDisabled() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .formParam("assertion", "header.payload.signature")
                .when().post("/oauth2/v4/token")
                .then()
                .statusCode(200)
                .body("token_type", equalTo("Bearer"));
    }
}

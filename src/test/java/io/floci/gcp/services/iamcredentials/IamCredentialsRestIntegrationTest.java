package io.floci.gcp.services.iamcredentials;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class IamCredentialsRestIntegrationTest {

    private static final String PATH =
            "/v1/projects/-/serviceAccounts/test@test-project.iam.gserviceaccount.com:generateAccessToken";
    private static final Map<String, Object> VALID_BODY = Map.of(
            "delegates", List.of(),
            "scope", List.of(IamCredentialsService.DEVSTORAGE_READ_WRITE_SCOPE),
            "lifetime", "3600s");

    @Test
    void generateAccessTokenReturnsGoogleCompatibleJson() {
        String expireTime = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(VALID_BODY)
                .when().post(PATH)
                .then()
                .statusCode(200)
                .body("accessToken", startsWith(IamCredentialsService.TOKEN_PREFIX))
                .body("expireTime", notNullValue())
                .extract().path("expireTime");

        assertTrue(Instant.parse(expireTime).isAfter(Instant.now().minusSeconds(1)));
    }

    @Test
    void specificRouteDoesNotFallThroughToIamCatchAll() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of("scope", List.of("https://www.googleapis.com/auth/userinfo.email")))
                .when().post(PATH)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("error.message", equalTo("unsupported scope"));
    }

    @Test
    void missingScopeReturnsGcpStyleError() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of("lifetime", "60s"))
                .when().post(PATH)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("error.errors[0].reason", equalTo("invalid"));
    }

    @Test
    void emptyScopeReturnsGcpStyleError() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of("scope", List.of(), "lifetime", "60s"))
                .when().post(PATH)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void unsupportedScopeReturnsGcpStyleError() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of("scope", List.of("https://www.googleapis.com/auth/userinfo.email")))
                .when().post(PATH)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void cloudPlatformScopeReturnsAccessToken() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of("scope", List.of(IamCredentialsService.CLOUD_PLATFORM_SCOPE)))
                .when().post(PATH)
                .then()
                .statusCode(200)
                .body("accessToken", startsWith(IamCredentialsService.TOKEN_PREFIX));
    }

    @Test
    void malformedLifetimeReturnsGcpStyleError() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of(
                        "scope", List.of(IamCredentialsService.DEVSTORAGE_READ_WRITE_SCOPE),
                        "lifetime", "not-a-duration"))
                .when().post(PATH)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void zeroLifetimeReturnsGcpStyleError() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of(
                        "scope", List.of(IamCredentialsService.DEVSTORAGE_READ_WRITE_SCOPE),
                        "lifetime", "0s"))
                .when().post(PATH)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void negativeLifetimeReturnsGcpStyleError() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of(
                        "scope", List.of(IamCredentialsService.DEVSTORAGE_READ_WRITE_SCOPE),
                        "lifetime", "-1s"))
                .when().post(PATH)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }
}

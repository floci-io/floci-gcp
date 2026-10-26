package io.floci.gcp.services.iamcredentials;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void signBlobWithAPayloadReturnsASignedBlob() {
        String sa = createServiceAccount("rest-signer");

        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("delegates", List.of(), "payload", "aGVsbG8="))
                .when().post("/v1/projects/-/serviceAccounts/" + sa + ":signBlob")
                .then()
                .statusCode(200)
                .body("keyId", matchesPattern("[0-9a-f]{40}"))
                .body("signedBlob", notNullValue())
                .body("signature", nullValue());
    }

    @Test
    void iamV1SignBlobKeepsItsShapeOnTheWildcardAndOnAProject() {
        String sa = createServiceAccount("v1-signer");

        String wildcardKeyId = given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("bytesToSign", "aGVsbG8="))
                .when().post("/v1/projects/-/serviceAccounts/" + sa + ":signBlob")
                .then()
                .statusCode(200)
                .body("signature", notNullValue())
                .body("signedBlob", nullValue())
                .extract().path("keyId");

        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("bytesToSign", "aGVsbG8="))
                .when().post("/v1/projects/sign-project/serviceAccounts/" + sa + ":signBlob")
                .then()
                .statusCode(200)
                .body("keyId", equalTo(wildcardKeyId))
                .body("signature", notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytesToSign", "payload"})
    void signBlobAcceptsUrlSafeBase64AndRejectsMalformedBytes(String field) {
        String sa = createServiceAccount("url-safe-" + field.toLowerCase());
        String signatureField = field.equals("payload") ? "signedBlob" : "signature";

        String standard = signBlob(sa, field, "+/8=").statusCode(200).extract().path(signatureField);

        signBlob(sa, field, "-_8").statusCode(200).body(signatureField, equalTo(standard));
        signBlob(sa, field, "!").statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    private static ValidatableResponse signBlob(String sa, String field, String bytes) {
        return given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of(field, bytes))
                .when().post("/v1/projects/-/serviceAccounts/" + sa + ":signBlob")
                .then();
    }

    @Test
    void signJwtReturnsASignedJwt() {
        String sa = createServiceAccount("jwt-signer");

        String jwt = given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("payload", "{\"sub\":\"user\"}"))
                .when().post("/v1/projects/-/serviceAccounts/" + sa + ":signJwt")
                .then()
                .statusCode(200)
                .body("keyId", matchesPattern("[0-9a-f]{40}"))
                .extract().path("signedJwt");

        assertEquals(3, jwt.split("\\.").length);
    }

    @Test
    void signingForAMissingAccountIsDenied() {
        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("payload", "aGVsbG8="))
                .when().post("/v1/projects/-/serviceAccounts/ghost@sign-project.iam.gserviceaccount.com:signBlob")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    private static String createServiceAccount(String accountId) {
        return given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("accountId", accountId))
                .when().post("/v1/projects/sign-project/serviceAccounts")
                .then()
                .statusCode(200)
                .extract().path("email");
    }
}

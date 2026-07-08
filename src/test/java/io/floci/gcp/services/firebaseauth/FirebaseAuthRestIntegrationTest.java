package io.floci.gcp.services.firebaseauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirebaseAuthRestIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLIENT = "/identitytoolkit.googleapis.com/v1/accounts";
    private static final String ADMIN = "/identitytoolkit.googleapis.com/v1/projects/test-project/accounts";

    @Test
    void signUpSignInAndRefreshRoundtrip() throws Exception {
        String email = "roundtrip@example.com";

        Map<String, Object> signUp = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .queryParam("key", "fake-api-key")
                .body(Map.of("email", email, "password", "secret123"))
                .when().post(CLIENT + ":signUp")
                .then()
                .statusCode(200)
                .body("kind", equalTo("identitytoolkit#SignupNewUserResponse"))
                .body("email", equalTo(email))
                .body("expiresIn", equalTo("3600"))
                .body("idToken", notNullValue())
                .body("refreshToken", notNullValue())
                .extract().as(Map.class);

        String localId = (String) signUp.get("localId");
        assertEquals(28, localId.length());

        String idToken = (String) signUp.get("idToken");
        Map<String, Object> header = decodeSegment(idToken, 0);
        Map<String, Object> payload = decodeSegment(idToken, 1);
        assertEquals("none", header.get("alg"));
        assertTrue(idToken.endsWith("."));
        assertEquals("https://securetoken.google.com/test-project", payload.get("iss"));
        assertEquals("test-project", payload.get("aud"));
        assertEquals(localId, payload.get("user_id"));
        assertEquals(email, payload.get("email"));
        assertEquals("password", ((Map<?, ?>) payload.get("firebase")).get("sign_in_provider"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .queryParam("key", "fake-api-key")
                .body(Map.of("email", email, "password", "secret123"))
                .when().post(CLIENT + ":signInWithPassword")
                .then()
                .statusCode(200)
                .body("kind", equalTo("identitytoolkit#VerifyPasswordResponse"))
                .body("registered", equalTo(true))
                .body("localId", equalTo(localId));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/x-www-form-urlencoded")
                .queryParam("key", "fake-api-key")
                .formParam("grant_type", "refresh_token")
                .formParam("refresh_token", (String) signUp.get("refreshToken"))
                .when().post("/securetoken.googleapis.com/v1/token")
                .then()
                .statusCode(200)
                .body("token_type", equalTo("Bearer"))
                .body("expires_in", equalTo("3600"))
                .body("user_id", equalTo(localId))
                .body("project_id", equalTo("12345"))
                .body("id_token", notNullValue())
                .body("refresh_token", notNullValue());
    }

    @Test
    void signInErrorsMatchEmulatorStrings() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .queryParam("key", "fake-api-key")
                .body(Map.of("email", "nobody@example.com", "password", "whatever"))
                .when().post(CLIENT + ":signInWithPassword")
                .then()
                .statusCode(400)
                .body("error.code", equalTo(400))
                .body("error.message", equalTo("EMAIL_NOT_FOUND"))
                .body("error.errors[0].reason", equalTo("invalid"))
                .body("error.errors[0].domain", equalTo("global"))
                .body("error.status", nullValue());

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .queryParam("key", "fake-api-key")
                .body(Map.of("email", "weak@example.com", "password", "abc"))
                .when().post(CLIENT + ":signUp")
                .then()
                .statusCode(400)
                .body("error.message", equalTo("WEAK_PASSWORD : Password should be at least 6 characters"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of("email", "nokey@example.com", "password", "secret123"))
                .when().post(CLIENT + ":signUp")
                .then()
                .statusCode(403)
                .body("error.message", equalTo("The request is missing a valid API key."))
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void adminCreateLookupUpdateDisableAndDelete() {
        String email = "admin-user@example.com";
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .header("Authorization", "Bearer owner")
                .body(Map.of("localId", "admin-fixed-id", "email", email,
                        "password", "secret123", "emailVerified", true))
                .when().post(ADMIN)
                .then()
                .statusCode(200)
                .body("localId", equalTo("admin-fixed-id"))
                .body("idToken", nullValue());

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .header("Authorization", "Bearer owner")
                .body(Map.of("email", new String[]{email}))
                .when().post(ADMIN + ":lookup")
                .then()
                .statusCode(200)
                .body("kind", equalTo("identitytoolkit#GetAccountInfoResponse"))
                .body("users", hasSize(1))
                .body("users[0].localId", equalTo("admin-fixed-id"))
                .body("users[0].emailVerified", equalTo(true))
                .body("users[0].passwordHash", startsWith("fakeHash:salt="));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .header("Authorization", "Bearer owner")
                .body(Map.of("localId", "admin-fixed-id",
                        "customAttributes", "{\"role\":\"admin\"}",
                        "disableUser", true))
                .when().post(ADMIN + ":update")
                .then()
                .statusCode(200)
                .body("kind", equalTo("identitytoolkit#SetAccountInfoResponse"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .queryParam("key", "fake-api-key")
                .body(Map.of("email", email, "password", "secret123"))
                .when().post("/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword")
                .then()
                .statusCode(400)
                .body("error.message", equalTo("USER_DISABLED"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .header("Authorization", "Bearer owner")
                .body(Map.of("localId", "admin-fixed-id"))
                .when().post(ADMIN + ":delete")
                .then()
                .statusCode(200)
                .body("kind", equalTo("identitytoolkit#DeleteAccountResponse"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .header("Authorization", "Bearer owner")
                .body(Map.of("localId", new String[]{"admin-fixed-id"}))
                .when().post(ADMIN + ":lookup")
                .then()
                .statusCode(200)
                .body("$", not(org.hamcrest.Matchers.hasKey("users")));
    }

    @Test
    void customTokenJsonFormCreatesUserWithClaims() throws Exception {
        String token = "{\"uid\":\"custom-uid-1\",\"claims\":{\"role\":\"tester\"}}";
        String idToken = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .queryParam("key", "fake-api-key")
                .body(Map.of("token", token))
                .when().post(CLIENT + ":signInWithCustomToken")
                .then()
                .statusCode(200)
                .body("kind", equalTo("identitytoolkit#VerifyCustomTokenResponse"))
                .body("isNewUser", equalTo(true))
                .extract().path("idToken");

        Map<String, Object> payload = decodeSegment(idToken, 1);
        assertEquals("tester", payload.get("role"));
        assertEquals("custom", ((Map<?, ?>) payload.get("firebase")).get("sign_in_provider"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .queryParam("key", "fake-api-key")
                .body(Map.of("token", "{\"uid\":\"custom-uid-1\",\"claims\":{\"firebase\":\"nope\"}}"))
                .when().post(CLIENT + ":signInWithCustomToken")
                .then()
                .statusCode(400)
                .body("error.message", equalTo("FORBIDDEN_CLAIM : firebase"));
    }

    @Test
    void batchGetPaginatesByLocalIdAndEmulatorClearWorks() {
        String project = "fb-batch";
        String base = "/identitytoolkit.googleapis.com/v1/projects/" + project + "/accounts";
        for (int i = 1; i <= 3; i++) {
            given()
                .urlEncodingEnabled(false)
                    .contentType("application/json")
                    .header("Authorization", "Bearer owner")
                    .body(Map.of("localId", "user-" + i, "email", "u" + i + "@example.com",
                            "password", "secret123"))
                    .when().post(base)
                    .then().statusCode(200);
        }

        String nextPageToken = given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer owner")
                .queryParam("maxResults", 2)
                .when().get(base + ":batchGet")
                .then()
                .statusCode(200)
                .body("kind", equalTo("identitytoolkit#DownloadAccountResponse"))
                .body("users", hasSize(2))
                .body("users[0].localId", equalTo("user-1"))
                .extract().path("nextPageToken");
        assertEquals("user-2", nextPageToken);

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer owner")
                .queryParam("maxResults", 2)
                .queryParam("nextPageToken", nextPageToken)
                .when().get(base + ":batchGet")
                .then()
                .statusCode(200)
                .body("users", hasSize(1))
                .body("users[0].localId", equalTo("user-3"))
                .body("$", not(org.hamcrest.Matchers.hasKey("nextPageToken")));

        given()
                .urlEncodingEnabled(false)
                .when().delete("/emulator/v1/projects/" + project + "/accounts")
                .then()
                .statusCode(200);

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer owner")
                .when().get(base + ":batchGet")
                .then()
                .statusCode(200)
                .body("$", not(org.hamcrest.Matchers.hasKey("users")));
    }

    @Test
    void nonBearerAuthorizationIsRejectedOnAdminAndClientEndpoints() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .header("Authorization", "Basic abc")
                .body(Map.of("localId", "whoever"))
                .when().post(ADMIN + ":lookup")
                .then()
                .statusCode(401)
                .body("error.status", equalTo("UNAUTHENTICATED"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of("localId", "whoever"))
                .when().post(ADMIN + ":lookup")
                .then()
                .statusCode(401);

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .header("Authorization", "Basic abc")
                .body(Map.of("email", "x@example.com", "password", "secret123"))
                .when().post(CLIENT + ":signUp")
                .then()
                .statusCode(403)
                .body("error.message", equalTo("The request is missing a valid API key."));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .header("Authorization", "Bearer not-owner")
                .queryParam("key", "fake-api-key")
                .body(Map.of("email", "x@example.com", "password", "secret123"))
                .when().post(CLIENT + ":signUp")
                .then()
                .statusCode(401)
                .body("error.status", equalTo("UNAUTHENTICATED"));
    }

    @Test
    void anonymousSignUpSetsAnonymousProvider() throws Exception {
        String idToken = given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .queryParam("key", "fake-api-key")
                .body(Map.of())
                .when().post(CLIENT + ":signUp")
                .then()
                .statusCode(200)
                .body("idToken", notNullValue())
                .extract().path("idToken");

        Map<String, Object> payload = decodeSegment(idToken, 1);
        assertEquals("anonymous", payload.get("provider_id"));
        assertEquals("anonymous", ((Map<?, ?>) payload.get("firebase")).get("sign_in_provider"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decodeSegment(String jwt, int index) throws Exception {
        String[] parts = jwt.split("\\.", -1);
        return MAPPER.readValue(Base64.getUrlDecoder().decode(parts[index]), Map.class);
    }
}

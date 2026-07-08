package io.floci.gcp.services.firebaseauth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(FirebaseAuthDisabledRestIntegrationTest.DisabledFirebaseAuthProfile.class)
class FirebaseAuthDisabledRestIntegrationTest {

    @Test
    void disabledFirebaseAuthReturnsUnavailableWrapper() {
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .queryParam("key", "fake-api-key")
                .body(Map.of())
                .when().post("/identitytoolkit.googleapis.com/v1/accounts:signUp")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Service firebaseauth is not enabled."));
    }

    public static class DisabledFirebaseAuthProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.firebaseauth.enabled", "false");
        }
    }
}

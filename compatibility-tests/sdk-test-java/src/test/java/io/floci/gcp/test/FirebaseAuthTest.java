package io.floci.gcp.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserRecord;
import com.google.firebase.internal.EmulatorCredentials;
import com.google.firebase.internal.FirebaseProcessEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * firebase-admin SDK against floci-gcp via FIREBASE_AUTH_EMULATOR_HOST. Client-mode
 * calls (API-key sign-in) resolve to the emulator's default project, mirroring the
 * official Auth emulator's single-project behavior — so the Firebase app is
 * initialized with that project ID.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirebaseAuthTest {

    private static final String PROJECT_ID =
            System.getenv().getOrDefault("FLOCI_GCP_DEFAULT_PROJECT", "floci-local");
    private static final String UID = "fb-compat-" + TestFixtures.uniqueName("u").replace("-", "");
    private static final String EMAIL = UID.toLowerCase() + "@example.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static FirebaseApp app;
    private static FirebaseAuth auth;
    private static String signedInIdToken;

    @BeforeAll
    static void setUp() {
        String host = TestFixtures.endpoint().replaceFirst("^https?://", "");
        FirebaseProcessEnvironment.setenv("FIREBASE_AUTH_EMULATOR_HOST", host);
        app = FirebaseApp.initializeApp(FirebaseOptions.builder()
                .setProjectId(PROJECT_ID)
                .setCredentials(new EmulatorCredentials())
                .build(), "floci-firebase-auth-test");
        auth = FirebaseAuth.getInstance(app);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (auth != null) {
            try {
                auth.deleteUser(UID);
            } catch (FirebaseAuthException ignored) {
                // already deleted by the test
            }
        }
        if (app != null) {
            app.delete();
        }
    }

    @Test
    @Order(1)
    void createUserAndGetByEmail() throws Exception {
        UserRecord created = auth.createUser(new UserRecord.CreateRequest()
                .setUid(UID)
                .setEmail(EMAIL)
                .setPassword("secret123")
                .setDisplayName("Compat User")
                .setEmailVerified(true));

        assertThat(created.getUid()).isEqualTo(UID);
        assertThat(created.getEmail()).isEqualTo(EMAIL);

        UserRecord fetched = auth.getUserByEmail(EMAIL);
        assertThat(fetched.getUid()).isEqualTo(UID);
        assertThat(fetched.getDisplayName()).isEqualTo("Compat User");
        assertThat(fetched.isEmailVerified()).isTrue();
    }

    @Test
    @Order(2)
    void setCustomClaimsRoundtrip() throws Exception {
        auth.setCustomUserClaims(UID, Map.of("role", "tester"));

        UserRecord fetched = auth.getUser(UID);
        assertThat(fetched.getCustomClaims()).containsEntry("role", "tester");
    }

    @Test
    @Order(3)
    void customTokenSignInAndVerifyIdToken() throws Exception {
        String customToken = auth.createCustomToken(UID, Map.of("premium", true));

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create(TestFixtures.endpoint()
                                + "/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                MAPPER.writeValueAsString(Map.of("token", customToken, "returnSecureToken", true))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("isNewUser").asBoolean()).isFalse();
        signedInIdToken = body.get("idToken").asText();

        FirebaseToken decoded = auth.verifyIdToken(signedInIdToken);
        assertThat(decoded.getUid()).isEqualTo(UID);
        assertThat(decoded.getClaims().get("premium")).isEqualTo(true);
        assertThat(decoded.getClaims().get("role")).isEqualTo("tester");
    }

    @Test
    @Order(4)
    void listUsersContainsCreatedUser() throws Exception {
        List<String> uids = new ArrayList<>();
        auth.listUsers(null).iterateAll().forEach(user -> uids.add(user.getUid()));

        assertThat(uids).contains(UID);
    }

    @Test
    @Order(5)
    void revokeRefreshTokensInvalidatesIdToken() throws Exception {
        Thread.sleep(1100);
        auth.revokeRefreshTokens(UID);

        assertThatThrownBy(() -> auth.verifyIdToken(signedInIdToken, true))
                .isInstanceOf(FirebaseAuthException.class);
    }

    @Test
    @Order(6)
    void deleteUserRemovesAccount() throws Exception {
        auth.deleteUser(UID);

        assertThatThrownBy(() -> auth.getUser(UID))
                .isInstanceOf(FirebaseAuthException.class);
    }
}

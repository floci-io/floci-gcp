package io.floci.gcp.services.cloudkms;

import io.floci.gcp.core.common.TokenRegistry;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(CloudKmsIamEnforcementIntegrationTest.CtfIamProfile.class)
class CloudKmsIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String LOCATION = "us-central1";
    private static final String KEY_RINGS_PATH =
            "/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/keyRings";
    private static final String PLAYER_EMAIL = "player@test-project.iam.gserviceaccount.com";
    private static final String PLAYER_TOKEN = "player-token";
    private static final String ROOT_TOKEN = "root-token-test";

    @Inject
    TokenRegistry tokenRegistry;

    @Inject
    IamService iamService;

    @BeforeEach
    void setUp() {
        tokenRegistry.register(PLAYER_TOKEN, PLAYER_EMAIL);
        iamService.setPolicy("projects/" + PROJECT, new StoredPolicy());
    }

    @Test
    void playerWithoutBindingIsDeniedListKeyRings() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(KEY_RINGS_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithCloudKmsAdminBindingCanListKeyRings() {
        bindPlayer("roles/cloudkms.admin");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(KEY_RINGS_PATH)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithEncrypterDecrypterIsDeniedListKeyRings() {
        bindPlayer("roles/cloudkms.cryptoKeyEncrypterDecrypter");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(KEY_RINGS_PATH)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithEncrypterDecrypterCanEncrypt() {
        String keyRingId = "ctf-kr-" + UUID.randomUUID().toString().substring(0, 8);
        String cryptoKeyId = "ctf-key-" + UUID.randomUUID().toString().substring(0, 8);
        seedEncryptDecryptKey(keyRingId, cryptoKeyId);
        bindPlayer("roles/cloudkms.cryptoKeyEncrypterDecrypter");

        String encryptPath = KEY_RINGS_PATH + "/" + keyRingId + "/cryptoKeys/" + cryptoKeyId + ":encrypt";
        String plaintext = Base64.getEncoder().encodeToString("ctf-secret".getBytes());

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(Map.of("plaintext", plaintext))
                .when().post(encryptPath)
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithCloudKmsAdminIsDeniedEncrypt() {
        String keyRingId = "ctf-kr-" + UUID.randomUUID().toString().substring(0, 8);
        String cryptoKeyId = "ctf-key-" + UUID.randomUUID().toString().substring(0, 8);
        seedEncryptDecryptKey(keyRingId, cryptoKeyId);
        bindPlayer("roles/cloudkms.admin");

        String encryptPath = KEY_RINGS_PATH + "/" + keyRingId + "/cryptoKeys/" + cryptoKeyId + ":encrypt";
        String plaintext = Base64.getEncoder().encodeToString("ctf-secret".getBytes());

        given()
                .urlEncodingEnabled(false)
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(Map.of("plaintext", plaintext))
                .when().post(encryptPath)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void rootTokenAlwaysCanListKeyRings() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(KEY_RINGS_PATH)
                .then()
                .statusCode(200);
    }

    private void bindPlayer(String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", role,
                "members", List.of("serviceAccount:" + PLAYER_EMAIL))));
        iamService.setPolicy("projects/" + PROJECT, policy);
    }

    private void seedEncryptDecryptKey(String keyRingId, String cryptoKeyId) {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .queryParam("keyRingId", keyRingId)
                .body(Map.of())
                .when().post(KEY_RINGS_PATH)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .queryParam("cryptoKeyId", cryptoKeyId)
                .body(Map.of("purpose", "ENCRYPT_DECRYPT"))
                .when().post(KEY_RINGS_PATH + "/" + keyRingId + "/cryptoKeys")
                .then()
                .statusCode(200);
    }

    public static class CtfIamProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.auth.validate-tokens", "true",
                    "floci-gcp.auth.root-service-account",
                    "operator@test-project.iam.gserviceaccount.com",
                    "floci-gcp.auth.root-access-token", ROOT_TOKEN,
                    "floci-gcp.services.iam.enforcement-enabled", "true",
                    "floci-gcp.services.iam.strict-enforcement-enabled", "true");
        }
    }
}

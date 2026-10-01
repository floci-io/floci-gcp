package io.floci.gcp.services.gcs;

import io.floci.gcp.services.credentials.CredentialAccessBoundaryRule;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class GcsXmlAuthenticationRestIntegrationTest {

    private static final String READER = "inRole:roles/storage.legacyObjectReader";

    @Inject
    CredentialTokenService tokenService;

    private String bucket;

    @BeforeEach
    void setUp() {
        tokenService.clear();
        bucket = "xml-auth-" + System.nanoTime();
        given()
                .contentType("application/json")
                .queryParam("project", "test-project")
                .body(Map.of("name", bucket))
                .when().post("/storage/v1/b")
                .then().statusCode(200);
    }

    @Test
    void unknownCredentialReturnsAuthenticationRequiredAcrossXmlRoutes() {
        String authorization = "Bearer floci-gcp-downscoped-missing";

        given()
                .header("Authorization", authorization)
                .when().get("/{bucket}/object.txt", bucket)
                .then()
                .statusCode(401)
                .contentType(containsString("application/xml"))
                .body("Error.Code", equalTo("AuthenticationRequired"));

        given()
                .header("Authorization", authorization)
                .body("object data")
                .when().put("/{bucket}/object.txt", bucket)
                .then()
                .statusCode(401)
                .contentType(containsString("application/xml"))
                .body("Error.Code", equalTo("AuthenticationRequired"));

        given()
                .header("Authorization", authorization)
                .when().post("/{bucket}/object.txt?uploads", bucket)
                .then()
                .statusCode(401)
                .contentType(containsString("application/xml"))
                .body("Error.Code", equalTo("AuthenticationRequired"));
    }

    @Test
    void insufficientCredentialReturnsXmlAccessDenied() {
        String token = tokenService.mintDownscopedToken("source-token", List.of(
                        new CredentialAccessBoundaryRule(bucket, "allowed/", List.of(READER))))
                .token().getTokenValue();
        String authorization = "Bearer " + token;

        given()
                .header("Authorization", authorization)
                .when().get("/{bucket}/outside.txt", bucket)
                .then()
                .statusCode(403)
                .contentType(containsString("application/xml"))
                .body("Error.Code", equalTo("AccessDenied"));

        given()
                .header("Authorization", authorization)
                .when().post("/{bucket}/outside.txt?uploads", bucket)
                .then()
                .statusCode(403)
                .contentType(containsString("application/xml"))
                .body("Error.Code", equalTo("AccessDenied"));
    }

    @Test
    void nonAuthenticationPermissionDenialRetainsForbiddenStatus() throws Exception {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String encodedKey = Base64.getEncoder().encodeToString(key);
        String keySha256 = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(key));

        given()
                .header("x-goog-encryption-algorithm", "AES256")
                .header("x-goog-encryption-key", encodedKey)
                .header("x-goog-encryption-key-sha256", keySha256)
                .body("encrypted-data")
                .when().put("/{bucket}/encrypted.txt", bucket)
                .then().statusCode(200);

        given()
                .when().get("/{bucket}/encrypted.txt", bucket)
                .then().statusCode(403);
    }
}

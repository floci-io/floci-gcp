package io.floci.gcp.services.sts;

import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class StsFederationRestIntegrationTest {

    private static final String OIDC_ISSUER = "https://token.actions.githubusercontent.com";
    private static final String AWS_ACCOUNT = "123456789012";

    @Inject
    CredentialTokenService tokenService;

    private String project;
    private String projectNumber;
    private String poolId;

    @BeforeEach
    void setUp() {
        tokenService.clear();
        project = "sts-fed-" + UUID.randomUUID().toString().substring(0, 8);
        projectNumber = ProjectNumbers.of(project);
        poolId = "pool-" + UUID.randomUUID().toString().substring(0, 8);

        given()
                .contentType("application/json")
                .body(Map.of("displayName", "Federation Test Pool"))
                .when().post("/v1/projects/" + project + "/locations/global/workloadIdentityPools"
                        + "?workloadIdentityPoolId=" + poolId)
                .then()
                .statusCode(200);
    }

    @Test
    void exchangesOidcJwtAndIdTokenOverFormAndJson() {
        KeyPair rsa = StsFederationTestFixtures.generateRsaKeyPair();
        String jwks = StsFederationTestFixtures.rsaJwksJson("k1", (RSAPublicKey) rsa.getPublic());
        String providerId = "oidc-prov";
        createOidcProvider(providerId, OIDC_ISSUER, "https://my-audience.example", jwks,
                Map.of("google.subject", "assertion.sub",
                        "attribute.repository", "assertion.repository"),
                "assertion.repository_owner == 'my-org'");

        String audience = "//iam.googleapis.com/projects/" + projectNumber
                + "/locations/global/workloadIdentityPools/" + poolId + "/providers/" + providerId;
        String jwt = StsFederationTestFixtures.signJwt("RS256", "k1",
                StsFederationTestFixtures.oidcClaims(OIDC_ISSUER, "https://my-audience.example",
                        "repo:my-org/my-repo:ref:refs/heads/main", Instant.now(), 600,
                        Map.of("repository", "my-org/my-repo", "repository_owner", "my-org")),
                rsa.getPrivate());

        // Form-encoded exchange with urn:ietf:params:oauth:token-type:jwt
        String formToken = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", StsService.TOKEN_EXCHANGE_GRANT_TYPE)
                .formParam("subject_token_type", StsService.JWT_TOKEN_TYPE)
                .formParam("subject_token", jwt)
                .formParam("requested_token_type", StsService.ACCESS_TOKEN_TYPE)
                .formParam("audience", audience)
                .formParam("scope", "https://www.googleapis.com/auth/cloud-platform")
                .when().post("/v1/token")
                .then()
                .statusCode(200)
                .body("access_token", startsWith(CredentialTokenService.FEDERATED_TOKEN_PREFIX))
                .body("issued_token_type", equalTo(StsService.ACCESS_TOKEN_TYPE))
                .body("token_type", equalTo(StsService.BEARER_TOKEN_TYPE))
                .body("scope", equalTo("https://www.googleapis.com/auth/cloud-platform"))
                .extract().path("access_token");

        StoredCredentialToken stored = tokenService.lookupBearerToken(formToken).orElseThrow();
        String expectedPoolPath = "iam.googleapis.com/projects/" + projectNumber
                + "/locations/global/workloadIdentityPools/" + poolId;
        assertEquals("principal://" + expectedPoolPath + "/subject/repo:my-org/my-repo:ref:refs/heads/main",
                stored.getPrincipal());
        assertTrue(stored.getPrincipalSets().contains(
                "principalSet://" + expectedPoolPath + "/attribute.repository/my-org/my-repo"));
        assertTrue(stored.getPrincipalSets().contains("principalSet://" + expectedPoolPath + "/*"));

        // JSON exchange (camelCase) with urn:ietf:params:oauth:token-type:id_token
        given()
                .contentType("application/json")
                .body(Map.of(
                        "grantType", StsService.TOKEN_EXCHANGE_GRANT_TYPE,
                        "subjectTokenType", StsService.ID_TOKEN_TYPE,
                        "subjectToken", jwt,
                        "requestedTokenType", StsService.ACCESS_TOKEN_TYPE,
                        "audience", audience))
                .when().post("/v1/token")
                .then()
                .statusCode(200)
                .body("access_token", startsWith(CredentialTokenService.FEDERATED_TOKEN_PREFIX));
    }

    @Test
    void exchangesEcSignedOidcJwtAndRejectsBadIssuerAudienceSignatureAndCondition() {
        KeyPair ec = StsFederationTestFixtures.generateEcP256KeyPair();
        KeyPair otherEc = StsFederationTestFixtures.generateEcP256KeyPair();
        String jwks = StsFederationTestFixtures.ecJwksJson("ec-1", (ECPublicKey) ec.getPublic());
        String providerId = "ec-prov";
        createOidcProvider(providerId, OIDC_ISSUER, null, jwks,
                Map.of("google.subject", "assertion.sub"),
                "assertion.env == 'prod'");

        String audience = "//iam.googleapis.com/projects/" + projectNumber
                + "/locations/global/workloadIdentityPools/" + poolId + "/providers/" + providerId;
        String defaultAud = "https://iam.googleapis.com/projects/" + projectNumber
                + "/locations/global/workloadIdentityPools/" + poolId + "/providers/" + providerId;

        String validJwt = StsFederationTestFixtures.signJwt("ES256", "ec-1",
                StsFederationTestFixtures.oidcClaims(OIDC_ISSUER, defaultAud, "svc-1",
                        Instant.now(), 600, Map.of("env", "prod")),
                ec.getPrivate());
        postJsonExchange(StsService.JWT_TOKEN_TYPE, validJwt, audience)
                .statusCode(200)
                .body("access_token", startsWith(CredentialTokenService.FEDERATED_TOKEN_PREFIX));

        // Failing attributeCondition
        String stagingJwt = StsFederationTestFixtures.signJwt("ES256", "ec-1",
                StsFederationTestFixtures.oidcClaims(OIDC_ISSUER, defaultAud, "svc-1",
                        Instant.now(), 600, Map.of("env", "staging")),
                ec.getPrivate());
        postJsonExchange(StsService.JWT_TOKEN_TYPE, stagingJwt, audience)
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));

        // Invalid signature
        String badSigJwt = StsFederationTestFixtures.signJwt("ES256", "ec-1",
                StsFederationTestFixtures.oidcClaims(OIDC_ISSUER, defaultAud, "svc-1",
                        Instant.now(), 600, Map.of("env", "prod")),
                otherEc.getPrivate());
        postJsonExchange(StsService.JWT_TOKEN_TYPE, badSigJwt, audience)
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));

        // Wrong issuer
        String wrongIssuerJwt = StsFederationTestFixtures.signJwt("ES256", "ec-1",
                StsFederationTestFixtures.oidcClaims("https://wrong.issuer", defaultAud, "svc-1",
                        Instant.now(), 600, Map.of("env", "prod")),
                ec.getPrivate());
        postJsonExchange(StsService.JWT_TOKEN_TYPE, wrongIssuerJwt, audience)
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));

        // Wrong audience
        String wrongAudJwt = StsFederationTestFixtures.signJwt("ES256", "ec-1",
                StsFederationTestFixtures.oidcClaims(OIDC_ISSUER, "https://other.aud", "svc-1",
                        Instant.now(), 600, Map.of("env", "prod")),
                ec.getPrivate());
        postJsonExchange(StsService.JWT_TOKEN_TYPE, wrongAudJwt, audience)
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));
    }

    @Test
    void exchangesAws4RequestAndRejectsWrongAccountAndDisabledOrDeletedProvider() {
        String providerId = "aws-prov";
        given()
                .contentType("application/json")
                .body(Map.of(
                        "aws", Map.of("accountId", AWS_ACCOUNT),
                        "attributeMapping", Map.of(
                                "google.subject", "assertion.arn",
                                "attribute.aws_role",
                                "assertion.arn.contains('assumed-role') "
                                        + "? assertion.arn.extract('{account_arn}assumed-role/') + 'assumed-role/' "
                                        + "+ assertion.arn.extract('assumed-role/{role_name}/') : assertion.arn")))
                .when().post("/v1/projects/" + project + "/locations/global/workloadIdentityPools/"
                        + poolId + "/providers?workloadIdentityPoolProviderId=" + providerId)
                .then()
                .statusCode(200);

        String audience = "//iam.googleapis.com/projects/" + projectNumber
                + "/locations/global/workloadIdentityPools/" + poolId + "/providers/" + providerId;
        String roleArn = "arn:aws:sts::" + AWS_ACCOUNT + ":assumed-role/app-role/pod-1";
        String awsToken = StsFederationTestFixtures.urlEncode(
                StsFederationTestFixtures.awsSubjectTokenJson("us-west-2", "ASIA123456", Instant.now(),
                        audience, roleArn));

        String accessToken = postJsonExchange(StsService.AWS4_REQUEST_TOKEN_TYPE, awsToken, audience)
                .statusCode(200)
                .body("access_token", startsWith(CredentialTokenService.FEDERATED_TOKEN_PREFIX))
                .extract().path("access_token");

        StoredCredentialToken stored = tokenService.lookupBearerToken(accessToken).orElseThrow();
        String expectedPoolPath = "iam.googleapis.com/projects/" + projectNumber
                + "/locations/global/workloadIdentityPools/" + poolId;
        assertEquals("principal://" + expectedPoolPath + "/subject/" + roleArn, stored.getPrincipal());
        assertTrue(stored.getPrincipalSets().contains("principalSet://" + expectedPoolPath
                + "/attribute.aws_role/arn:aws:sts::" + AWS_ACCOUNT + ":assumed-role/app-role"));

        // Mismatched AWS account
        String wrongAccountToken = StsFederationTestFixtures.urlEncode(
                StsFederationTestFixtures.awsSubjectTokenJson("us-west-2", "ASIA123456", Instant.now(),
                        audience, "arn:aws:sts::999999999999:assumed-role/app-role/pod-1"));
        postJsonExchange(StsService.AWS4_REQUEST_TOKEN_TYPE, wrongAccountToken, audience)
                .statusCode(400)
                .body("error", equalTo("invalid_grant"));

        // Disable provider -> invalid_target
        given()
                .contentType("application/json")
                .body(Map.of("disabled", true))
                .when().patch("/v1/projects/" + project + "/locations/global/workloadIdentityPools/"
                        + poolId + "/providers/" + providerId + "?updateMask=disabled")
                .then()
                .statusCode(200);
        postJsonExchange(StsService.AWS4_REQUEST_TOKEN_TYPE, awsToken, audience)
                .statusCode(400)
                .body("error", equalTo("invalid_target"));

        // Re-enable provider, then soft-delete pool -> invalid_target
        given()
                .contentType("application/json")
                .body(Map.of("disabled", false))
                .when().patch("/v1/projects/" + project + "/locations/global/workloadIdentityPools/"
                        + poolId + "/providers/" + providerId + "?updateMask=disabled")
                .then()
                .statusCode(200);
        given()
                .when().delete("/v1/projects/" + project + "/locations/global/workloadIdentityPools/" + poolId)
                .then()
                .statusCode(200);
        postJsonExchange(StsService.AWS4_REQUEST_TOKEN_TYPE, awsToken, audience)
                .statusCode(400)
                .body("error", equalTo("invalid_target"));
    }

    private void createOidcProvider(String providerId, String issuer, String allowedAudience,
            String jwksJson, Map<String, String> attributeMapping, String attributeCondition) {
        Map<String, Object> oidc = allowedAudience == null
                ? Map.of("issuerUri", issuer, "jwksJson", jwksJson)
                : Map.of("issuerUri", issuer, "allowedAudiences", List.of(allowedAudience),
                        "jwksJson", jwksJson);
        Map<String, Object> body = attributeCondition == null
                ? Map.of("oidc", oidc, "attributeMapping", attributeMapping)
                : Map.of("oidc", oidc, "attributeMapping", attributeMapping,
                        "attributeCondition", attributeCondition);
        given()
                .contentType("application/json")
                .body(body)
                .when().post("/v1/projects/" + project + "/locations/global/workloadIdentityPools/"
                        + poolId + "/providers?workloadIdentityPoolProviderId=" + providerId)
                .then()
                .statusCode(200);
    }

    private ValidatableResponse postJsonExchange(
            String subjectTokenType, String subjectToken, String audience) {
        return given()
                .contentType("application/json")
                .body(Map.of(
                        "grant_type", StsService.TOKEN_EXCHANGE_GRANT_TYPE,
                        "subject_token_type", subjectTokenType,
                        "subject_token", subjectToken,
                        "requested_token_type", StsService.ACCESS_TOKEN_TYPE,
                        "audience", audience))
                .when().post("/v1/token")
                .then();
    }
}

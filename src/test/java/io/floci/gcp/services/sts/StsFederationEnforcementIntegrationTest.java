package io.floci.gcp.services.sts;

import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.testing.IamEnforceProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestProfile(IamEnforceProfile.class)
class StsFederationEnforcementIntegrationTest {

    private static final String OIDC_ISSUER = "https://token.actions.githubusercontent.com";
    private static final String AWS_ACCOUNT = "123456789012";

    @Inject
    CredentialTokenService tokenService;
    @Inject
    IamService iam;
    @Inject
    GcsService gcsService;

    private String project;
    private String projectNumber;
    private String poolId;
    private String poolPath;

    @BeforeEach
    void setUp() {
        tokenService.clear();
        project = "sts-enf-" + UUID.randomUUID().toString().substring(0, 8);
        projectNumber = ProjectNumbers.of(project);
        poolId = "pool-" + UUID.randomUUID().toString().substring(0, 8);
        poolPath = "iam.googleapis.com/projects/" + projectNumber
                + "/locations/global/workloadIdentityPools/" + poolId;

        given()
                .contentType("application/json")
                .body(Map.of("displayName", "Enforced Federation Pool"))
                .when().post("/v1/projects/" + project + "/locations/global/workloadIdentityPools"
                        + "?workloadIdentityPoolId=" + poolId)
                .then()
                .statusCode(200);
    }

    @Test
    void federatedOidcPrincipalAndPrincipalSetAuthorizeServiceAccountImpersonation() {
        KeyPair rsa = StsFederationTestFixtures.generateRsaKeyPair();
        String jwks = StsFederationTestFixtures.rsaJwksJson("k1", (RSAPublicKey) rsa.getPublic());
        String providerId = "gh-oidc";
        given()
                .contentType("application/json")
                .body(Map.of(
                        "oidc", Map.of(
                                "issuerUri", OIDC_ISSUER,
                                "allowedAudiences", List.of("https://iam.test"),
                                "jwksJson", jwks),
                        "attributeMapping", Map.of(
                                "google.subject", "assertion.sub",
                                "attribute.repository", "assertion.repository")))
                .when().post("/v1/projects/" + project + "/locations/global/workloadIdentityPools/"
                        + poolId + "/providers?workloadIdentityPoolProviderId=" + providerId)
                .then()
                .statusCode(200);

        String saDirect = iam.createServiceAccount(project, "sa-direct", "Direct SA", null).getEmail();
        String saByAttr = iam.createServiceAccount(project, "sa-attr", "Attr SA", null).getEmail();

        // Grant roles/iam.workloadIdentityUser on saDirect to exact principal://...
        iam.setPolicy("projects/" + project + "/serviceAccounts/" + saDirect, policy(
                "roles/iam.workloadIdentityUser",
                "principal://" + poolPath + "/subject/user-alice"));

        // Grant roles/iam.workloadIdentityUser on saByAttr to principalSet://.../attribute.repository/...
        iam.setPolicy("projects/" + project + "/serviceAccounts/" + saByAttr, policy(
                "roles/iam.workloadIdentityUser",
                "principalSet://" + poolPath + "/attribute.repository/my-org/allowed-repo"));

        String audience = "//iam.googleapis.com/projects/" + projectNumber
                + "/locations/global/workloadIdentityPools/" + poolId + "/providers/" + providerId;

        String aliceToken = exchangeOidc(rsa, "k1", "user-alice", "my-org/allowed-repo", audience);
        String bobToken = exchangeOidc(rsa, "k1", "user-bob", "my-org/other-repo", audience);

        // Alice can impersonate saDirect (matched by principal://) and saByAttr (matched by principalSet://)
        generateAccessToken(saDirect, aliceToken)
                .statusCode(200)
                .body("accessToken", startsWith(CredentialTokenService.IMPERSONATED_TOKEN_PREFIX));
        generateAccessToken(saByAttr, aliceToken)
                .statusCode(200)
                .body("accessToken", startsWith(CredentialTokenService.IMPERSONATED_TOKEN_PREFIX));

        // Bob matches neither policy and is denied on both service accounts
        generateAccessToken(saDirect, bobToken).statusCode(403);
        generateAccessToken(saByAttr, bobToken).statusCode(403);
    }

    @Test
    void federatedAwsTokenAuthorizesDirectGcsAccessAndPreservesIdentityAcrossCabDownscoping() {
        String providerId = "aws-direct";
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

        String bucket = "sts-fed-bkt-" + UUID.randomUUID().toString().substring(0, 8);
        given()
                .contentType("application/json")
                .body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=" + project)
                .then()
                .statusCode(200);
        gcsService.putObject(bucket, "data/file.txt", "text/plain",
                "hello federated world".getBytes(StandardCharsets.UTF_8), "http://localhost:4588");

        // Grant roles/storage.objectViewer to the assumed-role principalSet
        String allowedRole = "arn:aws:sts::" + AWS_ACCOUNT + ":assumed-role/reader-role";
        iam.setPolicy("buckets/" + bucket, policy(
                "roles/storage.objectViewer",
                "principalSet://" + poolPath + "/attribute.aws_role/" + allowedRole));

        String audience = "//iam.googleapis.com/projects/" + projectNumber
                + "/locations/global/workloadIdentityPools/" + poolId + "/providers/" + providerId;
        String readerToken = exchangeAws(audience, allowedRole + "/session-1");
        String deniedToken = exchangeAws(audience, "arn:aws:sts::" + AWS_ACCOUNT + ":assumed-role/other-role/session-2");

        // Direct GCS read succeeds for allowed AWS role, denied for other AWS role
        given()
                .header("Authorization", "Bearer " + readerToken)
                .when().get("/storage/v1/b/{bucket}/o/{object}?alt=media", bucket, "data/file.txt")
                .then()
                .statusCode(200)
                .body(equalTo("hello federated world"));
        given()
                .header("Authorization", "Bearer " + deniedToken)
                .when().get("/storage/v1/b/{bucket}/o/{object}?alt=media", bucket, "data/file.txt")
                .then()
                .statusCode(403);

        // Downscoping the federated token preserves its federated principal for GCS IAM evaluation
        String cab = StsServiceTest.cab().replace("/buckets/bucket", "/buckets/" + bucket);
        String downscopedReader = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", StsService.TOKEN_EXCHANGE_GRANT_TYPE)
                .formParam("subject_token_type", StsService.ACCESS_TOKEN_TYPE)
                .formParam("subject_token", readerToken)
                .formParam("requested_token_type", StsService.ACCESS_TOKEN_TYPE)
                .formParam("options", cab)
                .when().post("/v1/token")
                .then()
                .statusCode(200)
                .extract().path("access_token");

        given()
                .header("Authorization", "Bearer " + downscopedReader)
                .when().get("/storage/v1/b/{bucket}/o/{object}?alt=media", bucket, "data/file.txt")
                .then()
                .statusCode(200)
                .body(equalTo("hello federated world"));
    }

    private String exchangeOidc(KeyPair rsa, String kid, String sub, String repo, String audience) {
        String jwt = StsFederationTestFixtures.signJwt("RS256", kid,
                StsFederationTestFixtures.oidcClaims(OIDC_ISSUER, "https://iam.test", sub,
                        Instant.now(), 600, Map.of("repository", repo)),
                rsa.getPrivate());
        return given()
                .contentType("application/json")
                .body(Map.of(
                        "grant_type", StsService.TOKEN_EXCHANGE_GRANT_TYPE,
                        "subject_token_type", StsService.JWT_TOKEN_TYPE,
                        "subject_token", jwt,
                        "requested_token_type", StsService.ACCESS_TOKEN_TYPE,
                        "audience", audience))
                .when().post("/v1/token")
                .then()
                .statusCode(200)
                .extract().path("access_token");
    }

    private String exchangeAws(String audience, String roleArn) {
        String subjectToken = StsFederationTestFixtures.urlEncode(
                StsFederationTestFixtures.awsSubjectTokenJson("us-east-1", "ASIAEXAMPLE", Instant.now(),
                        audience, roleArn));
        return given()
                .contentType("application/json")
                .body(Map.of(
                        "grant_type", StsService.TOKEN_EXCHANGE_GRANT_TYPE,
                        "subject_token_type", StsService.AWS4_REQUEST_TOKEN_TYPE,
                        "subject_token", subjectToken,
                        "requested_token_type", StsService.ACCESS_TOKEN_TYPE,
                        "audience", audience))
                .when().post("/v1/token")
                .then()
                .statusCode(200)
                .extract().path("access_token");
    }

    private ValidatableResponse generateAccessToken(String saEmail, String bearer) {
        return given()
                .header("Authorization", "Bearer " + bearer)
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(Map.of("scope", List.of("https://www.googleapis.com/auth/cloud-platform")))
                .when().post("/v1/projects/-/serviceAccounts/" + saEmail + ":generateAccessToken")
                .then()
                .log().ifValidationFails();
    }

    private static StoredPolicy policy(String role, String member) {
        StoredPolicy policy = new StoredPolicy();
        policy.setVersion(1);
        policy.setBindings(List.of(Map.of("role", role, "members", List.of(member))));
        return policy;
    }
}

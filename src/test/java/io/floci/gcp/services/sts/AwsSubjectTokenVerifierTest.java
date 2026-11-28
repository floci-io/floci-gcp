package io.floci.gcp.services.sts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AwsSubjectTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");
    private static final String ACCOUNT_ID = "123456789012";
    private static final String PROVIDER_NAME =
            "projects/123456789/locations/global/workloadIdentityPools/aws-pool/providers/aws-provider";
    private static final String AUDIENCE = "//iam.googleapis.com/" + PROVIDER_NAME;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AwsSubjectTokenVerifier verifier =
            new AwsSubjectTokenVerifier(mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void verifiesUrlEncodedAws4RequestWithAssumedRoleArn() {
        String roleArn = "arn:aws:sts::" + ACCOUNT_ID + ":assumed-role/ci-role/build-42";
        String token = StsFederationTestFixtures.urlEncode(
                StsFederationTestFixtures.awsSubjectTokenJson("us-east-1", "ASIAEXAMPLE123", NOW,
                        AUDIENCE, roleArn));

        Map<String, Object> assertion = verifier.verify(token, awsProvider(ACCOUNT_ID), PROVIDER_NAME, AUDIENCE);

        assertEquals(ACCOUNT_ID, assertion.get("account"));
        assertEquals(roleArn, assertion.get("arn"));
        assertEquals("ASIAEXAMPLE123:build-42", assertion.get("userid"));
    }

    @Test
    void verifiesRawJsonAws4RequestWithoutSessionToken() {
        String token = StsFederationTestFixtures.awsSubjectTokenJson(null, "AKIAEXAMPLE123", NOW,
                AUDIENCE, null);

        Map<String, Object> assertion = verifier.verify(token, awsProvider(ACCOUNT_ID), PROVIDER_NAME, AUDIENCE);

        assertEquals(ACCOUNT_ID, assertion.get("account"));
        assertEquals("arn:aws:iam::" + ACCOUNT_ID + ":user/AKIAEXAMPLE123", assertion.get("arn"));
        assertEquals("AKIAEXAMPLE123", assertion.get("userid"));
    }

    @Test
    void rejectsMismatchedAccountTargetResourceTimestampAndSignatureFormat() {
        ObjectNode provider = awsProvider(ACCOUNT_ID);

        // Wrong AWS account in session token ARN
        String wrongAccount = StsFederationTestFixtures.awsSubjectTokenJson("us-east-1", "ASIA1", NOW,
                AUDIENCE, "arn:aws:sts::999999999999:assumed-role/other/sess");
        assertInvalidGrant(() -> verifier.verify(wrongAccount, provider, PROVIDER_NAME, AUDIENCE));

        // Wrong x-goog-cloud-target-resource
        String wrongTarget = StsFederationTestFixtures.awsSubjectTokenJson("us-east-1", "ASIA1", NOW,
                "//iam.googleapis.com/projects/123456789/locations/global/workloadIdentityPools/other/providers/p",
                null);
        assertInvalidGrant(() -> verifier.verify(wrongTarget, provider, PROVIDER_NAME, AUDIENCE));

        // Expired x-amz-date (> 15 minutes old)
        String expiredDate = StsFederationTestFixtures.awsSubjectTokenJson("us-east-1", "ASIA1",
                NOW.minusSeconds(16 * 60), AUDIENCE, null);
        assertInvalidGrant(() -> verifier.verify(expiredDate, provider, PROVIDER_NAME, AUDIENCE));

        // Bad SigV4 signature (not 64 hex chars)
        String badSig = StsFederationTestFixtures.awsSubjectTokenJson("us-east-1", "ASIA1", NOW, AUDIENCE, null)
                .replace(StsFederationTestFixtures.VALID_HEX_SIGNATURE, "not-a-valid-hex-signature");
        assertInvalidGrant(() -> verifier.verify(badSig, provider, PROVIDER_NAME, AUDIENCE));

        // Wrong HTTP method
        String wrongMethod = StsFederationTestFixtures.awsSubjectTokenJson("us-east-1", "ASIA1", NOW, AUDIENCE, null)
                .replace("\"POST\"", "\"GET\"");
        assertInvalidGrant(() -> verifier.verify(wrongMethod, provider, PROVIDER_NAME, AUDIENCE));

        // Missing x-goog-cloud-target-resource in SignedHeaders
        String unsignedTarget = StsFederationTestFixtures.awsSubjectTokenJson("us-east-1", "ASIA1", NOW, AUDIENCE, null)
                .replace("host;x-amz-date;x-goog-cloud-target-resource", "host;x-amz-date");
        assertInvalidGrant(() -> verifier.verify(unsignedTarget, provider, PROVIDER_NAME, AUDIENCE));
    }

    private ObjectNode awsProvider(String accountId) {
        ObjectNode provider = mapper.createObjectNode();
        provider.putObject("aws").put("accountId", accountId);
        return provider;
    }

    private static void assertInvalidGrant(Runnable action) {
        StsException ex = assertThrows(StsException.class, action::run);
        assertEquals("invalid_grant", ex.error());
    }
}

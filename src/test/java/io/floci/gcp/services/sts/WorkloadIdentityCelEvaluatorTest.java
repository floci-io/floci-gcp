package io.floci.gcp.services.sts;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkloadIdentityCelEvaluatorTest {

    private final WorkloadIdentityCelEvaluator evaluator = new WorkloadIdentityCelEvaluator();

    @Test
    void evaluatesSubjectAttributesGroupsAndCondition() {
        Map<String, Object> assertion = Map.of(
                "sub", "repo:my-org/my-repo:ref:refs/heads/main",
                "repository_owner", "my-org",
                "repository", "my-org/my-repo",
                "groups", List.of("devs", "release"));
        Map<String, String> mapping = Map.of(
                "google.subject", "assertion.sub",
                "google.groups", "assertion.groups",
                "attribute.repository", "assertion.repository",
                "attribute.repository_owner", "assertion.repository_owner");

        WorkloadIdentityCelEvaluator.MappedAttributes mapped = evaluator.evaluate(
                assertion, mapping, "attribute.repository_owner == 'my-org' && assertion.sub.startsWith('repo:')");

        assertEquals("repo:my-org/my-repo:ref:refs/heads/main", mapped.subject());
        assertEquals(List.of("devs", "release"), mapped.groups());
        assertEquals("my-org/my-repo", mapped.attributes().get("repository"));
        assertEquals("my-org", mapped.attributes().get("repository_owner"));
    }

    @Test
    void supportsGcpExtractHelperForAwsRoleMapping() {
        Map<String, Object> assertion = Map.of(
                "arn", "arn:aws:sts::123456789012:assumed-role/deploy-role/ci-session");
        Map<String, String> mapping = Map.of(
                "google.subject", "assertion.arn",
                "attribute.aws_role",
                "assertion.arn.contains('assumed-role') "
                        + "? assertion.arn.extract('{account_arn}assumed-role/') + 'assumed-role/' "
                        + "+ assertion.arn.extract('assumed-role/{role_name}/') : assertion.arn");

        WorkloadIdentityCelEvaluator.MappedAttributes mapped = evaluator.evaluate(
                assertion, mapping, "attribute.aws_role == 'arn:aws:sts::123456789012:assumed-role/deploy-role'");

        assertEquals("arn:aws:sts::123456789012:assumed-role/deploy-role/ci-session", mapped.subject());
        assertEquals("arn:aws:sts::123456789012:assumed-role/deploy-role",
                mapped.attributes().get("aws_role"));
    }

    @Test
    void rejectsWhenAttributeConditionEvaluatesFalse() {
        Map<String, Object> assertion = Map.of("sub", "user-1", "repository_owner", "other-org");
        Map<String, String> mapping = Map.of(
                "google.subject", "assertion.sub",
                "attribute.owner", "assertion.repository_owner");

        StsException ex = assertThrows(StsException.class, () ->
                evaluator.evaluate(assertion, mapping, "attribute.owner == 'my-org'"));

        assertEquals("invalid_grant", ex.error());
    }

    @Test
    void rejectsMissingOrOversizedSubject() {
        StsException missing = assertThrows(StsException.class, () ->
                evaluator.evaluate(Map.of("sub", "u"), Map.of(), null));
        assertEquals("invalid_grant", missing.error());

        String oversized = "a".repeat(128);
        StsException tooLong = assertThrows(StsException.class, () ->
                evaluator.evaluate(Map.of("sub", oversized), Map.of("google.subject", "assertion.sub"), null));
        assertEquals("invalid_grant", tooLong.error());
    }
}

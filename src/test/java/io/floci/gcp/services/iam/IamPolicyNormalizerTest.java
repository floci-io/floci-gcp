package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.model.StoredPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamPolicyNormalizerTest {

    @Test
    void normalizesUnconditionalPolicyAndPreservesUnsupportedMemberForms() {
        StoredPolicy policy = policy(1, List.of(Map.of(
                "role", "roles/storage.objectViewer",
                "members", List.of("serviceAccount:reader@example.test", "group:readers@example.test"))));

        IamPolicy normalized = IamPolicyNormalizer.normalize(policy);

        assertEquals(1, normalized.version());
        assertEquals("roles/storage.objectViewer", normalized.bindings().getFirst().role());
        assertEquals(List.of("serviceAccount:reader@example.test", "group:readers@example.test"),
                normalized.bindings().getFirst().members());
        assertNull(normalized.bindings().getFirst().condition());
    }

    @Test
    void treatsOmittedPolicyVersionAsVersionOne() {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/storage.objectViewer",
                "members", List.of("allUsers"))));

        assertEquals(1, IamPolicyNormalizer.normalize(policy).version());
    }

    @Test
    void normalizesVersionThreeConditionalBinding() {
        StoredPolicy policy = policy(3, List.of(Map.of(
                "role", "roles/storage.objectViewer",
                "members", List.of("serviceAccount:reader@example.test"),
                "condition", Map.of(
                        "title", "reports only",
                        "description", "temporary access",
                        "expression", "resource.name.startsWith('projects/_/buckets/reports/')"))));

        IamCondition condition = IamPolicyNormalizer.normalize(policy).bindings().getFirst().condition();

        assertEquals("reports only", condition.title());
        assertEquals("temporary access", condition.description());
    }

    @Test
    void rejectsConditionalBindingOutsideVersionThree() {
        StoredPolicy policy = policy(1, List.of(Map.of(
                "role", "roles/storage.objectViewer",
                "members", List.of("allUsers"),
                "condition", Map.of("title", "restricted", "expression", "true"))));

        assertInvalid(policy, "version 3");
    }

    @Test
    void rejectsMalformedBindingFields() {
        assertInvalid(policy(2, List.of()), "version");
        assertInvalid(rawPolicy(List.of("not a binding")), "binding");
        assertInvalid(policy(1, List.of(Map.of("role", "", "members", List.of("allUsers")))), "role");
        assertInvalid(policy(1, List.of(Map.of("role", "roles/storage.objectViewer", "members", List.of()))),
                "members");
        assertInvalid(policy(1, List.of(Map.of(
                "role", "roles/storage.objectViewer",
                "members", List.of("allUsers"),
                "condition", Map.of("title", "condition", "expression", "")))), "expression");
    }

    @Test
    void resourceNamesRemainDistinctFromPolicyKeys() {
        IamResource bucket = IamResource.gcsBucket("reports");
        IamResource object = IamResource.gcsObject("reports", "2026/july.csv");

        assertEquals("projects/_/buckets/reports", bucket.name());
        assertEquals("projects/_/buckets/reports/objects/2026/july.csv", object.name());
        assertEquals("buckets/reports", bucket.policyResource());
        assertEquals(bucket.policyResource(), object.policyResource());
        assertNotEquals(object.name(), object.policyResource());
    }

    private static StoredPolicy policy(int version, List<Map<String, Object>> bindings) {
        StoredPolicy policy = new StoredPolicy();
        policy.setVersion(version);
        policy.setBindings(bindings);
        return policy;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static StoredPolicy rawPolicy(List<?> bindings) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings((List) bindings);
        return policy;
    }

    private static void assertInvalid(StoredPolicy policy, String messagePart) {
        GcpException exception = assertThrows(GcpException.class, () -> IamPolicyNormalizer.normalize(policy));
        assertEquals("INVALID_ARGUMENT", exception.getGcpStatus());
        assertTrue(exception.getMessage().contains(messagePart));
    }
}

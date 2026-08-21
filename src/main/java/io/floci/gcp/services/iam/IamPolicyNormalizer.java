package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.model.StoredPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts the wire-compatible stored policy DTO into immutable evaluator input.
 *
 * <p>This class validates only policy structure. CEL profile validation belongs to the condition
 * evaluator, and member/role support belongs to the evaluator's explicitly limited catalogs.</p>
 */
public final class IamPolicyNormalizer {

    private static final int VERSION_1 = 1;
    private static final int VERSION_3 = 3;

    private IamPolicyNormalizer() {
    }

    public static IamPolicy normalize(StoredPolicy storedPolicy) {
        if (storedPolicy == null) {
            throw invalidPolicy("policy is required");
        }
        int version = storedPolicy.getVersion() == 0 ? VERSION_1 : storedPolicy.getVersion();
        if (version != VERSION_1 && version != VERSION_3) {
            throw invalidPolicy("policy version must be 1 or 3");
        }

        List<?> storedBindings = storedPolicy.getBindings();
        if (storedBindings == null) {
            storedBindings = List.of();
        }

        List<IamBinding> bindings = new ArrayList<>(storedBindings.size());
        for (Object storedBinding : storedBindings) {
            bindings.add(normalizeBinding(storedBinding, version));
        }
        return new IamPolicy(version, bindings, storedPolicy.getEtag());
    }

    private static IamBinding normalizeBinding(Object value, int version) {
        if (!(value instanceof Map<?, ?> storedBinding)) {
            throw invalidPolicy("policy binding must be an object");
        }
        String role = requiredString(storedBinding.get("role"), "policy binding role");
        List<String> members = normalizeMembers(storedBinding.get("members"));
        IamCondition condition = normalizeCondition(storedBinding.get("condition"));
        if (condition != null && version != VERSION_3) {
            throw invalidPolicy("conditional policy bindings require version 3");
        }
        return new IamBinding(role, members, condition);
    }

    private static List<String> normalizeMembers(Object value) {
        if (!(value instanceof List<?> rawMembers) || rawMembers.isEmpty()) {
            throw invalidPolicy("policy binding members must be a non-empty list");
        }
        List<String> members = new ArrayList<>(rawMembers.size());
        for (Object rawMember : rawMembers) {
            members.add(requiredString(rawMember, "policy binding member"));
        }
        return members;
    }

    private static IamCondition normalizeCondition(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> rawCondition)) {
            throw invalidPolicy("policy binding condition must be an object");
        }
        String title = requiredString(rawCondition.get("title"), "policy binding condition title");
        String expression = requiredString(rawCondition.get("expression"),
                "policy binding condition expression");
        Object descriptionValue = rawCondition.get("description");
        String description = descriptionValue == null ? null
                : requiredString(descriptionValue, "policy binding condition description");
        return new IamCondition(title, expression, description);
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw invalidPolicy(field + " must be a non-blank string");
        }
        return stringValue;
    }

    private static GcpException invalidPolicy(String message) {
        return GcpException.invalidArgument(message);
    }
}

package io.floci.gcp.services.iam;

import com.google.iam.v1.Binding;
import com.google.iam.v1.Policy;
import com.google.protobuf.ByteString;
import com.google.type.Expr;
import io.floci.gcp.services.iam.model.StoredPolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between {@code google.iam.v1.Policy} protos and {@link StoredPolicy}.
 * Bindings round-trip losslessly, including {@code condition} blocks — the
 * emulator stores and returns conditions but never evaluates them.
 */
public final class IamPolicyCodec {

    private IamPolicyCodec() {}

    public static StoredPolicy toStoredPolicy(Policy policy) {
        StoredPolicy stored = new StoredPolicy();
        stored.setVersion(policy.getVersion());
        if (!policy.getEtag().isEmpty()) {
            stored.setEtag(policy.getEtag().toStringUtf8());
        }
        stored.setBindings(policy.getBindingsList().stream()
                .map(IamPolicyCodec::bindingToMap)
                .toList());
        return stored;
    }

    public static Policy toProtoPolicy(StoredPolicy stored) {
        Policy.Builder builder = Policy.newBuilder()
                .setVersion(stored.getVersion());
        if (stored.getEtag() != null) {
            builder.setEtag(ByteString.copyFromUtf8(stored.getEtag()));
        }
        if (stored.getBindings() != null) {
            for (Map<String, Object> binding : stored.getBindings()) {
                builder.addBindings(mapToBinding(binding));
            }
        }
        return builder.build();
    }

    private static Map<String, Object> bindingToMap(Binding binding) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", binding.getRole());
        map.put("members", List.copyOf(binding.getMembersList()));
        if (binding.hasCondition()) {
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put("expression", binding.getCondition().getExpression());
            condition.put("title", binding.getCondition().getTitle());
            condition.put("description", binding.getCondition().getDescription());
            condition.put("location", binding.getCondition().getLocation());
            map.put("condition", condition);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Binding mapToBinding(Map<String, Object> map) {
        Binding.Builder builder = Binding.newBuilder()
                .setRole((String) map.getOrDefault("role", ""));
        Object members = map.get("members");
        if (members instanceof List<?> list) {
            for (Object member : list) {
                builder.addMembers(String.valueOf(member));
            }
        }
        Object condition = map.get("condition");
        if (condition instanceof Map<?, ?> raw) {
            Map<String, Object> c = (Map<String, Object>) raw;
            builder.setCondition(Expr.newBuilder()
                    .setExpression((String) c.getOrDefault("expression", ""))
                    .setTitle((String) c.getOrDefault("title", ""))
                    .setDescription((String) c.getOrDefault("description", ""))
                    .setLocation((String) c.getOrDefault("location", ""))
                    .build());
        }
        return builder.build();
    }
}

package io.floci.gcp.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.iam.v1.AuditConfig;
import com.google.iam.v1.Binding;
import com.google.iam.v1.Policy;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.google.type.Expr;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.services.iam.model.StoredPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between {@code google.iam.v1.Policy} protos and {@link StoredPolicy}.
 * Bindings round-trip losslessly, including {@code condition} blocks, and so do {@code auditConfigs}.
 * Evaluation is handled by the shared IAM authorization service.
 */
public final class IamPolicyCodec {

    // Strict, so an unknown field or log type is refused rather than dropped from the stored configuration.
    private static final JsonFormat.Parser AUDIT_CONFIG_PARSER = JsonFormat.parser();
    private static final ObjectMapper JSON = new ObjectMapper();

    private IamPolicyCodec() {}

    @SuppressWarnings("unchecked")
    public static StoredPolicy fromJsonMap(Map<String, Object> policyMap) {
        StoredPolicy policy = new StoredPolicy();
        if (policyMap == null) {
            return policy;
        }
        if (policyMap.containsKey("version")) {
            policy.setVersion(((Number) policyMap.get("version")).intValue());
        }
        if (policyMap.containsKey("bindings")) {
            policy.setBindings((List<Map<String, Object>>) policyMap.get("bindings"));
        }
        if (policyMap.containsKey("etag")) {
            policy.setEtag((String) policyMap.get("etag"));
        }
        if (policyMap.get("auditConfigs") instanceof List<?> configs) {
            List<AuditConfig> parsed = new ArrayList<>();
            for (Object config : configs) {
                parsed.add(parseAuditConfig(config));
            }
            policy.setAuditConfigs(auditConfigMaps(parsed));
        } else if (policyMap.get("auditConfigs") != null) {
            throw GcpException.invalidArgument("Invalid policy: auditConfigs must be a list");
        }
        return policy;
    }

    public static StoredPolicy toStoredPolicy(Policy policy) {
        StoredPolicy stored = new StoredPolicy();
        stored.setVersion(policy.getVersion());
        if (!policy.getEtag().isEmpty()) {
            stored.setEtag(policy.getEtag().toStringUtf8());
        }
        stored.setBindings(policy.getBindingsList().stream()
                .map(IamPolicyCodec::bindingToMap)
                .toList());
        stored.setAuditConfigs(auditConfigMaps(policy.getAuditConfigsList()));
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
        if (stored.getAuditConfigs() != null) {
            for (Map<String, Object> config : stored.getAuditConfigs()) {
                builder.addAuditConfigs(parseAuditConfig(config));
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

    private static List<Map<String, Object>> auditConfigMaps(List<AuditConfig> configs) {
        if (configs.isEmpty()) {
            return null;
        }
        return configs.stream().map(ProtoJson::printAsMap).toList();
    }

    private static AuditConfig parseAuditConfig(Object config) {
        if (!(config instanceof Map<?, ?>)) {
            throw GcpException.invalidArgument("Invalid policy: each auditConfigs entry must be an object");
        }
        try {
            AuditConfig.Builder builder = AuditConfig.newBuilder();
            AUDIT_CONFIG_PARSER.merge(JSON.writeValueAsString(config), builder);
            return builder.build();
        } catch (InvalidProtocolBufferException | JsonProcessingException e) {
            throw GcpException.invalidArgument("Invalid policy auditConfigs: " + e.getMessage());
        }
    }
}
